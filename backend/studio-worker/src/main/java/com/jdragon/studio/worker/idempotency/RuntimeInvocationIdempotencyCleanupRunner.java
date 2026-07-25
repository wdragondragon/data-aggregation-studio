package com.jdragon.studio.worker.idempotency;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.ClusterLockService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;

/** Physically removes only expired COMPLETED guards; RUNNING/UNKNOWN are never auto-reopened. */
@Component
public class RuntimeInvocationIdempotencyCleanupRunner {
    private static final String LOCK_NAME = "runtime-invocation-idempotency-cleanup";

    private final JdbcTemplate jdbcTemplate;
    private final ClusterLockService clusterLockService;
    private final StudioPlatformProperties properties;

    public RuntimeInvocationIdempotencyCleanupRunner(JdbcTemplate jdbcTemplate,
                                                     ClusterLockService clusterLockService,
                                                     StudioPlatformProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.clusterLockService = clusterLockService;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${studio.runtime-invocation-idempotency.cleanup-initial-delay-millis:60000}",
            fixedDelayString = "${studio.runtime-invocation-idempotency.cleanup-delay-millis:86400000}")
    public void cleanup() {
        StudioPlatformProperties.RuntimeInvocationIdempotencyProperties config =
                properties.getRuntimeInvocationIdempotency();
        if (config == null || !config.isCleanupEnabled()) {
            return;
        }
        clusterLockService.runIfAcquired(LOCK_NAME, this::deleteExpiredCompleted);
    }

    int deleteExpiredCompleted() {
        StudioPlatformProperties.RuntimeInvocationIdempotencyProperties config =
                properties.getRuntimeInvocationIdempotency();
        int retentionDays = Math.max(1, config.getCompletedRetentionDays() == null
                ? 7 : config.getCompletedRetentionDays().intValue());
        int batchSize = Math.min(5000, Math.max(1, config.getCleanupBatchSize() == null
                ? 1000 : config.getCleanupBatchSize().intValue()));
        int maxBatches = Math.min(100, Math.max(1, config.getCleanupMaxBatches() == null
                ? 20 : config.getCleanupMaxBatches().intValue()));
        int runningUnknownAfterHours = Math.max(1, config.getRunningUnknownAfterHours() == null
                ? 24 : config.getRunningUnknownAfterHours().intValue());
        jdbcTemplate.update("update studio_runtime_idempotency set status='UNKNOWN'," +
                        "updated_at=?,version=coalesce(version,0)+1 where status='RUNNING' and updated_at<?",
                LocalDateTime.now(), LocalDateTime.now().minusHours(runningUnknownAfterHours));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<Long> ids = jdbcTemplate.query(
                    "select id from studio_runtime_idempotency " +
                            "where status=? and updated_at<? order by updated_at,id limit ?",
                    (result, row) -> Long.valueOf(result.getLong(1)),
                    RuntimeInvocationIdempotencyService.STATUS_COMPLETED, cutoff, Integer.valueOf(batchSize));
            if (ids.isEmpty()) {
                break;
            }
            int[][] deleted = jdbcTemplate.batchUpdate(
                    "delete from studio_runtime_idempotency where id=? and status='COMPLETED'",
                    ids, batchSize,
                    (PreparedStatement statement, Long id) -> statement.setLong(1, id.longValue()));
            for (int[] counts : deleted) {
                for (int count : counts) {
                    if (count > 0) {
                        total += count;
                    }
                }
            }
            if (ids.size() < batchSize) {
                break;
            }
        }
        return total;
    }
}
