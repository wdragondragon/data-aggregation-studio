package com.jdragon.studio.worker.filetransfer;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.aggregation.transfer.PreparedTransfer;
import com.jdragon.aggregation.transfer.model.TransferItemResult;
import com.jdragon.aggregation.transfer.model.TransferItemStatus;
import com.jdragon.aggregation.transfer.model.TransferPlanItem;
import com.jdragon.aggregation.transfer.model.TransferResult;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.service.FileTransferStateMutationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

final class FileTransferResultPersistenceService {

    private static final Logger log =
            LoggerFactory.getLogger(FileTransferResultPersistenceService.class);

    private final FileTransferStateMutationService mutationService;

    FileTransferResultPersistenceService(FileTransferStateMutationService mutationService) {
        this.mutationService = mutationService;
    }

    void persistResults(FileTransferRunEntity run, PreparedTransfer prepared,
                        TransferResult result) {
        Map<String, TransferPlanItem> plans = new LinkedHashMap<String, TransferPlanItem>();
        for (TransferPlanItem plan : prepared.plan().items()) {
            plans.put(plan.itemId(), plan);
        }
        for (TransferItemResult value : result.items()) {
            TransferPlanItem plan = plans.get(value.itemId());
            LambdaUpdateWrapper<FileTransferRunItemEntity> update =
                    new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                            .set(FileTransferRunItemEntity::getStatus, value.status().name())
                            .set(FileTransferRunItemEntity::getTransferredBytes,
                                    value.transferredBytes())
                            .set(FileTransferRunItemEntity::getResumedBytes, value.resumedBytes())
                            .set(FileTransferRunItemEntity::getCurrentBytesPerSecond, 0L)
                            .set(FileTransferRunItemEntity::getSourceChecksum,
                                    value.sourceChecksum())
                            .set(FileTransferRunItemEntity::getTargetChecksum,
                                    value.targetChecksum())
                            .set(FileTransferRunItemEntity::getAttempts, value.attempts())
                            .set(FileTransferRunItemEntity::getErrorCode, value.errorCode())
                            .set(FileTransferRunItemEntity::getErrorMessage, value.errorMessage())
                            .set(FileTransferRunItemEntity::getStartedAt, local(value.startedAt()))
                            .set(FileTransferRunItemEntity::getEndedAt, local(value.finishedAt()))
                            .set(FileTransferRunItemEntity::getPostActionStatus,
                                    value.status() == TransferItemStatus.POST_ACTION_FAILED
                                            ? "FAILED" : "SUCCESS")
                            .eq(FileTransferRunItemEntity::getRunId, run.getId())
                            .eq(FileTransferRunItemEntity::getCoreItemId, value.itemId());
            if (plan != null) {
                update.set(FileTransferRunItemEntity::getFileSize,
                        plan.sourceSnapshot().size());
            }
            update.set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now());
            mutationService().updateItemAndEvent(
                    run.getId(), value.itemId(), update, false, true);
            if (value.status() == TransferItemStatus.FAILED) {
                log.error("[FT_ITEM_FAILED] 文件传输项重试耗尽后失败 runId={} runRecordId={} "
                                + "itemId={} attempts={} errorCode={} message={} transferredBytes={} "
                                + "sourcePath={} targetPath={}",
                        run.getId(), run.getRunRecordId(), value.itemId(), value.attempts(),
                        value.errorCode(), value.errorMessage(), value.transferredBytes(),
                        plan == null ? null : plan.sourcePath(),
                        plan == null ? null : plan.targetPath());
            }
        }
    }

    private LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private FileTransferStateMutationService mutationService() {
        if (mutationService == null) {
            throw new IllegalStateException("File transfer state mutation service is required");
        }
        return mutationService;
    }
}
