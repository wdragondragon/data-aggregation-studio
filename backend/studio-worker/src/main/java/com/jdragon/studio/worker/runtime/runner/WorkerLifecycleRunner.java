package com.jdragon.studio.worker.runtime.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "studio.worker.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class WorkerLifecycleRunner {

    private final DispatchTaskMapper dispatchTaskMapper;
    private final WorkerLeaseMapper workerLeaseMapper;
    private final RunRecordMapper runRecordMapper;
    private final List<NodeExecutor> nodeExecutors;
    private final ExecutionEventPublisher executionEventPublisher;
    private final StudioPlatformProperties properties;
    private final CollectionTaskService collectionTaskService;
    private final QualityTaskService qualityTaskService;
    private final CollectionTaskAssemblerService collectionTaskAssemblerService;
    private final RunLogFileService runLogFileService;
    private final WorkerAuthorizationService workerAuthorizationService;
    private final ClusterInstanceIdentity clusterInstanceIdentity;
    private volatile boolean acceptingTasks = false;

    public WorkerLifecycleRunner(DispatchTaskMapper dispatchTaskMapper,
                                 WorkerLeaseMapper workerLeaseMapper,
                                 RunRecordMapper runRecordMapper,
                                 List<NodeExecutor> nodeExecutors,
                                 ExecutionEventPublisher executionEventPublisher,
                                 StudioPlatformProperties properties,
                                 CollectionTaskService collectionTaskService,
                                 QualityTaskService qualityTaskService,
                                 CollectionTaskAssemblerService collectionTaskAssemblerService,
                                 RunLogFileService runLogFileService,
                                 WorkerAuthorizationService workerAuthorizationService,
                                 ClusterInstanceIdentity clusterInstanceIdentity) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.workerLeaseMapper = workerLeaseMapper;
        this.runRecordMapper = runRecordMapper;
        this.nodeExecutors = nodeExecutors;
        this.executionEventPublisher = executionEventPublisher;
        this.properties = properties;
        this.collectionTaskService = collectionTaskService;
        this.qualityTaskService = qualityTaskService;
        this.collectionTaskAssemblerService = collectionTaskAssemblerService;
        this.runLogFileService = runLogFileService;
        this.workerAuthorizationService = workerAuthorizationService;
        this.clusterInstanceIdentity = clusterInstanceIdentity;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverLeasedRunningTasks() {
        String workerGroupCode = workerGroupCode();
        List<DispatchTaskEntity> runningTasks = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getStatus, "RUNNING")
                .and(wrapper -> wrapper.eq(DispatchTaskEntity::getWorkerGroupCode, workerGroupCode)
                        .or()
                        .eq(DispatchTaskEntity::getLeaseOwner, workerGroupCode)
                        .or()
                        .eq(DispatchTaskEntity::getLeaseOwner, workerCode()))
                .and(wrapper -> wrapper.eq(DispatchTaskEntity::getWorkerInstanceId, clusterInstanceIdentity.instanceId())
                        .or()
                        .isNull(DispatchTaskEntity::getWorkerInstanceId)));
        for (DispatchTaskEntity task : runningTasks) {
            recoverInterruptedTask(task);
        }
    }

    @Scheduled(fixedDelay = 5000L)
    public void heartbeat() {
        LocalDateTime now = LocalDateTime.now();
        String workerGroupCode = workerGroupCode();
        WorkerLeaseEntity lease = workerLeaseMapper.selectOne(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getWorkerGroupCode, workerGroupCode)
                .and(wrapper -> wrapper.eq(WorkerLeaseEntity::getInstanceId, clusterInstanceIdentity.instanceId())
                        .or()
                        .isNull(WorkerLeaseEntity::getInstanceId))
                .orderByDesc(WorkerLeaseEntity::getLastHeartbeatAt)
                .last("limit 1"));
        if (lease == null) {
            lease = new WorkerLeaseEntity();
            lease.setWorkerGroupCode(workerGroupCode);
            lease.setWorkerCode(workerCode());
            lease.setWorkerKind(workerKind());
            lease.setStatus("ONLINE");
            lease.setInstanceId(clusterInstanceIdentity.instanceId());
            lease.setHostName(clusterInstanceIdentity.hostName());
            lease.setPodName(clusterInstanceIdentity.podName());
            lease.setNodeName(clusterInstanceIdentity.nodeName());
            lease.setCapabilitiesJson(new LinkedHashMap<String, Object>());
            workerLeaseMapper.insert(lease);
        }
        lease.setStatus("ONLINE");
        lease.setWorkerGroupCode(workerGroupCode);
        lease.setWorkerCode(workerCode());
        lease.setWorkerKind(workerKind());
        lease.setInstanceId(clusterInstanceIdentity.instanceId());
        lease.setHostName(clusterInstanceIdentity.hostName());
        lease.setPodName(clusterInstanceIdentity.podName());
        lease.setNodeName(clusterInstanceIdentity.nodeName());
        lease.setLastHeartbeatAt(now);
        lease.setLeaseExpiresAt(now.plusSeconds(StudioConstants.WORKER_HEARTBEAT_TIMEOUT_SECONDS));
        Map<String, Object> capabilities = lease.getCapabilitiesJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(lease.getCapabilitiesJson());
        capabilities.put("workerGroupCode", workerGroupCode);
        capabilities.put("workerCode", workerCode());
        capabilities.put("apiBaseUrl", properties.getWorkerApiBaseUrl());
        capabilities.put("instanceId", clusterInstanceIdentity.instanceId());
        capabilities.put("podName", clusterInstanceIdentity.podName());
        capabilities.put("nodeName", clusterInstanceIdentity.nodeName());
        lease.setCapabilitiesJson(capabilities);
        workerLeaseMapper.updateById(lease);
        acceptingTasks = true;
        renewRunningTaskLeases();
    }

    @Scheduled(fixedDelay = 3000L)
    public void pollAndExecute() {
        if (!acceptingTasks || !hasCurrentActiveLease(LocalDateTime.now())) {
            acceptingTasks = false;
            return;
        }
        List<DispatchTaskEntity> queued = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getStatus, "QUEUED")
                .orderByAsc(DispatchTaskEntity::getCreatedAt)
                .last("limit 10"));
        for (DispatchTaskEntity task : queued) {
            if (!isAuthorized(task)) {
                continue;
            }
            if (!claimTask(task)) {
                continue;
            }
            LocalDateTime startedAt = LocalDateTime.now();
            RunRecordEntity runRecord = null;
            RunLogFileService.PreparedRunLog preparedRunLog = null;
            RunLogFileService.RunLogScope runLogScope = null;
            try {
                DispatchTaskEntity claimedTask = dispatchTaskMapper.selectById(task.getId());
                if (claimedTask != null) {
                    task = claimedTask;
                }
                runRecord = createRunRecord(task, startedAt);
                preparedRunLog = runLogFileService.prepare(runRecord.getId());
                runRecord.setLogFilePath(preparedRunLog.getRelativePath());
                runRecord.setLogCharset(preparedRunLog.getCharset());
                runRecord.setLogSizeBytes(0L);
                runRecord.setLogStorageType(runLogStorageType());
                runRecord.setLogStatus("WRITING");
                runRecordMapper.updateById(runRecord);
                task.setRunRecordId(runRecord.getId());
                updateOwnedRunningTask(task);
                runLogScope = runLogFileService.openScope(preparedRunLog);
                log.info("Starting dispatch task {} as runRecord {}", task.getId(), runRecord.getId());
                WorkflowNodeDefinition node = toNode(task);
                Map<String, Object> runtimeContext = new LinkedHashMap<String, Object>();
                runtimeContext.put("jobId", runRecord.getId());
                runtimeContext.put("runRecordId", runRecord.getId());
                runtimeContext.put("runLogId", String.valueOf(runRecord.getId()));
                runtimeContext.put("tenantId", task.getTenantId());
                runtimeContext.put("projectId", task.getProjectId());
                runtimeContext.put("workerGroupCode", workerGroupCode());
                runtimeContext.put("workerCode", workerCode());
                runtimeContext.put("workerInstanceId", clusterInstanceIdentity.instanceId());
                runtimeContext.put("workerPodName", clusterInstanceIdentity.podName());
                runtimeContext.put("workerNodeName", clusterInstanceIdentity.nodeName());
                runtimeContext.put("triggeredByUserId", task.getTriggeredByUserId());
                Map<String, Object> result = executeWithTaskContext(task, node, runtimeContext);
                String resultStatus = resolveExecutionStatus(result);
                LocalDateTime endedAt = LocalDateTime.now();
                if ("FAILED".equalsIgnoreCase(resultStatus)) {
                    log.warn("Dispatch task {} completed with FAILED result as runRecord {}", task.getId(), runRecord.getId());
                } else {
                    log.info("Completed dispatch task {} as runRecord {}", task.getId(), runRecord.getId());
                }
                closeRunLogScope(runLogScope);
                runLogScope = null;
                RunLogFileService.RunLogStorageResult logStorageResult = finalizeRunLog(preparedRunLog, runRecord);
                if ("FAILED".equalsIgnoreCase(resultStatus)) {
                    task.setStatus("FAILED");
                    task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
                    task.setPayloadJson(result);
                    updateOwnedRunningTask(task);
                    publishEvent("FAILED", task, runRecord, startedAt, endedAt,
                            logStorageResult, result);
                } else {
                    task.setStatus("SUCCESS");
                    task.setPayloadJson(result);
                    updateOwnedRunningTask(task);
                    publishEvent("SUCCESS", task, runRecord, startedAt, endedAt,
                            logStorageResult, result);
                }
            } catch (Throwable e) {
                log.error("Dispatch task {} failed", task.getId(), e);
                closeRunLogScope(runLogScope);
                runLogScope = null;
                task.setStatus("FAILED");
                task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
                Map<String, Object> payload = task.getPayloadJson() == null
                        ? new LinkedHashMap<String, Object>()
                        : task.getPayloadJson();
                payload.put("error", e.getMessage());
                payload.put("exceptionType", e.getClass().getName());
                payload.put("stackTrace", stackTraceOf(e));
                task.setPayloadJson(payload);
                updateOwnedRunningTask(task);
                if (runRecord != null) {
                    RunLogFileService.RunLogStorageResult logStorageResult = finalizeRunLog(preparedRunLog, runRecord);
                    publishEvent("FAILED", task, runRecord, startedAt, LocalDateTime.now(),
                            logStorageResult, payload);
                }
            } finally {
                closeRunLogScope(runLogScope);
            }
        }
    }

    @Scheduled(initialDelay = 10000L, fixedDelay = 10000L)
    public void syncActiveRunLogs() {
        Map<Long, RunLogFileService.RunLogStorageResult> results = runLogFileService.syncActiveObjectLogs();
        if (results == null || results.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, RunLogFileService.RunLogStorageResult> entry : results.entrySet()) {
            RunRecordEntity runRecord = runRecordMapper.selectById(entry.getKey());
            if (runRecord == null || !"RUNNING".equalsIgnoreCase(runRecord.getStatus())) {
                continue;
            }
            applyRunLogStorageResult(runRecord, entry.getValue());
            runRecordMapper.updateById(runRecord);
        }
    }

    @PreDestroy
    public void shutdown() {
        acceptingTasks = false;
        WorkerLeaseEntity update = new WorkerLeaseEntity();
        update.setStatus("OFFLINE");
        update.setLeaseExpiresAt(LocalDateTime.now());
        workerLeaseMapper.update(update, new LambdaUpdateWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getWorkerGroupCode, workerGroupCode())
                .eq(WorkerLeaseEntity::getInstanceId, clusterInstanceIdentity.instanceId()));
    }

    public boolean isAcceptingTasks() {
        return acceptingTasks;
    }

    private void renewRunningTaskLeases() {
        LocalDateTime leaseExpiresAt = LocalDateTime.now().plusMinutes(dispatchLeaseMinutes());
        List<DispatchTaskEntity> runningTasks = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getStatus, "RUNNING")
                .eq(DispatchTaskEntity::getWorkerGroupCode, workerGroupCode())
                .eq(DispatchTaskEntity::getWorkerInstanceId, clusterInstanceIdentity.instanceId()));
        for (DispatchTaskEntity task : runningTasks) {
            task.setLeaseExpiresAt(leaseExpiresAt);
            updateOwnedRunningTask(task);
        }
    }

    private boolean claimTask(DispatchTaskEntity task) {
        if (task == null || task.getId() == null) {
            return false;
        }
        DispatchTaskEntity update = new DispatchTaskEntity();
        update.setStatus("RUNNING");
        update.setWorkerGroupCode(workerGroupCode());
        update.setLeaseOwner(workerGroupCode());
        update.setWorkerInstanceId(clusterInstanceIdentity.instanceId());
        update.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(dispatchLeaseMinutes()));
        update.setPayloadJson(null);
        int updated = dispatchTaskMapper.update(update, new LambdaUpdateWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getId, task.getId())
                .eq(DispatchTaskEntity::getStatus, "QUEUED"));
        return updated == 1;
    }

    private boolean isAuthorized(DispatchTaskEntity task) {
        if (task == null) {
            return false;
        }
        return workerAuthorizationService.isWorkerAuthorizedForProject(task.getTenantId(), task.getProjectId(), workerGroupCode());
    }

    private void updateOwnedRunningTask(DispatchTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        dispatchTaskMapper.update(task, new LambdaUpdateWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getId, task.getId())
                .eq(DispatchTaskEntity::getWorkerGroupCode, workerGroupCode())
                .eq(DispatchTaskEntity::getWorkerInstanceId, clusterInstanceIdentity.instanceId()));
    }

    private void updateOwnedOrLegacyRunningTask(DispatchTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        dispatchTaskMapper.update(task, new LambdaUpdateWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getId, task.getId())
                .and(wrapper -> wrapper.eq(DispatchTaskEntity::getWorkerGroupCode, workerGroupCode())
                        .or()
                        .eq(DispatchTaskEntity::getLeaseOwner, workerGroupCode())
                        .or()
                        .eq(DispatchTaskEntity::getLeaseOwner, workerCode()))
                .and(wrapper -> wrapper.eq(DispatchTaskEntity::getWorkerInstanceId, clusterInstanceIdentity.instanceId())
                        .or()
                        .isNull(DispatchTaskEntity::getWorkerInstanceId)));
    }

    private void closeRunLogScope(RunLogFileService.RunLogScope runLogScope) {
        if (runLogScope != null) {
            runLogScope.close();
        }
    }

    private RunLogFileService.RunLogStorageResult finalizeRunLog(RunLogFileService.PreparedRunLog preparedRunLog,
                                                                 RunRecordEntity runRecord) {
        RunLogFileService.RunLogStorageResult result = runLogFileService.finalizeLog(preparedRunLog);
        if (runRecord != null) {
            applyRunLogStorageResult(runRecord, result);
            runRecordMapper.updateById(runRecord);
        }
        return result;
    }

    private void applyRunLogStorageResult(RunRecordEntity runRecord, RunLogFileService.RunLogStorageResult result) {
        if (runRecord == null || result == null) {
            return;
        }
        runRecord.setLogSizeBytes(result.getSizeBytes());
        runRecord.setLogStorageType(result.getStorageType());
        if (hasText(result.getBucket())) {
            runRecord.setLogObjectBucket(result.getBucket());
        }
        if (hasText(result.getObjectKey())) {
            runRecord.setLogObjectKey(result.getObjectKey());
        }
        runRecord.setLogChunkCount(Integer.valueOf(hasText(runRecord.getLogObjectKey()) ? 1 : 0));
        runRecord.setLogStatus(result.getStatus());
        runRecord.setLogErrorSummary(result.getErrorSummary());
    }

    private void recoverInterruptedTask(DispatchTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        RunRecordEntity runRecord = task.getRunRecordId() == null ? null : runRecordMapper.selectById(task.getRunRecordId());
        LocalDateTime startedAt = runRecord != null && runRecord.getStartedAt() != null
                ? runRecord.getStartedAt()
                : (task.getCreatedAt() != null ? task.getCreatedAt() : LocalDateTime.now());
        LocalDateTime endedAt = LocalDateTime.now();
        Map<String, Object> payload = task.getPayloadJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(task.getPayloadJson());
        payload.put("error", "Task was interrupted by worker restart before completion");
        payload.put("exceptionType", "WORKER_RESTART_INTERRUPTED");
        payload.put("recovered", Boolean.TRUE);
        task.setStatus("FAILED");
        task.setPayloadJson(payload);
        task.setLeaseExpiresAt(endedAt);
        updateOwnedOrLegacyRunningTask(task);
        if (runRecord != null) {
            RunLogFileService.RunLogStorageResult logStorageResult = runLogFileService.syncExistingLog(
                    runRecord.getLogFilePath(), runRecord.getLogCharset());
            applyRunLogStorageResult(runRecord, logStorageResult);
            runRecordMapper.updateById(runRecord);
            publishEvent("FAILED", task, runRecord, startedAt, endedAt, logStorageResult, payload);
        }
        log.warn("Recovered interrupted dispatch task {} owned by worker group {}", task.getId(), workerGroupCode());
    }

    private Map<String, Object> execute(WorkflowNodeDefinition node, Map<String, Object> runtimeContext) {
        for (NodeExecutor executor : nodeExecutors) {
            if (executor.supports(node)) {
                return executor.execute(node, runtimeContext == null
                        ? new LinkedHashMap<String, Object>()
                        : runtimeContext);
            }
        }
        throw new IllegalStateException("No executor for node type " + node.getNodeType());
    }

    private Map<String, Object> executeWithTaskContext(DispatchTaskEntity task,
                                                       WorkflowNodeDefinition node,
                                                       Map<String, Object> runtimeContext) {
        StudioRequestContext previousContext = StudioRequestContextHolder.getContext();
        StudioRequestContext taskContext = new StudioRequestContext();
        taskContext.setTenantId(task.getTenantId());
        taskContext.setProjectId(task.getProjectId());
        taskContext.setUsername(workerCode());
        StudioRequestContextHolder.setContext(taskContext);
        try {
            return execute(node, runtimeContext);
        } finally {
            if (previousContext == null) {
                StudioRequestContextHolder.clear();
            } else {
                StudioRequestContextHolder.setContext(previousContext);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private WorkflowNodeDefinition toNode(DispatchTaskEntity dispatchTask) {
        String nodeCode = dispatchTask.getNodeCode();
        Map<String, Object> payload = dispatchTask.getPayloadJson() == null
                ? new LinkedHashMap<String, Object>()
                : dispatchTask.getPayloadJson();
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeCode(nodeCode);
        node.setNodeName(nodeCode);
        node.setNodeType(com.jdragon.studio.dto.enums.NodeType.valueOf(String.valueOf(payload.get("nodeType"))));
        Object config = payload.get("config");
        if (config instanceof Map) {
            node.setConfig((Map<String, Object>) config);
        } else {
            node.setConfig(new LinkedHashMap<String, Object>());
        }
        if (node.getNodeType() == com.jdragon.studio.dto.enums.NodeType.COLLECTION_TASK) {
            Long collectionTaskId = resolveCollectionTaskId(dispatchTask, payload, node.getConfig());
            com.jdragon.studio.dto.model.CollectionTaskDefinitionView task = collectionTaskService.requireOnline(collectionTaskId);
            node.setNodeName(task.getName());
            node.setConfig(collectionTaskAssemblerService.assemble(task));
        } else if (node.getNodeType() == com.jdragon.studio.dto.enums.NodeType.QUALITY_TASK) {
            Long qualityTaskId = resolveQualityTaskId(dispatchTask, payload, node.getConfig());
            com.jdragon.studio.dto.model.QualityTaskDefinitionView task = qualityTaskService.requireOnline(qualityTaskId);
            node.setNodeName(task.getTaskName());
            Map<String, Object> qualityConfig = node.getConfig() == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(node.getConfig());
            qualityConfig.put("qualityTaskId", task.getId());
            node.setConfig(qualityConfig);
        }
        return node;
    }

    private Long resolveCollectionTaskId(DispatchTaskEntity dispatchTask,
                                         Map<String, Object> payload,
                                         Map<String, Object> config) {
        Long collectionTaskId = dispatchTask.getCollectionTaskId();
        if (collectionTaskId != null) {
            return collectionTaskId;
        }
        collectionTaskId = parseLong(payload.get("collectionTaskId"));
        if (collectionTaskId != null) {
            return collectionTaskId;
        }
        return parseLong(config.get("collectionTaskId"));
    }

    private Long resolveQualityTaskId(DispatchTaskEntity dispatchTask,
                                      Map<String, Object> payload,
                                      Map<String, Object> config) {
        Long qualityTaskId = dispatchTask.getQualityTaskId();
        if (qualityTaskId != null) {
            return qualityTaskId;
        }
        qualityTaskId = parseLong(payload.get("qualityTaskId"));
        if (qualityTaskId != null) {
            return qualityTaskId;
        }
        return parseLong(config.get("qualityTaskId"));
    }

    private void publishEvent(String eventType,
                              DispatchTaskEntity task,
                              RunRecordEntity runRecord,
                              LocalDateTime startedAt,
                              LocalDateTime endedAt,
                              RunLogFileService.RunLogStorageResult logStorageResult,
                              Map<String, Object> payload) {
        ExecutionEvent event = new ExecutionEvent();
        event.setEventType(eventType);
        event.setRunRecordId(runRecord.getId());
        event.setWorkflowDefinitionId(task.getWorkflowDefinitionId());
        event.setWorkflowVersionId(task.getWorkflowVersionId());
        event.setWorkflowRunId(task.getWorkflowRunId());
        event.setCollectionTaskId(task.getCollectionTaskId());
        event.setQualityTaskId(task.getQualityTaskId());
        event.setProjectId(task.getProjectId());
        event.setExecutionType(task.getExecutionType() == null ? DispatchExecutionType.WORKFLOW_NODE : DispatchExecutionType.valueOf(task.getExecutionType()));
        event.setNodeCode(task.getNodeCode());
        event.setWorkerGroupCode(workerGroupCode());
        event.setWorkerCode(workerCode());
        event.setWorkerInstanceId(clusterInstanceIdentity.instanceId());
        event.setWorkerPodName(clusterInstanceIdentity.podName());
        event.setWorkerNodeName(clusterInstanceIdentity.nodeName());
        event.setOccurredAt(endedAt);
        event.setStartedAt(startedAt);
        event.setEndedAt(endedAt);
        event.setLogFilePath(runRecord.getLogFilePath());
        event.setLogSizeBytes(resolveLogSizeBytes(runRecord, logStorageResult));
        event.setLogCharset(runRecord.getLogCharset());
        event.setLogStorageType(logStorageResult == null ? runRecord.getLogStorageType() : logStorageResult.getStorageType());
        event.setLogObjectBucket(resolveLogObjectBucket(runRecord, logStorageResult));
        event.setLogObjectKey(resolveLogObjectKey(runRecord, logStorageResult));
        event.setLogChunkCount(Integer.valueOf(hasText(event.getLogObjectKey()) ? 1 : 0));
        event.setLogStatus(logStorageResult == null ? runRecord.getLogStatus() : logStorageResult.getStatus());
        event.setLogErrorSummary(logStorageResult == null ? runRecord.getLogErrorSummary() : logStorageResult.getErrorSummary());
        event.setTriggeredByUserId(runRecord.getTriggeredByUserId());
        event.setPayload(payload);
        executionEventPublisher.publish(event);
    }

    private RunRecordEntity createRunRecord(DispatchTaskEntity task, LocalDateTime startedAt) {
        RunRecordEntity entity = new RunRecordEntity();
        entity.setTenantId(task.getTenantId());
        entity.setProjectId(task.getProjectId());
        entity.setExecutionType(task.getExecutionType());
        entity.setWorkflowRunId(task.getWorkflowRunId());
        entity.setWorkflowDefinitionId(task.getWorkflowDefinitionId());
        entity.setWorkflowVersionId(task.getWorkflowVersionId());
        entity.setCollectionTaskId(task.getCollectionTaskId());
        entity.setQualityTaskId(task.getQualityTaskId());
        entity.setNodeCode(task.getNodeCode());
        entity.setWorkerGroupCode(workerGroupCode());
        entity.setWorkerCode(workerCode());
        entity.setWorkerInstanceId(clusterInstanceIdentity.instanceId());
        entity.setWorkerPodName(clusterInstanceIdentity.podName());
        entity.setWorkerNodeName(clusterInstanceIdentity.nodeName());
        entity.setTriggeredByUserId(task.getTriggeredByUserId());
        entity.setStatus("RUNNING");
        entity.setMessage("Task execution started");
        entity.setStartedAt(startedAt);
        entity.setLogCharset("UTF-8");
        entity.setLogSizeBytes(0L);
        entity.setLogStorageType(runLogStorageType());
        entity.setLogStatus("WRITING");
        runRecordMapper.insert(entity);
        return entity;
    }

    private Long parseLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Long.parseLong(((String) value).trim());
        }
        return null;
    }

    private String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return writer.toString();
    }

    private String resolveExecutionStatus(Map<String, Object> result) {
        if (result == null) {
            return "SUCCESS";
        }
        Object status = result.get("status");
        return status == null ? "SUCCESS" : String.valueOf(status);
    }

    private long dispatchLeaseMinutes() {
        Long minutes = properties.getDispatch() == null ? null : properties.getDispatch().getDispatchLeaseMinutes();
        return Math.max(1L, minutes == null ? 10L : minutes.longValue());
    }

    private String runLogStorageType() {
        String value = properties.getRunLog() == null ? null : properties.getRunLog().getStorageType();
        return value == null || value.trim().isEmpty() ? RunLogStorageService.STORAGE_LOCAL : value.trim().toUpperCase();
    }

    private boolean hasCurrentActiveLease(LocalDateTime now) {
        WorkerLeaseEntity lease = workerLeaseMapper.selectOne(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getWorkerGroupCode, workerGroupCode())
                .eq(WorkerLeaseEntity::getInstanceId, clusterInstanceIdentity.instanceId())
                .eq(WorkerLeaseEntity::getStatus, StudioConstants.WORKER_STATUS_ONLINE)
                .orderByDesc(WorkerLeaseEntity::getLastHeartbeatAt)
                .last("limit 1"));
        if (lease == null) {
            return false;
        }
        if (lease.getLeaseExpiresAt() != null && lease.getLeaseExpiresAt().isAfter(now)) {
            return true;
        }
        LocalDateTime heartbeatThreshold = now.minusSeconds(StudioConstants.WORKER_HEARTBEAT_TIMEOUT_SECONDS);
        return lease.getLastHeartbeatAt() != null && lease.getLastHeartbeatAt().isAfter(heartbeatThreshold);
    }

    private void markCurrentLeaseOffline() {
        WorkerLeaseEntity update = new WorkerLeaseEntity();
        update.setStatus("OFFLINE");
        update.setLeaseExpiresAt(LocalDateTime.now());
        workerLeaseMapper.update(update, new LambdaUpdateWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getWorkerGroupCode, workerGroupCode())
                .eq(WorkerLeaseEntity::getInstanceId, clusterInstanceIdentity.instanceId()));
    }

    private String workerGroupCode() {
        return hasText(properties.getWorkerGroupCode()) ? properties.getWorkerGroupCode().trim() : workerCode();
    }

    private String workerCode() {
        return hasText(properties.getWorkerCode()) ? properties.getWorkerCode().trim() : workerGroupCodeFallback();
    }

    private String workerKind() {
        return properties.isDesktopRuntime() ? "DESKTOP" : "WORKER";
    }

    private String workerGroupCodeFallback() {
        String instanceId = clusterInstanceIdentity == null ? null : clusterInstanceIdentity.instanceId();
        return hasText(instanceId) ? instanceId : "worker-local";
    }

    private String resolveLogObjectBucket(RunRecordEntity runRecord, RunLogFileService.RunLogStorageResult result) {
        if (result != null && hasText(result.getBucket())) {
            return result.getBucket();
        }
        return runRecord.getLogObjectBucket();
    }

    private String resolveLogObjectKey(RunRecordEntity runRecord, RunLogFileService.RunLogStorageResult result) {
        if (result != null && hasText(result.getObjectKey())) {
            return result.getObjectKey();
        }
        return runRecord.getLogObjectKey();
    }

    private Long resolveLogSizeBytes(RunRecordEntity runRecord, RunLogFileService.RunLogStorageResult result) {
        if (result != null) {
            return Long.valueOf(result.getSizeBytes());
        }
        return runRecord.getLogSizeBytes() == null ? Long.valueOf(0L) : runRecord.getLogSizeBytes();
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}

