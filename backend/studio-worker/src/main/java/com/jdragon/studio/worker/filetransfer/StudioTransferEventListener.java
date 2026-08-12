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
    private final Map<String, Long> bytesByItem = new HashMap<String, Long>();
    private final Map<String, Long> lastItemUpdateAt = new HashMap<String, Long>();
    private final Map<String, ProgressMark> progressMarks = new HashMap<String, ProgressMark>();
    private long lastSampleAt;
    private long lastSampleBytes;
    private long retryCount;

    StudioTransferEventListener(FileTransferRunEntity run,
                                FileTransferRunMapper runMapper,
                                FileTransferRunItemMapper itemMapper,
                                FileTransferMetricSampleMapper metricMapper) {
        this.run = run;
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
        this.metricMapper = metricMapper;
        this.lastSampleAt = System.currentTimeMillis();
        this.lastSampleBytes = run.getTransferredBytes() == null ? 0L : run.getTransferredBytes();
        itemMapper.selectList(new LambdaQueryWrapper<FileTransferRunItemEntity>()
                        .eq(FileTransferRunItemEntity::getRunId, run.getId()))
                .forEach(item -> bytesByItem.put(item.getCoreItemId(), value(item.getTransferredBytes())));
    }

    @Override
    public synchronized void onEvent(TransferEvent event) {
        if (event == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (event.itemId() != null) {
            bytesByItem.put(event.itemId(), event.transferredBytes());
        }
        if (event.type() == TransferEventType.ITEM_RETRYING) {
            retryCount++;
        }
        if (event.type() == TransferEventType.ITEM_STATUS_CHANGED) {
            persistStatus(event, now);
        } else if (event.type() == TransferEventType.ITEM_PROGRESS
                && due(lastItemUpdateAt.get(event.itemId()), now, ITEM_UPDATE_INTERVAL_MILLIS)) {
            persistProgress(event, now);
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
        LambdaUpdateWrapper<FileTransferRunItemEntity> update = new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getStatus, status == null ? null : status.name())
                .set(FileTransferRunItemEntity::getTransferredBytes, event.transferredBytes())
                .set(FileTransferRunItemEntity::getAttempts, event.attempt())
                .set(FileTransferRunItemEntity::getErrorCode, event.errorCode())
                .set(FileTransferRunItemEntity::getErrorMessage, event.errorCode() == null ? null : event.message())
                .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunItemEntity::getRunId, run.getId())
                .eq(FileTransferRunItemEntity::getCoreItemId, event.itemId());
        if (status == TransferItemStatus.TRANSFERRING) {
            update.set(FileTransferRunItemEntity::getStartedAt, local(event));
        }
        if (terminal(status)) {
            update.set(FileTransferRunItemEntity::getEndedAt, local(event));
        }
        itemMapper.update(null, update);
        updateRunStatus(status, event.message());
        persistProgress(event, now);
    }

    private void persistProgress(TransferEvent event, long now) {
        ProgressMark previous = progressMarks.put(event.itemId(),
                new ProgressMark(event.transferredBytes(), now));
        long speed = 0L;
        if (!terminal(event.itemStatus()) && previous != null && now > previous.atMillis) {
            speed = Math.max(0L, (event.transferredBytes() - previous.bytes) * 1000L
                    / (now - previous.atMillis));
        }
        itemMapper.update(null, new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getTransferredBytes, event.transferredBytes())
                .set(FileTransferRunItemEntity::getCurrentBytesPerSecond, speed)
                .set(FileTransferRunItemEntity::getAttempts, event.attempt())
                .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunItemEntity::getRunId, run.getId())
                .eq(FileTransferRunItemEntity::getCoreItemId, event.itemId()));
    }

    private void updateRunStatus(TransferItemStatus itemStatus, String message) {
        if (itemStatus == TransferItemStatus.PAUSED) {
            runMapper.update(null, new LambdaUpdateWrapper<FileTransferRunEntity>()
                    .set(FileTransferRunEntity::getStatus, "PAUSED")
                    .set(FileTransferRunEntity::getMessage, message)
                    .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                    .eq(FileTransferRunEntity::getId, run.getId())
                    .ne(FileTransferRunEntity::getStatus, "CANCELED"));
        } else if (itemStatus == TransferItemStatus.TRANSFERRING) {
            runMapper.update(null, new LambdaUpdateWrapper<FileTransferRunEntity>()
                    .set(FileTransferRunEntity::getStatus, "RUNNING")
                    .set(FileTransferRunEntity::getMessage, message)
                    .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                    .eq(FileTransferRunEntity::getId, run.getId())
                    .notIn(FileTransferRunEntity::getStatus, "PAUSED", "CANCELED"));
        }
    }

    private void persistSample(long now) {
        long transferred = 0L;
        for (Long value : bytesByItem.values()) {
            transferred += value == null ? 0L : value;
        }
        long elapsed = Math.max(1L, now - lastSampleAt);
        long speed = Math.max(0L, (transferred - lastSampleBytes) * 1000L / elapsed);
        FileTransferRunEntity latest = runMapper.selectById(run.getId());
        if (latest == null) {
            return;
        }
        long peak = Math.max(latest.getPeakBytesPerSecond() == null ? 0L : latest.getPeakBytesPerSecond(), speed);
        long currentSpeed = terminalRun(latest.getStatus()) ? 0L : speed;
        runMapper.update(null, new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getTransferredBytes, transferred)
                .set(FileTransferRunEntity::getCurrentBytesPerSecond, currentSpeed)
                .set(FileTransferRunEntity::getPeakBytesPerSecond, peak)
                .set(FileTransferRunEntity::getRetryCount, retryCount)
                .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunEntity::getId, run.getId()));

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
        metricMapper.insert(sample);
        lastSampleAt = now;
        lastSampleBytes = transferred;
    }

    private long completedFiles(FileTransferRunEntity value) {
        return value(value.getSuccessFiles()) + value(value.getSkippedFiles());
    }

    private long value(Long value) {
        return value == null ? 0L : value;
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
