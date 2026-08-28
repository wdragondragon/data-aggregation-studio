package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;
import com.jdragon.studio.dto.enums.CollectionTaskExecutionMode;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.enums.StreamingDesiredState;
import com.jdragon.studio.dto.enums.StreamingObservedState;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.StreamTaskAttemptEntity;
import com.jdragon.studio.infra.entity.StreamTaskDeployEntity;
import com.jdragon.studio.infra.entity.StreamTaskEventEntity;
import com.jdragon.studio.infra.entity.StreamTaskRunEntity;
import com.jdragon.studio.infra.entity.StreamMetricBucketEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.StreamTaskAttemptMapper;
import com.jdragon.studio.infra.mapper.StreamTaskDeployMapper;
import com.jdragon.studio.infra.mapper.StreamTaskEventMapper;
import com.jdragon.studio.infra.mapper.StreamTaskRunMapper;
import com.jdragon.studio.infra.mapper.StreamMetricBucketMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates one active physical attempt for each native streaming deployment. */
@Service
@Slf4j
public class StreamingTaskCoordinatorService {

    private static final int RECONCILE_LIMIT = 500;
    private static final int DEFAULT_MAX_FAILURES = 10;
    private static final long DEFAULT_RETRY_INITIAL_MS = 5_000L;
    private static final long DEFAULT_RETRY_MAX_MS = 300_000L;

    private final StreamTaskDeployMapper deployMapper;
    private final StreamTaskRunMapper runMapper;
    private final StreamTaskAttemptMapper attemptMapper;
    private final StreamMetricBucketMapper metricMapper;
    private final StreamTaskEventMapper eventMapper;
    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final WorkerLeaseMapper workerLeaseMapper;
    private final CollectionTaskDefinitionMapper collectionTaskMapper;
    private final RuntimeResourceRevisionService resourceRevisionService;

    @Autowired
    public StreamingTaskCoordinatorService(StreamTaskDeployMapper deployMapper,
                                           StreamTaskRunMapper runMapper,
                                           StreamTaskAttemptMapper attemptMapper,
                                           StreamMetricBucketMapper metricMapper,
                                           StreamTaskEventMapper eventMapper,
                                           DispatchTaskMapper dispatchTaskMapper,
                                           RunRecordMapper runRecordMapper,
                                           WorkerLeaseMapper workerLeaseMapper,
                                           CollectionTaskDefinitionMapper collectionTaskMapper,
                                           RuntimeResourceRevisionService resourceRevisionService) {
        this.deployMapper = deployMapper;
        this.runMapper = runMapper;
        this.attemptMapper = attemptMapper;
        this.metricMapper = metricMapper;
        this.eventMapper = eventMapper;
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.workerLeaseMapper = workerLeaseMapper;
        this.collectionTaskMapper = collectionTaskMapper;
        this.resourceRevisionService = resourceRevisionService;
    }

    /** Compatibility constructor retained for isolated coordinator tests and older embedders. */
    public StreamingTaskCoordinatorService(StreamTaskDeployMapper deployMapper,
                                           StreamTaskRunMapper runMapper,
                                           StreamTaskAttemptMapper attemptMapper,
                                           StreamTaskEventMapper eventMapper,
                                           DispatchTaskMapper dispatchTaskMapper,
                                           RunRecordMapper runRecordMapper,
                                           WorkerLeaseMapper workerLeaseMapper,
                                           CollectionTaskDefinitionMapper collectionTaskMapper,
                                           RuntimeResourceRevisionService resourceRevisionService) {
        this(deployMapper, runMapper, attemptMapper, null, eventMapper, dispatchTaskMapper,
                runRecordMapper, workerLeaseMapper, collectionTaskMapper, resourceRevisionService);
    }

    @Transactional
    public int reconcileDeployments() {
        List<StreamTaskDeployEntity> deployments = deployMapper.selectList(
                new LambdaQueryWrapper<StreamTaskDeployEntity>()
                        .and(wrapper -> wrapper.eq(StreamTaskDeployEntity::getDesiredState,
                                        StreamingDesiredState.RUNNING.name())
                                .or().isNotNull(StreamTaskDeployEntity::getCurrentAttemptId)
                                .or().eq(StreamTaskDeployEntity::getObservedState,
                                        StreamingObservedState.STOPPING.name()))
                        .orderByAsc(StreamTaskDeployEntity::getUpdatedAt)
                        .last("limit " + RECONCILE_LIMIT));
        int changed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (StreamTaskDeployEntity deployment : deployments) {
            changed += reconcileDeployment(deployment, now) ? 1 : 0;
        }
        return changed;
    }

    @Transactional
    public boolean workerStarted(Long attemptId,
                                 Long dispatchTaskId,
                                 Long runRecordId,
                                 String workerInstanceId,
                                 String workerBootId) {
        StreamTaskAttemptEntity attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || !equalsLong(attempt.getDispatchTaskId(), dispatchTaskId)) {
            return false;
        }
        StreamTaskDeployEntity deployment = findDeployment(attempt.getCollectionTaskId());
        if (!isCurrentRunningAttempt(deployment, attempt)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = attemptMapper.update(null, new LambdaUpdateWrapper<StreamTaskAttemptEntity>()
                .set(StreamTaskAttemptEntity::getRunRecordId, runRecordId)
                .set(StreamTaskAttemptEntity::getWorkerInstanceId, workerInstanceId)
                .set(StreamTaskAttemptEntity::getWorkerBootId, workerBootId)
                .set(StreamTaskAttemptEntity::getStatus, "RUNNING")
                .set(StreamTaskAttemptEntity::getStartedAt, now)
                .set(StreamTaskAttemptEntity::getHeartbeatAt, now)
                .eq(StreamTaskAttemptEntity::getId, attemptId)
                .in(StreamTaskAttemptEntity::getStatus, "QUEUED", "STARTING"));
        if (updated == 0 && !"RUNNING".equalsIgnoreCase(attempt.getStatus())) {
            return false;
        }
        if (!advanceObservedState(deployment, StreamingObservedState.RUNNING, null, null, null)) {
            return false;
        }
        appendEvent(deployment, attempt.getRunId(), attemptId, "ATTEMPT_STARTED",
                StreamingObservedState.STARTING.name(), StreamingObservedState.RUNNING.name(),
                "Streaming attempt started on Worker", null);
        return true;
    }

