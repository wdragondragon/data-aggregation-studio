package com.jdragon.studio.worker.filetransfer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.aggregation.transfer.DynamicTemplateResolver;
import com.jdragon.aggregation.transfer.PreparedTransfer;
import com.jdragon.aggregation.transfer.SourceSnapshots;
import com.jdragon.aggregation.transfer.TransferFileSystemFactory;
import com.jdragon.aggregation.transfer.TransferPaths;
import com.jdragon.aggregation.transfer.TransferVerificationPlan;
import com.jdragon.aggregation.transfer.TransferVerifier;
import com.jdragon.aggregation.transfer.model.SourceSnapshot;
import com.jdragon.aggregation.transfer.model.SourceSuccessAction;
import com.jdragon.aggregation.transfer.model.TransferItemResult;
import com.jdragon.aggregation.transfer.model.TransferItemStatus;
import com.jdragon.aggregation.transfer.model.TransferPlan;
import com.jdragon.aggregation.transfer.model.TransferPlanItem;
import com.jdragon.aggregation.transfer.model.TransferPolicy;
import com.jdragon.aggregation.transfer.model.TransferResult;
import com.jdragon.aggregation.transfer.model.TransferRunStatus;
import com.jdragon.aggregation.transfer.model.TransferSpec;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.worker.filetransfer.FileTransferExecutionAssembler.TransferExecution;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class FileTransferPostActionRetryService {

    private final FileTransferRunItemMapper itemMapper;
    private final ObjectMapper objectMapper;

    FileTransferPostActionRetryService(FileTransferRunItemMapper itemMapper,
                                       ObjectMapper objectMapper) {
        this.itemMapper = itemMapper;
        this.objectMapper = objectMapper;
    }

    Optional<PreparedTransfer> prepare(FileTransferRunEntity run,
                                       TransferExecution execution,
                                       TransferSpec spec,
                                       String coreRunId,
                                       String retryCoreItemId,
                                       Map<String, Object> snapshot) {
        if (retryCoreItemId == null) {
            throw new IllegalArgumentException("Post-action retry item id is required");
        }
        FileTransferRunItemEntity item = findItem(run.getId(), retryCoreItemId);
        if (item == null) {
            throw new IllegalStateException("Post-action retry item was not found");
        }
        if (!Objects.equals(item.getSourceRuntimeClusterId(), execution.sourceClusterId)
                || !Objects.equals(item.getSourceDatasourceId(), execution.sourceDatasourceId)
                || !Objects.equals(item.getTargetRuntimeClusterId(), execution.targetClusterId)
                || !Objects.equals(item.getTargetDatasourceId(), execution.targetDatasourceId)) {
            return Optional.empty();
        }
        SourceSnapshot sourceSnapshot = objectMapper.convertValue(
                item.getSourceSnapshotJson(), SourceSnapshot.class);
        TransferSpec resolved = new DynamicTemplateResolver().resolve(
                spec, coreRunId, plannedAt(snapshot));
        String relativePath;
        try {
            relativePath = TransferPaths.relativeTo(
                    resolved.selection().rootPath(), item.getSourcePath());
        } catch (IllegalArgumentException outsideSelectionRoot) {
            relativePath = TransferPaths.fileName(item.getSourcePath());
        }
        TransferPlanItem planItem = new TransferPlanItem(retryCoreItemId,
                resolved.source().identity(), resolved.target().identity(), item.getSourcePath(),
                item.getTargetPath(), item.getTemporaryPath(), relativePath, sourceSnapshot);
        return Optional.of(new PreparedTransfer(resolved,
                new TransferPlan(coreRunId, plannedAt(snapshot), List.of(planItem))));
    }

    TransferResult retry(FileTransferRunEntity run,
                         PreparedTransfer prepared,
                         TransferFileSystemFactory sourceFactory,
                         TransferFileSystemFactory targetFactory) {
        TransferPlanItem planItem = prepared.plan().items().get(0);
        FileTransferRunItemEntity stored = findItem(run.getId(), planItem.itemId());
        Instant startedAt = Instant.now();
        int attempts = stored == null || stored.getAttempts() == null
                ? 1 : stored.getAttempts() + 1;
        String sourceChecksum = stored == null ? null : stored.getSourceChecksum();
        String targetChecksum = stored == null ? null : stored.getTargetChecksum();
        TransferItemResult itemResult;
        try (TransferFileSystem source = sourceFactory.open();
             TransferFileSystem target = targetFactory.open()) {
            TransferPolicy policy = prepared.resolvedSpec().policy();
            TransferVerifier verifier = new TransferVerifier();
            TransferVerificationPlan verificationPlan = verifier.plan(
                    prepared.plan(), planItem, policy);
            verifier.ensureSupported(source, target, verificationPlan);
            if (policy.sourceSuccessAction() == SourceSuccessAction.KEEP) {
                throw new IllegalStateException("KEEP has no source post-action to retry");
            }
            if (targetChecksum == null || !target.transferExists(planItem.targetPath())) {
                throw new IllegalStateException(
                        "Committed target cannot be verified for post-action retry");
            }
            String currentTargetChecksum = verifier.checksum(target, planItem.targetPath(),
                    verificationPlan, policy.checksumAlgorithm(), ignored -> { });
            if (!targetChecksum.equals(currentTargetChecksum)) {
                throw new IllegalStateException("Committed target changed before post-action retry");
            }
            boolean sourceExists = source.transferExists(planItem.sourcePath());
            if (sourceExists) {
                if (!SourceSnapshots.matches(
                        planItem.sourceSnapshot(), source.stat(planItem.sourcePath()))) {
                    throw new IllegalStateException("Source changed before post-action retry");
                }
                String currentSourceChecksum = verifier.checksum(source, planItem.sourcePath(),
                        verificationPlan, policy.checksumAlgorithm(), ignored -> { });
                if (sourceChecksum == null || !sourceChecksum.equals(currentSourceChecksum)
                        || !currentTargetChecksum.equals(currentSourceChecksum)) {
                    throw new IllegalStateException(
                            "Source or target checksum changed before post-action retry");
                }
                applyPostAction(source, planItem, policy, verificationPlan,
                        verifier, currentSourceChecksum);
            } else if (policy.sourceSuccessAction() == SourceSuccessAction.BACKUP) {
                verifyCompletedBackup(source, planItem, policy, verificationPlan,
                        verifier, currentTargetChecksum);
            }
            itemResult = new TransferItemResult(planItem.itemId(), TransferItemStatus.SUCCESS,
                    planItem.sourceSnapshot().size(), planItem.sourceSnapshot().size(),
                    0L, attempts, sourceChecksum, currentTargetChecksum,
                    null, null, startedAt, Instant.now());
        } catch (Exception exception) {
            itemResult = new TransferItemResult(planItem.itemId(),
                    TransferItemStatus.POST_ACTION_FAILED,
                    planItem.sourceSnapshot().size(), planItem.sourceSnapshot().size(),
                    0L, attempts, sourceChecksum, targetChecksum,
                    "POST_ACTION_FAILED", exception.getMessage(), startedAt, Instant.now());
        }
        TransferRunStatus status = itemResult.status() == TransferItemStatus.SUCCESS
                ? TransferRunStatus.SUCCESS : TransferRunStatus.PARTIAL_SUCCESS;
        return new TransferResult(prepared.plan().runId(), status,
                List.of(itemResult), 1L,
                itemResult.status() == TransferItemStatus.SUCCESS ? 1L : 0L,
                0L, itemResult.status() == TransferItemStatus.SUCCESS ? 0L : 1L,
                planItem.sourceSnapshot().size(), itemResult.transferredBytes(), 0L,
                startedAt, Instant.now());
    }

    private void applyPostAction(TransferFileSystem source, TransferPlanItem item,
                                 TransferPolicy policy,
                                 TransferVerificationPlan verificationPlan,
                                 TransferVerifier verifier,
                                 String checksum) throws Exception {
        if (policy.sourceSuccessAction() == SourceSuccessAction.DELETE) {
            source.delete(item.sourcePath());
            return;
        }
        String backupPath = TransferPaths.join(
                policy.sourceBackupRootPath(), item.relativePath());
        if (source.transferExists(backupPath)) {
            String backupChecksum = verifier.checksum(source, backupPath, verificationPlan,
                    policy.checksumAlgorithm(), ignored -> { });
            if (!checksum.equals(backupChecksum)) {
                throw new IllegalStateException(
                        "Source backup path contains different content");
            }
            source.delete(item.sourcePath());
            return;
        }
        source.move(item.sourcePath(), backupPath, false);
    }

    private void verifyCompletedBackup(TransferFileSystem source, TransferPlanItem item,
                                       TransferPolicy policy,
                                       TransferVerificationPlan verificationPlan,
                                       TransferVerifier verifier,
                                       String targetChecksum) throws Exception {
        String backupPath = TransferPaths.join(
                policy.sourceBackupRootPath(), item.relativePath());
        if (!source.transferExists(backupPath)
                || !targetChecksum.equals(verifier.checksum(source, backupPath, verificationPlan,
                        policy.checksumAlgorithm(), ignored -> { }))) {
            throw new IllegalStateException(
                    "Source backup cannot be verified for post-action retry");
        }
    }

    private FileTransferRunItemEntity findItem(Long runId, String coreItemId) {
        return itemMapper.selectOne(new LambdaQueryWrapper<FileTransferRunItemEntity>()
                .eq(FileTransferRunItemEntity::getRunId, runId)
                .eq(FileTransferRunItemEntity::getCoreItemId, coreItemId)
                .last("limit 1"));
    }

    private Instant plannedAt(Map<String, Object> snapshot) {
        Object value = snapshot == null ? null : snapshot.get("plannedAtMillis");
        if (value == null || String.valueOf(value).isBlank()) {
            return Instant.now();
        }
        long millis = value instanceof Number number
                ? number.longValue() : Long.parseLong(String.valueOf(value));
        return Instant.ofEpochMilli(millis);
    }
}
