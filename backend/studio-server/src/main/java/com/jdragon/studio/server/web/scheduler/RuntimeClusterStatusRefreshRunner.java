package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.RuntimeClusterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RuntimeClusterStatusRefreshRunner {

    private final RuntimeClusterService runtimeClusterService;
    private final ClusterLockService clusterLockService;

    public RuntimeClusterStatusRefreshRunner(RuntimeClusterService runtimeClusterService,
                                             ClusterLockService clusterLockService) {
        this.runtimeClusterService = runtimeClusterService;
        this.clusterLockService = clusterLockService;
    }

    @Scheduled(initialDelay = 30000L,
            fixedDelayString = "${studio.runtime-cluster-status-refresh-delay-millis:10000}")
    public void refreshOfflineStatuses() {
        clusterLockService.runIfAcquired("scheduler:runtime-cluster-status-refresh", () -> {
            try {
                runtimeClusterService.refreshOfflineStatuses();
            } catch (Exception ex) {
                log.warn("Failed to refresh runtime cluster status", ex);
            }
        });
    }
}
