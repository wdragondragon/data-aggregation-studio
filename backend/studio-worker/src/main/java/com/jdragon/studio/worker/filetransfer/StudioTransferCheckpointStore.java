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

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.convertValue(item.getCheckpointJson(), TransferCheckpoint.class));
        } catch (IllegalArgumentException exception) {
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
            throw new IOException("Transfer checkpoint cannot be serialized", exception);
        }
        FileTransferRunItemEntity item = find(checkpoint.itemId());
        if (item == null) {
            return;
        }
        update(item, new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getCheckpointJson, values,
                        "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                .set(FileTransferRunItemEntity::getTransferredBytes, checkpoint.confirmedOffset())
                .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunItemEntity::getId, item.getId()), true, false);
    }

    @Override
    public void delete(String runId, String itemId) {
        FileTransferRunItemEntity item = find(itemId);
        if (item == null) {
            return;
        }
        update(item, new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                .set(FileTransferRunItemEntity::getCheckpointJson, new LinkedHashMap<String, Object>(),
                        "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                .set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunItemEntity::getId, item.getId()), false, true);
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
