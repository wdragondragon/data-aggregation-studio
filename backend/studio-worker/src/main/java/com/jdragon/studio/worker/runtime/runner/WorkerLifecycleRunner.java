package com.jdragon.studio.worker.runtime.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchProtectedPayloadService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.RuntimeResourceRevisionService;
import com.jdragon.studio.infra.service.RuntimeClusterHeartbeatService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.worker.runtime.WorkflowDispatchNodeResolver;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import com.jdragon.studio.worker.plugin.ObjectStoragePluginRuntimeResolver;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "studio.worker.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class WorkerLifecycleRunner {

    /**
     * MyBatis-Plus' legacy Jackson type handler uses its own plain mapper.
     * Normalize completed payloads before persistence so JDBC values such as
     * LocalDateTime do not leave a dispatch permanently RUNNING.
     */
    private static final ObjectMapper DISPATCH_PAYLOAD_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<LinkedHashMap<String, Object>> PAYLOAD_MAP_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>() { };

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
    private final WorkflowDispatchNodeResolver workflowDispatchNodeResolver;
    private RuntimeClusterMapper runtimeClusterMapper;
    private FileTransferRunMapper fileTransferRunMapper;
    private RuntimeResourceRevisionService runtimeResourceRevisionService;
    private RuntimeClusterHeartbeatService runtimeClusterHeartbeatService;
    private DispatchProtectedPayloadService dispatchProtectedPayloadService;
    private ObjectStoragePluginRuntimeResolver pluginRuntimeResolver;
    private volatile boolean acceptingTasks = false;
    private final ExecutorService fileTransferExecutor;
    private final Semaphore fileTransferSlots;

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
                                 ClusterInstanceIdentity clusterInstanceIdentity,
                                 WorkflowDispatchNodeResolver workflowDispatchNodeResolver) {
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
        this.workflowDispatchNodeResolver = workflowDispatchNodeResolver;
        int fileTransferPoolSize = properties.getDispatch().getWorkerSchedulerPoolSize() == null
                || properties.getDispatch().getWorkerSchedulerPoolSize() < 1
                ? 4 : properties.getDispatch().getWorkerSchedulerPoolSize();
        this.fileTransferSlots = new Semaphore(fileTransferPoolSize);
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable,
                    "studio-worker-file-transfer-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.fileTransferExecutor = Executors.newFixedThreadPool(fileTransferPoolSize, threadFactory);
    }

    @Autowired(required = false)
    void setRuntimeClusterMapper(RuntimeClusterMapper runtimeClusterMapper) {
        this.runtimeClusterMapper = runtimeClusterMapper;
    }

    @Autowired(required = false)
    void setFileTransferRunMapper(FileTransferRunMapper fileTransferRunMapper) {
        this.fileTransferRunMapper = fileTransferRunMapper;
    }

    @Autowired(required = false)
    void setRuntimeResourceRevisionService(RuntimeResourceRevisionService runtimeResourceRevisionService) {
        this.runtimeResourceRevisionService = runtimeResourceRevisionService;
    }

    @Autowired
    void setDispatchProtectedPayloadService(DispatchProtectedPayloadService dispatchProtectedPayloadService) {
        this.dispatchProtectedPayloadService = dispatchProtectedPayloadService;
    }

    @Autowired
    void setRuntimeClusterHeartbeatService(RuntimeClusterHeartbeatService runtimeClusterHeartbeatService) {
        this.runtimeClusterHeartbeatService = runtimeClusterHeartbeatService;
    }

    @Autowired(required = false)
    void setPluginRuntimeResolver(ObjectStoragePluginRuntimeResolver pluginRuntimeResolver) {
        this.pluginRuntimeResolver = pluginRuntimeResolver;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverLeasedRunningTasks() {
        List<Long> runtimeClusterIds = currentRuntimeClusterIds();
        if (runtimeClusterIds.isEmpty()) {
            return;
        }
        String workerGroupCode = workerGroupCode();
        LambdaQueryWrapper<DispatchTaskEntity> query = new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getStatus, "RUNNING")
                .and(wrapper -> wrapper.eq(DispatchTaskEntity::getWorkerGroupCode, workerGroupCode)
                        .or()
                        .eq(DispatchTaskEntity::getLeaseOwner, workerGroupCode)
                        .or()
                        .eq(DispatchTaskEntity::getLeaseOwner, workerCode()))
                .and(wrapper -> wrapper.eq(DispatchTaskEntity::getWorkerInstanceId, clusterInstanceIdentity.instanceId())
                        .or()
                        .isNull(DispatchTaskEntity::getWorkerInstanceId))
                .in(DispatchTaskEntity::getTargetClusterId, runtimeClusterIds);
        if (hasText(clusterInstanceIdentity.bootId())) {
            query.and(wrapper -> wrapper.ne(DispatchTaskEntity::getWorkerBootId, clusterInstanceIdentity.bootId())
                    .or()
                    .isNull(DispatchTaskEntity::getWorkerBootId));
        } else {
            query.isNull(DispatchTaskEntity::getWorkerBootId);
        }
        List<DispatchTaskEntity> runningTasks = dispatchTaskMapper.selectList(query);
        for (DispatchTaskEntity task : runningTasks) {
            recoverInterruptedTask(task);
        }
        reconcileOrphanedFileTransferRuns(runtimeClusterIds);
        reconcileInvalidFileTransferDispatches(runtimeClusterIds);
    }

    private void reconcileOrphanedFileTransferRuns(List<Long> runtimeClusterIds) {
        if (fileTransferRunMapper == null || runtimeClusterIds == null || runtimeClusterIds.isEmpty()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(1L);
        List<FileTransferRunEntity> activeRuns = fileTransferRunMapper.selectList(
                new LambdaQueryWrapper<FileTransferRunEntity>()
                        .in(FileTransferRunEntity::getTargetRuntimeClusterId, runtimeClusterIds)
                        .in(FileTransferRunEntity::getStatus, "QUEUED", "RUNNING", "PAUSED")
                        .and(wrapper -> wrapper.le(FileTransferRunEntity::getUpdatedAt, cutoff)
                                .or().le(FileTransferRunEntity::getCreatedAt, cutoff))
                        .orderByAsc(FileTransferRunEntity::getUpdatedAt)
                        .last("limit 200"));
        for (FileTransferRunEntity run : activeRuns) {
            if (run == null || run.getId() == null) {
                continue;
            }
            Long activeDispatches = dispatchTaskMapper.selectCount(new LambdaQueryWrapper<DispatchTaskEntity>()
                    .eq(DispatchTaskEntity::getFileTransferRunId, run.getId())
                    .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING"));
            if (activeDispatches != null && activeDispatches.longValue() > 0L) {
                continue;
            }
            DispatchTaskEntity latestDispatch = dispatchTaskMapper.selectOne(new LambdaQueryWrapper<DispatchTaskEntity>()
                    .eq(DispatchTaskEntity::getFileTransferRunId, run.getId())
                    .orderByDesc(DispatchTaskEntity::getCreatedAt)
                    .orderByDesc(DispatchTaskEntity::getId)
                    .last("limit 1"));
            String reason = latestDispatch != null && "FAILED".equalsIgnoreCase(latestDispatch.getStatus())
                    ? "File transfer dispatch failed before execution; retry or remove the queue item"
                    : "File transfer run has no active dispatch; it was closed during worker recovery";
            fileTransferRunMapper.update(null, new LambdaUpdateWrapper<FileTransferRunEntity>()
                    .set(FileTransferRunEntity::getStatus, "FAILED")
                    .set(FileTransferRunEntity::getMessage, reason)
                    .set(FileTransferRunEntity::getCurrentBytesPerSecond, 0L)
                    .set(FileTransferRunEntity::getActiveFiles, 0)
                    .set(FileTransferRunEntity::getEndedAt, LocalDateTime.now())
                    .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                    .eq(FileTransferRunEntity::getId, run.getId())
                    .in(FileTransferRunEntity::getStatus, "QUEUED", "RUNNING", "PAUSED"));
            log.warn("Closed orphaned file transfer run {} during worker recovery: {}", run.getId(), reason);
        }
    }

    private void reconcileInvalidFileTransferDispatches(List<Long> runtimeClusterIds) {
        if (runtimeClusterIds == null || runtimeClusterIds.isEmpty()) {
            return;
        }
        List<DispatchTaskEntity> candidates = dispatchTaskMapper.selectList(
                new LambdaQueryWrapper<DispatchTaskEntity>()
                        .eq(DispatchTaskEntity::getExecutionType, DispatchExecutionType.FILE_TRANSFER.name())
                        .eq(DispatchTaskEntity::getStatus, "QUEUED")
                        .in(DispatchTaskEntity::getTargetClusterId, runtimeClusterIds)
                        .orderByAsc(DispatchTaskEntity::getCreatedAt)
                        .last("limit 200"));
        for (DispatchTaskEntity task : candidates) {
            if (resolveFileTransferRunId(task) != null) {
                continue;
            }
            String reason = "File transfer dispatch identity is incomplete; the dispatch was rejected during worker recovery";
            Map<String, Object> payload = task.getPayloadJson() == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(task.getPayloadJson());
            payload.put("error", reason);
            payload.put("exceptionType", "FILE_TRANSFER_DISPATCH_IDENTITY_INCOMPLETE");
            DispatchTaskEntity update = new DispatchTaskEntity();
            update.setStatus("FAILED");
            update.setPayloadJson(payload);
            update.setLeaseExpiresAt(LocalDateTime.now());
            int updated = dispatchTaskMapper.update(update, new LambdaUpdateWrapper<DispatchTaskEntity>()
                    .set(DispatchTaskEntity::getProtectedPayloadCiphertext, null)
                    .eq(DispatchTaskEntity::getId, task.getId())
                    .eq(DispatchTaskEntity::getStatus, "QUEUED"));
            if (updated == 1) {
                log.warn("Rejected file transfer dispatch {} during worker recovery: {}", task.getId(), reason);
            }
        }
    }

    public void heartbeat() {
        LocalDateTime now = LocalDateTime.now();
        List<RuntimeClusterEntity> runtimeClusters = currentRuntimeClusters();
        if (runtimeClusters.isEmpty()) {
            acceptingTasks = false;
            markCurrentLeaseOffline();
            log.warn("Worker runtime cluster {} is not registered or enabled; task polling is disabled", runtimeClusterCode());
            return;
        }
        for (RuntimeClusterEntity runtimeCluster : runtimeClusters) {
            heartbeatLease(runtimeCluster.getTenantId(), runtimeCluster.getId(), now);
        }
        updateRuntimeClusterHeartbeat(runtimeClusters, now);
        acceptingTasks = true;
        renewRunningTaskLeases();
    }

    private void heartbeatLease(String tenantId, Long runtimeClusterId, LocalDateTime now) {
        String workerGroupCode = workerGroupCode();
        LambdaQueryWrapper<WorkerLeaseEntity> leaseQuery = new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getTenantId, tenantId)
                .eq(WorkerLeaseEntity::getWorkerGroupCode, workerGroupCode)
                .and(wrapper -> wrapper.eq(WorkerLeaseEntity::getInstanceId, clusterInstanceIdentity.instanceId())
                        .or()
                        .isNull(WorkerLeaseEntity::getInstanceId));
        leaseQuery.eq(WorkerLeaseEntity::getRuntimeClusterId, runtimeClusterId)
                .eq(WorkerLeaseEntity::getRuntimeClusterCode, runtimeClusterCode());
        WorkerLeaseEntity lease = workerLeaseMapper.selectOne(leaseQuery
                .orderByDesc(WorkerLeaseEntity::getLastHeartbeatAt)
                .last("limit 1"));
        if (lease == null) {
            lease = new WorkerLeaseEntity();
            lease.setTenantId(tenantId);
            lease.setRuntimeClusterId(runtimeClusterId);
            lease.setRuntimeClusterCode(runtimeClusterCode());
            lease.setWorkerGroupCode(workerGroupCode);
            lease.setWorkerCode(workerCode());
            lease.setWorkerKind(workerKind());
            lease.setStatus("ONLINE");
            lease.setInstanceId(clusterInstanceIdentity.instanceId());
            lease.setBootId(clusterInstanceIdentity.bootId());
            lease.setHostName(clusterInstanceIdentity.hostName());
            lease.setPodName(clusterInstanceIdentity.podName());
            lease.setNodeName(clusterInstanceIdentity.nodeName());
            lease.setCapabilitiesJson(new LinkedHashMap<String, Object>());
            workerLeaseMapper.insert(lease);
        }
        lease.setStatus("ONLINE");
        lease.setTenantId(tenantId);
        lease.setWorkerGroupCode(workerGroupCode);
        lease.setWorkerCode(workerCode());
        lease.setWorkerKind(workerKind());
        lease.setRuntimeClusterId(runtimeClusterId);
        lease.setRuntimeClusterCode(runtimeClusterCode());
        lease.setInstanceId(clusterInstanceIdentity.instanceId());
        lease.setBootId(clusterInstanceIdentity.bootId());
        lease.setRuntimeVersion(properties.getRuntimeVersion());
        String pluginFingerprint = properties.getPluginFingerprint();
        Map<String, Object> pluginRuntimeStatus = null;
        if (pluginRuntimeResolver != null) {
            pluginRuntimeStatus = pluginRuntimeResolver.statusSnapshot();
            if (pluginRuntimeResolver.lazyEnabled()) {
                pluginFingerprint = pluginRuntimeResolver.fingerprint();
            }
        }
        lease.setPluginFingerprint(pluginFingerprint);
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
        capabilities.put("bootId", clusterInstanceIdentity.bootId());
        capabilities.put("runtimeClusterId", runtimeClusterId);
        capabilities.put("runtimeClusterCode", runtimeClusterCode());
        capabilities.put("podName", clusterInstanceIdentity.podName());
        capabilities.put("nodeName", clusterInstanceIdentity.nodeName());
        if (pluginRuntimeStatus != null) {
            capabilities.put("pluginRuntime", pluginRuntimeStatus);
        }
        lease.setCapabilitiesJson(capabilities);
        workerLeaseMapper.updateById(lease);
    }

    @Scheduled(fixedDelay = 3000L)
    public void pollAndExecute() {
        if (!acceptingTasks || !hasCurrentActiveLease(LocalDateTime.now())) {
            acceptingTasks = false;
            return;
        }
        List<Long> runtimeClusterIds = currentRuntimeClusterIds();
        if (runtimeClusterIds.isEmpty()) {
            acceptingTasks = false;
            return;
        }
        LambdaQueryWrapper<DispatchTaskEntity> queuedQuery = new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getStatus, "QUEUED")
                .in(DispatchTaskEntity::getTargetClusterId, runtimeClusterIds);
        List<DispatchTaskEntity> queued = dispatchTaskMapper.selectList(queuedQuery
                .orderByAsc(DispatchTaskEntity::getCreatedAt)
                .last("limit 10"));
        for (DispatchTaskEntity task : queued) {
            boolean targetsCurrentRuntime = task != null && task.getTargetClusterId() != null
                    && runtimeClusterIds.contains(task.getTargetClusterId());
            boolean projectRuntimeAuthorized = targetsCurrentRuntime
                    && workerAuthorizationService.isProjectRuntimeClusterGrantEnabled(
                    task.getTenantId(), task.getProjectId(), task.getTargetClusterId());
            boolean runtimeClusterAuthorized = projectRuntimeAuthorized
                    && workerAuthorizationService.isRuntimeClusterAuthorizedForProject(
                    task.getTenantId(), task.getProjectId(), task.getTargetClusterId());
            boolean authorized = task != null && task.getTargetClusterId() != null
                    && runtimeClusterAuthorized;
            if (!authorized) {
                if (targetsCurrentRuntime && !projectRuntimeAuthorized) {
                    failQueuedUnauthorizedTask(task);
                }
                continue;
            }
            boolean fileTransfer = isFileTransferTask(task);
            if (fileTransfer && !fileTransferSlots.tryAcquire()) {
                continue;
            }
            if (!claimTask(task)) {
                if (fileTransfer) {
                    fileTransferSlots.release();
                }
                continue;
            }
            if (fileTransfer) {
                DispatchTaskEntity claimedTask = task;
                fileTransferExecutor.execute(() -> {
                    try {
                        executeClaimedTask(claimedTask);
                    } finally {
                        fileTransferSlots.release();
                    }
                });
                continue;
            }
            executeClaimedTask(task);
        }
    }

    private boolean isFileTransferTask(DispatchTaskEntity task) {
        return task != null && DispatchExecutionType.FILE_TRANSFER.name().equalsIgnoreCase(task.getExecutionType());
    }

    private void executeClaimedTask(DispatchTaskEntity claimedTask) {
            DispatchTaskEntity task = claimedTask;
            LocalDateTime startedAt = LocalDateTime.now();
            RunRecordEntity runRecord = null;
            RunLogFileService.PreparedRunLog preparedRunLog = null;
            RunLogFileService.RunLogScope runLogScope = null;
            Map<String, Object> runtimeContext = new LinkedHashMap<String, Object>();
            try {
                DispatchTaskEntity reloadedTask = dispatchTaskMapper.selectById(task.getId());
                if (reloadedTask != null) {
                    task = reloadedTask;
                }
                if (!isAuthorized(task)) {
                    failClaimedUnauthorizedTask(task);
                    return;
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
                if (!updateOwnedRunningTask(task)) {
                    log.warn("Dispatch task {} lost its claim before execution started", task.getId());
                    failUnlinkedRunRecord(runRecord, "Dispatch claim was lost before the run record was linked");
                    return;
                }
                runLogScope = runLogFileService.openScope(preparedRunLog);
                log.info("Starting dispatch task {} as runRecord {}", task.getId(), runRecord.getId());
                runtimeContext.put("jobId", runRecord.getId());
                runtimeContext.put("runRecordId", runRecord.getId());
                runtimeContext.put("runLogId", String.valueOf(runRecord.getId()));
                runtimeContext.put("tenantId", task.getTenantId());
                runtimeContext.put("projectId", task.getProjectId());
                runtimeContext.put("workerGroupCode", workerGroupCode());
                runtimeContext.put("workerCode", workerCode());
                runtimeContext.put("workerInstanceId", clusterInstanceIdentity.instanceId());
                runtimeContext.put("workerBootId", clusterInstanceIdentity.bootId());
                runtimeContext.put("runtimeClusterId", task.getTargetClusterId());
                runtimeContext.put("runtimeClusterCode", runtimeClusterCode());
                runtimeContext.put("resourceRevision", task.getResourceRevision());
                runtimeContext.put("workerPodName", clusterInstanceIdentity.podName());
                runtimeContext.put("workerNodeName", clusterInstanceIdentity.nodeName());
                runtimeContext.put("triggeredByUserId", task.getTriggeredByUserId());
                Map<String, Object> result = executeWithTaskContext(task, runtimeContext);
                String resultStatus = resolveExecutionStatus(result);
                LocalDateTime endedAt = LocalDateTime.now();
                if ("FAILED".equalsIgnoreCase(resultStatus)) {
                    log.warn("Dispatch task {} completed with FAILED result as runRecord {}", task.getId(), runRecord.getId());
                } else {
                    log.info("Completed dispatch task {} as runRecord {}", task.getId(), runRecord.getId());
                }
                closeRunLogScope(runLogScope);
                runLogScope = null;
                if ("FAILED".equalsIgnoreCase(resultStatus)) {
                    task.setStatus("FAILED");
                    task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
                    task.setPayloadJson(result);
                    if (!updateOwnedRunningTask(task)) {
                        log.warn("Dispatch task {} lost its claim before FAILED completion was committed", task.getId());
                        return;
                    }
                    RunLogFileService.RunLogStorageResult logStorageResult = finalizeRunLog(preparedRunLog, runRecord);
                    publishEvent("FAILED", task, runRecord, startedAt, endedAt,
                            logStorageResult, result);
                } else {
                    task.setStatus("SUCCESS");
                    task.setPayloadJson(result);
                    if (!updateOwnedRunningTask(task)) {
                        log.warn("Dispatch task {} lost its claim before SUCCESS completion was committed", task.getId());
                        return;
                    }
                    RunLogFileService.RunLogStorageResult logStorageResult = finalizeRunLog(preparedRunLog, runRecord);
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
                copyPluginRevisions(payload, runtimeContext);
                task.setPayloadJson(payload);
                boolean failureCommitted = updateOwnedRunningTask(task);
                if (!failureCommitted) {
                    log.warn("Dispatch task {} lost its claim; failure event is suppressed", task.getId());
                } else {
                    markFailedFileTransferRun(task, failureMessage(e), LocalDateTime.now());
                }
                if (failureCommitted && runRecord != null) {
                    RunLogFileService.RunLogStorageResult logStorageResult = finalizeRunLog(preparedRunLog, runRecord);
                    publishEvent("FAILED", task, runRecord, startedAt, LocalDateTime.now(),
                            logStorageResult, payload);
                }
            } finally {
                closeRunLogScope(runLogScope);
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
        fileTransferExecutor.shutdownNow();
        WorkerLeaseEntity update = new WorkerLeaseEntity();
        update.setStatus("OFFLINE");
        update.setLeaseExpiresAt(LocalDateTime.now());
        workerLeaseMapper.update(update, new LambdaUpdateWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getWorkerGroupCode, workerGroupCode())
                .eq(WorkerLeaseEntity::getInstanceId, clusterInstanceIdentity.instanceId())
                .eq(WorkerLeaseEntity::getBootId, clusterInstanceIdentity.bootId()));
    }

    public boolean isAcceptingTasks() {
        return acceptingTasks;
    }

    private void renewRunningTaskLeases() {
        LocalDateTime leaseExpiresAt = LocalDateTime.now().plusMinutes(dispatchLeaseMinutes());
        List<DispatchTaskEntity> runningTasks = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getStatus, "RUNNING")
                .eq(DispatchTaskEntity::getWorkerGroupCode, workerGroupCode())
                .eq(DispatchTaskEntity::getWorkerInstanceId, clusterInstanceIdentity.instanceId())
                .eq(DispatchTaskEntity::getWorkerBootId, clusterInstanceIdentity.bootId()));
        for (DispatchTaskEntity task : runningTasks) {
            task.setLeaseExpiresAt(leaseExpiresAt);
            updateOwnedRunningTask(task);
        }
    }

    private boolean claimTask(DispatchTaskEntity task) {
        if (task == null || task.getId() == null || task.getTargetClusterId() == null) {
            return false;
        }
        DispatchTaskEntity update = new DispatchTaskEntity();
        update.setStatus("RUNNING");
        update.setClaimToken(UUID.randomUUID().toString());
        update.setWorkerBootId(clusterInstanceIdentity.bootId());
        update.setWorkerGroupCode(workerGroupCode());
        update.setLeaseOwner(workerGroupCode());
        update.setWorkerInstanceId(clusterInstanceIdentity.instanceId());
        update.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(dispatchLeaseMinutes()));
        update.setPayloadJson(null);
        int updated = dispatchTaskMapper.update(update, new LambdaUpdateWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getId, task.getId())
                .eq(DispatchTaskEntity::getStatus, "QUEUED")
                .eq(DispatchTaskEntity::getTargetClusterId, task.getTargetClusterId()));
        if (updated == 1) {
            task.setStatus(update.getStatus());
            task.setClaimToken(update.getClaimToken());
            task.setWorkerBootId(update.getWorkerBootId());
            task.setWorkerGroupCode(update.getWorkerGroupCode());
            task.setLeaseOwner(update.getLeaseOwner());
            task.setWorkerInstanceId(update.getWorkerInstanceId());
            task.setLeaseExpiresAt(update.getLeaseExpiresAt());
        }
        return updated == 1;
    }

    private boolean isAuthorized(DispatchTaskEntity task) {
        if (task == null) {
            return false;
        }
        return task.getTargetClusterId() != null
                && currentRuntimeClusterIds().contains(task.getTargetClusterId())
                && workerAuthorizationService.isRuntimeClusterAuthorizedForProject(
                task.getTenantId(), task.getProjectId(), task.getTargetClusterId());
    }

    private boolean updateOwnedRunningTask(DispatchTaskEntity task) {
        if (task == null || task.getId() == null) {
            return false;
        }
        task.setPayloadJson(normalizePayloadForPersistence(task.getPayloadJson()));
        LambdaUpdateWrapper<DispatchTaskEntity> update = new LambdaUpdateWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getId, task.getId())
                .eq(DispatchTaskEntity::getStatus, "RUNNING")
                .eq(DispatchTaskEntity::getClaimToken, task.getClaimToken())
                .eq(DispatchTaskEntity::getWorkerBootId, clusterInstanceIdentity.bootId())
                .eq(DispatchTaskEntity::getWorkerGroupCode, workerGroupCode())
                .eq(DispatchTaskEntity::getWorkerInstanceId, clusterInstanceIdentity.instanceId());
        if (!"RUNNING".equalsIgnoreCase(task.getStatus())) {
            task.setProtectedPayloadCiphertext(null);
            update.set(DispatchTaskEntity::getProtectedPayloadCiphertext, null);
        }
        return dispatchTaskMapper.update(task, update) == 1;
    }

    private Map<String, Object> normalizePayloadForPersistence(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return payload == null ? null : new LinkedHashMap<String, Object>();
        }
        try {
            return DISPATCH_PAYLOAD_MAPPER.readValue(
                    DISPATCH_PAYLOAD_MAPPER.writeValueAsBytes(payload), PAYLOAD_MAP_TYPE);
        } catch (IOException ex) {
            throw new IllegalStateException("Dispatch payload cannot be serialized for persistence", ex);
        }
    }

    private void copyPluginRevisions(Map<String, Object> target, Map<String, Object> runtimeContext) {
        if (target == null || runtimeContext == null) {
            return;
        }
        Object rawRevisions = runtimeContext.get("pluginRevisions");
        if (!(rawRevisions instanceof Map<?, ?>)) {
            return;
        }
        Map<String, String> revisions = new LinkedHashMap<String, String>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawRevisions).entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String coordinate = String.valueOf(entry.getKey()).trim();
            String identity = String.valueOf(entry.getValue()).trim();
            if (!coordinate.isEmpty() && !identity.isEmpty()) {
                revisions.put(coordinate, identity);
            }
        }
        if (!revisions.isEmpty()) {
            target.put("pluginRevisions", revisions);
        }
    }

    private void failQueuedUnauthorizedTask(DispatchTaskEntity task) {
        if (task == null || task.getId() == null || task.getTargetClusterId() == null) {
            return;
        }
        String reason = "Runtime cluster authorization was revoked while the task was queued";
        Map<String, Object> payload = task.getPayloadJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(task.getPayloadJson());
        payload.put("error", reason);
        payload.put("exceptionType", "RUNTIME_CLUSTER_AUTHORIZATION_REVOKED");
        DispatchTaskEntity update = new DispatchTaskEntity();
        update.setStatus("FAILED");
        update.setPayloadJson(payload);
        update.setLeaseExpiresAt(LocalDateTime.now());
        int updated = dispatchTaskMapper.update(update, new LambdaUpdateWrapper<DispatchTaskEntity>()
                .set(DispatchTaskEntity::getProtectedPayloadCiphertext, null)
                .eq(DispatchTaskEntity::getId, task.getId())
                .eq(DispatchTaskEntity::getStatus, "QUEUED")
                .eq(DispatchTaskEntity::getTargetClusterId, task.getTargetClusterId()));
        if (updated == 1) {
            task.setStatus("FAILED");
            task.setPayloadJson(payload);
            task.setLeaseExpiresAt(update.getLeaseExpiresAt());
            markFailedFileTransferRun(task, reason, update.getLeaseExpiresAt());
        } else {
            log.debug("Queued dispatch task {} changed before revoked authorization could be recorded", task.getId());
        }
    }

    private void failClaimedUnauthorizedTask(DispatchTaskEntity task) {
        if (task == null) {
            return;
        }
        Map<String, Object> payload = task.getPayloadJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(task.getPayloadJson());
        payload.put("error", "Runtime cluster authorization was revoked before execution");
        payload.put("exceptionType", "RUNTIME_CLUSTER_AUTHORIZATION_REVOKED");
        task.setStatus("FAILED");
        task.setAttempts(task.getAttempts() == null ? 1 : task.getAttempts() + 1);
        task.setPayloadJson(payload);
        task.setLeaseExpiresAt(LocalDateTime.now());
        if (!updateOwnedRunningTask(task)) {
            log.warn("Dispatch task {} lost its claim while rejecting revoked runtime authorization", task.getId());
        } else {
            markFailedFileTransferRun(task, String.valueOf(payload.get("error")), task.getLeaseExpiresAt());
        }
    }

    private void failUnlinkedRunRecord(RunRecordEntity runRecord, String reason) {
        if (runRecord == null || runRecord.getId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("error", reason);
        payload.put("exceptionType", "DISPATCH_RUN_RECORD_LINK_FAILED");
        RunRecordEntity update = new RunRecordEntity();
        update.setStatus("FAILED");
        update.setEndedAt(LocalDateTime.now());
        update.setMessage(reason);
        update.setLogStatus("FAILED");
        update.setPayloadJson(payload);
        update.setResultJson(payload);
        int updated = runRecordMapper.update(update, new LambdaUpdateWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getId, runRecord.getId())
                .eq(RunRecordEntity::getStatus, "RUNNING")
                .eq(RunRecordEntity::getWorkerBootId, clusterInstanceIdentity.bootId()));
        if (updated != 1) {
            log.warn("Run record {} could not be closed after dispatch linking failed", runRecord.getId());
        }
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
        String originalStatus = task.getStatus();
        String originalClaimToken = task.getClaimToken();
        String originalBootId = task.getWorkerBootId();
        String originalWorkerGroupCode = task.getWorkerGroupCode();
        String originalLeaseOwner = task.getLeaseOwner();
        String originalWorkerInstanceId = task.getWorkerInstanceId();
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
        DispatchTaskEntity dispatchUpdate = new DispatchTaskEntity();
        dispatchUpdate.setStatus("FAILED");
        dispatchUpdate.setPayloadJson(payload);
        dispatchUpdate.setLeaseExpiresAt(endedAt);
        LambdaUpdateWrapper<DispatchTaskEntity> dispatchCas = new LambdaUpdateWrapper<DispatchTaskEntity>()
                .set(DispatchTaskEntity::getProtectedPayloadCiphertext, null)
                .eq(DispatchTaskEntity::getId, task.getId())
                .eq(DispatchTaskEntity::getStatus, originalStatus);
        appendNullableDispatchCondition(dispatchCas, DispatchTaskEntity::getClaimToken, originalClaimToken);
        appendNullableDispatchCondition(dispatchCas, DispatchTaskEntity::getWorkerBootId, originalBootId);
        appendNullableDispatchCondition(dispatchCas, DispatchTaskEntity::getWorkerGroupCode, originalWorkerGroupCode);
        appendNullableDispatchCondition(dispatchCas, DispatchTaskEntity::getLeaseOwner, originalLeaseOwner);
        appendNullableDispatchCondition(dispatchCas, DispatchTaskEntity::getWorkerInstanceId, originalWorkerInstanceId);
        if (dispatchTaskMapper.update(dispatchUpdate, dispatchCas) != 1) {
            log.warn("Dispatch task {} changed while recovering interrupted execution; recovery was skipped", task.getId());
            return;
        }
        task.setStatus("FAILED");
        task.setPayloadJson(payload);
        task.setLeaseExpiresAt(endedAt);
        markInterruptedFileTransferRun(task, endedAt);
        if (runRecord != null) {
            RunLogFileService.RunLogStorageResult logStorageResult = runLogFileService.syncExistingLog(
                    runRecord.getLogFilePath(), runRecord.getLogCharset());
            applyRunLogStorageResult(runRecord, logStorageResult);
            runRecord.setStatus("FAILED");
            runRecord.setEndedAt(endedAt);
            runRecord.setMessage("Task was interrupted by worker restart before completion");
            runRecord.setPayloadJson(payload);
            runRecord.setResultJson(payload);
            int runRecordUpdated = runRecordMapper.update(runRecord, new LambdaUpdateWrapper<RunRecordEntity>()
                    .eq(RunRecordEntity::getId, runRecord.getId())
                    .eq(RunRecordEntity::getStatus, "RUNNING"));
            if (runRecordUpdated == 1) {
                publishEvent("FAILED", task, runRecord, startedAt, endedAt, logStorageResult, payload);
            } else {
                log.warn("Run record {} changed while recovering dispatch task {}; failure event was suppressed",
                        runRecord.getId(), task.getId());
            }
        }
        log.warn("Recovered interrupted dispatch task {} owned by worker group {}", task.getId(), workerGroupCode());
    }

    private void markInterruptedFileTransferRun(DispatchTaskEntity task, LocalDateTime endedAt) {
        markFailedFileTransferRun(task,
                "File transfer was interrupted by worker restart; resume can reuse valid checkpoints", endedAt);
    }

    private void markFailedFileTransferRun(DispatchTaskEntity task, String reason, LocalDateTime endedAt) {
        if (fileTransferRunMapper == null || task == null) {
            return;
        }
        Long runId = resolveFileTransferRunId(task);
        if (runId == null) {
            return;
        }
        LocalDateTime completedAt = endedAt == null ? LocalDateTime.now() : endedAt;
        fileTransferRunMapper.update(null, new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getStatus, "FAILED")
                .set(FileTransferRunEntity::getMessage,
                        hasText(reason) ? reason : "File transfer dispatch failed before execution")
                .set(FileTransferRunEntity::getCurrentBytesPerSecond, 0L)
                .set(FileTransferRunEntity::getActiveFiles, 0)
                .set(FileTransferRunEntity::getEndedAt, completedAt)
                .set(FileTransferRunEntity::getUpdatedAt, completedAt)
                .eq(FileTransferRunEntity::getId, runId)
                .in(FileTransferRunEntity::getStatus, "QUEUED", "RUNNING", "PAUSED"));
    }

    private Long resolveFileTransferRunId(DispatchTaskEntity task) {
        if (task == null) {
            return null;
        }
        if (task.getFileTransferRunId() != null) {
            return task.getFileTransferRunId();
        }
        Map<String, Object> payload = task.getPayloadJson();
        if (payload == null) {
            return null;
        }
        Long runId = parseLong(payload.get("fileTransferRunId"));
        if (runId != null) {
            return runId;
        }
        Object config = payload.get("config");
        if (config instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) config).entrySet()) {
                if ("fileTransferRunId".equals(String.valueOf(entry.getKey()))) {
                    return parseLong(entry.getValue());
                }
            }
        }
        return null;
    }

    private String failureMessage(Throwable throwable) {
        if (throwable == null || !hasText(throwable.getMessage())) {
            return "File transfer dispatch failed before execution";
        }
        return "File transfer dispatch failed before execution: " + throwable.getMessage();
    }

    private <T> void appendNullableDispatchCondition(LambdaUpdateWrapper<DispatchTaskEntity> wrapper,
                                                      SFunction<DispatchTaskEntity, T> column,
                                                      T value) {
        if (value == null) {
            wrapper.isNull(column);
        } else {
            wrapper.eq(column, value);
        }
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
                                                       Map<String, Object> runtimeContext) {
        StudioRequestContext previousContext = StudioRequestContextHolder.getContext();
        StudioRequestContext taskContext = new StudioRequestContext();
        taskContext.setTenantId(task.getTenantId());
        taskContext.setProjectId(task.getProjectId());
        taskContext.setUsername(workerCode());
        StudioRequestContextHolder.setContext(taskContext);
        try {
            WorkflowNodeDefinition node = toNode(task);
            if (!clearProtectedPayload(task)) {
                throw new IllegalStateException("Dispatch claim was lost before protected execution input was consumed");
            }
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
        Map<String, Object> payload = dispatchTask.getPayloadJson() == null
                ? new LinkedHashMap<String, Object>()
                : dispatchTask.getPayloadJson();
        WorkflowNodeDefinition node;
        if (DispatchExecutionType.WORKFLOW_NODE.name().equals(dispatchTask.getExecutionType())) {
            if (hasText(dispatchTask.getProtectedPayloadCiphertext())) {
                throw new IllegalStateException("Workflow dispatch must not contain protected ad-hoc input");
            }
            node = workflowDispatchNodeResolver.resolve(dispatchTask);
        } else {
            node = new WorkflowNodeDefinition();
            node.setNodeCode(dispatchTask.getNodeCode());
            node.setNodeName(dispatchTask.getNodeCode());
            node.setNodeType(com.jdragon.studio.dto.enums.NodeType.valueOf(String.valueOf(payload.get("nodeType"))));
            Object config = payload.get("config");
            node.setConfig(config instanceof Map
                    ? new LinkedHashMap<String, Object>((Map<String, Object>) config)
                    : new LinkedHashMap<String, Object>());
            if (hasText(dispatchTask.getProtectedPayloadCiphertext())) {
                if (dispatchProtectedPayloadService == null) {
                    throw new IllegalStateException("Protected dispatch input service is unavailable");
                }
                Map<String, Object> protectedConfig = dispatchProtectedPayloadService.unprotect(
                        dispatchTask.getProtectedPayloadCiphertext());
                Map<String, Object> mergedConfig = new LinkedHashMap<String, Object>(node.getConfig());
                mergedConfig.putAll(protectedConfig);
                node.setConfig(mergedConfig);
            }
        }
        if (node.getNodeType() == com.jdragon.studio.dto.enums.NodeType.COLLECTION_TASK) {
            Long collectionTaskId = resolveCollectionTaskId(dispatchTask, payload, node.getConfig());
            com.jdragon.studio.dto.model.CollectionTaskDefinitionView task =
                    collectionTaskService.requireOnlineForExecution(collectionTaskId);
            assertResourceRevision(dispatchTask, runtimeResourceRevisionService == null
                    ? (task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString())
                    : runtimeResourceRevisionService.collectionTaskRevision(collectionTaskId));
            node.setNodeName(task.getName());
            node.setConfig(collectionTaskAssemblerService.assemble(task));
        } else if (node.getNodeType() == com.jdragon.studio.dto.enums.NodeType.QUALITY_TASK) {
            Long qualityTaskId = resolveQualityTaskId(dispatchTask, payload, node.getConfig());
            com.jdragon.studio.dto.model.QualityTaskDefinitionView task = qualityTaskService.requireOnlineForExecution(qualityTaskId);
            assertResourceRevision(dispatchTask, runtimeResourceRevisionService == null
                    ? (task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString())
                    : runtimeResourceRevisionService.qualityTaskRevision(qualityTaskId));
            node.setNodeName(task.getTaskName());
            Map<String, Object> qualityConfig = node.getConfig() == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(node.getConfig());
            qualityConfig.put("qualityTaskId", task.getId());
            node.setConfig(qualityConfig);
        } else if (node.getNodeType() == com.jdragon.studio.dto.enums.NodeType.FILE_TRANSFER) {
            Long fileTransferTaskId = dispatchTask.getFileTransferTaskId();
            Long fileTransferRunId = dispatchTask.getFileTransferRunId();
            if (fileTransferTaskId == null) {
                fileTransferTaskId = parseLong(payload.get("fileTransferTaskId"));
            }
            if (fileTransferRunId == null) {
                fileTransferRunId = parseLong(payload.get("fileTransferRunId"));
            }
            if (fileTransferRunId == null) {
                throw new IllegalStateException("File transfer dispatch identity is incomplete: fileTransferRunId is missing");
            }
            Map<String, Object> fileTransferConfig = node.getConfig() == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(node.getConfig());
            if (fileTransferTaskId == null) {
                fileTransferConfig.remove("fileTransferTaskId");
            } else {
                fileTransferConfig.put("fileTransferTaskId", fileTransferTaskId);
            }
            fileTransferConfig.put("fileTransferRunId", fileTransferRunId);
            node.setConfig(fileTransferConfig);
        }
        return node;
    }

    private boolean clearProtectedPayload(DispatchTaskEntity task) {
        if (task == null || !hasText(task.getProtectedPayloadCiphertext())) {
            return true;
        }
        DispatchTaskEntity update = new DispatchTaskEntity();
        int updated = dispatchTaskMapper.update(update, new LambdaUpdateWrapper<DispatchTaskEntity>()
                .set(DispatchTaskEntity::getProtectedPayloadCiphertext, null)
                .eq(DispatchTaskEntity::getId, task.getId())
                .eq(DispatchTaskEntity::getStatus, "RUNNING")
                .eq(DispatchTaskEntity::getClaimToken, task.getClaimToken())
                .eq(DispatchTaskEntity::getWorkerBootId, clusterInstanceIdentity.bootId())
                .eq(DispatchTaskEntity::getWorkerGroupCode, workerGroupCode())
                .eq(DispatchTaskEntity::getWorkerInstanceId, clusterInstanceIdentity.instanceId()));
        if (updated == 1) {
            task.setProtectedPayloadCiphertext(null);
            return true;
        }
        return false;
    }

    private void assertResourceRevision(DispatchTaskEntity dispatchTask, String currentRevision) {
        if (dispatchTask == null || !hasText(dispatchTask.getResourceRevision()) || !hasText(currentRevision)) {
            return;
        }
        if (!dispatchTask.getResourceRevision().equals(currentRevision)) {
            throw new IllegalStateException("Resource configuration changed after dispatch; submit a new run");
        }
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
        event.setFileTransferTaskId(task.getFileTransferTaskId());
        event.setFileTransferRunId(task.getFileTransferRunId());
        event.setProjectId(task.getProjectId());
        event.setExecutionType(task.getExecutionType() == null ? DispatchExecutionType.WORKFLOW_NODE : DispatchExecutionType.valueOf(task.getExecutionType()));
        event.setNodeCode(task.getNodeCode());
        event.setRequestedClusterId(task.getTargetClusterId());
        event.setActualClusterId(runRecord.getActualClusterId());
        event.setActualClusterCode(runRecord.getActualClusterCode());
        event.setWorkerGroupCode(workerGroupCode());
        event.setWorkerCode(workerCode());
        event.setWorkerInstanceId(clusterInstanceIdentity.instanceId());
        event.setWorkerBootId(hasText(runRecord.getWorkerBootId())
                ? runRecord.getWorkerBootId() : clusterInstanceIdentity.bootId());
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
        entity.setFileTransferTaskId(task.getFileTransferTaskId());
        entity.setFileTransferRunId(task.getFileTransferRunId());
        entity.setNodeCode(task.getNodeCode());
        entity.setWorkerGroupCode(workerGroupCode());
        entity.setWorkerCode(workerCode());
        entity.setWorkerInstanceId(clusterInstanceIdentity.instanceId());
        entity.setWorkerBootId(clusterInstanceIdentity.bootId());
        entity.setWorkerPodName(clusterInstanceIdentity.podName());
        entity.setWorkerNodeName(clusterInstanceIdentity.nodeName());
        entity.setTriggeredByUserId(task.getTriggeredByUserId());
        entity.setStatus("RUNNING");
        entity.setRequestedClusterId(task.getTargetClusterId());
        entity.setActualClusterId(task.getTargetClusterId());
        entity.setActualClusterCode(runtimeClusterCode());
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
                .eq(WorkerLeaseEntity::getBootId, clusterInstanceIdentity.bootId())
                .eq(WorkerLeaseEntity::getRuntimeClusterCode, runtimeClusterCode())
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
                .eq(WorkerLeaseEntity::getInstanceId, clusterInstanceIdentity.instanceId())
                .eq(WorkerLeaseEntity::getBootId, clusterInstanceIdentity.bootId()));
    }

    private List<RuntimeClusterEntity> currentRuntimeClusters() {
        if (runtimeClusterMapper == null) {
            return new ArrayList<RuntimeClusterEntity>();
        }
        return runtimeClusterMapper.selectList(new LambdaQueryWrapper<RuntimeClusterEntity>()
                .eq(RuntimeClusterEntity::getCode, runtimeClusterCode().toUpperCase(Locale.ROOT))
                .eq(RuntimeClusterEntity::getEnabled, 1)
                .orderByAsc(RuntimeClusterEntity::getTenantId)
                .orderByAsc(RuntimeClusterEntity::getId));
    }

    private List<Long> currentRuntimeClusterIds() {
        List<Long> ids = new ArrayList<Long>();
        for (RuntimeClusterEntity cluster : currentRuntimeClusters()) {
            if (cluster.getId() != null) {
                ids.add(cluster.getId());
            }
        }
        return ids;
    }

    private void updateRuntimeClusterHeartbeat(List<RuntimeClusterEntity> clusters, LocalDateTime now) {
        for (RuntimeClusterEntity cluster : clusters) {
            Map<String, Object> instance = new LinkedHashMap<String, Object>();
            instance.put("bootId", clusterInstanceIdentity.bootId());
            instance.put("workerGroupCode", workerGroupCode());
            try {
                runtimeClusterHeartbeatService.recordById(cluster.getTenantId(), cluster.getId(),
                        clusterInstanceIdentity.instanceId(), instance, properties.getRuntimeVersion(),
                        properties.getWorkerApiBaseUrl(), now);
            } catch (RuntimeException ex) {
                log.warn("Failed to update runtime cluster heartbeat for tenant {} cluster {}: {}",
                        cluster.getTenantId(), cluster.getCode(), ex.getMessage());
            }
        }
    }

    private String runtimeClusterCode() {
        if (!hasText(properties.getRuntimeClusterCode())) {
            throw new IllegalStateException("STUDIO_CLUSTER_CODE must be configured explicitly for studio-worker");
        }
        return properties.getRuntimeClusterCode().trim();
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

