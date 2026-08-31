package com.jdragon.studio.worker.runtime.streaming;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.core.plugin.PluginType;
import com.jdragon.aggregation.core.streaming.job.StreamingBatch;
import com.jdragon.aggregation.core.streaming.job.StreamingCheckpoint;
import com.jdragon.aggregation.core.streaming.job.StreamingJobContainer;
import com.jdragon.aggregation.core.streaming.job.StreamingJobListener;
import com.jdragon.aggregation.core.streaming.job.StreamingMetricsSnapshot;
import com.jdragon.aggregation.core.streaming.job.StreamingSource;
import com.jdragon.aggregation.pluginloader.PluginClassLoaderCloseable;
import com.jdragon.aggregation.pluginloader.spi.AbstractPlugin;
import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;
import com.jdragon.studio.dto.enums.CollectionTaskExecutionMode;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.infra.service.RuntimeResourceRevisionService;
import com.jdragon.studio.infra.service.StreamingTaskCoordinatorService;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
@Slf4j
public class StreamingTaskWorkerExecutor {

    private static final long DEFAULT_STOP_TIMEOUT_MS = 60_000L;
    private static final long CHECKPOINT_RETRY_INITIAL_DELAY_MS = 1_000L;
    private static final long CHECKPOINT_RETRY_MAX_DELAY_MS = 30_000L;
    private static final long CHECKPOINT_RETRY_STOP_CHECK_MS = 250L;

    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final CollectionTaskService collectionTaskService;
    private final CollectionTaskAssemblerService assemblerService;
    private final RuntimeResourceRevisionService resourceRevisionService;
    private final StreamingTaskCoordinatorService coordinatorService;
    private final RunLogFileService runLogFileService;
    private final StudioPlatformProperties properties;
    private final ClusterInstanceIdentity identity;
    private final Map<Long, ActiveStreamingAttempt> activeAttempts =
            new ConcurrentHashMap<Long, ActiveStreamingAttempt>();
    private final ScheduledExecutorService forcedStopExecutor;
    private final ScheduledExecutorService metricsExecutor;

