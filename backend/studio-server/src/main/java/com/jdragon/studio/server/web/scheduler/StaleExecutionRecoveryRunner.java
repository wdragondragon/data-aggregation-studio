package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.StaleExecutionRecoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StaleExecutionRecoveryRunner {

    private final StaleExecutionRecoveryService staleExecutionRecoveryService;
    private final ClusterLockService clusterLockService;

    public StaleExecutionRecoveryRunner(StaleExecutionRecoveryService staleExecutionRecoveryService,
                                        ClusterLockService clusterLockService) {
        this.staleExecutionRecoveryService = staleExecutionRecoveryService;
        this.clusterLockService = clusterLockService;
    }

    @Scheduled(initialDelay = 60000L, fixedDelay = 60000L)
    public void recoverStaleExecutions() {
        clusterLockService.runIfAcquired("scheduler:stale-execution-recovery", this::recoverStaleExecutionsLocked);
    }

    private void recoverStaleExecutionsLocked() {
        try {
            staleExecutionRecoveryService.recoverAllStale();
        } catch (Exception e) {
            log.warn("Failed to recover stale executions", e);
        }
    }
}
