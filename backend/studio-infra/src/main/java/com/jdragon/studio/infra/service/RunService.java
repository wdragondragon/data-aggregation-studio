package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.dto.model.QueuedTaskView;
import com.jdragon.studio.dto.model.RunListView;
import com.jdragon.studio.dto.model.RunRecordView;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RunService {

    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final CollectionTaskService collectionTaskService;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final StudioSecurityService securityService;

    public RunService(DispatchTaskMapper dispatchTaskMapper,
                      RunRecordMapper runRecordMapper,
                      CollectionTaskService collectionTaskService,
                      WorkflowDefinitionMapper workflowDefinitionMapper,
                      StudioSecurityService securityService) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.collectionTaskService = collectionTaskService;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.securityService = securityService;
    }

    public RunListView list(Long collectionTaskId,
                            Long workflowDefinitionId,
                            LocalDateTime startTime,
                            LocalDateTime endTime) {
        RunListView view = new RunListView();
        Map<Long, String> collectionTaskNames = collectionTaskNames();
        Map<Long, String> workflowNames = workflowNames();
        String currentTenantId = securityService.currentTenantId();
        Long currentProjectId = securityService.currentProjectId();
        List<DispatchTaskEntity> queued = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, currentTenantId)
                .eq(collectionTaskId != null, DispatchTaskEntity::getCollectionTaskId, collectionTaskId)
                .eq(workflowDefinitionId != null, DispatchTaskEntity::getWorkflowDefinitionId, workflowDefinitionId)
                .eq(currentProjectId != null, DispatchTaskEntity::getProjectId, currentProjectId)
                .ge(startTime != null, DispatchTaskEntity::getCreatedAt, startTime)
                .le(endTime != null, DispatchTaskEntity::getCreatedAt, endTime)
                .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING")
                .orderByDesc(DispatchTaskEntity::getCreatedAt));
        for (DispatchTaskEntity entity : queued) {
            view.getQueuedTasks().add(toQueuedTaskView(entity, collectionTaskNames, workflowNames));
        }
        List<RunRecordEntity> records = runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getTenantId, currentTenantId)
                .eq(collectionTaskId != null, RunRecordEntity::getCollectionTaskId, collectionTaskId)
                .eq(workflowDefinitionId != null, RunRecordEntity::getWorkflowDefinitionId, workflowDefinitionId)
                .eq(currentProjectId != null, RunRecordEntity::getProjectId, currentProjectId)
                .ge(startTime != null, RunRecordEntity::getCreatedAt, startTime)
                .le(endTime != null, RunRecordEntity::getCreatedAt, endTime)
                .orderByDesc(RunRecordEntity::getCreatedAt));
        for (RunRecordEntity entity : records) {
            view.getRunRecords().add(toRunRecordView(entity, collectionTaskNames, workflowNames));
        }
        return view;
    }

    public RunRecordView get(Long runRecordId) {
        RunRecordEntity entity = getEntity(runRecordId);
        return toRunRecordView(entity, collectionTaskNames(), workflowNames());
    }

    public RunRecordEntity getEntity(Long runRecordId) {
        RunRecordEntity entity = runRecordMapper.selectById(runRecordId);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record not found: " + runRecordId);
        }
        if (!securityService.currentTenantId().equals(entity.getTenantId())
                || (securityService.currentProjectId() != null
                && !securityService.currentProjectId().equals(entity.getProjectId()))) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Run record not found: " + runRecordId);
        }
        return entity;
    }

    public RunLogView buildHistoricalFallback(RunRecordEntity entity) {
        RunLogView view = new RunLogView();
        view.setRunRecordId(entity.getId());
        view.setCharset(entity.getLogCharset() == null ? "UTF-8" : entity.getLogCharset());
        view.setContentType("text/plain;charset=UTF-8");
        view.setDownloadName("run-" + entity.getId() + "-summary.log");
        view.setSizeBytes(0L);
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setHistoricalFallback(true);
        view.setTruncated(false);
        view.setContent(buildFallbackContent(entity));
        return view;
    }

    private Map<Long, String> collectionTaskNames() {
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        List<CollectionTaskDefinitionView> tasks = collectionTaskService.list(null, null, null);
        for (CollectionTaskDefinitionView task : tasks) {
            if (task.getId() != null) {
                result.put(task.getId(), task.getName());
            }
        }
        return result;
    }

    private Map<Long, String> workflowNames() {
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        List<WorkflowDefinitionEntity> definitions = workflowDefinitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .eq(WorkflowDefinitionEntity::getTenantId, securityService.currentTenantId())
                .orderByAsc(WorkflowDefinitionEntity::getCode));
        for (WorkflowDefinitionEntity definition : definitions) {
            if (definition.getId() != null) {
                result.put(definition.getId(), definition.getName());
            }
        }
        return result;
    }

    private QueuedTaskView toQueuedTaskView(DispatchTaskEntity entity,
                                            Map<Long, String> collectionTaskNames,
                                            Map<Long, String> workflowNames) {
        QueuedTaskView view = new QueuedTaskView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setExecutionType(entity.getExecutionType());
        view.setWorkflowRunId(entity.getWorkflowRunId());
        view.setWorkflowDefinitionId(entity.getWorkflowDefinitionId());
        view.setWorkflowVersionId(entity.getWorkflowVersionId());
        view.setWorkflowName(resolveWorkflowName(entity.getWorkflowDefinitionId(), workflowNames));
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setCollectionTaskName(resolveCollectionTaskName(entity.getCollectionTaskId(), collectionTaskNames));
        view.setNodeCode(entity.getNodeCode());
        view.setStatus(entity.getStatus());
        view.setLeaseOwner(entity.getLeaseOwner());
        view.setAttempts(entity.getAttempts());
        view.setMaxRetries(entity.getMaxRetries());
        view.setPayloadJson(entity.getPayloadJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(entity.getPayloadJson()));
        return view;
    }

    private RunRecordView toRunRecordView(RunRecordEntity entity,
                                          Map<Long, String> collectionTaskNames,
                                          Map<Long, String> workflowNames) {
        RunRecordView view = new RunRecordView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setExecutionType(entity.getExecutionType());
        view.setWorkflowRunId(entity.getWorkflowRunId());
        view.setWorkflowDefinitionId(entity.getWorkflowDefinitionId());
        view.setWorkflowVersionId(entity.getWorkflowVersionId());
        view.setWorkflowName(resolveWorkflowName(entity.getWorkflowDefinitionId(), workflowNames));
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setCollectionTaskName(resolveCollectionTaskName(entity.getCollectionTaskId(), collectionTaskNames));
        view.setNodeCode(entity.getNodeCode());
        view.setWorkerCode(entity.getWorkerCode());
        view.setStatus(entity.getStatus());
        view.setMessage(entity.getMessage());
        view.setStartedAt(entity.getStartedAt());
        view.setEndedAt(entity.getEndedAt());
        view.setLogFilePath(entity.getLogFilePath());
        view.setLogSizeBytes(entity.getLogSizeBytes());
        view.setLogCharset(entity.getLogCharset());
        view.setPayloadJson(entity.getPayloadJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(entity.getPayloadJson()));
        view.setResultJson(entity.getResultJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(entity.getResultJson()));
        return view;
    }

    private String resolveCollectionTaskName(Long collectionTaskId, Map<Long, String> collectionTaskNames) {
        if (collectionTaskId == null) {
            return null;
        }
        return collectionTaskNames.get(collectionTaskId);
    }

    private String resolveWorkflowName(Long workflowDefinitionId, Map<Long, String> workflowNames) {
        if (workflowDefinitionId == null) {
            return null;
        }
        return workflowNames.get(workflowDefinitionId);
    }

    private String buildFallbackContent(RunRecordEntity entity) {
        StringBuilder builder = new StringBuilder();
        builder.append("Run Record Summary").append('\n');
        builder.append("==================").append('\n');
        builder.append("Run Record ID: ").append(entity.getId()).append('\n');
        builder.append("Status: ").append(entity.getStatus()).append('\n');
        builder.append("Worker: ").append(entity.getWorkerCode()).append('\n');
        builder.append("Node: ").append(entity.getNodeCode()).append('\n');
        builder.append("Started At: ").append(entity.getStartedAt()).append('\n');
        builder.append("Ended At: ").append(entity.getEndedAt()).append('\n');
        builder.append('\n').append("Message").append('\n');
        builder.append("-------").append('\n');
        builder.append(entity.getMessage() == null ? "" : entity.getMessage()).append('\n');
        builder.append('\n').append("Payload").append('\n');
        builder.append("-------").append('\n');
        builder.append(toJsonBlock(entity.getPayloadJson())).append('\n');
        builder.append('\n').append("Result").append('\n');
        builder.append("------").append('\n');
        builder.append(toJsonBlock(entity.getResultJson())).append('\n');
        return builder.toString();
    }

    private String toJsonBlock(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "(none)";
        }
        return String.valueOf(value);
    }
}
