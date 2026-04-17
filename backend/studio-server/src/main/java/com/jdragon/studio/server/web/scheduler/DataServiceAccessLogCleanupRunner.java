package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.service.DataServiceMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DataServiceAccessLogCleanupRunner {

    private static final Logger log = LoggerFactory.getLogger(DataServiceAccessLogCleanupRunner.class);
    private static final int RETENTION_DAYS = 90;

    private final DataServiceMetricsService dataServiceMetricsService;

    public DataServiceAccessLogCleanupRunner(DataServiceMetricsService dataServiceMetricsService) {
        this.dataServiceMetricsService = dataServiceMetricsService;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredAccessLogs() {
        try {
            int deleted = dataServiceMetricsService.purgeExpiredAccessLogs(RETENTION_DAYS);
            if (deleted > 0) {
                log.info("Purged {} expired data service access logs", deleted);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to purge expired data service access logs", ex);
        }
    }
}
