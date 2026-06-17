package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.DataSourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DatasourceHealthScheduleRunner {

    private final ClusterLockService clusterLockService;
    private final DataSourceService dataSourceService;

    public DatasourceHealthScheduleRunner(ClusterLockService clusterLockService,
                                          DataSourceService dataSourceService) {
        this.clusterLockService = clusterLockService;
        this.dataSourceService = dataSourceService;
    }

    @Scheduled(initialDelay = 60000L, fixedDelayString = "${studio.datasource-health.scheduled.interval-delay-ms:60000}")
    public void refreshDatasourceHealth() {
        clusterLockService.runIfAcquired("scheduler:datasource-health", new Runnable() {
            @Override
            public void run() {
                try {
                    dataSourceService.dispatchDueScheduledConnectionTests();
                } catch (Exception e) {
                    log.warn("Datasource health scheduler failed", e);
                }
            }
        });
    }
}