    public StreamingTaskWorkerExecutor(DispatchTaskMapper dispatchTaskMapper,
                                       RunRecordMapper runRecordMapper,
                                       CollectionTaskService collectionTaskService,
                                       CollectionTaskAssemblerService assemblerService,
                                       RuntimeResourceRevisionService resourceRevisionService,
                                       StreamingTaskCoordinatorService coordinatorService,
                                       RunLogFileService runLogFileService,
                                       StudioPlatformProperties properties,
                                       ClusterInstanceIdentity identity) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.collectionTaskService = collectionTaskService;
        this.assemblerService = assemblerService;
        this.resourceRevisionService = resourceRevisionService;
        this.coordinatorService = coordinatorService;
        this.runLogFileService = runLogFileService;
        this.properties = properties;
        this.identity = identity;
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    "studio-worker-streaming-stop-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.forcedStopExecutor = Executors.newSingleThreadScheduledExecutor(factory);
        AtomicInteger metricsThreadNumber = new AtomicInteger();
        ThreadFactory metricsFactory = runnable -> {
            Thread thread = new Thread(runnable,
                    "studio-worker-streaming-metrics-" + metricsThreadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.metricsExecutor = Executors.newSingleThreadScheduledExecutor(metricsFactory);
    }

    public void execute(DispatchTaskEntity claimedTask, Consumer<Runnable> gracefulCancellationRegistrar) {
        Long attemptId = payloadLong(claimedTask, "streamAttemptId");
        Long runId = payloadLong(claimedTask, "streamRunId");
        Long generation = payloadLong(claimedTask, "streamGeneration");
        if (attemptId == null || runId == null || generation == null) {
            failMalformedDispatch(claimedTask);
            return;
        }

        RunRecordEntity runRecord = createRunRecord(claimedTask);
        RunLogFileService.PreparedRunLog preparedLog = null;
        RunLogFileService.RunLogScope logScope = null;
        ActiveStreamingAttempt active = new ActiveStreamingAttempt(attemptId);
        StreamingMetricsAccumulator metrics = new StreamingMetricsAccumulator();
        ScheduledFuture<?> metricsFuture = null;
        activeAttempts.put(attemptId, active);
        StudioRequestContext previousContext = StudioRequestContextHolder.getContext();
        try {
            linkRunRecord(claimedTask, runRecord.getId());
            preparedLog = runLogFileService.prepareStreaming(
                    claimedTask.getCollectionTaskId(), runId, attemptId, runRecord.getId(),
                    claimedTask.getTenantId(), claimedTask.getProjectId());
            applyPreparedLog(runRecord, preparedLog);
            runRecordMapper.updateById(runRecord);
            logScope = runLogFileService.openScope(preparedLog);
            setTaskContext(claimedTask);

            CollectionTaskDefinitionView task = collectionTaskService.requireOnlineForExecution(
                    claimedTask.getCollectionTaskId());
            if (task.getExecutionMode() != CollectionTaskExecutionMode.STREAMING) {
                throw new IllegalStateException("Collection task is no longer configured for STREAMING execution");
            }
            assertResourceRevision(claimedTask);
            Map<String, Object> jobConfig = configureStreamingJob(task, claimedTask);
            String readerType = readerType(jobConfig);
            String pluginName = readerType.endsWith("reader") ? readerType : readerType + "reader";

            try (PluginClassLoaderCloseable loader =
                         PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(
                                 PluginType.READER, pluginName)) {
                AbstractPlugin plugin = loader.loadPlugin();
                if (!(plugin instanceof StreamingSource)) {
                    throw new IllegalStateException("Reader plugin does not implement StreamingSource: " + readerType);
                }
                StreamingJobContainer container = createContainer(jobConfig, (StreamingSource) plugin);
                active.attach(container, stopTimeoutMs(task));
                container.setListener(listener(attemptId, metrics, container));
                metricsFuture = metricsExecutor.scheduleAtFixedRate(
                        () -> safeRecordMetrics(attemptId, metrics.sample(container.getSourceMetrics()), "periodic"),
                        60L, 60L, TimeUnit.SECONDS);
                if (gracefulCancellationRegistrar != null) {
                    gracefulCancellationRegistrar.accept(active::requestStop);
                }
                container.setRunContext("stream.runId", runId);
                container.setRunContext("stream.attemptId", attemptId);
                container.setRunContext("tenantId", claimedTask.getTenantId());
                container.setRunContext("projectId", claimedTask.getProjectId());
                container.setRunContext("runRecordId", runRecord.getId());
                if (!coordinatorService.workerStarted(attemptId, claimedTask.getId(), runRecord.getId(),
                        identity.instanceId(), identity.bootId())) {
                    active.requestStop();
                    throw new StaleStreamingAttemptException();
                }
                log.info("Streaming attempt started taskId={} runId={} attemptId={} generation={} dispatchId={}",
                        claimedTask.getCollectionTaskId(), runId, attemptId, generation, claimedTask.getId());
                container.start();
                deactivateAttempt(attemptId, active);
                Map<String, Object> checkpoint = checkpoint(container.getLastCommittedCheckpoint());
                if (coordinatorService.isStopRequested(attemptId, claimedTask.getId(), generation)) {
                    completeDispatch(claimedTask, runRecord, "SUCCESS",
                            "Streaming attempt stopped after deployment offline request");
                    coordinatorService.attemptStopped(attemptId, runRecord.getId(), checkpoint);
                    log.info("Streaming attempt stopped taskId={} runId={} attemptId={} dispatchId={}",
                            claimedTask.getCollectionTaskId(), runId, attemptId, claimedTask.getId());
                } else {
                    throw new IllegalStateException("Streaming container ended while deployment still requested RUNNING");
                }
            }
        } catch (StaleStreamingAttemptException ignored) {
            deactivateAttempt(attemptId, active);
            completeDispatch(claimedTask, runRecord, "SUCCESS",
                    "Streaming attempt was fenced before execution");
            coordinatorService.attemptStopped(attemptId, runRecord.getId(), new LinkedHashMap<String, Object>());
        } catch (Throwable failure) {
            deactivateAttempt(attemptId, active);
            String summary = safeSummary(failure);
            log.error("Streaming attempt failed taskId={} runId={} attemptId={} generation={} dispatchId={} "
                            + "errorType={} message={}",
                    claimedTask.getCollectionTaskId(), runId, attemptId, generation, claimedTask.getId(),
                    failure.getClass().getSimpleName(), summary);
            safeAttemptFailed(attemptId, failure.getClass().getSimpleName(), summary);
        } finally {
            if (metricsFuture != null) {
                metricsFuture.cancel(false);
            }
            deactivateAttempt(attemptId, active);
            try {
                safeRecordMetrics(attemptId, metrics.flush(), "final");
                safeCloseScope(logScope, attemptId);
                safeFinalizeLog(preparedLog, runRecord, attemptId);
            } finally {
                restoreTaskContext(previousContext);
                Thread.interrupted();
            }
        }
    }

    public void heartbeatActiveAttempts() {
        for (Long attemptId : activeAttempts.keySet()) {
            coordinatorService.heartbeat(attemptId, identity.instanceId(), identity.bootId());
        }
    }

    public void recoverInterrupted(DispatchTaskEntity task) {
        coordinatorService.workerRestartInterrupted(task);
    }

    public int activeAttemptCount() {
        return activeAttempts.size();
    }

    @PreDestroy
    public void shutdown() {
        for (ActiveStreamingAttempt attempt : activeAttempts.values()) {
            attempt.cancelNow();
        }
        activeAttempts.clear();
        forcedStopExecutor.shutdownNow();
        metricsExecutor.shutdownNow();
    }

    protected StreamingJobContainer createContainer(Map<String, Object> config, StreamingSource source) {
        return new StreamingJobContainer(Configuration.from(config), source);
    }

    private StreamingJobListener listener(Long attemptId,
                                          StreamingMetricsAccumulator metrics,
                                          StreamingJobContainer container) {
        return new StreamingJobListener() {
            @Override
            public void onBatchRetry(StreamingBatch batch, int attempt, Throwable failure) {
                metrics.onBatchRetry();
            }

            @Override
            public void onBatchFailed(StreamingBatch batch, Throwable failure) {
                metrics.onBatchFailed(batch);
            }

            @Override
            public void onBatchCommitted(StreamingBatch batch,
                                         com.jdragon.aggregation.core.statistics.communication.Communication communication,
                                         long durationMillis) {
                reportBatchCommitted(attemptId, batch.getCheckpoint(), container);
                metrics.onBatchCommitted(communication, container.getSourceMetrics());
                safeRecordMetrics(attemptId, metrics.sample(container.getSourceMetrics()), "batch-commit");
            }
        };
    }

    private void reportBatchCommitted(Long attemptId,
                                      StreamingCheckpoint checkpoint,
                                      StreamingJobContainer container) {
        long retryDelayMs = CHECKPOINT_RETRY_INITIAL_DELAY_MS;
        int failedAttempts = 0;
        while (true) {
            if (container.isStopRequested() || container.isCancellationRequested()) {
                throw new CheckpointReportCancelledException(
                        "Streaming checkpoint report stopped after Kafka commit");
            }
            try {
                if (!coordinatorService.batchCommitted(attemptId, checkpoint(checkpoint))) {
                    container.requestStop();
                    throw new StaleStreamingAttemptException(
                            "Streaming attempt was fenced after Kafka checkpoint commit");
                }
                return;
            } catch (StaleStreamingAttemptException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                failedAttempts++;
                log.warn("Streaming checkpoint report failed attemptId={} retry={} retryDelayMs={} "
                                + "errorType={} message={}",
                        attemptId, failedAttempts, retryDelayMs,
                        failure.getClass().getSimpleName(), safeSummary(failure));
                if (!waitForCheckpointRetry(container, retryDelayMs)) {
                    throw new CheckpointReportCancelledException(
                            "Streaming checkpoint report stopped after Kafka commit", failure);
                }
                retryDelayMs = Math.min(CHECKPOINT_RETRY_MAX_DELAY_MS, retryDelayMs * 2L);
            }
        }
    }

    protected boolean waitForCheckpointRetry(StreamingJobContainer container, long delayMillis) {
        long remaining = Math.max(0L, delayMillis);
        while (remaining > 0L) {
            if (container.isStopRequested() || container.isCancellationRequested()) {
                return false;
            }
            long sleepMillis = Math.min(CHECKPOINT_RETRY_STOP_CHECK_MS, remaining);
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= sleepMillis;
        }
        return !container.isStopRequested() && !container.isCancellationRequested();
    }

    private void recordMetrics(Long attemptId, StreamingMetricsAccumulator.MetricDelta delta) {
        if (attemptId == null || delta == null) {
            return;
        }
        Map<String, Long> counters = delta.getCounters();
        Map<String, Long> gauges = delta.getGauges();
        LocalDateTime bucketStart = delta.getBucketStart();
        if (bucketStart == null) {
            return;
        }
        coordinatorService.recordMetricBucket(attemptId, null, null,
                bucketStart,
                value(counters.get("recordsRead")),
                value(counters.get("writeSucceedRecords")),
                value(counters.get("writeFailedRecords")),
                value(counters.get("dirtyRecords")),
                value(counters.get("bytesRead")),
                value(counters.get("batchCount")),
                value(counters.get("retryCount")),
                value(gauges.get("currentLag")),
                value(gauges.get("maxLag")),
                delta.getLastMessageAt(),
                delta.getLastCheckpointAt(),
                value(counters.get("rebalanceCount")));
    }

    private void deactivateAttempt(Long attemptId, ActiveStreamingAttempt active) {
        activeAttempts.remove(attemptId, active);
        active.close();
    }

    private void safeAttemptFailed(Long attemptId, String errorCode, String errorSummary) {
        try {
            coordinatorService.attemptFailed(attemptId, errorCode, errorSummary);
        } catch (RuntimeException reportFailure) {
            log.error("Streaming attempt failure report failed attemptId={} errorType={} message={}",
                    attemptId, reportFailure.getClass().getSimpleName(), safeSummary(reportFailure));
        }
    }

    private void safeRecordMetrics(Long attemptId,
                                   StreamingMetricsAccumulator.MetricDelta delta,
                                   String phase) {
        try {
            recordMetrics(attemptId, delta);
        } catch (RuntimeException reportFailure) {
            log.warn("Streaming metric report failed attemptId={} phase={} errorType={} message={}",
                    attemptId, phase, reportFailure.getClass().getSimpleName(), safeSummary(reportFailure));
        }
    }

    private void safeCloseScope(RunLogFileService.RunLogScope scope, Long attemptId) {
        try {
            closeScope(scope);
        } catch (RuntimeException closeFailure) {
            log.warn("Streaming log scope close failed attemptId={} errorType={} message={}",
                    attemptId, closeFailure.getClass().getSimpleName(), safeSummary(closeFailure));
        }
    }

    private void safeFinalizeLog(RunLogFileService.PreparedRunLog preparedLog,
                                 RunRecordEntity runRecord,
                                 Long attemptId) {
        try {
            finalizeLog(preparedLog, runRecord);
        } catch (RuntimeException reportFailure) {
            log.warn("Streaming log finalization failed attemptId={} errorType={} message={}",
                    attemptId, reportFailure.getClass().getSimpleName(), safeSummary(reportFailure));
        }
    }

    private long value(Long value) {
        return value == null ? 0L : Math.max(0L, value.longValue());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> configureStreamingJob(CollectionTaskDefinitionView task,
                                                      DispatchTaskEntity dispatch) {
        Map<String, Object> config = assemblerService.assemble(task);
        Map<String, Object> result = new LinkedHashMap<String, Object>(config);
        Map<String, Object> reader = map(result.get("reader"));
        Map<String, Object> readerConfig = map(reader.get("config"));
        Map<String, Object> options = task.getStreamingOptions() == null
                ? new LinkedHashMap<String, Object>()
                : new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
                        task.getStreamingOptions(), Map.class);
        String groupId = payloadText(dispatch, "groupId");
        if (groupId != null && !hasSourceReaderOption(task, "groupId")) {
            readerConfig.put("groupId", groupId);
        }
        copyOptionIfAbsent(options, readerConfig, "offsetReset");
        copyOptionIfAbsent(options, readerConfig, "resetOffset");
        copyOptionIfAbsent(options, readerConfig, "pollTimeoutMs");
        if (Boolean.TRUE.equals(asBoolean(readerConfig.get("resetOffset")))) {
            readerConfig.put("resetOffset", Long.valueOf(1L).equals(
                    payloadLong(dispatch, "streamAttemptNo")));
        }
        reader.put("config", readerConfig);
        result.put("reader", reader);

        Map<String, Object> streaming = new LinkedHashMap<String, Object>();
        // Kafka consumer options belong to reader.config. Keep streaming only
        // for container-level limits and retry controls so the two execution
        // modes cannot observe conflicting values.
        copyOption(options, streaming, "maxBatchRecords");
        copyOption(options, streaming, "maxBatchBytes");
        copyOption(options, streaming, "batchRetryCount");
        result.put("streaming", streaming);
        return result;
    }

    private void copyOption(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private void copyOptionIfAbsent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (!target.containsKey(key)) {
            copyOption(source, target, key);
        }
    }

    private boolean hasSourceReaderOption(CollectionTaskDefinitionView task, String key) {
        if (task == null || task.getSourceBindings() == null || task.getSourceBindings().isEmpty()
                || task.getSourceBindings().get(0) == null
                || task.getSourceBindings().get(0).getReaderOptions() == null) {
            return false;
        }
        Object value = task.getSourceBindings().get(0).getReaderOptions().get(key);
        return value != null && !(value instanceof String && ((String) value).trim().isEmpty());
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return value == null ? Boolean.FALSE : Boolean.valueOf(String.valueOf(value));
    }

    private String readerType(Map<String, Object> jobConfig) {
        String value = text(map(jobConfig.get("reader")).get("type"));
        if (value == null) {
            throw new IllegalStateException("Streaming job reader type is missing");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private void assertResourceRevision(DispatchTaskEntity dispatch) {
        String expected = dispatch.getResourceRevision();
        if (expected == null || expected.trim().isEmpty()) {
            return;
        }
        String actual = resourceRevisionService.collectionTaskRevision(dispatch.getCollectionTaskId());
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Collection task configuration changed after streaming dispatch");
        }
    }

    private RunRecordEntity createRunRecord(DispatchTaskEntity task) {
        RunRecordEntity record = new RunRecordEntity();
        record.setTenantId(task.getTenantId());
        record.setProjectId(task.getProjectId());
        record.setExecutionType(task.getExecutionType());
        record.setCollectionTaskId(task.getCollectionTaskId());
        record.setTriggeredByUserId(task.getTriggeredByUserId());
        record.setNodeCode(task.getNodeCode());
        record.setStatus("RUNNING");
        record.setTerminationRequested(0);
        record.setRequestedClusterId(task.getTargetClusterId());
        record.setActualClusterId(task.getTargetClusterId());
        record.setActualClusterCode(properties.getRuntimeClusterCode());
        record.setWorkerGroupCode(properties.getWorkerGroupCode());
        record.setWorkerCode(properties.getWorkerCode());
        record.setWorkerInstanceId(identity.instanceId());
        record.setWorkerBootId(identity.bootId());
        record.setWorkerPodName(identity.podName());
        record.setWorkerNodeName(identity.nodeName());
        record.setMessage("Streaming attempt started");
        record.setStartedAt(LocalDateTime.now());
        record.setLogCharset("UTF-8");
        record.setLogSizeBytes(0L);
        record.setLogStorageType(runLogStorageType());
        record.setLogStatus("WRITING");
        runRecordMapper.insert(record);
        return record;
    }

    private void linkRunRecord(DispatchTaskEntity task, Long runRecordId) {
        int updated = dispatchTaskMapper.update(null, new LambdaUpdateWrapper<DispatchTaskEntity>()
                .set(DispatchTaskEntity::getRunRecordId, runRecordId)
                .eq(DispatchTaskEntity::getId, task.getId())
                .eq(DispatchTaskEntity::getStatus, "RUNNING")
                .eq(DispatchTaskEntity::getClaimToken, task.getClaimToken())
                .eq(DispatchTaskEntity::getWorkerInstanceId, identity.instanceId())
                .eq(DispatchTaskEntity::getWorkerBootId, identity.bootId()));
        if (updated != 1) {
            throw new IllegalStateException("Streaming dispatch claim was lost before run record link");
        }
        task.setRunRecordId(runRecordId);
    }

    private void completeDispatch(DispatchTaskEntity task,
                                  RunRecordEntity runRecord,
                                  String status,
                                  String message) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("message", message);
        dispatchTaskMapper.update(null, new LambdaUpdateWrapper<DispatchTaskEntity>()
                .set(DispatchTaskEntity::getStatus, status)
                .set(DispatchTaskEntity::getLeaseExpiresAt, now)
                .set(DispatchTaskEntity::getProtectedPayloadCiphertext, null)
                .set(DispatchTaskEntity::getPayloadJson, result, jsonTypeHandler())
                .eq(DispatchTaskEntity::getId, task.getId())
                .eq(DispatchTaskEntity::getStatus, "RUNNING")
                .eq(DispatchTaskEntity::getClaimToken, task.getClaimToken()));
        runRecordMapper.update(null, new LambdaUpdateWrapper<RunRecordEntity>()
                .set(RunRecordEntity::getStatus, status)
                .set(RunRecordEntity::getEndedAt, now)
                .set(RunRecordEntity::getMessage, message)
                .set(RunRecordEntity::getResultJson, result, jsonTypeHandler())
                .eq(RunRecordEntity::getId, runRecord.getId())
                .eq(RunRecordEntity::getStatus, "RUNNING"));
    }

    private void failMalformedDispatch(DispatchTaskEntity task) {
        String summary = "Streaming dispatch identity is incomplete";
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("errorCode", "STREAMING_DISPATCH_IDENTITY_INCOMPLETE");
        payload.put("error", summary);
        dispatchTaskMapper.update(null, new LambdaUpdateWrapper<DispatchTaskEntity>()
                .set(DispatchTaskEntity::getStatus, "FAILED")
                .set(DispatchTaskEntity::getPayloadJson, payload, jsonTypeHandler())
                .set(DispatchTaskEntity::getLeaseExpiresAt, LocalDateTime.now())
                .eq(DispatchTaskEntity::getId, task.getId())
                .eq(DispatchTaskEntity::getStatus, "RUNNING")
                .eq(DispatchTaskEntity::getClaimToken, task.getClaimToken()));
    }

    private void applyPreparedLog(RunRecordEntity record, RunLogFileService.PreparedRunLog logFile) {
        record.setLogFilePath(logFile.getRelativePath());
        record.setLogCharset(logFile.getCharset());
        record.setLogSizeBytes(0L);
        record.setLogStatus("WRITING");
    }

    private void finalizeLog(RunLogFileService.PreparedRunLog preparedLog, RunRecordEntity runRecord) {
        if (preparedLog == null || runRecord == null || runRecord.getId() == null) {
            return;
        }
        RunLogFileService.RunLogStorageResult result = runLogFileService.finalizeLog(preparedLog);
        runRecordMapper.update(null, new LambdaUpdateWrapper<RunRecordEntity>()
                .set(RunRecordEntity::getLogSizeBytes, result.getSizeBytes())
                .set(RunRecordEntity::getLogStorageType, result.getStorageType())
                .set(RunRecordEntity::getLogObjectBucket, result.getBucket())
                .set(RunRecordEntity::getLogObjectKey, result.getObjectKey())
                .set(RunRecordEntity::getLogChunkCount, result.getChunkCount())
                .set(RunRecordEntity::getLogStatus, result.getStatus())
                .set(RunRecordEntity::getLogErrorSummary, result.getErrorSummary())
                .eq(RunRecordEntity::getId, runRecord.getId()));
    }

    private void closeScope(RunLogFileService.RunLogScope scope) {
        if (scope != null) {
            scope.close();
        }
    }

    private void setTaskContext(DispatchTaskEntity task) {
        StudioRequestContext context = new StudioRequestContext();
        context.setTenantId(task.getTenantId());
        context.setProjectId(task.getProjectId());
        context.setUsername(properties.getWorkerCode());
        StudioRequestContextHolder.setContext(context);
    }

    private void restoreTaskContext(StudioRequestContext previous) {
        if (previous == null) {
            StudioRequestContextHolder.clear();
        } else {
            StudioRequestContextHolder.setContext(previous);
        }
    }

    private Map<String, Object> checkpoint(StreamingCheckpoint checkpoint) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (checkpoint != null) {
            result.put("batchId", checkpoint.getBatchId());
            result.put("state", new LinkedHashMap<String, Object>(checkpoint.getState()));
        }
        return result;
    }

