package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.infra.entity.FileTransferMetricSampleEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.model.FileTransferEventIntent;
import com.jdragon.studio.infra.model.FileTransferOutboxEventType;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class FileTransferStateMutationService {

    private final FileTransferRunMapper runMapper;
    private final FileTransferRunItemMapper itemMapper;
    private final FileTransferMetricSampleMapper metricMapper;
    private final FileTransferOutboxWriter outboxWriter;
    private DispatchTaskMapper dispatchTaskMapper;

    public FileTransferStateMutationService(FileTransferRunMapper runMapper,
                                            FileTransferRunItemMapper itemMapper,
                                            FileTransferMetricSampleMapper metricMapper,
                                            FileTransferOutboxWriter outboxWriter) {
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
        this.metricMapper = metricMapper;
        this.outboxWriter = outboxWriter;
    }

    @Autowired
    void setDispatchTaskMapper(DispatchTaskMapper dispatchTaskMapper) {
        this.dispatchTaskMapper = dispatchTaskMapper;
    }

    @Transactional
    public void insertRunAndEvent(FileTransferRunEntity run) {
        runMapper.insert(run);
        append(run, FileTransferOutboxEventType.RUN_CREATED, null, null, false, true);
    }

    @Transactional
    public int updateRunAndEvent(Long runId, LambdaUpdateWrapper<FileTransferRunEntity> update,
                                 boolean progress, boolean force) {
        int updated = runMapper.update(null, update);
        if (updated > 0) {
            FileTransferRunEntity run = requireRun(runId);
            append(run, FileTransferOutboxEventType.RUN_CHANGED, null, null, progress, force);
        }
        return updated;
    }

    /**
     * Reclaims an interrupted transfer by atomically making the run dispatchable
     * and inserting its new Worker dispatch. The previous dispatch must already
     * be fenced and terminal before this method is invoked.
     */
    @Transactional
    public int requeueRunAndDispatchAndEvent(Long runId,
                                              LambdaUpdateWrapper<FileTransferRunEntity> runUpdate,
                                              DispatchTaskEntity recoveryDispatch) {
        if (dispatchTaskMapper == null) {
            throw new IllegalStateException("Dispatch task mapper is unavailable for file transfer recovery");
        }
        int updated = runMapper.update(null, runUpdate);
        if (updated <= 0) {
            return updated;
        }
        append(requireRun(runId), FileTransferOutboxEventType.RUN_CHANGED,
                null, Map.of("source", "WORKER_RESTART_RECOVERY"), false, true);
        dispatchTaskMapper.insert(recoveryDispatch);
        return updated;
    }

    /**
     * Resumes a transfer without creating a second execution owner. A running
     * dispatch means the paused transfer thread is still waiting in place; a
     * queued dispatch or a newly inserted dispatch means checkpoint recovery
     * will be claimed by a Worker.
     */
    @Transactional
    public String resumeRunAndEnsureDispatchAndEvent(FileTransferRunEntity requestedRun,
                                                     DispatchTaskEntity recoveryDispatch) {
        if (dispatchTaskMapper == null) {
            throw new IllegalStateException("Dispatch task mapper is unavailable for file transfer resume");
        }
        Long running = dispatchTaskMapper.selectCount(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, requestedRun.getTenantId())
                .eq(DispatchTaskEntity::getProjectId, requestedRun.getProjectId())
                .eq(DispatchTaskEntity::getFileTransferRunId, requestedRun.getId())
                .eq(DispatchTaskEntity::getStatus, "RUNNING"));
        Long active = dispatchTaskMapper.selectCount(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, requestedRun.getTenantId())
                .eq(DispatchTaskEntity::getProjectId, requestedRun.getProjectId())
                .eq(DispatchTaskEntity::getFileTransferRunId, requestedRun.getId())
                .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING"));
        boolean runningOwner = running != null && running.longValue() > 0L;
        boolean activeOwner = active != null && active.longValue() > 0L;
        String status = runningOwner ? "RUNNING" : "QUEUED";
        String message = runningOwner
                ? "Resume requested; active transfer continues"
                : "Resume requested; checkpoint recovery queued";
        int updated = runMapper.update(null, new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getStatus, status)
                .set(FileTransferRunEntity::getMessage, message)
                .set(FileTransferRunEntity::getEndedAt, null)
                .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunEntity::getTenantId, requestedRun.getTenantId())
                .eq(FileTransferRunEntity::getProjectId, requestedRun.getProjectId())
                .eq(FileTransferRunEntity::getId, requestedRun.getId())
                .in(FileTransferRunEntity::getStatus, "PAUSED", "FAILED", "QUEUED"));
        if (updated <= 0) {
            return null;
        }
        append(requireRun(requestedRun.getId()), FileTransferOutboxEventType.RUN_CHANGED,
                null, Map.of("source", "SERVER_RESUME"), false, true);
        if (!activeOwner) {
            dispatchTaskMapper.insert(recoveryDispatch);
        }
        return status;
    }

    @Transactional
    public FileTransferRunItemEntity insertOrUpdatePlanItemAndEvent(FileTransferRunItemEntity item, boolean created) {
        int updated;
        if (created) {
            updated = itemMapper.insert(item);
        } else {
            updated = itemMapper.update(null, new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                    .set(FileTransferRunItemEntity::getDirection, item.getDirection())
                    .set(FileTransferRunItemEntity::getChannel, item.getChannel())
                    .set(FileTransferRunItemEntity::getRuntimeClusterId, item.getRuntimeClusterId())
                    .set(FileTransferRunItemEntity::getSourceRuntimeClusterId, item.getSourceRuntimeClusterId())
                    .set(FileTransferRunItemEntity::getSourceDatasourceId, item.getSourceDatasourceId())
                    .set(FileTransferRunItemEntity::getSourcePath, item.getSourcePath())
                    .set(FileTransferRunItemEntity::getTargetRuntimeClusterId, item.getTargetRuntimeClusterId())
                    .set(FileTransferRunItemEntity::getTargetDatasourceId, item.getTargetDatasourceId())
                    .set(FileTransferRunItemEntity::getTargetPath, item.getTargetPath())
                    .set(FileTransferRunItemEntity::getTemporaryPath, item.getTemporaryPath())
                    .set(FileTransferRunItemEntity::getStatus, item.getStatus())
                    .set(FileTransferRunItemEntity::getFileSize, item.getFileSize())
                    .set(FileTransferRunItemEntity::getSourceSnapshotJson, item.getSourceSnapshotJson(),
                            "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                    .set(FileTransferRunItemEntity::getConflictAction, item.getConflictAction())
                    .set(FileTransferRunItemEntity::getSourceAction, item.getSourceAction())
                    .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                    .eq(FileTransferRunItemEntity::getId, item.getId()));
        }
        if (updated > 0) {
            FileTransferRunEntity run = requireRun(item.getRunId());
            append(run, FileTransferOutboxEventType.ITEM_CHANGED, item.getId(), null, false, true);
        }
        return item;
    }

    @Transactional
    public int updateItemAndEvent(Long runId, String coreItemId,
                                  LambdaUpdateWrapper<FileTransferRunItemEntity> update,
                                  boolean progress, boolean force) {
        return updateItemAndEvent(runId, coreItemId, update, null, progress, force);
    }

    @Transactional
    public int updateItemAndEvent(Long runId, String coreItemId,
                                  LambdaUpdateWrapper<FileTransferRunItemEntity> update,
                                  Map<String, Object> payload,
                                  boolean progress, boolean force) {
        int updated = itemMapper.update(null, update);
        if (updated > 0) {
            FileTransferRunItemEntity item = itemMapper.selectOne(new LambdaQueryWrapper<FileTransferRunItemEntity>()
                    .eq(FileTransferRunItemEntity::getRunId, runId)
                    .eq(FileTransferRunItemEntity::getCoreItemId, coreItemId)
                    .last("limit 1"));
            FileTransferRunEntity run = requireRun(runId);
            append(run, FileTransferOutboxEventType.ITEM_CHANGED,
                    item == null ? null : item.getId(), payload, progress, force);
        }
        return updated;
    }

    @Transactional
    public int updateItemsAndEvents(Long runId,
                                    LambdaUpdateWrapper<FileTransferRunItemEntity> update,
                                    List<Long> itemIds) {
        int updated = itemMapper.update(null, update);
        if (updated > 0 && itemIds != null && !itemIds.isEmpty()) {
            FileTransferRunEntity run = requireRun(runId);
            for (Long itemId : itemIds) {
                if (itemId != null) {
                    append(run, FileTransferOutboxEventType.ITEM_CHANGED, itemId, null, false, true);
                }
            }
        }
        return updated;
    }

    /**
     * Pausing is a run-level command, but the queue renders file-item state and
     * speed independently. Persist both sides atomically so a pause cannot
     * leave stale TRANSFERRING/old-speed rows visible until the Worker catches up.
     */
    @Transactional
    public int pauseRunAndItemsAndEvent(FileTransferRunEntity requestedRun,
                                        LambdaUpdateWrapper<FileTransferRunEntity> runUpdate,
                                        LambdaUpdateWrapper<FileTransferRunItemEntity> itemUpdate) {
        List<FileTransferRunItemEntity> activeItems = itemMapper.selectList(
                new LambdaQueryWrapper<FileTransferRunItemEntity>()
                        .eq(FileTransferRunItemEntity::getTenantId, requestedRun.getTenantId())
                        .eq(FileTransferRunItemEntity::getProjectId, requestedRun.getProjectId())
                        .eq(FileTransferRunItemEntity::getRunId, requestedRun.getId())
                        .in(FileTransferRunItemEntity::getStatus,
                                "QUEUED", "DISCOVERING", "TRANSFERRING", "VERIFYING",
                                "COMMITTING", "POST_ACTION", "PAUSED"));
        int updatedRun = runMapper.update(null, runUpdate);
        int updatedItems = itemMapper.update(null, itemUpdate);
        if (updatedRun > 0) {
            FileTransferRunEntity latest = requireRun(requestedRun.getId());
            append(latest, FileTransferOutboxEventType.RUN_CHANGED, null, null, false, true);
            if (updatedItems > 0) {
                for (FileTransferRunItemEntity item : activeItems) {
                    if (item.getId() != null) {
                        append(latest, FileTransferOutboxEventType.ITEM_CHANGED,
                                item.getId(), null, false, true);
                    }
                }
            }
        }
        return updatedRun;
    }

    /**
     * A paused run must not retain an item-level live state after a Worker
     * restart. Normalize only non-terminal items; checkpoint and byte counters
     * remain untouched so a later resume can continue from the saved offset.
     */
    @Transactional
    public int normalizePausedRunItemsAndEvent(FileTransferRunEntity requestedRun) {
        if (requestedRun == null || requestedRun.getId() == null) {
            return 0;
        }
        List<FileTransferRunItemEntity> activeItems = itemMapper.selectList(
                new LambdaQueryWrapper<FileTransferRunItemEntity>()
                        .eq(FileTransferRunItemEntity::getTenantId, requestedRun.getTenantId())
                        .eq(FileTransferRunItemEntity::getProjectId, requestedRun.getProjectId())
                        .eq(FileTransferRunItemEntity::getRunId, requestedRun.getId())
                        .in(FileTransferRunItemEntity::getStatus,
                                "QUEUED", "DISCOVERING", "TRANSFERRING", "VERIFYING",
                                "COMMITTING", "POST_ACTION"));
        if (activeItems == null || activeItems.isEmpty()) {
            return 0;
        }
        // Acquire the run row under the PAUSED predicate before changing item
        // state. A concurrent resume either wins first (and this returns 0) or
        // waits until item normalization commits before it creates/uses a
        // dispatch, so a resumed item cannot be written back to PAUSED.
        int pausedRun = runMapper.update(null, new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getCurrentBytesPerSecond, 0L)
                .set(FileTransferRunEntity::getActiveFiles, 0)
                .eq(FileTransferRunEntity::getTenantId, requestedRun.getTenantId())
                .eq(FileTransferRunEntity::getProjectId, requestedRun.getProjectId())
                .eq(FileTransferRunEntity::getId, requestedRun.getId())
                .eq(FileTransferRunEntity::getStatus, "PAUSED"));
        if (pausedRun <= 0) {
            return 0;
        }
        int updated = itemMapper.update(null, new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getStatus, "PAUSED")
                .set(FileTransferRunItemEntity::getCurrentBytesPerSecond, 0L)
                .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunItemEntity::getTenantId, requestedRun.getTenantId())
                .eq(FileTransferRunItemEntity::getProjectId, requestedRun.getProjectId())
                .eq(FileTransferRunItemEntity::getRunId, requestedRun.getId())
                .in(FileTransferRunItemEntity::getStatus,
                        "QUEUED", "DISCOVERING", "TRANSFERRING", "VERIFYING",
                        "COMMITTING", "POST_ACTION"));
        if (updated > 0) {
            FileTransferRunEntity latest = requireRun(requestedRun.getId());
            append(latest, FileTransferOutboxEventType.RUN_CHANGED,
                    null, Map.of("source", "WORKER_PAUSED_RECOVERY"), false, true);
            for (FileTransferRunItemEntity item : activeItems) {
                if (item.getId() != null) {
                    append(latest, FileTransferOutboxEventType.ITEM_CHANGED,
                            item.getId(), Map.of("source", "WORKER_PAUSED_RECOVERY"), false, true);
                }
            }
        }
        return updated;
    }

    @Transactional
    public void persistMetricSampleAndRunEvent(Long runId,
                                               LambdaUpdateWrapper<FileTransferRunEntity> runUpdate,
                                               FileTransferMetricSampleEntity sample) {
        int updated = runMapper.update(null, runUpdate);
        if (updated <= 0) {
            return;
        }
        metricMapper.insert(sample);
        append(requireRun(runId), FileTransferOutboxEventType.RUN_CHANGED,
                null, null, true, false);
    }

    @Transactional
    public void removeRunAndEvent(FileTransferRunEntity run) {
        itemMapper.delete(new LambdaQueryWrapper<FileTransferRunItemEntity>()
                .eq(FileTransferRunItemEntity::getRunId, run.getId())
                .eq(FileTransferRunItemEntity::getTenantId, run.getTenantId())
                .eq(FileTransferRunItemEntity::getProjectId, run.getProjectId()));
        runMapper.deleteById(run.getId());
        append(run, FileTransferOutboxEventType.RUN_REMOVED, null,
                Map.of("source", "SERVER"), false, true);
    }

    @Transactional
    public void removeItemAndEvent(FileTransferRunEntity run, FileTransferRunItemEntity item) {
        itemMapper.deleteById(item.getId());
        append(run, FileTransferOutboxEventType.ITEM_REMOVED, item.getId(),
                Map.of("source", "SERVER"), false, true);
    }

    public FileTransferRunEntity requireRun(Long runId) {
        FileTransferRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalStateException("File transfer run no longer exists: " + runId);
        }
        return run;
    }

    private void append(FileTransferRunEntity run, FileTransferOutboxEventType type, Long itemId,
                        Map<String, Object> payload, boolean progress, boolean force) {
        FileTransferEventIntent intent = FileTransferEventIntent.builder()
                .tenantId(run.getTenantId())
                .projectId(run.getProjectId())
                .eventType(type)
                .runId(run.getId())
                .itemId(itemId)
                .occurredAt(LocalDateTime.now())
                .payload(payload)
                .build();
        if (progress || force) {
            String key = itemId == null ? "run:" + run.getId() : "item:" + itemId;
            outboxWriter.appendProgress(intent, key, force);
        } else {
            outboxWriter.append(intent);
        }
    }
}
