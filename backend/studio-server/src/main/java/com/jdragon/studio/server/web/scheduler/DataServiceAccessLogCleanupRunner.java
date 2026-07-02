package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.service.DataServiceMetricsService;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.DataIngestionMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DataServiceAccessLogCleanupRunner {

    private static final Logger log = LoggerFactory.getLogger(DataServiceAccessLogCleanupRunner.class);
    private static final int RETENTION_DAYS = 90;

    private final DataServiceMetricsService dataServiceMetricsService;
    private final DataIngestionMetricsService dataIngestionMetricsService;
    private final ClusterLockService clusterLockService;

    public DataServiceAccessLogCleanupRunner(DataServiceMetricsService dataServiceMetricsService,
                                             DataIngestionMetricsService dataIngestionMetricsService,
                                             ClusterLockService clusterLockService) {
        this.dataServiceMetricsService = dataServiceMetricsService;
        this.dataIngestionMetricsService = dataIngestionMetricsService;
        this.clusterLockService = clusterLockService;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredAccessLogs() {
        clusterLockService.runIfAcquired("scheduler:data-service-access-log-cleanup", this::cleanupExpiredAccessLogsLocked);
    }

    private void cleanupExpiredAccessLogsLocked() {
        try {
            int deleted = dataServiceMetricsService.purgeExpiredAccessLogs(RETENTION_DAYS);
            if (deleted > 0) {
                log.info("Purged {} expired data service access logs", deleted);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to purge expired data service access logs", ex);
        }
        try {
            int deletedIngestionLogs = dataIngestionMetricsService.purgeExpiredAccessLogs(RETENTION_DAYS);
            if (deletedIngestionLogs > 0) {
                log.info("Purged {} expired data ingestion access logs", deletedIngestionLogs);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to purge expired data ingestion access logs", ex);
        }
    }
}
