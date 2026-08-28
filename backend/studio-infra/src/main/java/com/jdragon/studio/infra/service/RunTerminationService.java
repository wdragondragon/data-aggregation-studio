package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskExecutionMode;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.RunTerminationView;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single state transition point for user requested run termination.
 * Both API entry points use the same compare-and-set predicates so a late
 * worker completion cannot turn a manually failed record back into success.
 */
@Service
public class RunTerminationService {

    public static final String ERROR_CODE = "USER_TERMINATED";
    public static final String REASON = "Manually terminated by user";
    public static final int REQUESTED = 1;

    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final RunService runService;
    private final CollectionTaskService collectionTaskService;
    private final StudioSecurityService securityService;

    public RunTerminationService(DispatchTaskMapper dispatchTaskMapper,
                                 RunRecordMapper runRecordMapper,
                                 RunService runService,
                                 CollectionTaskService collectionTaskService,
                                 StudioSecurityService securityService) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.runService = runService;
        this.collectionTaskService = collectionTaskService;
        this.securityService = securityService;
    }

    @Transactional
    public RunTerminationView terminateCollectionTask(Long collectionTaskId) {
        CollectionTaskDefinitionView definition = collectionTaskService.get(collectionTaskId);
        if (definition.getExecutionMode() == CollectionTaskExecutionMode.STREAMING) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "STREAMING collection tasks must be stopped with the offline operation");
        }
        String tenantId = securityService.currentTenantId();
        if (definition.getTenantId() != null && !definition.getTenantId().equals(tenantId)) {
            throw new StudioException(StudioErrorCode.NOT_FOUND,
                    "Collection task not found: " + collectionTaskId);
        }
        Long projectId = securityService.currentProjectId() == null
                ? definition.getProjectId()
                : securityService.currentProjectId();
        List<DispatchTaskEntity> activeTasks = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, tenantId)
                .eq(projectId != null, DispatchTaskEntity::getProjectId, projectId)
                .eq(DispatchTaskEntity::getCollectionTaskId, collectionTaskId)
                .in(DispatchTaskEntity::getExecutionType, "COLLECTION_TASK", "WORKFLOW_NODE")
                .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING")
                .orderByAsc(DispatchTaskEntity::getCreatedAt));
        if (activeTasks.isEmpty()) {
            List<DispatchTaskEntity> terminatedHistory = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                    .eq(DispatchTaskEntity::getTenantId, tenantId)
                    .eq(projectId != null, DispatchTaskEntity::getProjectId, projectId)
                    .eq(DispatchTaskEntity::getCollectionTaskId, collectionTaskId)
                    .in(DispatchTaskEntity::getExecutionType, "COLLECTION_TASK", "WORKFLOW_NODE")
                    .eq(DispatchTaskEntity::getStatus, "FAILED")
                    .eq(DispatchTaskEntity::getTerminationRequested, REQUESTED)
                    .orderByDesc(DispatchTaskEntity::getCreatedAt)
                    .last("limit 1"));
            if (!terminatedHistory.isEmpty()) {
                return terminateDispatchTask(terminatedHistory.get(0));
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "No queued or running instance exists for collection task " + collectionTaskId);
        }
        if (activeTasks.size() > 1) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Multiple active instances exist; terminate a specific run record instead");
        }
        return terminateDispatchTask(activeTasks.get(0));
    }

    @Transactional
    public RunTerminationView terminateRunRecord(Long runRecordId) {
        RunRecordEntity record = runService.getEntity(runRecordId);
        List<DispatchTaskEntity> tasks = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, record.getTenantId())
                .eq(record.getProjectId() != null, DispatchTaskEntity::getProjectId, record.getProjectId())
                .eq(DispatchTaskEntity::getRunRecordId, runRecordId)
                .orderByDesc(DispatchTaskEntity::getCreatedAt));
        if (tasks.isEmpty()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND,
                    "Dispatch task not found for run record " + runRecordId);
        }
        if (tasks.size() > 1) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Multiple dispatch tasks are linked to run record " + runRecordId);
        }
        return terminate(tasks.get(0), record);
    }

    private RunTerminationView terminateDispatchTask(DispatchTaskEntity task) {
        RunRecordEntity record = task.getRunRecordId() == null
                ? null
                : runRecordMapper.selectById(task.getRunRecordId());
        return terminate(task, record);
    }

    private RunTerminationView terminate(DispatchTaskEntity task, RunRecordEntity record) {
        if (task == null && record == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record dispatch task not found");
        }
        boolean changed = false;
        LocalDateTime endedAt = LocalDateTime.now();
        boolean taskWasActive = task != null && isActive(task.getStatus());
        boolean recordWasActive = record != null && "RUNNING".equalsIgnoreCase(record.getStatus());
        boolean terminationStartedForActiveInstance = taskWasActive || recordWasActive;
        if (task != null && terminationStartedForActiveInstance) {
            changed = terminateDispatchTask(task, endedAt);
        }

        DispatchTaskEntity persistedTask = task == null || task.getId() == null
                ? null
                : dispatchTaskMapper.selectById(task.getId());
        if (record == null && persistedTask != null && persistedTask.getRunRecordId() != null) {
            record = runRecordMapper.selectById(persistedTask.getRunRecordId());
            recordWasActive = record != null && "RUNNING".equalsIgnoreCase(record.getStatus());
            terminationStartedForActiveInstance = terminationStartedForActiveInstance || recordWasActive;
        }
        if (record != null && terminationStartedForActiveInstance) {
            changed = terminateRunRecord(record, endedAt) || changed;
        }

        RunRecordEntity persistedRecord = record == null || record.getId() == null ? null : runRecordMapper.selectById(record.getId());
        if (persistedTask != null && persistedTask.getRunRecordId() != null && persistedRecord == null) {
            persistedRecord = runRecordMapper.selectById(persistedTask.getRunRecordId());
        }
        return buildView(persistedTask, persistedRecord, changed);
    }

    private boolean terminateDispatchTask(DispatchTaskEntity task, LocalDateTime endedAt) {
        if (task == null || isRequested(task.getTerminationRequested())) {
            return false;
        }
        Map<String, Object> payload = terminationPayload(task.getPayloadJson(), endedAt);
        int updated;
        if (isActive(task.getStatus())) {
            updated = dispatchTaskMapper.update(null,
                    dispatchTerminationPredicate(activeDispatchPredicate(task.getId(), task.getTenantId(), task.getProjectId()),
                            payload, endedAt));
            if (updated == 0) {
                // The API observed an active instance, but the worker may have
                // committed its terminal result before our compare-and-set.
                // Use a current conditional update so manual termination wins
                // even under MySQL REPEATABLE READ.
                updated = dispatchTaskMapper.update(null,
                        dispatchTerminationPredicate(racedTerminalDispatchPredicate(task.getId(), task.getTenantId(), task.getProjectId()),
                                payload, endedAt));
            }
        } else if (isTerminal(task.getStatus())) {
            updated = dispatchTaskMapper.update(null,
                    dispatchTerminationPredicate(terminalDispatchPredicate(task), payload, endedAt));
        } else {
            return false;
        }
        return updated > 0;
    }

    private boolean terminateRunRecord(RunRecordEntity record, LocalDateTime endedAt) {
        if (record == null || isRequested(record.getTerminationRequested())) {
            return false;
        }
        Map<String, Object> payload = terminationPayload(record.getPayloadJson(), endedAt);
        Map<String, Object> result = terminationPayload(record.getResultJson(), endedAt);
        LocalDateTime recordEndedAt = record.getEndedAt() == null ? endedAt : record.getEndedAt();
        String message = appendMessage(record.getMessage(), REASON);
        int updated;
        if ("RUNNING".equalsIgnoreCase(record.getStatus())) {
            updated = runRecordMapper.update(null,
                    runRecordTerminationPredicate(activeRunRecordPredicate(record.getId(), record.getTenantId(), record.getProjectId()),
                            payload, result, recordEndedAt, message));
            if (updated == 0) {
                updated = runRecordMapper.update(null,
                        runRecordTerminationPredicate(racedTerminalRunRecordPredicate(record.getId(), record.getTenantId(), record.getProjectId()),
                                payload, result, recordEndedAt, message));
            }
        } else if (isTerminal(record.getStatus())) {
            updated = runRecordMapper.update(null,
                    runRecordTerminationPredicate(terminalRunRecordPredicate(record),
                            payload, result, recordEndedAt, message));
        } else {
            return false;
        }
        return updated > 0;
    }

    private LambdaUpdateWrapper<DispatchTaskEntity> dispatchTerminationPredicate(
            LambdaUpdateWrapper<DispatchTaskEntity> predicate,
            Map<String, Object> payload,
            LocalDateTime endedAt) {
        return predicate
                .set(DispatchTaskEntity::getStatus, "FAILED")
                .set(DispatchTaskEntity::getTerminationRequested, REQUESTED)
                .set(DispatchTaskEntity::getLeaseExpiresAt, endedAt)
                .set(DispatchTaskEntity::getPayloadJson, payload,
                        "typeHandler=" + JacksonTypeHandler.class.getCanonicalName());
    }

    private LambdaUpdateWrapper<RunRecordEntity> runRecordTerminationPredicate(
            LambdaUpdateWrapper<RunRecordEntity> predicate,
            Map<String, Object> payload,
            Map<String, Object> result,
            LocalDateTime endedAt,
            String message) {
        return predicate
                .set(RunRecordEntity::getStatus, "FAILED")
                .set(RunRecordEntity::getTerminationRequested, REQUESTED)
                .set(RunRecordEntity::getEndedAt, endedAt)
                .set(RunRecordEntity::getMessage, message)
                .set(RunRecordEntity::getPayloadJson, payload,
                        "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                .set(RunRecordEntity::getResultJson, result,
                        "typeHandler=" + JacksonTypeHandler.class.getCanonicalName());
    }

    private LambdaUpdateWrapper<DispatchTaskEntity> activeDispatchPredicate(Long id, String tenantId, Long projectId) {
        return new LambdaUpdateWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getId, id)
                .eq(DispatchTaskEntity::getTenantId, tenantId)
                .eq(projectId != null, DispatchTaskEntity::getProjectId, projectId)
                .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING")
                .and(wrapper -> wrapper.eq(DispatchTaskEntity::getTerminationRequested, 0)
                        .or().isNull(DispatchTaskEntity::getTerminationRequested));
    }

    private LambdaUpdateWrapper<RunRecordEntity> activeRunRecordPredicate(Long id, String tenantId, Long projectId) {
        return new LambdaUpdateWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getId, id)
                .eq(RunRecordEntity::getTenantId, tenantId)
                .eq(projectId != null, RunRecordEntity::getProjectId, projectId)
                .eq(RunRecordEntity::getStatus, "RUNNING")
                .and(wrapper -> wrapper.eq(RunRecordEntity::getTerminationRequested, 0)
                        .or().isNull(RunRecordEntity::getTerminationRequested));
    }

    private LambdaUpdateWrapper<DispatchTaskEntity> racedTerminalDispatchPredicate(Long id,
                                                                                    String tenantId,
                                                                                    Long projectId) {
        return new LambdaUpdateWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getId, id)
                .eq(DispatchTaskEntity::getTenantId, tenantId)
                .eq(projectId != null, DispatchTaskEntity::getProjectId, projectId)
                .in(DispatchTaskEntity::getStatus, "SUCCESS", "FAILED")
                .and(wrapper -> wrapper.eq(DispatchTaskEntity::getTerminationRequested, 0)
                        .or().isNull(DispatchTaskEntity::getTerminationRequested));
    }

    private LambdaUpdateWrapper<RunRecordEntity> racedTerminalRunRecordPredicate(Long id,
                                                                                  String tenantId,
                                                                                  Long projectId) {
        return new LambdaUpdateWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getId, id)
                .eq(RunRecordEntity::getTenantId, tenantId)
                .eq(projectId != null, RunRecordEntity::getProjectId, projectId)
                .in(RunRecordEntity::getStatus, "SUCCESS", "FAILED")
                .and(wrapper -> wrapper.eq(RunRecordEntity::getTerminationRequested, 0)
                        .or().isNull(RunRecordEntity::getTerminationRequested));
    }

    private LambdaUpdateWrapper<DispatchTaskEntity> terminalDispatchPredicate(DispatchTaskEntity current) {
        return new LambdaUpdateWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getId, current.getId())
                .eq(DispatchTaskEntity::getTenantId, current.getTenantId())
                .eq(current.getProjectId() != null, DispatchTaskEntity::getProjectId, current.getProjectId())
                .eq(DispatchTaskEntity::getStatus, current.getStatus())
                .and(wrapper -> wrapper.eq(DispatchTaskEntity::getTerminationRequested, 0)
                        .or().isNull(DispatchTaskEntity::getTerminationRequested));
    }

    private LambdaUpdateWrapper<RunRecordEntity> terminalRunRecordPredicate(RunRecordEntity current) {
        return new LambdaUpdateWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getId, current.getId())
                .eq(RunRecordEntity::getTenantId, current.getTenantId())
                .eq(current.getProjectId() != null, RunRecordEntity::getProjectId, current.getProjectId())
                .eq(RunRecordEntity::getStatus, current.getStatus())
                .and(wrapper -> wrapper.eq(RunRecordEntity::getTerminationRequested, 0)
                        .or().isNull(RunRecordEntity::getTerminationRequested));
    }

    private Map<String, Object> terminationPayload(Map<String, Object> existing, LocalDateTime endedAt) {
        Map<String, Object> payload = existing == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(existing);
        payload.put("errorCode", ERROR_CODE);
        payload.put("terminationReason", REASON);
        payload.put("terminationRequestedAt", String.valueOf(endedAt));
        Long userId = securityService.currentUserId();
        if (userId != null) {
            payload.put("terminationRequestedBy", userId);
        } else if (securityService.currentUsername() != null) {
            payload.put("terminationRequestedBy", securityService.currentUsername());
        }
        payload.put("error", REASON);
        return payload;
    }

    private RunTerminationView buildView(DispatchTaskEntity task, RunRecordEntity record, boolean changed) {
        RunTerminationView view = new RunTerminationView();
        view.setDispatchTaskId(task == null ? null : task.getId());
        view.setRunRecordId(record == null ? (task == null ? null : task.getRunRecordId()) : record.getId());
        view.setCollectionTaskId(record == null
                ? (task == null ? null : task.getCollectionTaskId())
                : record.getCollectionTaskId());
        String status = record != null && record.getStatus() != null
                ? record.getStatus()
                : task == null ? null : task.getStatus();
        view.setStatus(status);
        view.setChanged(changed);
        view.setTerminationRequested((task != null && isRequested(task.getTerminationRequested()))
                || (record != null && isRequested(record.getTerminationRequested())));
        view.setMessage(changed || view.isTerminationRequested()
                ? REASON
                : "Run is already in terminal state: " + status);
        return view;
    }

    private boolean isActive(String status) {
        return "QUEUED".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status);
    }

    private boolean isRequested(Integer value) {
        return value != null && value.intValue() == REQUESTED;
    }

    private boolean isTerminal(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }

    private String appendMessage(String current, String reason) {
        String base = current == null || current.trim().isEmpty() ? "" : current.trim();
        String result = base.isEmpty() ? reason : base + " | " + reason;
        return result.length() <= 2000 ? result : result.substring(0, 1997) + "...";
    }
}
