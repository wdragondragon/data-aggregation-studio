package com.jdragon.studio.worker.runtime.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class WorkerLifecycleRunner {

    private final DispatchTaskMapper dispatchTaskMapper;
    private final WorkerLeaseMapper workerLeaseMapper;
    private final RunRecordMapper runRecordMapper;
    private final List<NodeExecutor> nodeExecutors;
    private final ExecutionEventPublisher executionEventPublisher;
    private final StudioPlatformProperties properties;
    private final CollectionTaskService collectionTaskService;
    private final CollectionTaskAssemblerService collectionTaskAssemblerService;
    private final RunLogFileService runLogFileService;

    public WorkerLifecycleRunner(DispatchTaskMapper dispatchTaskMapper,
                                 WorkerLeaseMapper workerLeaseMapper,
                                 RunRecordMapper runRecordMapper,
                                 List<NodeExecutor> nodeExecutors,
                                 ExecutionEventPublisher executionEventPublisher,
                                 StudioPlatformProperties properties,
                                 CollectionTaskService collectionTaskService,
                                 CollectionTaskAssemblerService collectionTaskAssemblerService,
                                 RunLogFileService runLogFileService) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.workerLeaseMapper = workerLeaseMapper;
        this.runRecordMapper = runRecordMapper;
        this.nodeExecutors = nodeExecutors;
        this.executionEventPublisher = executionEventPublisher;
        this.properties = properties;
        this.collectionTaskService = collectionTaskService;
        this.collectionTaskAssemblerService = collectionTaskAssemblerService;
        this.runLogFileService = runLogFileService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverLeasedRunningTasks() {
        List<DispatchTaskEntity> runningTasks = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getStatus, "RUNNING")
                .eq(DispatchTaskEntity::getLeaseOwner, properties.getWorkerCode()));
        for (DispatchTaskEntity task : runningTasks) {
            recoverInterruptedTask(task);
        }
    }

    @Scheduled(fixedDelay = 5000L)
    public void heartbeat() {
        WorkerLeaseEntity lease = workerLeaseMapper.selectOne(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getWorkerCode, properties.getWorkerCode())
                .last("limit 1"));
        if (lease == null) {
            lease = new WorkerLeaseEntity();
            lease.setWorkerCode(properties.getWorkerCode());
            lease.setWorkerKind(properties.isDesktopRuntime() ? "DESKTOP" : "ONLINE");
            lease.setStatus("ONLINE");
            try {
                lease.setHostName(InetAddress.getLocalHost().getHostName());
            } catch (Exception e) {
                lease.setHostName("localhost");
            }
            lease.setCapabilitiesJson(new LinkedHashMap<String, Object>());
            workerLeaseMapper.insert(lease);
        }
        lease.setStatus("ONLINE");
        lease.setLastHeartbeatAt(LocalDateTime.now());
        Map<String, Object> capabilities = lease.getCapabilitiesJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(lease.getCapabilitiesJson());
        capabilities.put("apiBaseUrl", properties.getWorkerApiBaseUrl());
        lease.setCapabilitiesJson(capabilities);
        workerLeaseMapper.updateById(lease);
    }

    @Scheduled(fixedDelay = 3000L)
    public void pollAndExecute() {
        List<DispatchTaskEntity> queued = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getStatus, "QUEUED")
                .last("limit 10"));
        for (DispatchTaskEntity task : queued) {
            task.setStatus("RUNNING");
            task.setLeaseOwner(properties.getWorkerCode());
            task.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(10));
            LocalDateTime startedAt = LocalDateTime.now();
            RunRecordEntity runRecord = null;
            RunLogFileService.PreparedRunLog preparedRunLog = null;
            RunLogFileService.RunLogScope runLogScope = null;
            try {
                runRecord = createRunRecord(task, startedAt);
                preparedRunLog = runLogFileService.prepare(runRecord.getId());
                runRecord.setLogFilePath(preparedRunLog.getRelativePath());
                runRecord.setLogCharset(preparedRunLog.getCharset());
                runRecord.setLogSizeBytes(0L);
                runRecordMapper.updateById(runRecord);
                task.setRunRecordId(runRecord.getId());
                dispatchTaskMapper.updateById(task);
                runLogScope = runLogFileService.openScope(preparedRunLog);
                log.info("Starting dispatch task {} as runRecord {}", task.getId(), runRecord.getId());
                WorkflowNodeDefinition node = toNode(task);
                Map<String, Object> runtimeContext = new LinkedHashMap<String, Object>();
                runtimeContext.put("jobId", runRecord.getId());
                runtimeContext.put("runRecordId", runRecord.getId());
                runtimeContext.put("runLogId", String.valueOf(runRecord.getId()));
                runtimeContext.put("tenantId", task.getTenantId());
                runtimeContext.put("projectId", task.getProjectId());
                runtimeContext.put("workerCode", properties.getWorkerCode());
                Map<String, Object> result = executeWithTaskContext(task, node, runtimeContext);
                String resultStatus = resolveExecutionStatus(result);
                LocalDateTime endedAt = LocalDateTime.now();
                if ("FAILED".equalsIgnoreCase(resultStatus)) {
                    log.warn("Dispatch task {} completed with FAILED result as runRecord {}", task.getId(), runRecord.getId());
                    task.setStatus("FAILED");
                    task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
                    task.setPayloadJson(result);
                    dispatchTaskMapper.updateById(task);
                    publishEvent("FAILED", task, runRecord, startedAt, endedAt,
                            runLogFileService.fileSize(preparedRunLog.getRelativePath()), result);
                } else {
                    log.info("Completed dispatch task {} as runRecord {}", task.getId(), runRecord.getId());
                    task.setStatus("SUCCESS");
                    task.setPayloadJson(result);
                    dispatchTaskMapper.updateById(task);
                    publishEvent("SUCCESS", task, runRecord, startedAt, endedAt,
                            runLogFileService.fileSize(preparedRunLog.getRelativePath()), result);
                }
            } catch (Throwable e) {
                log.error("Dispatch task {} failed", task.getId(), e);
                task.setStatus("FAILED");
                task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
                Map<String, Object> payload = task.getPayloadJson() == null
                        ? new LinkedHashMap<String, Object>()
                        : task.getPayloadJson();
                payload.put("error", e.getMessage());
                payload.put("exceptionType", e.getClass().getName());
                payload.put("stackTrace", stackTraceOf(e));
                task.setPayloadJson(payload);
                dispatchTaskMapper.updateById(task);
                if (runRecord != null) {
                    publishEvent("FAILED", task, runRecord, startedAt, LocalDateTime.now(),
                            runLogFileService.fileSize(runRecord.getLogFilePath()), payload);
                }
            } finally {
                if (runLogScope != null) {
                    runLogScope.close();
                }
            }
        }
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
        dispatchTaskMapper.updateById(task);
        if (runRecord != null) {
            long logSize = runLogFileService.fileSize(runRecord.getLogFilePath());
            publishEvent("FAILED", task, runRecord, startedAt, endedAt, logSize, payload);
        }
        log.warn("Recovered interrupted dispatch task {} owned by worker {}", task.getId(), properties.getWorkerCode());
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
        taskContext.setUsername(properties.getWorkerCode());
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

    private void publishEvent(String eventType,
                              DispatchTaskEntity task,
                              RunRecordEntity runRecord,
                              LocalDateTime startedAt,
                              LocalDateTime endedAt,
                              long logSizeBytes,
                              Map<String, Object> payload) {
        ExecutionEvent event = new ExecutionEvent();
        event.setEventType(eventType);
        event.setRunRecordId(runRecord.getId());
        event.setWorkflowDefinitionId(task.getWorkflowDefinitionId());
        event.setWorkflowVersionId(task.getWorkflowVersionId());
        event.setWorkflowRunId(task.getWorkflowRunId());
        event.setCollectionTaskId(task.getCollectionTaskId());
        event.setProjectId(task.getProjectId());
        event.setExecutionType(task.getExecutionType() == null ? DispatchExecutionType.WORKFLOW_NODE : DispatchExecutionType.valueOf(task.getExecutionType()));
        event.setNodeCode(task.getNodeCode());
        event.setWorkerCode(properties.getWorkerCode());
        event.setOccurredAt(endedAt);
        event.setStartedAt(startedAt);
        event.setEndedAt(endedAt);
        event.setLogFilePath(runRecord.getLogFilePath());
        event.setLogSizeBytes(logSizeBytes);
        event.setLogCharset(runRecord.getLogCharset());
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
        entity.setNodeCode(task.getNodeCode());
        entity.setWorkerCode(properties.getWorkerCode());
        entity.setTriggeredByUserId(task.getTriggeredByUserId());
        entity.setStatus("RUNNING");
        entity.setMessage("Task execution started");
        entity.setStartedAt(startedAt);
        entity.setLogCharset("UTF-8");
        entity.setLogSizeBytes(0L);
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
}

