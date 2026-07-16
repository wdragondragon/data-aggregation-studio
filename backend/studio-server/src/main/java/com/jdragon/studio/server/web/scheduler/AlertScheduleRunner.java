package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.service.AlertDeliveryService;
import com.jdragon.studio.infra.service.AlertEvaluationService;
import com.jdragon.studio.infra.service.ClusterLockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AlertScheduleRunner {

    private final ClusterLockService clusterLockService;
    private final AlertEvaluationService alertEvaluationService;
    private final AlertDeliveryService alertDeliveryService;

    public AlertScheduleRunner(ClusterLockService clusterLockService,
                               AlertEvaluationService alertEvaluationService,
                               AlertDeliveryService alertDeliveryService) {
        this.clusterLockService = clusterLockService;
        this.alertEvaluationService = alertEvaluationService;
        this.alertDeliveryService = alertDeliveryService;
    }

    @Scheduled(initialDelay = 30000L, fixedDelayString = "${studio.alert.evaluation-delay-millis:30000}")
    public void evaluateRules() {
        clusterLockService.executeIfAcquiredNonReentrant("scheduler:alert-evaluation", 120L, true,
                () -> {
                    try {
                        alertEvaluationService.evaluateAll();
                    } catch (Exception ex) {
                        log.warn("Alert evaluation scheduler failed", ex);
                    }
                    return Boolean.TRUE;
                }, () -> Boolean.FALSE);
    }

    @Scheduled(initialDelay = 10000L, fixedDelayString = "${studio.alert.delivery-delay-millis:5000}")
    public void dispatchDeliveries() {
        clusterLockService.executeIfAcquiredNonReentrant("scheduler:alert-delivery", 120L, true,
                () -> {
                    try {
                        alertDeliveryService.dispatchDue();
                    } catch (Exception ex) {
                        log.warn("Alert delivery scheduler failed", ex);
                    }
                    return Boolean.TRUE;
                }, () -> Boolean.FALSE);
    }

    @Scheduled(cron = "${studio.alert.cleanup-cron:0 25 3 * * *}")
    public void cleanupHistory() {
        clusterLockService.executeIfAcquiredNonReentrant("scheduler:alert-cleanup", 600L, true,
                () -> {
                    try {
                        alertDeliveryService.cleanup();
                    } catch (Exception ex) {
                        log.warn("Alert history cleanup failed", ex);
                    }
                    return Boolean.TRUE;
                }, () -> Boolean.FALSE);
    }
}
