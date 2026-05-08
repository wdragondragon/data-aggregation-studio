package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.service.StaleExecutionRecoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StaleExecutionRecoveryRunner {

    private final StaleExecutionRecoveryService staleExecutionRecoveryService;

    public StaleExecutionRecoveryRunner(StaleExecutionRecoveryService staleExecutionRecoveryService) {
        this.staleExecutionRecoveryService = staleExecutionRecoveryService;
    }

    @Scheduled(initialDelay = 60000L, fixedDelay = 60000L)
    public void recoverStaleExecutions() {
        try {
            staleExecutionRecoveryService.recoverAllStale();
        } catch (Exception e) {
            log.warn("Failed to recover stale executions", e);
        }
    }
}
