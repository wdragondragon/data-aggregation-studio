package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StaleExecutionRecoveryService {

    private static final int RUN_RECORD_MESSAGE_MAX_LENGTH = 2000;
    private static final String AUTO_RECOVERY_REASON = "Automatically closed stale RUNNING record because worker heartbeat is offline and dispatch lease expired.";
    private static final String SUPERSEDED_REASON = "Automatically closed stale RUNNING record because a later terminal record exists for the same node.";
    private static final String MANUAL_TERMINATE_REASON = "Manually terminated by user";

    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final WorkerLeaseMapper workerLeaseMapper;
    private final StudioPlatformProperties properties;

    public StaleExecutionRecoveryService(DispatchTaskMapper dispatchTaskMapper,
                                         RunRecordMapper runRecordMapper,
                                         WorkerLeaseMapper workerLeaseMapper,
                                         StudioPlatformProperties properties) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.workerLeaseMapper = workerLeaseMapper;
        this.properties = properties;
    }

    @Transactional
    public void recoverAllStale() {
        recoverScope(Scope.all());
    }

    @Transactional
    public void recoverWorkflow(String tenantId, Long projectId, Long workflowDefinitionId) {
        recoverScope(Scope.workflow(tenantId, projectId, workflowDefinitionId));
    }

    @Transactional
    public void recoverCollectionTask(String tenantId, Long projectId, Long collectionTaskId) {
        recoverScope(Scope.collectionTask(tenantId, projectId, collectionTaskId));
    }

    @Transactional
    public void recoverQualityTask(String tenantId, Long projectId, Long qualityTaskId) {
        recoverScope(Scope.qualityTask(tenantId, projectId, qualityTaskId));
    }

    @Transactional
    public void terminateWorkflowRun(String tenantId, Long projectId, Long workflowRunId) {
        Scope scope = Scope.workflowRun(tenantId, projectId, workflowRunId);
        LocalDateTime endedAt = LocalDateTime.now();
        for (DispatchTaskEntity task : selectActiveDispatchTasks(scope)) {
            failDispatchTask(task, endedAt, MANUAL_TERMINATE_REASON);
        }
        for (RunRecordEntity record : selectCandidateRunRecords(scope)) {
            failRunRecord(record, endedAt, MANUAL_TERMINATE_REASON);
        }
    }

    public boolean hasActiveWorkflowRun(String tenantId, Long projectId, Long workflowDefinitionId) {
        return hasActiveScope(Scope.workflow(tenantId, projectId, workflowDefinitionId));
    }

    public boolean hasActiveWorkflowRunInstance(String tenantId, Long projectId, Long workflowRunId) {
        return hasActiveScope(Scope.workflowRun(tenantId, projectId, workflowRunId));
    }

    public boolean hasActiveCollectionTaskRun(String tenantId, Long projectId, Long collectionTaskId) {
        return hasActiveScope(Scope.collectionTask(tenantId, projectId, collectionTaskId));
    }

    public boolean hasActiveQualityTaskRun(String tenantId, Long projectId, Long qualityTaskId) {
        return hasActiveScope(Scope.qualityTask(tenantId, projectId, qualityTaskId));
    }

    private void recoverScope(Scope scope) {
        LocalDateTime now = LocalDateTime.now();
        for (DispatchTaskEntity task : selectRunningDispatchTasks(scope)) {
            if (isStaleDispatchTask(task, now)) {
                failDispatchTask(task, now, AUTO_RECOVERY_REASON);
                failLinkedRunRecord(task, now);
            }
        }
        for (RunRecordEntity record : selectCandidateRunRecords(scope)) {
            if (hasNewerTerminalRecord(record)) {
                failRunRecord(record, now, SUPERSEDED_REASON);
            } else if (isStaleRunRecord(record, now)) {
                failRunRecord(record, now, AUTO_RECOVERY_REASON);
            }
        }
    }

    private boolean hasActiveScope(Scope scope) {
        LocalDateTime now = LocalDateTime.now();
        for (DispatchTaskEntity task : selectActiveDispatchTasks(scope)) {
            if (isActiveDispatchTask(task, now)) {
                return true;
            }
        }
        for (RunRecordEntity record : selectCandidateRunRecords(scope)) {
            if (isActiveRunRecord(record, now)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActiveDispatchTask(DispatchTaskEntity task, LocalDateTime now) {
        if (task == null || task.getStatus() == null) {
            return false;
        }
        if ("QUEUED".equalsIgnoreCase(task.getStatus())) {
            return true;
        }
        if (!"RUNNING".equalsIgnoreCase(task.getStatus())) {
            return false;
        }
        if (task.getLeaseExpiresAt() != null && task.getLeaseExpiresAt().isAfter(now)) {
            return true;
        }
        if (hasActiveWorker(task.getTenantId(), task.getLeaseOwner(), now)) {
            return true;
        }
        return isRecent(task.getCreatedAt(), now);
    }

    private boolean isActiveRunRecord(RunRecordEntity record, LocalDateTime now) {
        if (record == null || !"RUNNING".equalsIgnoreCase(record.getStatus())) {
            return false;
        }
        if (hasNewerTerminalRecord(record)) {
            return false;
        }
        if (hasActiveDispatchForRecord(record, now)) {
            return true;
        }
        if (hasActiveWorker(record.getTenantId(), record.getWorkerCode(), now)) {
            return true;
        }
        return isRecent(firstNonNull(record.getStartedAt(), record.getCreatedAt()), now);
    }

    private boolean isStaleDispatchTask(DispatchTaskEntity task, LocalDateTime now) {
        if (task == null || !"RUNNING".equalsIgnoreCase(task.getStatus())) {
            return false;
        }
        if (task.getLeaseExpiresAt() != null && task.getLeaseExpiresAt().isAfter(now)) {
            return false;
        }
        if (hasActiveWorker(task.getTenantId(), task.getLeaseOwner(), now)) {
            return false;
        }
        return !isRecent(firstNonNull(task.getLeaseExpiresAt(), task.getUpdatedAt(), task.getCreatedAt()), now);
    }

    private boolean isStaleRunRecord(RunRecordEntity record, LocalDateTime now) {
        if (record == null || !"RUNNING".equalsIgnoreCase(record.getStatus())) {
            return false;
        }
        if (hasActiveDispatchForRecord(record, now)) {
            return false;
        }
        if (hasActiveWorker(record.getTenantId(), record.getWorkerCode(), now)) {
            return false;
        }
        return !isRecent(firstNonNull(record.getStartedAt(), record.getCreatedAt()), now);
    }

    private boolean hasActiveDispatchForRecord(RunRecordEntity record, LocalDateTime now) {
        if (record == null) {
            return false;
        }
        LambdaQueryWrapper<DispatchTaskEntity> query = new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, record.getTenantId())
                .eq(record.getProjectId() != null, DispatchTaskEntity::getProjectId, record.getProjectId())
                .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING");
        if (record.getId() != null) {
            query.eq(DispatchTaskEntity::getRunRecordId, record.getId());
        } else if (record.getWorkflowRunId() != null && record.getNodeCode() != null) {
            query.eq(DispatchTaskEntity::getWorkflowRunId, record.getWorkflowRunId())
                    .eq(DispatchTaskEntity::getNodeCode, record.getNodeCode());
        } else {
            return false;
        }
        for (DispatchTaskEntity task : dispatchTaskMapper.selectList(query)) {
            if (isActiveDispatchTask(task, now)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasActiveWorker(String tenantId, String workerCode, LocalDateTime now) {
        if (workerCode == null || workerCode.trim().isEmpty()) {
            return false;
        }
        WorkerLeaseEntity lease = workerLeaseMapper.selectOne(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(tenantId != null, WorkerLeaseEntity::getTenantId, tenantId)
                .eq(WorkerLeaseEntity::getWorkerCode, workerCode)
                .eq(WorkerLeaseEntity::getStatus, "ONLINE")
                .orderByDesc(WorkerLeaseEntity::getLastHeartbeatAt)
                .last("limit 1"));
        if (lease == null || lease.getLastHeartbeatAt() == null) {
            return false;
        }
        return !lease.getLastHeartbeatAt().isBefore(workerOfflineCutoff(now));
    }

    private boolean hasNewerTerminalRecord(RunRecordEntity record) {
        if (record == null || record.getWorkflowRunId() == null || record.getNodeCode() == null || record.getCreatedAt() == null) {
            return false;
        }
        Long count = runRecordMapper.selectCount(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getTenantId, record.getTenantId())
                .eq(record.getProjectId() != null, RunRecordEntity::getProjectId, record.getProjectId())
                .eq(RunRecordEntity::getWorkflowRunId, record.getWorkflowRunId())
                .eq(RunRecordEntity::getNodeCode, record.getNodeCode())
                .ne(record.getId() != null, RunRecordEntity::getId, record.getId())
                .in(RunRecordEntity::getStatus, "SUCCESS", "FAILED")
                .gt(RunRecordEntity::getCreatedAt, record.getCreatedAt()));
        return count != null && count > 0L;
    }

    private void failLinkedRunRecord(DispatchTaskEntity task, LocalDateTime endedAt) {
        if (task == null || task.getRunRecordId() == null) {
            return;
        }
        RunRecordEntity record = runRecordMapper.selectById(task.getRunRecordId());
        if (record != null && "RUNNING".equalsIgnoreCase(record.getStatus())) {
            failRunRecord(record, endedAt, AUTO_RECOVERY_REASON);
        }
    }

    private void failDispatchTask(DispatchTaskEntity task, LocalDateTime endedAt, String reason) {
        Map<String, Object> payload = task.getPayloadJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(task.getPayloadJson());
        payload.put("error", reason);
        payload.put("exceptionType", "STALE_EXECUTION_RECOVERY");
        payload.put("recovered", Boolean.TRUE);
        payload.put("recoveredAt", String.valueOf(endedAt));
        task.setStatus("FAILED");
        task.setPayloadJson(payload);
        task.setLeaseExpiresAt(endedAt);
        dispatchTaskMapper.updateById(task);
    }

    private void failRunRecord(RunRecordEntity record, LocalDateTime endedAt, String reason) {
        Map<String, Object> payload = record.getPayloadJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(record.getPayloadJson());
        payload.put("error", reason);
        payload.put("exceptionType", "STALE_EXECUTION_RECOVERY");
        payload.put("recovered", Boolean.TRUE);
        payload.put("recoveredAt", String.valueOf(endedAt));
        record.setStatus("FAILED");
        record.setEndedAt(record.getEndedAt() == null ? endedAt : record.getEndedAt());
        record.setMessage(appendMessage(record.getMessage(), reason));
        record.setPayloadJson(payload);
        record.setResultJson(payload);
        runRecordMapper.updateById(record);
    }

    private List<DispatchTaskEntity> selectActiveDispatchTasks(Scope scope) {
        return dispatchTaskMapper.selectList(applyDispatchScope(new LambdaQueryWrapper<DispatchTaskEntity>()
                .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING"), scope));
    }

    private List<DispatchTaskEntity> selectRunningDispatchTasks(Scope scope) {
        return dispatchTaskMapper.selectList(applyDispatchScope(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getStatus, "RUNNING"), scope));
    }

    private List<RunRecordEntity> selectCandidateRunRecords(Scope scope) {
        return runRecordMapper.selectList(applyRunRecordScope(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getStatus, "RUNNING"), scope));
    }

    private LambdaQueryWrapper<DispatchTaskEntity> applyDispatchScope(LambdaQueryWrapper<DispatchTaskEntity> query, Scope scope) {
        return query.eq(scope.tenantId != null, DispatchTaskEntity::getTenantId, scope.tenantId)
                .eq(scope.projectId != null, DispatchTaskEntity::getProjectId, scope.projectId)
                .eq(scope.workflowDefinitionId != null, DispatchTaskEntity::getWorkflowDefinitionId, scope.workflowDefinitionId)
                .eq(scope.workflowRunId != null, DispatchTaskEntity::getWorkflowRunId, scope.workflowRunId)
                .eq(scope.collectionTaskId != null, DispatchTaskEntity::getCollectionTaskId, scope.collectionTaskId)
                .eq(scope.qualityTaskId != null, DispatchTaskEntity::getQualityTaskId, scope.qualityTaskId);
    }

    private LambdaQueryWrapper<RunRecordEntity> applyRunRecordScope(LambdaQueryWrapper<RunRecordEntity> query, Scope scope) {
        return query.eq(scope.tenantId != null, RunRecordEntity::getTenantId, scope.tenantId)
                .eq(scope.projectId != null, RunRecordEntity::getProjectId, scope.projectId)
                .eq(scope.workflowDefinitionId != null, RunRecordEntity::getWorkflowDefinitionId, scope.workflowDefinitionId)
                .eq(scope.workflowRunId != null, RunRecordEntity::getWorkflowRunId, scope.workflowRunId)
                .eq(scope.collectionTaskId != null, RunRecordEntity::getCollectionTaskId, scope.collectionTaskId)
                .eq(scope.qualityTaskId != null, RunRecordEntity::getQualityTaskId, scope.qualityTaskId);
    }

    private boolean isRecent(LocalDateTime value, LocalDateTime now) {
        return value != null && value.isAfter(workerOfflineCutoff(now));
    }

    private LocalDateTime workerOfflineCutoff(LocalDateTime now) {
        long minutes = properties.getDispatch().getWorkerOfflineGraceMinutes() == null
                ? 120L
                : properties.getDispatch().getWorkerOfflineGraceMinutes();
        return now.minusMinutes(Math.max(1L, minutes));
    }

    private LocalDateTime firstNonNull(LocalDateTime... values) {
        if (values == null) {
            return null;
        }
        for (LocalDateTime value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String appendMessage(String current, String reason) {
        String base = current == null || current.trim().isEmpty() ? "" : current.trim();
        String result = base.isEmpty() ? reason : base + " | " + reason;
        if (result.length() <= RUN_RECORD_MESSAGE_MAX_LENGTH) {
            return result;
        }
        return result.substring(0, RUN_RECORD_MESSAGE_MAX_LENGTH - 3) + "...";
    }

    private static class Scope {
        private String tenantId;
        private Long projectId;
        private Long workflowDefinitionId;
        private Long workflowRunId;
        private Long collectionTaskId;
        private Long qualityTaskId;

        private static Scope all() {
            return new Scope();
        }

        private static Scope workflow(String tenantId, Long projectId, Long workflowDefinitionId) {
            Scope scope = new Scope();
            scope.tenantId = tenantId;
            scope.projectId = projectId;
            scope.workflowDefinitionId = workflowDefinitionId;
            return scope;
        }

        private static Scope workflowRun(String tenantId, Long projectId, Long workflowRunId) {
            Scope scope = new Scope();
            scope.tenantId = tenantId;
            scope.projectId = projectId;
            scope.workflowRunId = workflowRunId;
            return scope;
        }

        private static Scope collectionTask(String tenantId, Long projectId, Long collectionTaskId) {
            Scope scope = new Scope();
            scope.tenantId = tenantId;
            scope.projectId = projectId;
            scope.collectionTaskId = collectionTaskId;
            return scope;
        }

        private static Scope qualityTask(String tenantId, Long projectId, Long qualityTaskId) {
            Scope scope = new Scope();
            scope.tenantId = tenantId;
            scope.projectId = projectId;
            scope.qualityTaskId = qualityTaskId;
            return scope;
        }
    }
}
