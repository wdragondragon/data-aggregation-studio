package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.model.FileTransferEventMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
public class FileTransferOutboxCleanupService {

    private static final String CLEANUP_LOCK = "file-transfer-outbox-cleanup";
    private static final List<String> TERMINAL_STATUSES = List.of(
            "SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELED", "POST_ACTION_FAILED", "CONFLICT");

    private final JdbcTemplate jdbcTemplate;
    private final ClusterLockService clusterLockService;
    private final StudioPlatformProperties properties;
    private final MeterRegistry meterRegistry;
    private volatile LocalDateTime lastCleanupAt;
    private volatile long lastDeletedCount;
    private volatile String lastCleanupError;

    public FileTransferOutboxCleanupService(JdbcTemplate jdbcTemplate,
                                            ClusterLockService clusterLockService,
                                            StudioPlatformProperties properties,
                                            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.clusterLockService = clusterLockService;
        this.properties = properties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Scheduled(fixedDelayString = "${studio.file-transfer.cleanup-interval-millis:3600000}",
            scheduler = "fileTransferOutboxTaskScheduler")
    public void cleanup() {
        if (properties.getFileTransfer().getEventMode() != FileTransferEventMode.OUTBOX) {
            return;
        }
        long leaseSeconds = Math.max(60L,
                Math.min(3_600L, cleanupIntervalMillis() / 1_000L));
        if (!clusterLockService.tryAcquireNonReentrant(CLEANUP_LOCK, leaseSeconds)) {
            return;
        }
        try {
            long deleted = cleanupBatches();
            lastCleanupAt = LocalDateTime.now();
            lastDeletedCount = deleted;
            lastCleanupError = null;
            increment(deleted);
            if (deleted > 0L) {
                log.info("[FT_OUTBOX_CLEANUP_COMPLETED] 文件传输 Outbox 清理完成 deletedEvents={} "
                                + "retentionDays={} batchSize={}",
                        deleted, retentionDays(), cleanupBatchSize());
            }
        } catch (RuntimeException exception) {
            lastCleanupError = exception.getMessage();
            log.error("Failed to clean file transfer Outbox", exception);
        } finally {
            clusterLockService.release(CLEANUP_LOCK);
        }
    }

    long cleanupBatches() {
        long total = 0L;
        for (int batch = 0; batch < cleanupMaxBatches(); batch++) {
            List<Long> ids = candidateIds(cleanupBatchSize());
            if (ids.isEmpty()) {
                break;
            }
            total += physicalDelete(ids);
            if (ids.size() < cleanupBatchSize()) {
                break;
            }
        }
        return total;
    }

    private List<Long> candidateIds(int limit) {
        String terminalPlaceholders = String.join(",", TERMINAL_STATUSES.stream().map(ignored -> "?").toList());
        boolean sqlite = sqlite();
        String cutoffExpression = sqlite ? "datetime('now', ?)" : "date_sub(current_timestamp, interval ? day)";
        String activeExpression = sqlite ? "datetime('now', ?)" : "date_sub(current_timestamp, interval ? hour)";
        String sql = "select o.id from file_transfer_event_outbox o "
                + "left join file_transfer_run r on r.id=o.run_id "
                + "and r.tenant_id=o.tenant_id and r.project_id=o.project_id "
                + "where o.created_at < " + cutoffExpression + " "
                + "and (r.id is null or r.deleted=1 or r.status in (" + terminalPlaceholders + ")) "
                + "and not exists (select 1 from file_transfer_event_consumer_cursor c "
                + "where c.tenant_id=o.tenant_id and c.project_id=o.project_id "
                + "and c.last_seen_at >= " + activeExpression + " and c.last_event_id < o.id) "
                + "order by o.id limit " + Math.max(1, limit);
        Object[] arguments = new Object[TERMINAL_STATUSES.size() + 2];
        arguments[0] = sqlite ? "-" + retentionDays() + " days" : retentionDays();
        for (int index = 0; index < TERMINAL_STATUSES.size(); index++) {
            arguments[index + 1] = TERMINAL_STATUSES.get(index);
        }
        arguments[arguments.length - 1] = sqlite ? "-" + cursorStaleHours() + " hours" : cursorStaleHours();
        return jdbcTemplate.queryForList(sql, Long.class, arguments);
    }

    private boolean sqlite() {
        try {
            return jdbcTemplate.getDataSource() != null
                    && JdbcUtils.extractDatabaseMetaData(jdbcTemplate.getDataSource(),
                    metadata -> "SQLite".equalsIgnoreCase(metadata.getDatabaseProductName()));
        } catch (Exception exception) {
            return false;
        }
    }

    private int physicalDelete(List<Long> ids) {
        String placeholders = String.join(",", ids.stream().map(ignored -> "?").toList());
        return jdbcTemplate.update("delete from file_transfer_event_outbox where id in (" + placeholders + ")",
                ids.toArray());
    }

    private int retentionDays() {
        Integer value = properties.getFileTransfer().getEventRetentionDays();
        return value == null ? 7 : Math.max(1, value);
    }

    private int cursorStaleHours() {
        Integer value = properties.getFileTransfer().getCursorStaleHours();
        return value == null ? 48 : Math.max(1, value);
    }

    private int cleanupBatchSize() {
        Integer value = properties.getFileTransfer().getCleanupBatchSize();
        return value == null ? 1_000 : Math.max(1, value);
    }

    private int cleanupMaxBatches() {
        Integer value = properties.getFileTransfer().getCleanupMaxBatches();
        return value == null ? 20 : Math.max(1, value);
    }

    private long cleanupIntervalMillis() {
        Integer value = properties.getFileTransfer().getCleanupIntervalMillis();
        return value == null ? 3_600_000L : Math.max(1_000L, value.longValue());
    }

    private void increment(long deleted) {
        if (meterRegistry != null && deleted > 0L) {
            Counter.builder("studio.file-transfer.outbox.cleanup-deleted")
                    .tag("result", "success")
                    .register(meterRegistry)
                    .increment(deleted);
        }
    }

    public CleanupStatus status() {
        return new CleanupStatus(lastCleanupAt, lastDeletedCount, lastCleanupError);
    }

    public record CleanupStatus(LocalDateTime lastCleanupAt, long lastDeletedCount, String lastError) {
    }
}
