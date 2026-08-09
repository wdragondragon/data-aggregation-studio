package com.jdragon.studio.worker.filetransfer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.transfer.TransferCheckpointStore;
import com.jdragon.aggregation.transfer.model.TransferCheckpoint;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class StudioTransferCheckpointStore implements TransferCheckpointStore {

    private final Long studioRunId;
    private final FileTransferRunItemMapper itemMapper;
    private final ObjectMapper objectMapper;

    StudioTransferCheckpointStore(Long studioRunId, FileTransferRunItemMapper itemMapper,
                                  ObjectMapper objectMapper) {
        this.studioRunId = studioRunId;
        this.itemMapper = itemMapper;
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
        item.setCheckpointJson(values);
        item.setTransferredBytes(checkpoint.confirmedOffset());
        item.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(item);
    }

    @Override
    public void delete(String runId, String itemId) {
        FileTransferRunItemEntity item = find(itemId);
        if (item == null) {
            return;
        }
        item.setCheckpointJson(new LinkedHashMap<String, Object>());
        item.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(item);
    }

    private FileTransferRunItemEntity find(String itemId) {
        return itemMapper.selectOne(new LambdaQueryWrapper<FileTransferRunItemEntity>()
                .eq(FileTransferRunItemEntity::getRunId, studioRunId)
                .eq(FileTransferRunItemEntity::getCoreItemId, itemId)
                .last("limit 1"));
    }
}
