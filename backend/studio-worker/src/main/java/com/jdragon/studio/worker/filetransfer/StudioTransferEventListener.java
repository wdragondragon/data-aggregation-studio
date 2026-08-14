package com.jdragon.studio.worker.filetransfer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.aggregation.transfer.TransferEventListener;
import com.jdragon.aggregation.transfer.model.TransferEvent;
import com.jdragon.aggregation.transfer.model.TransferEventType;
import com.jdragon.aggregation.transfer.model.TransferItemStatus;
import com.jdragon.studio.infra.entity.FileTransferMetricSampleEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.service.FileTransferStateMutationService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

final class StudioTransferEventListener implements TransferEventListener {

    private static final long ITEM_UPDATE_INTERVAL_MILLIS = 1_000L;
    private static final long SAMPLE_INTERVAL_MILLIS = 5_000L;

    private final FileTransferRunEntity run;
    private final FileTransferRunMapper runMapper;
    private final FileTransferRunItemMapper itemMapper;
    private final FileTransferMetricSampleMapper metricMapper;
    private final FileTransferStateMutationService mutationService;
    private final Map<String, Long> bytesByItem = new HashMap<String, Long>();
    /** Observed stream bytes are display-only and never advance a checkpoint. */
    private final Map<String, Long> observedBytesByItem = new HashMap<String, Long>();
    /** Activity bytes include checkpoint checksum rebuild reads and drive speed only. */
    private final Map<String, Long> activityBytesByItem = new HashMap<String, Long>();
    private final Map<String, Long> resumedBytesByItem = new HashMap<String, Long>();
    private final Map<String, Long> lastItemUpdateAt = new HashMap<String, Long>();
    private final Map<String, ProgressMark> progressMarks = new HashMap<String, ProgressMark>();
    private long lastSampleAt;
    private long lastSampleBytes;
    private long lastSampleObservedBytes;
    private long retryCount;

    StudioTransferEventListener(FileTransferRunEntity run,
                                FileTransferRunMapper runMapper,
                                FileTransferRunItemMapper itemMapper,
                                FileTransferMetricSampleMapper metricMapper) {
        this(run, runMapper, itemMapper, metricMapper, null);
    }

    StudioTransferEventListener(FileTransferRunEntity run,
                                FileTransferRunMapper runMapper,
                                FileTransferRunItemMapper itemMapper,
                                FileTransferMetricSampleMapper metricMapper,
                                FileTransferStateMutationService mutationService) {
        this.run = run;
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
        this.metricMapper = metricMapper;
        this.mutationService = mutationService;
        this.lastSampleAt = System.currentTimeMillis();
        long initialTransferredBytes = 0L;
        for (FileTransferRunItemEntity item : itemMapper.selectList(new LambdaQueryWrapper<FileTransferRunItemEntity>()
                        .eq(FileTransferRunItemEntity::getRunId, run.getId()))
                ) {
            long transferredBytes = value(item.getTransferredBytes());
            bytesByItem.put(item.getCoreItemId(), transferredBytes);
            observedBytesByItem.put(item.getCoreItemId(), transferredBytes);
            activityBytesByItem.put(item.getCoreItemId(), transferredBytes);
            resumedBytesByItem.put(item.getCoreItemId(), value(item.getResumedBytes()));
            initialTransferredBytes += transferredBytes;
        }
        this.lastSampleBytes = initialTransferredBytes == 0L
                ? value(run.getTransferredBytes()) : initialTransferredBytes;
        this.lastSampleObservedBytes = this.lastSampleBytes;
    }