    private long stopTimeoutMs(CollectionTaskDefinitionView task) {
        Long configured = task.getStreamingOptions() == null
                ? null : task.getStreamingOptions().getStopTimeoutMs();
        return Math.max(1L, configured == null ? DEFAULT_STOP_TIMEOUT_MS : configured.longValue());
    }

    private String runLogStorageType() {
        String value = properties.getRunLog() == null ? null : properties.getRunLog().getStorageType();
        return value == null || value.trim().isEmpty()
                ? RunLogStorageService.STORAGE_LOCAL : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String jsonTypeHandler() {
        return "typeHandler=" + JacksonTypeHandler.class.getCanonicalName();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map
                ? new LinkedHashMap<String, Object>((Map<String, Object>) value)
                : new LinkedHashMap<String, Object>();
    }

    private Long payloadLong(DispatchTaskEntity task, String key) {
        Object value = task.getPayloadJson() == null ? null : task.getPayloadJson().get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String payloadText(DispatchTaskEntity task, String key) {
        return task.getPayloadJson() == null ? null : text(task.getPayloadJson().get(key));
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private String safeSummary(Throwable failure) {
        String raw = failure == null ? "Streaming attempt failed" : failure.getMessage();
        if (raw == null || raw.trim().isEmpty()) {
            raw = failure == null ? "Streaming attempt failed" : failure.getClass().getSimpleName();
        }
        String sanitized = StudioSensitiveLogSanitizer.sanitize(raw);
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000);
    }

    private final class ActiveStreamingAttempt {
        private final Long attemptId;
        private volatile StreamingJobContainer container;
        private volatile long stopTimeoutMs = DEFAULT_STOP_TIMEOUT_MS;
        private volatile ScheduledFuture<?> forcedStop;

        private ActiveStreamingAttempt(Long attemptId) {
            this.attemptId = attemptId;
        }

        private synchronized void attach(StreamingJobContainer container, long stopTimeoutMs) {
            this.container = container;
            this.stopTimeoutMs = stopTimeoutMs;
        }

        private synchronized void requestStop() {
            if (container == null) {
                return;
            }
            container.requestStop();
            if (forcedStop == null) {
                forcedStop = forcedStopExecutor.schedule(this::cancelNow,
                        stopTimeoutMs, TimeUnit.MILLISECONDS);
            }
        }

        private synchronized void cancelNow() {
            if (container != null) {
                log.warn("Forcing streaming attempt cancellation after stop timeout attemptId={}", attemptId);
                container.cancel();
            }
        }

        private synchronized void close() {
            if (forcedStop != null) {
                forcedStop.cancel(false);
                forcedStop = null;
            }
            container = null;
        }
    }

    private static final class StaleStreamingAttemptException extends RuntimeException {
        private StaleStreamingAttemptException() {
            super("Streaming attempt was fenced before execution");
        }

        private StaleStreamingAttemptException(String message) {
            super(message);
        }
    }

    private static final class CheckpointReportCancelledException extends RuntimeException {
        private CheckpointReportCancelledException(String message) {
            super(message);
        }

        private CheckpointReportCancelledException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