    public boolean isStopRequested(Long attemptId, Long dispatchTaskId, Long generation) {
        StreamTaskAttemptEntity attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || !equalsLong(attempt.getDispatchTaskId(), dispatchTaskId)) {
            return true;
        }
        StreamTaskDeployEntity deployment = findDeployment(attempt.getCollectionTaskId());
        if (deployment == null
                || !StreamingDesiredState.RUNNING.name().equals(deployment.getDesiredState())
                || !equalsLong(deployment.getCurrentAttemptId(), attemptId)
                || !equalsLong(deployment.getGeneration(), generation)) {
            return true;
        }
        DispatchTaskEntity dispatch = dispatchTaskMapper.selectById(dispatchTaskId);
        return dispatch == null || Integer.valueOf(1).equals(dispatch.getTerminationRequested());
    }

    public void heartbeat(Long attemptId, String workerInstanceId, String workerBootId) {
        attemptMapper.update(null, new LambdaUpdateWrapper<StreamTaskAttemptEntity>()
                .set(StreamTaskAttemptEntity::getHeartbeatAt, LocalDateTime.now())
                .eq(StreamTaskAttemptEntity::getId, attemptId)
                .eq(StreamTaskAttemptEntity::getStatus, "RUNNING")
                .eq(StreamTaskAttemptEntity::getWorkerInstanceId, workerInstanceId)
                .eq(StreamTaskAttemptEntity::getWorkerBootId, workerBootId));
    }

    @Transactional
    public boolean batchCommitted(Long attemptId, Map<String, Object> checkpoint) {
        StreamTaskAttemptEntity attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || !"RUNNING".equalsIgnoreCase(attempt.getStatus())) {
            return false;
        }
        StreamTaskDeployEntity deployment = findDeployment(attempt.getCollectionTaskId());
        if (!isCurrentRunningAttempt(deployment, attempt)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> checkpointCopy = copyMap(checkpoint);
        attempt.setCheckpointJson(checkpointCopy);
        attempt.setCommittedBatchCount(safeLong(attempt.getCommittedBatchCount()) + 1L);
        attempt.setHeartbeatAt(now);
        attemptMapper.updateById(attempt);
        int updated = deployMapper.update(null, new LambdaUpdateWrapper<StreamTaskDeployEntity>()
                .set(StreamTaskDeployEntity::getObservedState, StreamingObservedState.RUNNING.name())
                .set(StreamTaskDeployEntity::getConsecutiveFailureCount, 0)
                .set(StreamTaskDeployEntity::getNextRetryAt, null)
                .set(StreamTaskDeployEntity::getLastCheckpointJson, checkpointCopy, jsonTypeHandler())
                .set(StreamTaskDeployEntity::getLastCheckpointAt, now)
                .set(StreamTaskDeployEntity::getLastErrorCode, null)
                .set(StreamTaskDeployEntity::getLastErrorSummary, null)
                .set(StreamTaskDeployEntity::getVersion, safeInt(deployment.getVersion()) + 1)
                .set(StreamTaskDeployEntity::getUpdatedAt, now)
                .eq(StreamTaskDeployEntity::getId, deployment.getId())
                .eq(StreamTaskDeployEntity::getVersion, safeInt(deployment.getVersion()))
                .eq(StreamTaskDeployEntity::getDesiredState, StreamingDesiredState.RUNNING.name())
                .eq(StreamTaskDeployEntity::getCurrentAttemptId, attemptId)
                .eq(StreamTaskDeployEntity::getGeneration, attempt.getGeneration()));
        return updated == 1;
    }

    /**
     * Persists the current absolute values for one minute bucket. The Worker owns
     * delta calculation; writing absolute bucket values makes retries idempotent.
     */
    @Transactional
    public boolean recordMetricBucket(Long attemptId,
                                      Long runId,
                                      Long collectionTaskId,
                                      LocalDateTime bucketStart,
                                      long recordsRead,
                                      long writeSucceedRecords,
                                      long writeFailedRecords,
                                      long dirtyRecords,
                                      long bytesRead,
                                      long batchCount,
                                      long retryCount,
                                      long currentLag,
                                      long maxLag,
                                      LocalDateTime lastMessageAt,
                                      LocalDateTime lastCheckpointAt,
                                      long rebalanceCount) {
        if (attemptId == null || bucketStart == null || metricMapper == null) {
            return false;
        }
        StreamTaskAttemptEntity attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            return false;
        }
        StreamMetricBucketEntity entity = metricMapper.selectOne(new LambdaQueryWrapper<StreamMetricBucketEntity>()
                .eq(StreamMetricBucketEntity::getTenantId, attempt.getTenantId())
                .eq(StreamMetricBucketEntity::getProjectId, attempt.getProjectId())
                .eq(StreamMetricBucketEntity::getAttemptId, attemptId)
                .eq(StreamMetricBucketEntity::getBucketStart, bucketStart)
                .last("limit 1"));
        if (entity == null) {
            entity = new StreamMetricBucketEntity();
            entity.setId(IdWorker.getId());
            entity.setTenantId(attempt.getTenantId());
            entity.setProjectId(attempt.getProjectId());
            entity.setCollectionTaskId(collectionTaskId == null ? attempt.getCollectionTaskId() : collectionTaskId);
            entity.setRunId(runId == null ? attempt.getRunId() : runId);
            entity.setAttemptId(attemptId);
            entity.setBucketStart(bucketStart);
            entity.setRecordsRead(0L);
            entity.setWriteSucceedRecords(0L);
            entity.setWriteFailedRecords(0L);
            entity.setDirtyRecords(0L);
            entity.setBytesRead(0L);
            entity.setBatchCount(0L);
            entity.setRetryCount(0L);
            entity.setCurrentLag(0L);
            entity.setMaxLag(0L);
            entity.setRebalanceCount(0L);
            metricMapper.insert(entity);
        }
        entity.setRecordsRead(Math.max(0L, recordsRead));
        entity.setWriteSucceedRecords(Math.max(0L, writeSucceedRecords));
        entity.setWriteFailedRecords(Math.max(0L, writeFailedRecords));
        entity.setDirtyRecords(Math.max(0L, dirtyRecords));
        entity.setBytesRead(Math.max(0L, bytesRead));
        entity.setBatchCount(Math.max(0L, batchCount));
        entity.setRetryCount(Math.max(0L, retryCount));
        entity.setCurrentLag(Math.max(0L, currentLag));
        entity.setMaxLag(Math.max(0L, maxLag));
        entity.setLastMessageAt(lastMessageAt);
        entity.setLastCheckpointAt(lastCheckpointAt);
        entity.setRebalanceCount(Math.max(0L, rebalanceCount));
        return metricMapper.updateById(entity) == 1;
    }

    @Transactional
    public void attemptStopped(Long attemptId, Long runRecordId, Map<String, Object> checkpoint) {
        StreamTaskAttemptEntity attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || isTerminalAttempt(attempt.getStatus())) {
            return;
        }
        StreamTaskDeployEntity deployment = findDeployment(attempt.getCollectionTaskId());
        if (deployment == null) {
            return;
        }
        if (StreamingDesiredState.RUNNING.name().equals(deployment.getDesiredState())) {
            failAttempt(deployment, attempt, "STREAMING_ATTEMPT_ENDED",
                    "Streaming attempt ended while deployment still requested RUNNING", "FAILED");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> checkpointCopy = copyMap(checkpoint);
        attemptMapper.update(null, new LambdaUpdateWrapper<StreamTaskAttemptEntity>()
                .set(StreamTaskAttemptEntity::getStatus, "STOPPED")
                .set(StreamTaskAttemptEntity::getEndedAt, now)
                .set(StreamTaskAttemptEntity::getHeartbeatAt, now)
                .set(StreamTaskAttemptEntity::getRunRecordId, runRecordId)
                .set(StreamTaskAttemptEntity::getCheckpointJson, checkpointCopy, jsonTypeHandler())
                .eq(StreamTaskAttemptEntity::getId, attemptId)
                .in(StreamTaskAttemptEntity::getStatus, "QUEUED", "STARTING", "RUNNING", "STOPPING"));
        finishStoppedDeployment(deployment, attempt, checkpointCopy, now);
    }

    @Transactional
    public void attemptFailed(Long attemptId, String errorCode, String errorSummary) {
        StreamTaskAttemptEntity attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || isTerminalAttempt(attempt.getStatus())) {
            return;
        }
        StreamTaskDeployEntity deployment = findDeployment(attempt.getCollectionTaskId());
        if (deployment == null) {
            return;
        }
        failAttempt(deployment, attempt, errorCode, errorSummary, "FAILED");
    }

    @Transactional
    public void workerRestartInterrupted(DispatchTaskEntity dispatch) {
        if (dispatch == null || dispatch.getId() == null) {
            return;
        }
        Long attemptId = longValue(dispatch.getPayloadJson(), "streamAttemptId");
        StreamTaskAttemptEntity attempt = attemptId == null ? null : attemptMapper.selectById(attemptId);
        if (attempt == null || isTerminalAttempt(attempt.getStatus())) {
            return;
        }
        StreamTaskDeployEntity deployment = findDeployment(attempt.getCollectionTaskId());
        if (deployment != null) {
            failAttempt(deployment, attempt, "WORKER_RESTART_INTERRUPTED",
                    "Streaming attempt was interrupted by Worker restart", "INTERRUPTED");
        }
    }

    private boolean reconcileDeployment(StreamTaskDeployEntity deployment, LocalDateTime now) {
        StreamTaskDeployEntity current = deployMapper.selectById(deployment.getId());
        if (current == null) {
            return false;
        }
        CollectionTaskDefinitionEntity task = collectionTaskMapper.selectById(current.getCollectionTaskId());
        if (StreamingDesiredState.STOPPED.name().equals(current.getDesiredState())) {
            return requestStop(current, now);
        }
        if (!isRunnableStreamingTask(task)) {
            return failDeploymentConfiguration(current, task);
        }
        if (StreamingObservedState.FAILED.name().equals(current.getObservedState())) {
            return false;
        }
        if (current.getNextRetryAt() != null && current.getNextRetryAt().isAfter(now)) {
            return false;
        }
        if (current.getCurrentAttemptId() == null) {
            return createAttempt(current, task, now);
        }
        StreamTaskAttemptEntity attempt = attemptMapper.selectById(current.getCurrentAttemptId());
        if (attempt == null) {
            return clearMissingAttempt(current, now);
        }
        if (isTerminalAttempt(attempt.getStatus())) {
            return handleTerminalCurrentAttempt(current, attempt);
        }
        DispatchTaskEntity dispatch = dispatchTaskMapper.selectById(attempt.getDispatchTaskId());
        if (dispatch == null) {
            failAttempt(current, attempt, "STREAMING_DISPATCH_MISSING",
                    "Streaming dispatch is missing", "FAILED");
            return true;
        }
        if ("QUEUED".equalsIgnoreCase(dispatch.getStatus())) {
            return false;
        }
        if ("RUNNING".equalsIgnoreCase(dispatch.getStatus())) {
            if (dispatch.getLeaseExpiresAt() == null || dispatch.getLeaseExpiresAt().isAfter(now)
                    || isWorkerAlive(dispatch, now)) {
                return false;
            }
            failAttempt(current, attempt, "WORKER_LEASE_EXPIRED",
                    "Streaming attempt owner lease expired", "INTERRUPTED");
            return true;
        }
        failAttempt(current, attempt, "STREAMING_DISPATCH_TERMINATED",
                "Streaming dispatch ended with status " + dispatch.getStatus(), "FAILED");
        return true;
    }

    private boolean createAttempt(StreamTaskDeployEntity deployment,
                                  CollectionTaskDefinitionEntity task,
                                  LocalDateTime now) {
        Long runId = deployment.getCurrentRunId();
        StreamTaskRunEntity run = runId == null ? null : runMapper.selectById(runId);
        if (run == null || !"RUNNING".equalsIgnoreCase(run.getStatus())) {
            return failDeploymentConfiguration(deployment, task);
        }
        long attemptId = IdWorker.getId();
        long dispatchId = IdWorker.getId();
        int attemptNo = nextAttemptNo(runId);
        int expectedVersion = safeInt(deployment.getVersion());
        int claimed = deployMapper.update(null, new LambdaUpdateWrapper<StreamTaskDeployEntity>()
                .set(StreamTaskDeployEntity::getCurrentAttemptId, attemptId)
                .set(StreamTaskDeployEntity::getObservedState, StreamingObservedState.STARTING.name())
                .set(StreamTaskDeployEntity::getNextRetryAt, null)
                .set(StreamTaskDeployEntity::getVersion, expectedVersion + 1)
                .set(StreamTaskDeployEntity::getUpdatedAt, now)
                .eq(StreamTaskDeployEntity::getId, deployment.getId())
                .eq(StreamTaskDeployEntity::getVersion, expectedVersion)
                .eq(StreamTaskDeployEntity::getGeneration, deployment.getGeneration())
                .eq(StreamTaskDeployEntity::getDesiredState, StreamingDesiredState.RUNNING.name())
                .isNull(StreamTaskDeployEntity::getCurrentAttemptId));
        if (claimed == 0) {
            return false;
        }

        StreamTaskAttemptEntity attempt = new StreamTaskAttemptEntity();
        attempt.setId(attemptId);
        attempt.setTenantId(deployment.getTenantId());
        attempt.setProjectId(deployment.getProjectId());
        attempt.setRunId(runId);
        attempt.setCollectionTaskId(task.getId());
        attempt.setGeneration(deployment.getGeneration());
        attempt.setAttemptNo(attemptNo);
        attempt.setDispatchTaskId(dispatchId);
        attempt.setRuntimeClusterId(deployment.getRuntimeClusterId());
        attempt.setStatus("QUEUED");
        attempt.setCommittedBatchCount(0L);
        attemptMapper.insert(attempt);

        DispatchTaskEntity dispatch = new DispatchTaskEntity();
        dispatch.setId(dispatchId);
        dispatch.setTenantId(deployment.getTenantId());
        dispatch.setProjectId(deployment.getProjectId());
        dispatch.setExecutionType(DispatchExecutionType.STREAMING_COLLECTION_TASK.name());
        dispatch.setCollectionTaskId(task.getId());
        dispatch.setTriggeredByUserId(run.getStartedBy());
        dispatch.setNodeCode("streaming-collection-" + task.getId());
        dispatch.setStatus("QUEUED");
        dispatch.setTerminationRequested(0);
        dispatch.setTargetClusterId(deployment.getRuntimeClusterId());
        dispatch.setResourceRevision(resourceRevisionService.collectionTaskRevision(task.getId(), task.getUpdatedAt()));
        dispatch.setAttempts(0);
        dispatch.setMaxRetries(0);
        dispatch.setPayloadJson(dispatchPayload(deployment, run, attempt, task));
        dispatchTaskMapper.insert(dispatch);

        appendEvent(deployment, runId, attemptId, "ATTEMPT_QUEUED",
                deployment.getObservedState(), StreamingObservedState.STARTING.name(),
                "Streaming attempt queued for Worker dispatch", details("dispatchTaskId", dispatchId));
        return true;
    }

    private boolean requestStop(StreamTaskDeployEntity deployment, LocalDateTime now) {
        if (deployment.getCurrentAttemptId() == null) {
            return finishStoppedDeployment(deployment, null, copyMap(deployment.getLastCheckpointJson()), now);
        }
        StreamTaskAttemptEntity attempt = attemptMapper.selectById(deployment.getCurrentAttemptId());
        if (attempt == null || isTerminalAttempt(attempt.getStatus())) {
            return finishStoppedDeployment(deployment, attempt, copyMap(deployment.getLastCheckpointJson()), now);
        }
        DispatchTaskEntity dispatch = dispatchTaskMapper.selectById(attempt.getDispatchTaskId());
        if (dispatch == null || "QUEUED".equalsIgnoreCase(dispatch.getStatus())) {
            if (dispatch != null) {
                Map<String, Object> payload = copyMap(dispatch.getPayloadJson());
                payload.put("errorCode", "STREAMING_OFFLINE_BEFORE_START");
                payload.put("error", "Streaming deployment was stopped before Worker start");
                dispatchTaskMapper.update(null, new LambdaUpdateWrapper<DispatchTaskEntity>()
                        .set(DispatchTaskEntity::getStatus, "SUCCESS")
                        .set(DispatchTaskEntity::getTerminationRequested, 1)
                        .set(DispatchTaskEntity::getPayloadJson, payload, jsonTypeHandler())
                        .eq(DispatchTaskEntity::getId, dispatch.getId())
                        .eq(DispatchTaskEntity::getStatus, "QUEUED"));
            }
            attemptMapper.update(null, new LambdaUpdateWrapper<StreamTaskAttemptEntity>()
                    .set(StreamTaskAttemptEntity::getStatus, "STOPPED")
                    .set(StreamTaskAttemptEntity::getEndedAt, now)
                    .eq(StreamTaskAttemptEntity::getId, attempt.getId())
                    .in(StreamTaskAttemptEntity::getStatus, "QUEUED", "STARTING"));
            return finishStoppedDeployment(deployment, attempt,
                    copyMap(deployment.getLastCheckpointJson()), now);
        }
        if ("RUNNING".equalsIgnoreCase(dispatch.getStatus())) {
            dispatchTaskMapper.update(null, new LambdaUpdateWrapper<DispatchTaskEntity>()
                    .set(DispatchTaskEntity::getTerminationRequested, 1)
                    .eq(DispatchTaskEntity::getId, dispatch.getId())
                    .eq(DispatchTaskEntity::getStatus, "RUNNING"));
            advanceObservedState(deployment, StreamingObservedState.STOPPING, null, null, null);
            return true;
        }
        return finishStoppedDeployment(deployment, attempt,
                copyMap(deployment.getLastCheckpointJson()), now);
    }

    private boolean finishStoppedDeployment(StreamTaskDeployEntity deployment,
                                            StreamTaskAttemptEntity attempt,
                                            Map<String, Object> checkpoint,
                                            LocalDateTime now) {
        int expectedVersion = safeInt(deployment.getVersion());
        int updated = deployMapper.update(null, new LambdaUpdateWrapper<StreamTaskDeployEntity>()
                .set(StreamTaskDeployEntity::getObservedState, StreamingObservedState.STOPPED.name())
                .set(StreamTaskDeployEntity::getCurrentAttemptId, null)
                .set(StreamTaskDeployEntity::getNextRetryAt, null)
                .set(StreamTaskDeployEntity::getLastCheckpointJson, checkpoint, jsonTypeHandler())
                .set(StreamTaskDeployEntity::getLastCheckpointAt,
                        checkpoint.isEmpty() ? deployment.getLastCheckpointAt() : now)
                .set(StreamTaskDeployEntity::getVersion, expectedVersion + 1)
                .set(StreamTaskDeployEntity::getUpdatedAt, now)
                .eq(StreamTaskDeployEntity::getId, deployment.getId())
                .eq(StreamTaskDeployEntity::getVersion, expectedVersion)
                .eq(StreamTaskDeployEntity::getDesiredState, StreamingDesiredState.STOPPED.name()));
        if (updated == 0) {
            return false;
        }
        StreamTaskRunEntity run = deployment.getCurrentRunId() == null
                ? null : runMapper.selectById(deployment.getCurrentRunId());
        if (run != null && !"STOPPED".equalsIgnoreCase(run.getStatus())) {
            run.setStatus("STOPPED");
            if (run.getStopRequestedAt() == null) {
                run.setStopRequestedAt(now);
            }
            run.setStoppedAt(now);
            run.setFinalCheckpointJson(checkpoint);
            runMapper.updateById(run);
        }
        appendEvent(deployment, deployment.getCurrentRunId(), attempt == null ? null : attempt.getId(),
                "STOPPED", deployment.getObservedState(), StreamingObservedState.STOPPED.name(),
                "Streaming deployment stopped", null);
        return true;
    }

    private void failAttempt(StreamTaskDeployEntity deployment,
                             StreamTaskAttemptEntity attempt,
                             String errorCode,
                             String errorSummary,
                             String attemptStatus) {
        LocalDateTime now = LocalDateTime.now();
        String safeCode = text(errorCode, "STREAMING_ATTEMPT_FAILED", 128);
        String safeSummary = text(errorSummary, "Streaming attempt failed", 1000);
        if (StreamingDesiredState.STOPPED.name().equals(deployment.getDesiredState())) {
            attemptMapper.update(null, new LambdaUpdateWrapper<StreamTaskAttemptEntity>()
                    .set(StreamTaskAttemptEntity::getStatus, "STOPPED")
                    .set(StreamTaskAttemptEntity::getEndedAt, now)
                    .set(StreamTaskAttemptEntity::getErrorCode, safeCode)
                    .set(StreamTaskAttemptEntity::getErrorSummary, safeSummary)
                    .eq(StreamTaskAttemptEntity::getId, attempt.getId())
                    .in(StreamTaskAttemptEntity::getStatus, "QUEUED", "STARTING", "RUNNING", "STOPPING"));
            finishStoppedDeployment(deployment, attempt, copyMap(attempt.getCheckpointJson()), now);
            return;
        }

        CollectionTaskDefinitionEntity task = collectionTaskMapper.selectById(deployment.getCollectionTaskId());
        int failureCount = safeInt(deployment.getConsecutiveFailureCount()) + 1;
        int maxFailures = intOption(task, "maxConsecutiveFailures", DEFAULT_MAX_FAILURES, 1);
        boolean exhausted = failureCount >= maxFailures;
        LocalDateTime retryAt = exhausted ? null : now.plusNanos(retryDelayMillis(task, failureCount) * 1_000_000L);
        attemptMapper.update(null, new LambdaUpdateWrapper<StreamTaskAttemptEntity>()
                .set(StreamTaskAttemptEntity::getStatus, attemptStatus)
                .set(StreamTaskAttemptEntity::getEndedAt, now)
                .set(StreamTaskAttemptEntity::getHeartbeatAt, now)
                .set(StreamTaskAttemptEntity::getRetryAfter, retryAt)
                .set(StreamTaskAttemptEntity::getErrorCode, safeCode)
                .set(StreamTaskAttemptEntity::getErrorSummary, safeSummary)
                .eq(StreamTaskAttemptEntity::getId, attempt.getId())
                .in(StreamTaskAttemptEntity::getStatus, "QUEUED", "STARTING", "RUNNING", "STOPPING"));
        closeDispatchAndRunRecord(attempt, safeCode, safeSummary, now);

        int expectedVersion = safeInt(deployment.getVersion());
        String nextState = exhausted
                ? StreamingObservedState.FAILED.name() : StreamingObservedState.RECOVERING.name();
        int updated = deployMapper.update(null, new LambdaUpdateWrapper<StreamTaskDeployEntity>()
                .set(StreamTaskDeployEntity::getObservedState, nextState)
                .set(StreamTaskDeployEntity::getCurrentAttemptId, null)
                .set(StreamTaskDeployEntity::getConsecutiveFailureCount, failureCount)
                .set(StreamTaskDeployEntity::getNextRetryAt, retryAt)
                .set(StreamTaskDeployEntity::getLastErrorCode, safeCode)
                .set(StreamTaskDeployEntity::getLastErrorSummary, safeSummary)
                .set(StreamTaskDeployEntity::getVersion, expectedVersion + 1)
                .set(StreamTaskDeployEntity::getUpdatedAt, now)
                .eq(StreamTaskDeployEntity::getId, deployment.getId())
                .eq(StreamTaskDeployEntity::getVersion, expectedVersion)
                .eq(StreamTaskDeployEntity::getDesiredState, StreamingDesiredState.RUNNING.name())
                .eq(StreamTaskDeployEntity::getCurrentAttemptId, attempt.getId()));
        if (updated == 1) {
            appendEvent(deployment, attempt.getRunId(), attempt.getId(),
                    exhausted ? "ATTEMPT_FAILURE_LIMIT_REACHED" : "ATTEMPT_RECOVERY_SCHEDULED",
                    deployment.getObservedState(), nextState, safeSummary,
                    details("consecutiveFailureCount", failureCount,
                            "retryAt", retryAt == null ? null : retryAt.toString()));
        }
    }

    private void closeDispatchAndRunRecord(StreamTaskAttemptEntity attempt,
                                           String errorCode,
                                           String errorSummary,
                                           LocalDateTime now) {
        if (attempt.getDispatchTaskId() != null) {
            DispatchTaskEntity dispatch = dispatchTaskMapper.selectById(attempt.getDispatchTaskId());
            if (dispatch != null && ("QUEUED".equalsIgnoreCase(dispatch.getStatus())
                    || "RUNNING".equalsIgnoreCase(dispatch.getStatus()))) {
                Map<String, Object> payload = copyMap(dispatch.getPayloadJson());
                payload.put("errorCode", errorCode);
                payload.put("error", errorSummary);
                dispatchTaskMapper.update(null, new LambdaUpdateWrapper<DispatchTaskEntity>()
                        .set(DispatchTaskEntity::getStatus, "FAILED")
                        .set(DispatchTaskEntity::getLeaseExpiresAt, now)
                        .set(DispatchTaskEntity::getProtectedPayloadCiphertext, null)
                        .set(DispatchTaskEntity::getPayloadJson, payload, jsonTypeHandler())
                        .eq(DispatchTaskEntity::getId, dispatch.getId())
                        .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING"));
            }
        }
        Long runRecordId = attempt.getRunRecordId();
        if (runRecordId != null) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("errorCode", errorCode);
            result.put("error", errorSummary);
            runRecordMapper.update(null, new LambdaUpdateWrapper<RunRecordEntity>()
                    .set(RunRecordEntity::getStatus, "FAILED")
                    .set(RunRecordEntity::getEndedAt, now)
                    .set(RunRecordEntity::getMessage, errorSummary)
                    .set(RunRecordEntity::getResultJson, result, jsonTypeHandler())
                    .eq(RunRecordEntity::getId, runRecordId)
                    .eq(RunRecordEntity::getStatus, "RUNNING"));
        }
    }

    private boolean handleTerminalCurrentAttempt(StreamTaskDeployEntity deployment,
                                                 StreamTaskAttemptEntity attempt) {
        if (StreamingDesiredState.STOPPED.name().equals(deployment.getDesiredState())) {
            return finishStoppedDeployment(deployment, attempt,
                    copyMap(attempt.getCheckpointJson()), LocalDateTime.now());
        }
        failAttempt(deployment, attempt, "STREAMING_ATTEMPT_TERMINAL",
                "Streaming attempt became terminal before deployment stopped", "FAILED");
        return true;
    }

    private boolean clearMissingAttempt(StreamTaskDeployEntity deployment, LocalDateTime now) {
        int expectedVersion = safeInt(deployment.getVersion());
        return deployMapper.update(null, new LambdaUpdateWrapper<StreamTaskDeployEntity>()
                .set(StreamTaskDeployEntity::getCurrentAttemptId, null)
                .set(StreamTaskDeployEntity::getObservedState, StreamingObservedState.RECOVERING.name())
                .set(StreamTaskDeployEntity::getNextRetryAt, now)
                .set(StreamTaskDeployEntity::getLastErrorCode, "STREAMING_ATTEMPT_MISSING")
                .set(StreamTaskDeployEntity::getLastErrorSummary, "Current streaming attempt record is missing")
                .set(StreamTaskDeployEntity::getVersion, expectedVersion + 1)
                .set(StreamTaskDeployEntity::getUpdatedAt, now)
                .eq(StreamTaskDeployEntity::getId, deployment.getId())
                .eq(StreamTaskDeployEntity::getVersion, expectedVersion)
                .eq(StreamTaskDeployEntity::getCurrentAttemptId, deployment.getCurrentAttemptId())) == 1;
    }

    private boolean failDeploymentConfiguration(StreamTaskDeployEntity deployment,
                                                CollectionTaskDefinitionEntity task) {
        String summary = task == null
                ? "Streaming collection task no longer exists"
                : "Streaming collection task is not ONLINE with STREAMING execution mode";
        int expectedVersion = safeInt(deployment.getVersion());
        int updated = deployMapper.update(null, new LambdaUpdateWrapper<StreamTaskDeployEntity>()
                .set(StreamTaskDeployEntity::getObservedState, StreamingObservedState.FAILED.name())
                .set(StreamTaskDeployEntity::getNextRetryAt, null)
                .set(StreamTaskDeployEntity::getLastErrorCode, "STREAMING_TASK_NOT_RUNNABLE")
                .set(StreamTaskDeployEntity::getLastErrorSummary, summary)
                .set(StreamTaskDeployEntity::getVersion, expectedVersion + 1)
                .set(StreamTaskDeployEntity::getUpdatedAt, LocalDateTime.now())
                .eq(StreamTaskDeployEntity::getId, deployment.getId())
                .eq(StreamTaskDeployEntity::getVersion, expectedVersion)
                .eq(StreamTaskDeployEntity::getDesiredState, StreamingDesiredState.RUNNING.name()));
        if (updated == 1) {
            appendEvent(deployment, deployment.getCurrentRunId(), deployment.getCurrentAttemptId(),
                    "DEPLOYMENT_FAILED", deployment.getObservedState(), StreamingObservedState.FAILED.name(),
                    summary, null);
        }
        return updated == 1;
    }

    private boolean advanceObservedState(StreamTaskDeployEntity deployment,
                                         StreamingObservedState state,
                                         Integer failureCount,
                                         String errorCode,
                                         String errorSummary) {
        int expectedVersion = safeInt(deployment.getVersion());
        LambdaUpdateWrapper<StreamTaskDeployEntity> update = new LambdaUpdateWrapper<StreamTaskDeployEntity>()
                .set(StreamTaskDeployEntity::getObservedState, state.name())
                .set(StreamTaskDeployEntity::getVersion, expectedVersion + 1)
                .set(StreamTaskDeployEntity::getUpdatedAt, LocalDateTime.now())
                .eq(StreamTaskDeployEntity::getId, deployment.getId())
                .eq(StreamTaskDeployEntity::getVersion, expectedVersion)
                .eq(StreamTaskDeployEntity::getCurrentAttemptId, deployment.getCurrentAttemptId());
        if (failureCount != null) {
            update.set(StreamTaskDeployEntity::getConsecutiveFailureCount, failureCount);
        }
        if (errorCode != null) {
            update.set(StreamTaskDeployEntity::getLastErrorCode, errorCode);
        }
        if (errorSummary != null) {
            update.set(StreamTaskDeployEntity::getLastErrorSummary, errorSummary);
        }
        return deployMapper.update(null, update) == 1;
    }

    private boolean isWorkerAlive(DispatchTaskEntity dispatch, LocalDateTime now) {
        if (dispatch.getWorkerInstanceId() == null || dispatch.getWorkerBootId() == null) {
            return false;
        }
        WorkerLeaseEntity lease = workerLeaseMapper.selectOne(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getTenantId, dispatch.getTenantId())
                .eq(dispatch.getTargetClusterId() != null, WorkerLeaseEntity::getRuntimeClusterId,
                        dispatch.getTargetClusterId())
                .eq(WorkerLeaseEntity::getInstanceId, dispatch.getWorkerInstanceId())
                .eq(WorkerLeaseEntity::getBootId, dispatch.getWorkerBootId())
                .orderByDesc(WorkerLeaseEntity::getLastHeartbeatAt)
                .last("limit 1"));
        if (lease == null || !StudioConstants.WORKER_STATUS_ONLINE.equalsIgnoreCase(lease.getStatus())) {
            return false;
        }
        LocalDateTime threshold = now.minusSeconds(StudioConstants.WORKER_HEARTBEAT_TIMEOUT_SECONDS);
        return lease.getLeaseExpiresAt() != null && lease.getLeaseExpiresAt().isAfter(now)
                || lease.getLastHeartbeatAt() != null && lease.getLastHeartbeatAt().isAfter(threshold);
    }

    private boolean isCurrentRunningAttempt(StreamTaskDeployEntity deployment,
                                            StreamTaskAttemptEntity attempt) {
        return deployment != null
                && StreamingDesiredState.RUNNING.name().equals(deployment.getDesiredState())
                && equalsLong(deployment.getCurrentAttemptId(), attempt.getId())
                && equalsLong(deployment.getGeneration(), attempt.getGeneration());
    }

    private boolean isRunnableStreamingTask(CollectionTaskDefinitionEntity task) {
        return task != null
                && CollectionTaskExecutionMode.STREAMING.name().equalsIgnoreCase(task.getExecutionMode())
                && CollectionTaskStatus.ONLINE.name().equalsIgnoreCase(task.getStatus());
    }

    private boolean isTerminalAttempt(String status) {
        return "STOPPED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "INTERRUPTED".equalsIgnoreCase(status);
    }

    private int nextAttemptNo(Long runId) {
        Long count = attemptMapper.selectCount(new LambdaQueryWrapper<StreamTaskAttemptEntity>()
                .eq(StreamTaskAttemptEntity::getRunId, runId));
        return (count == null ? 0 : count.intValue()) + 1;
    }

    private long retryDelayMillis(CollectionTaskDefinitionEntity task, int failureCount) {
        long initial = longOption(task, "retryInitialDelayMs", DEFAULT_RETRY_INITIAL_MS, 1L);
        long maximum = longOption(task, "retryMaxDelayMs", DEFAULT_RETRY_MAX_MS, initial);
        long multiplier = 1L << Math.min(30, Math.max(0, failureCount - 1));
        if (initial > Long.MAX_VALUE / multiplier) {
            return maximum;
        }
        return Math.min(maximum, initial * multiplier);
    }

    private int intOption(CollectionTaskDefinitionEntity task, String key, int fallback, int minimum) {
        Object value = option(task, key);
        if (value instanceof Number) {
            return Math.max(minimum, ((Number) value).intValue());
        }
        try {
            return value == null ? fallback : Math.max(minimum, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long longOption(CollectionTaskDefinitionEntity task, String key, long fallback, long minimum) {
        Object value = option(task, key);
        if (value instanceof Number) {
            return Math.max(minimum, ((Number) value).longValue());
        }
        try {
            return value == null ? fallback : Math.max(minimum, Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Object option(CollectionTaskDefinitionEntity task, String key) {
        return task == null || task.getStreamingOptionsJson() == null
                ? null : task.getStreamingOptionsJson().get(key);
    }

    private Map<String, Object> dispatchPayload(StreamTaskDeployEntity deployment,
                                                StreamTaskRunEntity run,
                                                StreamTaskAttemptEntity attempt,
                                                CollectionTaskDefinitionEntity task) {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("collectionTaskId", task.getId());
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("nodeType", "COLLECTION_TASK");
        payload.put("collectionTaskId", task.getId());
        payload.put("streamDeploymentId", deployment.getId());
        payload.put("streamRunId", run.getId());
        payload.put("streamAttemptId", attempt.getId());
        payload.put("streamAttemptNo", attempt.getAttemptNo());
        payload.put("streamGeneration", deployment.getGeneration());
        payload.put("groupId", run.getGroupId());
        payload.put("config", config);
        return payload;
    }

    private void appendEvent(StreamTaskDeployEntity deployment,
                             Long runId,
                             Long attemptId,
                             String eventType,
                             String fromState,
                             String toState,
                             String message,
                             Map<String, Object> details) {
        StreamTaskEventEntity event = new StreamTaskEventEntity();
        event.setId(IdWorker.getId());
        event.setTenantId(deployment.getTenantId());
        event.setProjectId(deployment.getProjectId());
        event.setCollectionTaskId(deployment.getCollectionTaskId());
        event.setDeploymentId(deployment.getId());
        event.setRunId(runId);
        event.setAttemptId(attemptId);
        event.setGeneration(deployment.getGeneration());
        event.setEventType(eventType);
        event.setFromState(fromState);
        event.setToState(toState);
        event.setMessage(text(message, eventType, 1000));
        event.setDetailsJson(copyMap(details));
        event.setOccurredAt(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private StreamTaskDeployEntity findDeployment(Long collectionTaskId) {
        if (collectionTaskId == null) {
            return null;
        }
        return deployMapper.selectOne(new LambdaQueryWrapper<StreamTaskDeployEntity>()
                .eq(StreamTaskDeployEntity::getCollectionTaskId, collectionTaskId)
                .last("limit 1"));
    }

    private Map<String, Object> details(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (values == null) {
            return result;
        }
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private String jsonTypeHandler() {
        return "typeHandler=" + JacksonTypeHandler.class.getCanonicalName();
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null
                ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(source);
    }

    private Long longValue(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String text(String value, String fallback, int maxLength) {
        String sanitized = StudioSensitiveLogSanitizer.sanitize(
                value == null || value.trim().isEmpty() ? fallback : value.trim());
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }

    private boolean equalsLong(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value.longValue();
    }
}
