package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Bounded retention for native streaming evidence. It is opt-in so local test
 * data and the retained Kafka compatibility runs are never removed implicitly.
 */
@Slf4j
public class StreamingHistoryCleanupService {

    private static final String CLEANUP_LOCK = "studio-native-streaming-history-cleanup";
    private static final List<String> ACTIVE_ATTEMPT_STATUSES = List.of(
            "QUEUED", "STARTING", "RUNNING", "STOPPING", "RECOVERING");

    private final JdbcTemplate jdbcTemplate;
    private final ClusterLockService clusterLockService;
    private final StudioPlatformProperties properties;
    private final RunLogObjectStore runLogObjectStore;
    private final MeterRegistry meterRegistry;
    private volatile LocalDateTime lastCleanupAt;
    private volatile long lastDeletedCount;
    private volatile String lastCleanupError;

    public StreamingHistoryCleanupService(JdbcTemplate jdbcTemplate,
                                          ClusterLockService clusterLockService,
                                          StudioPlatformProperties properties,
                                          RunLogObjectStore runLogObjectStore,
                                          ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.clusterLockService = clusterLockService;
        this.properties = properties;
        this.runLogObjectStore = runLogObjectStore;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Scheduled(fixedDelayString = "${studio.streaming-history.cleanup-interval-millis:3600000}",
            scheduler = "fileTransferOutboxTaskScheduler")
    public void cleanup() {
        if (!cleanupEnabled()) {
            return;
        }
        long leaseSeconds = Math.max(60L, Math.min(3_600L, cleanupIntervalMillis() / 1_000L));
        if (!clusterLockService.tryAcquireNonReentrant(CLEANUP_LOCK, leaseSeconds)) {
            return;
        }
        try {
            long deleted = cleanupBatches();
            lastCleanupAt = LocalDateTime.now();
            lastDeletedCount = deleted;
            lastCleanupError = null;
            if (deleted > 0) {
                increment(deleted);
                log.info("[STREAMING_HISTORY_CLEANUP_COMPLETED] Native streaming history cleanup completed "
                                + "deletedRows={} retentionDays={} batchSize={}",
                        deleted, retentionDays(), cleanupBatchSize());
            }
        } catch (RuntimeException exception) {
            lastCleanupError = exception.getMessage();
            log.error("Failed to clean native streaming history", exception);
        } finally {
            clusterLockService.release(CLEANUP_LOCK);
        }
    }

    /** Runs at most cleanupMaxBatches * cleanupBatchSize rows per invocation. */
    long cleanupBatches() {
        long total = 0L;
        for (int batch = 0; batch < cleanupMaxBatches(); batch++) {
            long deleted = cleanupOneBatch("stream_metric_bucket", "bucket_start");
            deleted += cleanupOneBatch("stream_task_event", "occurred_at");
            deleted += cleanupLogChunkBatch();
            deleted += cleanupAttemptBatch();
            deleted += cleanupRunBatch();
            total += deleted;
            if (deleted < cleanupBatchSize()) {
                break;
            }
        }
        return total;
    }

    private long cleanupOneBatch(String table, String timestampColumn) {
        String cutoff = cutoffExpression(timestampColumn);
        String placeholders = String.join(",", ACTIVE_ATTEMPT_STATUSES.stream().map(value -> "?").toList());
        String activeDeploymentGuard = "stream_task_event".equals(table)
                ? " and not exists (select 1 from stream_task_deploy d where d.id=t.deployment_id "
                + "and d.desired_state='RUNNING')"
                : "";
        String sql = "select t.id from " + table + " t where t." + timestampColumn + " < " + cutoff
                + " and not exists (select 1 from stream_task_attempt a where a.id=t.attempt_id"
                + " and a.status in (" + placeholders + "))" + activeDeploymentGuard
                + " order by t.id limit " + cleanupBatchSize();
        Object[] arguments = cleanupArguments();
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, arguments);
        if (ids.isEmpty()) {
            return 0L;
        }
        return jdbcTemplate.update("delete from " + table + " where id in ("
                + String.join(",", ids.stream().map(value -> "?").toList()) + ")", ids.toArray());
    }

