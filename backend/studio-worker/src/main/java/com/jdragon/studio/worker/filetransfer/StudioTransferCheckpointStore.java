package com.jdragon.studio.worker.filetransfer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.transfer.TransferCheckpointStore;
import com.jdragon.aggregation.transfer.model.TransferCheckpoint;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.service.FileTransferStateMutationService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
final class StudioTransferCheckpointStore implements TransferCheckpointStore {

    private final Long studioRunId;
    private final FileTransferRunItemMapper itemMapper;
    private final FileTransferStateMutationService mutationService;
    private final ObjectMapper objectMapper;

    StudioTransferCheckpointStore(Long studioRunId, FileTransferRunItemMapper itemMapper,
                                  FileTransferStateMutationService mutationService,
                                  ObjectMapper objectMapper) {
        this.studioRunId = studioRunId;
        this.itemMapper = itemMapper;
        this.mutationService = mutationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<TransferCheckpoint> load(String runId, String itemId) throws IOException {
        FileTransferRunItemEntity item = find(itemId);
        if (item == null || item.getCheckpointJson() == null || item.getCheckpointJson().isEmpty()) {
            log.debug("[FT_CHECKPOINT_NOT_FOUND] 未找到可用文件传输断点 runId={} coreRunId={} itemId={}",
                    studioRunId, runId, itemId);
            return Optional.empty();
        }
        try {
            TransferCheckpoint checkpoint = objectMapper.convertValue(
                    item.getCheckpointJson(), TransferCheckpoint.class);
            log.debug("[FT_CHECKPOINT_LOADED] 文件传输断点加载完成 runId={} coreRunId={} itemId={} "
                            + "confirmedOffset={} schemaVersion={}",
                    studioRunId, runId, itemId, checkpoint.confirmedOffset(), checkpoint.schemaVersion());
            return Optional.of(checkpoint);
        } catch (IllegalArgumentException exception) {
            log.warn("[FT_CHECKPOINT_LOAD_FAILED] 文件传输断点无法解析 runId={} coreRunId={} "
                            + "itemId={} exceptionType={} message={}",
                    studioRunId, runId, itemId, exception.getClass().getName(), exception.getMessage());
            throw new IOException("Stored transfer checkpoint is invalid", exception);
        }
    }

    @Override
    public void save(TransferCheckpoint checkpoint) throws IOException {
        if (checkpoint == null || checkpoint.itemId() == null) {
            return;
        }
        Map<String, Object> values;
        try {
            values = objectMapper.convertValue(checkpoint,
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IllegalArgumentException exception) {
            log.warn("[FT_CHECKPOINT_SAVE_FAILED] 文件传输断点无法序列化 runId={} coreRunId={} "
                            + "itemId={} exceptionType={} message={}",
                    studioRunId, checkpoint.runId(), checkpoint.itemId(),
                    exception.getClass().getName(), exception.getMessage());
            throw new IOException("Transfer checkpoint cannot be serialized", exception);
        }
        FileTransferRunItemEntity item = find(checkpoint.itemId());
        if (item == null) {
            log.warn("[FT_CHECKPOINT_ITEM_NOT_FOUND] 保存断点时未找到文件传输项 runId={} coreRunId={} itemId={}",
                    studioRunId, checkpoint.runId(), checkpoint.itemId());
            return;
        }
        update(item, new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getCheckpointJson, values,
                        "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                .set(FileTransferRunItemEntity::getTransferredBytes, checkpoint.confirmedOffset())
                .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunItemEntity::getId, item.getId()), true, false);
        log.debug("[FT_CHECKPOINT_SAVED] 文件传输断点保存完成 runId={} coreRunId={} itemId={} "
                        + "confirmedOffset={} schemaVersion={}",
                studioRunId, checkpoint.runId(), checkpoint.itemId(),
                checkpoint.confirmedOffset(), checkpoint.schemaVersion());
    }

    @Override
    public void delete(String runId, String itemId) {
        FileTransferRunItemEntity item = find(itemId);
        if (item == null) {
            log.warn("[FT_CHECKPOINT_ITEM_NOT_FOUND] 删除断点时未找到文件传输项 runId={} coreRunId={} itemId={}",
                    studioRunId, runId, itemId);
            return;
        }
        update(item, new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getCheckpointJson, new LinkedHashMap<String, Object>(),
                        "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunItemEntity::getId, item.getId()), false, true);
        log.debug("[FT_CHECKPOINT_REMOVED] 文件传输断点已清除 runId={} coreRunId={} itemId={}",
                studioRunId, runId, itemId);
    }

    private FileTransferRunItemEntity find(String itemId) {
        return itemMapper.selectOne(new LambdaQueryWrapper<FileTransferRunItemEntity>()
                .eq(FileTransferRunItemEntity::getRunId, studioRunId)
                .eq(FileTransferRunItemEntity::getCoreItemId, itemId)
                .last("limit 1"));
    }

    private void update(FileTransferRunItemEntity item,
                        LambdaUpdateWrapper<FileTransferRunItemEntity> update,
                        boolean progress, boolean force) {
        mutationService().updateItemAndEvent(studioRunId, item.getCoreItemId(), update, progress, force);
    }

    private FileTransferStateMutationService mutationService() {
        if (mutationService == null) {
            throw new IllegalStateException("File transfer state mutation service is required");
        }
        return mutationService;
    }
}