    @Override
    public synchronized void onEvent(TransferEvent event) {
        if (event == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (event.itemId() != null) {
            if (restartedFromZero(event)) {
                bytesByItem.put(event.itemId(), 0L);
                observedBytesByItem.put(event.itemId(), 0L);
                activityBytesByItem.put(event.itemId(), 0L);
                resumedBytesByItem.put(event.itemId(), 0L);
                progressMarks.remove(event.itemId());
            }
            bytesByItem.put(event.itemId(), event.transferredBytes());
            long observedBytes = live(event) ? observedBytes(event) : event.transferredBytes();
            observedBytesByItem.put(event.itemId(), Math.max(
                    observedBytesByItem.getOrDefault(event.itemId(), 0L), observedBytes));
            long activityBytes = activityBytes(event);
            activityBytesByItem.put(event.itemId(), Math.max(
                    activityBytesByItem.getOrDefault(event.itemId(), 0L), activityBytes));
        }
        if (event.type() == TransferEventType.ITEM_RETRYING) {
            retryCount++;
        }
        if (event.type() == TransferEventType.ITEM_STATUS_CHANGED) {
            persistStatus(event, now);
        } else if (event.type() == TransferEventType.ITEM_PROGRESS
                && due(lastItemUpdateAt.get(event.itemId()), now, ITEM_UPDATE_INTERVAL_MILLIS)) {
            if (live(event)) {
                persistLiveProgress(event, now);
            } else {
                persistProgress(event, now);
            }
            lastItemUpdateAt.put(event.itemId(), now);
        }
        if (now - lastSampleAt >= SAMPLE_INTERVAL_MILLIS) {
            persistSample(now);
        }
    }

    synchronized void flushSample() {
        persistSample(System.currentTimeMillis());
    }

    private void persistStatus(TransferEvent event, long now) {
        TransferItemStatus status = event.itemStatus();
        FileTransferRunEntity latestRun = runMapper.selectById(run.getId());
        // A transfer event already in flight may arrive after the user pause
        // request. Never let that stale event resurrect an item to TRANSFERRING.
        if (status == TransferItemStatus.TRANSFERRING && latestRun != null
                && "PAUSED".equalsIgnoreCase(latestRun.getStatus())) {
            return;
        }
        LambdaUpdateWrapper<FileTransferRunItemEntity> update = new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getStatus, status == null ? null : status.name())
                .set(FileTransferRunItemEntity::getTransferredBytes, event.transferredBytes())
                .set(FileTransferRunItemEntity::getAttempts, event.attempt())
                .set(FileTransferRunItemEntity::getErrorCode, event.errorCode())
                .set(FileTransferRunItemEntity::getErrorMessage, event.errorCode() == null ? null : event.message())
                .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunItemEntity::getRunId, run.getId())
                .eq(FileTransferRunItemEntity::getCoreItemId, event.itemId());
        Long resumedBytes = resumedBytes(event);
        if (restartedFromZero(event)) {
            resumedBytes = 0L;
        }
        if (resumedBytes != null) {
            resumedBytesByItem.put(event.itemId(), resumedBytes);
            update.set(FileTransferRunItemEntity::getResumedBytes, resumedBytes);
        }
        if (status == TransferItemStatus.TRANSFERRING) {
            update.set(FileTransferRunItemEntity::getStartedAt, local(event));
        }
        if (terminal(status)) {
            update.set(FileTransferRunItemEntity::getEndedAt, local(event));
        }
        updateItem(event.itemId(), update, false, true);
        updateRunStatus(status, event.message());
        persistProgress(event, now);
    }

    private void persistProgress(TransferEvent event, long now) {
        Long resumedBytes = resumedBytes(event);
        if (resumedBytes != null) {
            resumedBytesByItem.put(event.itemId(), resumedBytes);
        }
        long activityBytes = activityBytes(event);
        ProgressMark previous = progressMarks.put(event.itemId(),
                new ProgressMark(activityBytes, now));
        long speed = 0L;
        if (!terminal(event.itemStatus()) && previous != null && now > previous.atMillis) {
            speed = Math.max(0L, (activityBytes - previous.bytes) * 1000L
                    / (now - previous.atMillis));
        }
        LambdaUpdateWrapper<FileTransferRunItemEntity> update = new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getTransferredBytes, event.transferredBytes())
                .set(FileTransferRunItemEntity::getCurrentBytesPerSecond, speed)
                .set(FileTransferRunItemEntity::getAttempts, event.attempt())
                .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunItemEntity::getRunId, run.getId())
                .eq(FileTransferRunItemEntity::getCoreItemId, event.itemId());
        if (resumedBytes != null) {
            update.set(FileTransferRunItemEntity::getResumedBytes, resumedBytes);
        }
        updateItem(event.itemId(), update, true, terminal(event.itemStatus()));
    }

    private void persistLiveProgress(TransferEvent event, long now) {
        long observedBytes = observedBytes(event);
        long activityBytes = activityBytes(event);
        ProgressMark previous = progressMarks.put(event.itemId(), new ProgressMark(activityBytes, now));
        long speed = 0L;
        if (previous != null && now > previous.atMillis) {
            speed = Math.max(0L, (activityBytes - previous.bytes) * 1000L / (now - previous.atMillis));
        }
        LambdaUpdateWrapper<FileTransferRunItemEntity> update = new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getCurrentBytesPerSecond, speed)
                .set(FileTransferRunItemEntity::getAttempts, event.attempt())
                .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunItemEntity::getRunId, run.getId())
                .eq(FileTransferRunItemEntity::getCoreItemId, event.itemId());
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("live", true);
        payload.put("confirmedBytes", event.transferredBytes());
        payload.put("observedBytes", observedBytes);
        payload.put("activityBytes", activityBytes);
        payload.put("liveBytesPerSecond", speed);
        Object resumePhase = event.details().get("resumePhase");
        if (resumePhase != null) {
            payload.put("resumePhase", resumePhase);
        }
        mutationService().updateItemAndEvent(run.getId(), event.itemId(), update, payload, true, false);
    }

    private boolean restartedFromZero(TransferEvent event) {
        return event != null && "RESTARTED_FROM_ZERO".equalsIgnoreCase(
                String.valueOf(event.details().get("resumePhase")));
    }

    private void updateRunStatus(TransferItemStatus itemStatus, String message) {
        if (itemStatus == TransferItemStatus.PAUSED) {
            updateRun(new LambdaUpdateWrapper<FileTransferRunEntity>()
                    .set(FileTransferRunEntity::getStatus, "PAUSED")
                    .set(FileTransferRunEntity::getMessage, message)
                    .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                    .eq(FileTransferRunEntity::getId, run.getId())
                    .ne(FileTransferRunEntity::getStatus, "CANCELED"), false, true);
        } else if (itemStatus == TransferItemStatus.TRANSFERRING) {
            updateRun(new LambdaUpdateWrapper<FileTransferRunEntity>()
                    .set(FileTransferRunEntity::getStatus, "RUNNING")
                    .set(FileTransferRunEntity::getMessage, message)
                    .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                    .eq(FileTransferRunEntity::getId, run.getId())
                    .notIn(FileTransferRunEntity::getStatus, "PAUSED", "CANCELED"), false, true);
        }
    }

    private void persistSample(long now) {
        long transferred = 0L;
        for (Long value : bytesByItem.values()) {
            transferred += value == null ? 0L : value;
        }
        long activity = 0L;
        for (Long value : activityBytesByItem.values()) {
            activity += value == null ? 0L : value;
        }
        activity = Math.max(transferred, activity);
        long resumed = 0L;
        long resumedFiles = 0L;
        for (Long value : resumedBytesByItem.values()) {
            long itemResumed = value == null ? 0L : value;
            resumed += itemResumed;
            if (itemResumed > 0L) {
                resumedFiles++;
            }
        }
        long elapsed = Math.max(1L, now - lastSampleAt);
        long speed = Math.max(0L, (activity - lastSampleObservedBytes) * 1000L / elapsed);
        FileTransferRunEntity latest = runMapper.selectById(run.getId());
        if (latest == null) {
            return;
        }
        long peak = Math.max(latest.getPeakBytesPerSecond() == null ? 0L : latest.getPeakBytesPerSecond(), speed);
        long currentSpeed = terminalRun(latest.getStatus()) ? 0L : speed;
        LambdaUpdateWrapper<FileTransferRunEntity> runUpdate = new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getTransferredBytes, transferred)
                .set(FileTransferRunEntity::getResumedBytes, resumed)
                .set(FileTransferRunEntity::getResumedFiles, resumedFiles)
                .set(FileTransferRunEntity::getCurrentBytesPerSecond, currentSpeed)
                .set(FileTransferRunEntity::getPeakBytesPerSecond, peak)
                .set(FileTransferRunEntity::getRetryCount, retryCount)
                .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunEntity::getId, run.getId());

        FileTransferMetricSampleEntity sample = new FileTransferMetricSampleEntity();
        sample.setTenantId(run.getTenantId());
        sample.setProjectId(run.getProjectId());
        sample.setRunId(run.getId());
        sample.setRunRecordId(latest.getRunRecordId());
        sample.setTaskId(run.getTaskId());
        sample.setRuntimeClusterId(run.getTargetRuntimeClusterId());
        sample.setSourceDatasourceId(run.getSourceDatasourceId());
        sample.setTargetDatasourceId(run.getTargetDatasourceId());
        sample.setChannel(run.getChannel());
        sample.setStatus(latest.getStatus());
        sample.setSampledAt(LocalDateTime.now());
        sample.setTransferredBytes(transferred);
        sample.setBytesPerSecond(speed);
        sample.setCompletedFiles(completedFiles(latest));
        sample.setFailedFiles(latest.getFailedFiles());
        sample.setActiveFiles(latest.getActiveFiles());
        sample.setRetryCount((int) Math.min(Integer.MAX_VALUE, retryCount));
        mutationService().persistMetricSampleAndRunEvent(run.getId(), runUpdate, sample);
        lastSampleAt = now;
        lastSampleBytes = transferred;
        lastSampleObservedBytes = activity;
    }

    private long completedFiles(FileTransferRunEntity value) {
        return value(value.getSuccessFiles()) + value(value.getSkippedFiles());
    }

    private void updateItem(String coreItemId, LambdaUpdateWrapper<FileTransferRunItemEntity> update,
                            boolean progress, boolean force) {
        mutationService().updateItemAndEvent(run.getId(), coreItemId, update, progress, force);
    }

    private void updateRun(LambdaUpdateWrapper<FileTransferRunEntity> update,
                           boolean progress, boolean force) {
        mutationService().updateRunAndEvent(run.getId(), update, progress, force);
    }

    private FileTransferStateMutationService mutationService() {
        if (mutationService == null) {
            throw new IllegalStateException("File transfer state mutation service is required");
        }
        return mutationService;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private Long resumedBytes(TransferEvent event) {
        if (event.details() == null || !event.details().containsKey("resumedBytes")) {
            return null;
        }
        Object value = event.details().get("resumedBytes");
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean live(TransferEvent event) {
        return Boolean.TRUE.equals(event.details().get("live"));
    }

    private long observedBytes(TransferEvent event) {
        Object value = event.details().get("observedBytes");
        if (value instanceof Number number) {
            return Math.max(event.transferredBytes(), number.longValue());
        }
        try {
            return Math.max(event.transferredBytes(), Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return event.transferredBytes();
        }
    }

    private long activityBytes(TransferEvent event) {
        Object value = event.details().get("activityBytes");
        if (value instanceof Number number) {
            return Math.max(event.transferredBytes(), number.longValue());
        }
        try {
            return Math.max(event.transferredBytes(), Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return live(event) ? observedBytes(event) : event.transferredBytes();
        }
    }

    private boolean due(Long previous, long now, long interval) {
        return previous == null || now - previous >= interval;
    }

    private boolean terminal(TransferItemStatus status) {
        return status == TransferItemStatus.SUCCESS || status == TransferItemStatus.SKIPPED
                || status == TransferItemStatus.CONFLICT || status == TransferItemStatus.POST_ACTION_FAILED
                || status == TransferItemStatus.FAILED || status == TransferItemStatus.CANCELED;
    }

    private boolean terminalRun(String status) {
        return status != null && ("SUCCESS".equalsIgnoreCase(status)
                || "PARTIAL_SUCCESS".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELED".equalsIgnoreCase(status));
    }

    private LocalDateTime local(TransferEvent event) {
        return LocalDateTime.ofInstant(event.timestamp(), ZoneId.systemDefault());
    }

    private static final class ProgressMark {
        private final long bytes;
        private final long atMillis;

        private ProgressMark(long bytes, long atMillis) {
            this.bytes = bytes;
            this.atMillis = atMillis;
        }
    }
}