    private long cleanupAttemptBatch() {
        String cutoff = cutoffExpression("ended_at");
        String placeholders = String.join(",", ACTIVE_ATTEMPT_STATUSES.stream().map(value -> "?").toList());
        String sql = "select a.id from stream_task_attempt a where a.ended_at is not null and a.ended_at < " + cutoff
                + " and a.status not in (" + placeholders + ")"
                + " and not exists (select 1 from stream_task_deploy d where d.current_attempt_id=a.id"
                + " and (d.desired_state='RUNNING' or d.observed_state in ('STARTING','RUNNING','STOPPING','RECOVERING')))"
                + " and not exists (select 1 from run_log_chunk l where l.stream_attempt_id=a.id)"
                + " order by a.id limit " + cleanupBatchSize();
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, cleanupArguments());
        if (ids.isEmpty()) {
            return 0L;
        }
        return jdbcTemplate.update("delete from stream_task_attempt where id in ("
                + String.join(",", ids.stream().map(value -> "?").toList()) + ")", ids.toArray());
    }

    private long cleanupRunBatch() {
        String cutoff = cutoffExpression("stopped_at");
        String sql = "select r.id from stream_task_run r where r.stopped_at is not null and r.stopped_at < " + cutoff
                + " and r.status in ('STOPPED','FAILED','CANCELLED','INTERRUPTED')"
                + " and not exists (select 1 from stream_task_deploy d where d.current_run_id=r.id"
                + " and (d.desired_state='RUNNING' or d.observed_state in ('STARTING','RUNNING','STOPPING','RECOVERING')))"
                + " and not exists (select 1 from stream_task_attempt a where a.run_id=r.id)"
                + " order by r.id limit " + cleanupBatchSize();
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, new Object[]{cutoffArgument()});
        if (ids.isEmpty()) {
            return 0L;
        }
        return jdbcTemplate.update("delete from stream_task_run where id in ("
                + String.join(",", ids.stream().map(value -> "?").toList()) + ")", ids.toArray());
    }

    private long cleanupLogChunkBatch() {
        String cutoff = cutoffExpression("chunk_started_at");
        String placeholders = String.join(",", ACTIVE_ATTEMPT_STATUSES.stream().map(value -> "?").toList());
        String sql = "select t.id, t.object_bucket, t.object_key from run_log_chunk t where t.chunk_started_at < " + cutoff
                + " and not exists (select 1 from stream_task_attempt a where a.id=t.stream_attempt_id"
                + " and a.status in (" + placeholders + ")) order by t.id limit " + cleanupBatchSize();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, cleanupArguments());
        if (rows.isEmpty()) {
            return 0L;
        }
        long deleted = 0L;
        for (Map<String, Object> row : rows) {
            String bucket = text(row.get("object_bucket"));
            String objectKey = text(row.get("object_key"));
            if (bucket != null && objectKey != null) {
                try {
                    runLogObjectStore.delete(bucket, objectKey);
                } catch (RuntimeException failure) {
                    log.warn("[STREAMING_HISTORY_CLEANUP_OBJECT_RETRY] Retaining log chunk because object deletion failed "
                            + "bucket={} objectKey={}", bucket, objectKey, failure);
                    continue;
                }
            }
            Object id = row.get("id");
            deleted += jdbcTemplate.update("delete from run_log_chunk where id=?", id);
        }
        return deleted;
    }

    private String cutoffExpression(String ignoredColumn) {
        return sqlite() ? "datetime('now', ?)" : "date_sub(current_timestamp, interval ? day)";
    }

    private Object cutoffArgument() {
        return sqlite() ? "-" + retentionDays() + " days" : retentionDays();
    }

    private Object[] cleanupArguments() {
        Object[] arguments = new Object[1 + ACTIVE_ATTEMPT_STATUSES.size()];
        arguments[0] = cutoffArgument();
        for (int index = 0; index < ACTIVE_ATTEMPT_STATUSES.size(); index++) {
            arguments[index + 1] = ACTIVE_ATTEMPT_STATUSES.get(index);
        }
        return arguments;
    }

    private boolean sqlite() {
        try {
            return jdbcTemplate.getDataSource() != null
                    && JdbcUtils.extractDatabaseMetaData(jdbcTemplate.getDataSource(), metadata ->
                    "SQLite".equalsIgnoreCase(metadata.getDatabaseProductName()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean cleanupEnabled() {
        return properties.getStreamingHistory() != null && properties.getStreamingHistory().isCleanupEnabled();
    }

    private int retentionDays() {
        Integer value = properties.getStreamingHistory().getRetentionDays();
        return value == null ? 30 : Math.max(1, value);
    }

    private int cleanupBatchSize() {
        Integer value = properties.getStreamingHistory().getCleanupBatchSize();
        return value == null ? 1_000 : Math.max(1, value);
    }

    private int cleanupMaxBatches() {
        Integer value = properties.getStreamingHistory().getCleanupMaxBatches();
        return value == null ? 20 : Math.max(1, value);
    }

    private long cleanupIntervalMillis() {
        Integer value = properties.getStreamingHistory().getCleanupIntervalMillis();
        return value == null ? 3_600_000L : Math.max(1_000L, value.longValue());
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private void increment(long deleted) {
        if (meterRegistry != null && deleted > 0) {
            Counter.builder("studio.streaming.history.cleanup-deleted")
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
