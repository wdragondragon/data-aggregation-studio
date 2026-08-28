package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskExecutionMode;
import com.jdragon.studio.dto.enums.StreamingDesiredState;
import com.jdragon.studio.dto.enums.StreamingObservedState;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskListView;
import com.jdragon.studio.dto.model.CollectionTaskStreamingRuntimeView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.RunLogChunkView;
import com.jdragon.studio.dto.model.StreamingMetricBucketView;
import com.jdragon.studio.dto.model.StreamingTaskAttemptView;
import com.jdragon.studio.dto.model.StreamingTaskDeploymentView;
import com.jdragon.studio.dto.model.StreamingTaskEventView;
import com.jdragon.studio.dto.model.StreamingTaskRunView;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.RunLogChunkEntity;
import com.jdragon.studio.infra.entity.StreamMetricBucketEntity;
import com.jdragon.studio.infra.entity.StreamTaskAttemptEntity;
import com.jdragon.studio.infra.entity.StreamTaskDeployEntity;
import com.jdragon.studio.infra.entity.StreamTaskEventEntity;
import com.jdragon.studio.infra.entity.StreamTaskRunEntity;
import com.jdragon.studio.infra.mapper.RunLogChunkMapper;
import com.jdragon.studio.infra.mapper.StreamMetricBucketMapper;
import com.jdragon.studio.infra.mapper.StreamTaskAttemptMapper;
import com.jdragon.studio.infra.mapper.StreamTaskDeployMapper;
import com.jdragon.studio.infra.mapper.StreamTaskEventMapper;
import com.jdragon.studio.infra.mapper.StreamTaskRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StreamingTaskRuntimeService {

    private static final int CAS_ATTEMPTS = 5;

    private final StreamTaskDeployMapper deployMapper;
    private final StreamTaskRunMapper runMapper;
    private final StreamTaskAttemptMapper attemptMapper;
    private final StreamMetricBucketMapper metricMapper;
    private final StreamTaskEventMapper eventMapper;
    private final RunLogChunkMapper logChunkMapper;
    private final StudioSecurityService securityService;

    public StreamingTaskRuntimeService(StreamTaskDeployMapper deployMapper,
                                       StreamTaskRunMapper runMapper,
                                       StreamTaskAttemptMapper attemptMapper,
                                       StreamMetricBucketMapper metricMapper,
                                       StreamTaskEventMapper eventMapper,
                                       RunLogChunkMapper logChunkMapper,
                                       StudioSecurityService securityService) {
        this.deployMapper = deployMapper;
        this.runMapper = runMapper;
        this.attemptMapper = attemptMapper;
        this.metricMapper = metricMapper;
        this.eventMapper = eventMapper;
        this.logChunkMapper = logChunkMapper;
        this.securityService = securityService;
    }

    @Transactional
    public void ensureDeployment(CollectionTaskDefinitionEntity task) {
        if (!isStreaming(task)) {
            return;
        }
        StreamTaskDeployEntity deployment = findDeployment(task.getId());
        if (deployment == null) {
            deployment = new StreamTaskDeployEntity();
            deployment.setId(IdWorker.getId());
            deployment.setTenantId(task.getTenantId());
            deployment.setProjectId(task.getProjectId());
            deployment.setCollectionTaskId(task.getId());
            deployment.setRuntimeClusterId(task.getRuntimeClusterId());
            deployment.setGeneration(0L);
            deployment.setDesiredState(StreamingDesiredState.STOPPED.name());
            deployment.setObservedState(StreamingObservedState.STOPPED.name());
            deployment.setConsecutiveFailureCount(0);
            deployment.setVersion(0);
            deployMapper.insert(deployment);
            appendEvent(task, deployment, null, "DEPLOYMENT_CREATED", null,
                    StreamingObservedState.STOPPED.name(), "Streaming deployment created");
            return;
        }
        if (!StreamingDesiredState.RUNNING.name().equals(deployment.getDesiredState())
                && !equalsLong(deployment.getRuntimeClusterId(), task.getRuntimeClusterId())) {
            deployment.setRuntimeClusterId(task.getRuntimeClusterId());
            requireCasUpdate(deployment, "Streaming deployment changed while saving; retry the request");
        }
    }

    public boolean isRunning(Long collectionTaskId) {
        StreamTaskDeployEntity deployment = findDeployment(collectionTaskId);
        return deployment != null && StreamingDesiredState.RUNNING.name().equals(deployment.getDesiredState());
    }

    public void assertEditable(CollectionTaskDefinitionEntity task) {
        if (task != null && isStreaming(task) && isRunning(task.getId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Running streaming collection task must be offline before it can be edited");
        }
    }

    @Transactional
    public CollectionTaskStreamingRuntimeView online(CollectionTaskDefinitionEntity task) {
        requireStreaming(task);
        for (int attempt = 0; attempt < CAS_ATTEMPTS; attempt++) {
            StreamTaskDeployEntity deployment = requireDeployment(task);
            if (StreamingDesiredState.RUNNING.name().equals(deployment.getDesiredState())) {
                return runtime(task);
            }
            String fromState = deployment.getObservedState();
            long generation = safeLong(deployment.getGeneration()) + 1L;
            long runId = IdWorker.getId();
            deployment.setGeneration(generation);
            deployment.setDesiredState(StreamingDesiredState.RUNNING.name());
            deployment.setObservedState(StreamingObservedState.STARTING.name());
            deployment.setRuntimeClusterId(task.getRuntimeClusterId());
            deployment.setCurrentRunId(runId);
            deployment.setCurrentAttemptId(null);
            deployment.setConsecutiveFailureCount(0);
            deployment.setNextRetryAt(null);
            deployment.setLastErrorCode(null);
            deployment.setLastErrorSummary(null);
            if (casUpdate(deployment) == 0) {
                continue;
            }
            StreamTaskRunEntity run = new StreamTaskRunEntity();
            run.setId(runId);
            run.setTenantId(task.getTenantId());
            run.setProjectId(task.getProjectId());
            run.setCollectionTaskId(task.getId());
            run.setGeneration(generation);
            run.setRuntimeClusterId(task.getRuntimeClusterId());
            run.setStatus("RUNNING");
            run.setDeliverySemantics("AT_LEAST_ONCE");
            run.setGroupId(groupId(task));
            run.setStartedBy(securityService.currentUserId());
            run.setStartedAt(LocalDateTime.now());
            runMapper.insert(run);
            appendEvent(task, deployment, runId, "ONLINE", fromState,
                    StreamingObservedState.STARTING.name(), "Streaming collection task requested online");
            return runtime(task);
        }
        throw stateConflict();
    }

    @Transactional
    public CollectionTaskStreamingRuntimeView offline(CollectionTaskDefinitionEntity task) {
        requireStreaming(task);
        for (int attempt = 0; attempt < CAS_ATTEMPTS; attempt++) {
            StreamTaskDeployEntity deployment = requireDeployment(task);
            if (StreamingDesiredState.STOPPED.name().equals(deployment.getDesiredState())) {
                return runtime(task);
            }
            String fromState = deployment.getObservedState();
            boolean hasAttempt = deployment.getCurrentAttemptId() != null;
            long generation = safeLong(deployment.getGeneration()) + 1L;
            deployment.setGeneration(generation);
            deployment.setDesiredState(StreamingDesiredState.STOPPED.name());
            deployment.setObservedState(hasAttempt
                    ? StreamingObservedState.STOPPING.name() : StreamingObservedState.STOPPED.name());
            deployment.setNextRetryAt(null);
            if (casUpdate(deployment) == 0) {
                continue;
            }
            StreamTaskRunEntity run = findRun(deployment.getCurrentRunId());
            if (run != null) {
                LocalDateTime now = LocalDateTime.now();
                run.setStatus(hasAttempt ? "STOPPING" : "STOPPED");
                run.setStopRequestedAt(now);
                run.setStoppedBy(securityService.currentUserId());
                run.setStopReason("USER_OFFLINE");
                if (!hasAttempt) {
                    run.setStoppedAt(now);
                }
                runMapper.updateById(run);
            }
            appendEvent(task, deployment, deployment.getCurrentRunId(), "OFFLINE", fromState,
                    deployment.getObservedState(), "Streaming collection task requested offline");
            return runtime(task);
        }
        throw stateConflict();
    }

    @Transactional
    public CollectionTaskStreamingRuntimeView recover(CollectionTaskDefinitionEntity task) {
        requireStreaming(task);
        for (int attempt = 0; attempt < CAS_ATTEMPTS; attempt++) {
            StreamTaskDeployEntity deployment = requireDeployment(task);
            if (!StreamingDesiredState.RUNNING.name().equals(deployment.getDesiredState())
                    || !StreamingObservedState.FAILED.name().equals(deployment.getObservedState())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Streaming collection task can recover only when desiredState=RUNNING and observedState=FAILED");
            }
            long generation = safeLong(deployment.getGeneration()) + 1L;
            deployment.setGeneration(generation);
            deployment.setObservedState(StreamingObservedState.RECOVERING.name());
            deployment.setCurrentAttemptId(null);
            deployment.setConsecutiveFailureCount(0);
            deployment.setNextRetryAt(LocalDateTime.now());
            deployment.setLastErrorCode(null);
            deployment.setLastErrorSummary(null);
            if (casUpdate(deployment) == 0) {
                continue;
            }
            appendEvent(task, deployment, deployment.getCurrentRunId(), "RECOVER", "FAILED",
                    StreamingObservedState.RECOVERING.name(), "Streaming collection task recovery requested");
            return runtime(task);
        }
        throw stateConflict();
    }

    public CollectionTaskStreamingRuntimeView runtime(CollectionTaskDefinitionEntity task) {
        requireStreaming(task);
        StreamTaskDeployEntity deployment = requireDeployment(task);
        CollectionTaskStreamingRuntimeView view = new CollectionTaskStreamingRuntimeView();
        view.setCollectionTaskId(task.getId());
        view.setTaskName(task.getName());
        view.setDeployment(toView(deployment));
        view.setCurrentRun(toView(findRun(deployment.getCurrentRunId())));
        view.setCurrentAttempt(toView(findAttempt(deployment.getCurrentAttemptId())));
        List<StreamTaskAttemptEntity> attempts = attemptMapper.selectList(
                new LambdaQueryWrapper<StreamTaskAttemptEntity>()
                        .eq(StreamTaskAttemptEntity::getCollectionTaskId, task.getId())
                        .orderByDesc(StreamTaskAttemptEntity::getCreatedAt)
                        .orderByDesc(StreamTaskAttemptEntity::getId)
                        .last("limit 20"));
        List<StreamingTaskAttemptView> attemptViews = new ArrayList<StreamingTaskAttemptView>();
        for (StreamTaskAttemptEntity entity : attempts) {
            attemptViews.add(toView(entity));
        }
        view.setRecentAttempts(attemptViews);
        return view;
    }

    public List<StreamingMetricBucketView> metrics(CollectionTaskDefinitionEntity task,
                                                   LocalDateTime startTime,
                                                   LocalDateTime endTime) {
        requireStreaming(task);
        return aggregateMetricBuckets(task, startTime, endTime,
                metricBucketStarts(task, startTime, endTime));
    }

    /**
     * Returns task-level minute metrics after cross-attempt aggregation. Pagination
     * is deliberately applied to the distinct minute keys rather than raw attempt
     * rows, otherwise a restart can split one minute across two pages and produce
     * duplicate or partial counters.
     */
    public PageView<StreamingMetricBucketView> metricsPage(CollectionTaskDefinitionEntity task,
                                                           LocalDateTime startTime,
                                                           LocalDateTime endTime,
                                                           Integer pageNo,
                                                           Integer pageSize) {
        return metricsPage(task, startTime, endTime, pageNo, pageSize, false);
    }

    public PageView<StreamingMetricBucketView> metricsPage(CollectionTaskDefinitionEntity task,
                                                           LocalDateTime startTime,
                                                           LocalDateTime endTime,
                                                           Integer pageNo,
                                                           Integer pageSize,
                                                           boolean onlyWithRecords) {
        requireStreaming(task);
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        List<LocalDateTime> allBucketStarts = metricBucketStarts(task, startTime, endTime, onlyWithRecords);
        long total = allBucketStarts.size();
        long offset = ((long) safePageNo - 1L) * safePageSize;
        if (offset >= total) {
            return PageView.of(safePageNo, safePageSize, total,
                    new ArrayList<StreamingMetricBucketView>());
        }
        int fromIndex = (int) offset;
        int toIndex = Math.min(allBucketStarts.size(), fromIndex + safePageSize);
        List<LocalDateTime> pageBucketStarts = allBucketStarts.subList(fromIndex, toIndex);
        List<StreamingMetricBucketView> pageItems = aggregateMetricBuckets(
                task, startTime, endTime, pageBucketStarts);
        return PageView.of(safePageNo, safePageSize, total, pageItems);
    }

    private List<LocalDateTime> metricBucketStarts(CollectionTaskDefinitionEntity task,
                                                   LocalDateTime startTime,
                                                   LocalDateTime endTime) {
        return metricBucketStarts(task, startTime, endTime, false);
    }

    private List<LocalDateTime> metricBucketStarts(CollectionTaskDefinitionEntity task,
                                                   LocalDateTime startTime,
                                                   LocalDateTime endTime,
                                                   boolean onlyWithRecords) {
        LambdaQueryWrapper<StreamMetricBucketEntity> query = metricQuery(task, startTime, endTime)
                .gt(onlyWithRecords, StreamMetricBucketEntity::getRecordsRead, 0L)
                .select(StreamMetricBucketEntity::getBucketStart)
                .groupBy(StreamMetricBucketEntity::getBucketStart)
                .orderByDesc(StreamMetricBucketEntity::getBucketStart);
        List<Object> values = metricMapper.selectObjs(query);
        List<LocalDateTime> starts = new ArrayList<LocalDateTime>(values.size());
        for (Object value : values) {
            LocalDateTime bucketStart = toLocalDateTime(value);
            if (bucketStart != null) {
                starts.add(bucketStart);
            }
        }
        return starts;
    }

    private List<StreamingMetricBucketView> aggregateMetricBuckets(CollectionTaskDefinitionEntity task,
                                                                    LocalDateTime startTime,
                                                                    LocalDateTime endTime,
                                                                    List<LocalDateTime> bucketStarts) {
        if (bucketStarts == null || bucketStarts.isEmpty()) {
            return new ArrayList<StreamingMetricBucketView>();
        }
        LambdaQueryWrapper<StreamMetricBucketEntity> query = metricQuery(task, startTime, endTime)
                .in(StreamMetricBucketEntity::getBucketStart, bucketStarts)
                .orderByDesc(StreamMetricBucketEntity::getBucketStart)
                .orderByDesc(StreamMetricBucketEntity::getAttemptId)
                .orderByDesc(StreamMetricBucketEntity::getId);
        Map<LocalDateTime, StreamingMetricBucketView> buckets = new LinkedHashMap<LocalDateTime, StreamingMetricBucketView>();
        for (StreamMetricBucketEntity entity : metricMapper.selectList(query)) {
            StreamingMetricBucketView current = buckets.get(entity.getBucketStart());
            if (current == null) {
                current = toView(entity);
                buckets.put(entity.getBucketStart(), current);
                continue;
            }
            // Counter fields are deltas and can be safely summed across attempts;
            // lag and timestamps describe the latest sample for the minute.
            current.setRecordsRead(sum(current.getRecordsRead(), entity.getRecordsRead()));
            current.setWriteSucceedRecords(sum(current.getWriteSucceedRecords(), entity.getWriteSucceedRecords()));
            current.setWriteFailedRecords(sum(current.getWriteFailedRecords(), entity.getWriteFailedRecords()));
            current.setDirtyRecords(sum(current.getDirtyRecords(), entity.getDirtyRecords()));
            current.setBytesRead(sum(current.getBytesRead(), entity.getBytesRead()));
            current.setBatchCount(sum(current.getBatchCount(), entity.getBatchCount()));
            current.setRetryCount(sum(current.getRetryCount(), entity.getRetryCount()));
            current.setRebalanceCount(sum(current.getRebalanceCount(), entity.getRebalanceCount()));
            if (isNewerAttempt(entity, current)) {
                current.setAttemptId(entity.getAttemptId());
                current.setRunId(entity.getRunId());
                current.setCurrentLag(safeLong(entity.getCurrentLag()));
            }
            current.setMaxLag(Math.max(safeLong(current.getMaxLag()), safeLong(entity.getMaxLag())));
            if (entity.getLastMessageAt() != null
                    && (current.getLastMessageAt() == null || entity.getLastMessageAt().isAfter(current.getLastMessageAt()))) {
                current.setLastMessageAt(entity.getLastMessageAt());
            }
            if (entity.getLastCheckpointAt() != null
                    && (current.getLastCheckpointAt() == null || entity.getLastCheckpointAt().isAfter(current.getLastCheckpointAt()))) {
                current.setLastCheckpointAt(entity.getLastCheckpointAt());
            }
        }
        // The IN query does not guarantee order on every supported database.
        // Rebuild the result using the already sorted minute-key list.
        List<StreamingMetricBucketView> result = new ArrayList<StreamingMetricBucketView>(buckets.size());
        for (LocalDateTime bucketStart : bucketStarts) {
            StreamingMetricBucketView bucket = buckets.get(bucketStart);
            if (bucket != null) {
                result.add(bucket);
            }
        }
        return result;
    }

    private LambdaQueryWrapper<StreamMetricBucketEntity> metricQuery(CollectionTaskDefinitionEntity task,
                                                                      LocalDateTime startTime,
                                                                      LocalDateTime endTime) {
        return new LambdaQueryWrapper<StreamMetricBucketEntity>()
                .eq(StreamMetricBucketEntity::getCollectionTaskId, task.getId())
                .ge(startTime != null, StreamMetricBucketEntity::getBucketStart, startTime)
                .le(endTime != null, StreamMetricBucketEntity::getBucketStart, endTime);
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        if (value instanceof java.util.Date) {
            return LocalDateTime.ofInstant(((java.util.Date) value).toInstant(), ZoneId.systemDefault());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Long sum(Long left, Long right) {
        return safeLong(left) + safeLong(right);
    }

    private boolean isNewerAttempt(StreamMetricBucketEntity entity, StreamingMetricBucketView current) {
        Long attemptId = entity.getAttemptId();
        Long currentAttemptId = current.getAttemptId();
        return attemptId != null && (currentAttemptId == null || attemptId > currentAttemptId);
    }

    public PageView<StreamingTaskEventView> events(CollectionTaskDefinitionEntity task,
                                                   Integer pageNo,
                                                   Integer pageSize) {
        requireStreaming(task);
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        IPage<StreamTaskEventEntity> page = eventMapper.selectPage(new Page<StreamTaskEventEntity>(safePageNo, safePageSize),
                new LambdaQueryWrapper<StreamTaskEventEntity>()
                        .eq(StreamTaskEventEntity::getCollectionTaskId, task.getId())
                        .orderByDesc(StreamTaskEventEntity::getOccurredAt)
                        .orderByDesc(StreamTaskEventEntity::getId));
        List<StreamingTaskEventView> views = new ArrayList<StreamingTaskEventView>();
        for (StreamTaskEventEntity entity : page.getRecords()) {
            views.add(toView(entity));
        }
        return PageView.of(safePageNo, safePageSize, page.getTotal(), views);
    }

    public PageView<RunLogChunkView> logChunks(CollectionTaskDefinitionEntity task,
                                               Integer pageNo,
                                               Integer pageSize) {
        requireStreaming(task);
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        IPage<RunLogChunkEntity> page = logChunkMapper.selectPage(new Page<RunLogChunkEntity>(safePageNo, safePageSize),
                new LambdaQueryWrapper<RunLogChunkEntity>()
                        .eq(RunLogChunkEntity::getCollectionTaskId, task.getId())
                        .orderByDesc(RunLogChunkEntity::getChunkStartedAt)
                        .orderByDesc(RunLogChunkEntity::getId));
        List<RunLogChunkView> views = new ArrayList<RunLogChunkView>();
        for (RunLogChunkEntity entity : page.getRecords()) {
            views.add(toView(entity));
        }
        return PageView.of(safePageNo, safePageSize, page.getTotal(), views);
    }

    public void hydrate(CollectionTaskDefinitionView view, Long collectionTaskId) {
        if (view == null || view.getExecutionMode() != CollectionTaskExecutionMode.STREAMING) {
            return;
        }
        applyRuntime(view, findDeployment(collectionTaskId));
    }

    public void hydrate(CollectionTaskListView view, Long collectionTaskId) {
        if (view == null || view.getExecutionMode() != CollectionTaskExecutionMode.STREAMING) {
            return;
        }
        StreamTaskDeployEntity deployment = findDeployment(collectionTaskId);
        if (deployment == null) {
            return;
        }
        view.setDesiredState(enumValue(StreamingDesiredState.class, deployment.getDesiredState()));
        view.setObservedState(enumValue(StreamingObservedState.class, deployment.getObservedState()));
        view.setStreamingGeneration(deployment.getGeneration());
        view.setCurrentStreamRunId(deployment.getCurrentRunId());
        view.setCurrentStreamAttemptId(deployment.getCurrentAttemptId());
    }

    private void applyRuntime(CollectionTaskDefinitionView view, StreamTaskDeployEntity deployment) {
        if (deployment == null) {
            return;
        }
        view.setDesiredState(enumValue(StreamingDesiredState.class, deployment.getDesiredState()));
        view.setObservedState(enumValue(StreamingObservedState.class, deployment.getObservedState()));
        view.setStreamingGeneration(deployment.getGeneration());
        view.setCurrentStreamRunId(deployment.getCurrentRunId());
        view.setCurrentStreamAttemptId(deployment.getCurrentAttemptId());
    }

    private StreamTaskDeployEntity requireDeployment(CollectionTaskDefinitionEntity task) {
        StreamTaskDeployEntity deployment = findDeployment(task.getId());
        if (deployment == null) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Streaming deployment is missing for collection task " + task.getId());
        }
        return deployment;
    }

    private StreamTaskDeployEntity findDeployment(Long collectionTaskId) {
        if (collectionTaskId == null) {
            return null;
        }
        return deployMapper.selectOne(new LambdaQueryWrapper<StreamTaskDeployEntity>()
                .eq(StreamTaskDeployEntity::getCollectionTaskId, collectionTaskId)
                .last("limit 1"));
    }

    private StreamTaskRunEntity findRun(Long runId) {
        return runId == null ? null : runMapper.selectById(runId);
    }

    private StreamTaskAttemptEntity findAttempt(Long attemptId) {
        return attemptId == null ? null : attemptMapper.selectById(attemptId);
    }

    private void appendEvent(CollectionTaskDefinitionEntity task,
                             StreamTaskDeployEntity deployment,
                             Long runId,
                             String eventType,
                             String fromState,
                             String toState,
                             String message) {
        StreamTaskEventEntity event = new StreamTaskEventEntity();
        event.setId(IdWorker.getId());
        event.setTenantId(task.getTenantId());
        event.setProjectId(task.getProjectId());
        event.setCollectionTaskId(task.getId());
        event.setDeploymentId(deployment.getId());
        event.setRunId(runId);
        event.setAttemptId(deployment.getCurrentAttemptId());
        event.setGeneration(deployment.getGeneration());
        event.setEventType(eventType);
        event.setFromState(fromState);
        event.setToState(toState);
        event.setMessage(message);
        event.setActorId(securityService.currentUserId());
        event.setOccurredAt(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private void requireCasUpdate(StreamTaskDeployEntity deployment, String message) {
        if (casUpdate(deployment) == 0) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR, message);
        }
    }

    private int casUpdate(StreamTaskDeployEntity deployment) {
        int expectedVersion = deployment.getVersion() == null ? 0 : deployment.getVersion().intValue();
        int nextVersion = expectedVersion + 1;
        LocalDateTime updatedAt = LocalDateTime.now();
        int updated = deployMapper.update(null, new LambdaUpdateWrapper<StreamTaskDeployEntity>()
                .set(StreamTaskDeployEntity::getRuntimeClusterId, deployment.getRuntimeClusterId())
                .set(StreamTaskDeployEntity::getGeneration, deployment.getGeneration())
                .set(StreamTaskDeployEntity::getDesiredState, deployment.getDesiredState())
                .set(StreamTaskDeployEntity::getObservedState, deployment.getObservedState())
                .set(StreamTaskDeployEntity::getCurrentRunId, deployment.getCurrentRunId())
                .set(StreamTaskDeployEntity::getCurrentAttemptId, deployment.getCurrentAttemptId())
                .set(StreamTaskDeployEntity::getConsecutiveFailureCount, deployment.getConsecutiveFailureCount())
                .set(StreamTaskDeployEntity::getNextRetryAt, deployment.getNextRetryAt())
                .set(StreamTaskDeployEntity::getLastCheckpointJson, deployment.getLastCheckpointJson(),
                        jsonTypeHandler())
                .set(StreamTaskDeployEntity::getLastCheckpointAt, deployment.getLastCheckpointAt())
                .set(StreamTaskDeployEntity::getLastErrorCode, deployment.getLastErrorCode())
                .set(StreamTaskDeployEntity::getLastErrorSummary, deployment.getLastErrorSummary())
                .set(StreamTaskDeployEntity::getVersion, nextVersion)
                .set(StreamTaskDeployEntity::getUpdatedAt, updatedAt)
                .eq(StreamTaskDeployEntity::getId, deployment.getId())
                .eq(StreamTaskDeployEntity::getVersion, expectedVersion));
        if (updated > 0) {
            deployment.setVersion(nextVersion);
            deployment.setUpdatedAt(updatedAt);
        }
        return updated;
    }

    private String jsonTypeHandler() {
        return "typeHandler=" + JacksonTypeHandler.class.getCanonicalName();
    }

    private void requireStreaming(CollectionTaskDefinitionEntity task) {
        if (!isStreaming(task)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Collection task is not configured for STREAMING execution");
        }
    }

    private boolean isStreaming(CollectionTaskDefinitionEntity task) {
        return task != null && CollectionTaskExecutionMode.STREAMING.name().equalsIgnoreCase(task.getExecutionMode());
    }

    private String groupId(CollectionTaskDefinitionEntity task) {
        Object configured = task.getStreamingOptionsJson() == null
                ? null : task.getStreamingOptionsJson().get("groupId");
        if (configured != null && !String.valueOf(configured).trim().isEmpty()) {
            return String.valueOf(configured).trim();
        }
        return "studio." + task.getTenantId() + "." + task.getId();
    }

    private StudioException stateConflict() {
        return new StudioException(StudioErrorCode.BUSINESS_ERROR,
                "Streaming collection task state changed concurrently; retry the request");
    }

    private StreamingTaskDeploymentView toView(StreamTaskDeployEntity entity) {
        if (entity == null) {
            return null;
        }
        StreamingTaskDeploymentView view = new StreamingTaskDeploymentView();
        view.setId(entity.getId());
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setGeneration(entity.getGeneration());
        view.setDesiredState(enumValue(StreamingDesiredState.class, entity.getDesiredState()));
        view.setObservedState(enumValue(StreamingObservedState.class, entity.getObservedState()));
        view.setCurrentRunId(entity.getCurrentRunId());
        view.setCurrentAttemptId(entity.getCurrentAttemptId());
        view.setConsecutiveFailureCount(entity.getConsecutiveFailureCount());
        view.setNextRetryAt(entity.getNextRetryAt());
        view.setLastCheckpoint(copyMap(entity.getLastCheckpointJson()));
        view.setLastCheckpointAt(entity.getLastCheckpointAt());
        view.setLastErrorCode(entity.getLastErrorCode());
        view.setLastErrorSummary(entity.getLastErrorSummary());
        view.setUpdatedAt(entity.getUpdatedAt());
        return view;
    }

    private StreamingTaskRunView toView(StreamTaskRunEntity entity) {
        if (entity == null) {
            return null;
        }
        StreamingTaskRunView view = new StreamingTaskRunView();
        view.setId(entity.getId());
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setGeneration(entity.getGeneration());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setStatus(entity.getStatus());
        view.setDeliverySemantics(entity.getDeliverySemantics());
        view.setGroupId(entity.getGroupId());
        view.setStartedBy(entity.getStartedBy());
        view.setStartedAt(entity.getStartedAt());
        view.setStopRequestedAt(entity.getStopRequestedAt());
        view.setStoppedBy(entity.getStoppedBy());
        view.setStoppedAt(entity.getStoppedAt());
        view.setStopReason(entity.getStopReason());
        view.setFinalCheckpoint(copyMap(entity.getFinalCheckpointJson()));
        return view;
    }

    private StreamingTaskAttemptView toView(StreamTaskAttemptEntity entity) {
        if (entity == null) {
            return null;
        }
        StreamingTaskAttemptView view = new StreamingTaskAttemptView();
        view.setId(entity.getId());
        view.setRunId(entity.getRunId());
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setGeneration(entity.getGeneration());
        view.setAttemptNo(entity.getAttemptNo());
        view.setDispatchTaskId(entity.getDispatchTaskId());
        view.setRunRecordId(entity.getRunRecordId());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setWorkerInstanceId(entity.getWorkerInstanceId());
        view.setWorkerBootId(entity.getWorkerBootId());
        view.setStatus(entity.getStatus());
        view.setStartedAt(entity.getStartedAt());
        view.setEndedAt(entity.getEndedAt());
        view.setHeartbeatAt(entity.getHeartbeatAt());
        view.setRetryAfter(entity.getRetryAfter());
        view.setCheckpoint(copyMap(entity.getCheckpointJson()));
        view.setErrorCode(entity.getErrorCode());
        view.setErrorSummary(entity.getErrorSummary());
        view.setCommittedBatchCount(entity.getCommittedBatchCount());
        return view;
    }

    private StreamingMetricBucketView toView(StreamMetricBucketEntity entity) {
        StreamingMetricBucketView view = new StreamingMetricBucketView();
        view.setId(entity.getId());
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setRunId(entity.getRunId());
        view.setAttemptId(entity.getAttemptId());
        view.setBucketStart(entity.getBucketStart());
        view.setRecordsRead(entity.getRecordsRead());
        view.setWriteSucceedRecords(entity.getWriteSucceedRecords());
        view.setWriteFailedRecords(entity.getWriteFailedRecords());
        view.setDirtyRecords(entity.getDirtyRecords());
        view.setBytesRead(entity.getBytesRead());
        view.setBatchCount(entity.getBatchCount());
        view.setRetryCount(entity.getRetryCount());
        view.setCurrentLag(entity.getCurrentLag());
        view.setMaxLag(entity.getMaxLag());
        view.setLastMessageAt(entity.getLastMessageAt());
        view.setLastCheckpointAt(entity.getLastCheckpointAt());
        view.setRebalanceCount(entity.getRebalanceCount());
        return view;
    }

    private StreamingTaskEventView toView(StreamTaskEventEntity entity) {
        StreamingTaskEventView view = new StreamingTaskEventView();
        view.setId(entity.getId());
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setDeploymentId(entity.getDeploymentId());
        view.setRunId(entity.getRunId());
        view.setAttemptId(entity.getAttemptId());
        view.setGeneration(entity.getGeneration());
        view.setEventType(entity.getEventType());
        view.setFromState(entity.getFromState());
        view.setToState(entity.getToState());
        view.setMessage(entity.getMessage());
        view.setDetails(copyMap(entity.getDetailsJson()));
        view.setActorId(entity.getActorId());
        view.setOccurredAt(entity.getOccurredAt());
        return view;
    }

    private RunLogChunkView toView(RunLogChunkEntity entity) {
        RunLogChunkView view = new RunLogChunkView();
        view.setId(entity.getId());
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setRunRecordId(entity.getRunRecordId());
        view.setStreamAttemptId(entity.getStreamAttemptId());
        view.setSequenceNo(entity.getSequenceNo());
        view.setStatus(entity.getStatus());
        view.setLocalPath(entity.getLocalPath());
        view.setStorageType(entity.getStorageType());
        view.setObjectBucket(entity.getObjectBucket());
        view.setObjectKey(entity.getObjectKey());
        view.setSizeBytes(entity.getSizeBytes());
        view.setChecksumSha256(entity.getChecksumSha256());
        view.setChunkStartedAt(entity.getChunkStartedAt());
        view.setChunkEndedAt(entity.getChunkEndedAt());
        view.setUploadedAt(entity.getUploadedAt());
        return view;
    }

    private <E extends Enum<E>> E enumValue(Class<E> enumType, String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return Enum.valueOf(enumType, value.trim().toUpperCase());
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(source);
    }

    private boolean equalsLong(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private int normalizePageNo(Integer value) {
        return value == null ? 1 : Math.max(1, value.intValue());
    }

    private int normalizePageSize(Integer value) {
        return value == null ? 20 : Math.max(1, Math.min(200, value.intValue()));
    }
}
