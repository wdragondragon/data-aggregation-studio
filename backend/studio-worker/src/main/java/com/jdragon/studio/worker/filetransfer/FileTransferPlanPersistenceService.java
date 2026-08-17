package com.jdragon.studio.worker.filetransfer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.transfer.PreparedTransfer;
import com.jdragon.aggregation.transfer.model.TransferPlanItem;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.service.FileTransferStateMutationService;
import com.jdragon.studio.worker.filetransfer.FileTransferExecutionAssembler.TransferExecution;

import java.util.LinkedHashMap;
import java.util.Map;

final class FileTransferPlanPersistenceService {

    private final FileTransferRunItemMapper itemMapper;
    private final FileTransferStateMutationService mutationService;
    private final ObjectMapper objectMapper;

    FileTransferPlanPersistenceService(FileTransferRunItemMapper itemMapper,
                                       FileTransferStateMutationService mutationService,
                                       ObjectMapper objectMapper) {
        this.itemMapper = itemMapper;
        this.mutationService = mutationService;
        this.objectMapper = objectMapper;
    }

    void persistResolvedTaskSpec(FileTransferRunEntity run, PreparedTransfer prepared) {
        Map<String, Object> snapshot = copy(run.getResolvedSpecJson());
        snapshot.put("selection", objectMapper.convertValue(prepared.resolvedSpec().selection(),
                new TypeReference<LinkedHashMap<String, Object>>() { }));
        snapshot.put("mapping", objectMapper.convertValue(prepared.resolvedSpec().mapping(),
                new TypeReference<LinkedHashMap<String, Object>>() { }));
        snapshot.put("policy", objectMapper.convertValue(prepared.resolvedSpec().policy(),
                new TypeReference<LinkedHashMap<String, Object>>() { }));
        snapshot.put("runtime", objectMapper.convertValue(prepared.resolvedSpec().runtime(),
                new TypeReference<LinkedHashMap<String, Object>>() { }));
        snapshot.put("timeZone", prepared.resolvedSpec().timeZone());
        snapshot.put("parameters",
                new LinkedHashMap<String, String>(prepared.resolvedSpec().parameters()));
        snapshot.put("plannedAtMillis", prepared.plan().plannedAt().toEpochMilli());
        snapshot.put("dynamicResolved", Boolean.TRUE);
        run.setResolvedSpecJson(snapshot);
        mutationService().updateRunAndEvent(run.getId(),
                new LambdaUpdateWrapper<FileTransferRunEntity>()
                        .set(FileTransferRunEntity::getResolvedSpecJson, snapshot,
                                "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                        .eq(FileTransferRunEntity::getId, run.getId()), false, true);
    }

    void persistPlan(FileTransferRunEntity run, TransferExecution execution,
                     PreparedTransfer prepared) {
        for (TransferPlanItem planItem : prepared.plan().items()) {
            FileTransferRunItemEntity item = itemMapper.selectOne(
                    new LambdaQueryWrapper<FileTransferRunItemEntity>()
                            .eq(FileTransferRunItemEntity::getRunId, run.getId())
                            .eq(FileTransferRunItemEntity::getCoreItemId, planItem.itemId())
                            .last("limit 1"));
            boolean created = item == null;
            if (created) {
                item = new FileTransferRunItemEntity();
                item.setTenantId(run.getTenantId());
                item.setProjectId(run.getProjectId());
                item.setRunId(run.getId());
                item.setCoreItemId(planItem.itemId());
            }
            item.setDirection(execution.direction);
            item.setChannel(execution.channel);
            item.setRuntimeClusterId(execution.targetClusterId);
            item.setSourceRuntimeClusterId(execution.sourceClusterId);
            item.setSourceDatasourceId(execution.sourceDatasourceId);
            item.setSourcePath(planItem.sourcePath());
            item.setTargetRuntimeClusterId(execution.targetClusterId);
            item.setTargetDatasourceId(execution.targetDatasourceId);
            item.setTargetPath(planItem.targetPath());
            item.setTemporaryPath(planItem.temporaryPath());
            item.setStatus("QUEUED");
            item.setFileSize(planItem.sourceSnapshot().size());
            item.setTransferredBytes(item.getTransferredBytes() == null
                    ? 0L : item.getTransferredBytes());
            item.setResumedBytes(item.getResumedBytes() == null ? 0L : item.getResumedBytes());
            item.setCurrentBytesPerSecond(0L);
            item.setAttempts(item.getAttempts() == null ? 0 : item.getAttempts());
            item.setSourceSnapshotJson(objectMapper.convertValue(planItem.sourceSnapshot(),
                    new TypeReference<LinkedHashMap<String, Object>>() { }));
            Map<String, Object> policy = map(execution.spec.get("policy"));
            item.setConflictAction(text(policy.get("conflictPolicy"), "FAIL"));
            item.setSourceAction(text(policy.get("sourceSuccessAction"), "KEEP"));
            mutationService().insertOrUpdatePlanItemAndEvent(item, created);
        }
    }

    void updatePlanTotals(FileTransferRunEntity run, long totalFiles, long totalBytes) {
        run.setTotalFiles(totalFiles);
        run.setTotalBytes(totalBytes);
        run.setActiveFiles((int) Math.min(Integer.MAX_VALUE, totalFiles));
        run.setMessage("File discovery completed");
        mutationService().updateRunAndEvent(run.getId(),
                new LambdaUpdateWrapper<FileTransferRunEntity>()
                        .set(FileTransferRunEntity::getTotalFiles, totalFiles)
                        .set(FileTransferRunEntity::getTotalBytes, totalBytes)
                        .set(FileTransferRunEntity::getActiveFiles, run.getActiveFiles())
                        .set(FileTransferRunEntity::getMessage, run.getMessage())
                        .eq(FileTransferRunEntity::getId, run.getId()), false, true);
    }

    private Map<String, Object> copy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return objectMapper.convertValue(values,
                new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return new LinkedHashMap<String, Object>();
        }
        return objectMapper.convertValue(value,
                new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).trim().isEmpty()
                ? fallback : String.valueOf(value).trim();
    }

    private FileTransferStateMutationService mutationService() {
        if (mutationService == null) {
            throw new IllegalStateException("File transfer state mutation service is required");
        }
        return mutationService;
    }
}
