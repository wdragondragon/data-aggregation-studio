package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.StreamingTaskCoordinatorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StreamingTaskScheduleRunner {

    private final StreamingTaskCoordinatorService coordinatorService;
    private final ClusterLockService clusterLockService;

    public StreamingTaskScheduleRunner(StreamingTaskCoordinatorService coordinatorService,
                                       ClusterLockService clusterLockService) {
        this.coordinatorService = coordinatorService;
        this.clusterLockService = clusterLockService;
    }

    @Scheduled(initialDelay = 5000L, fixedDelayString = "${studio.worker.streaming.coordinator-delay-ms:3000}")
    public void reconcileStreamingTasks() {
        clusterLockService.runIfAcquired("scheduler:native-streaming", this::reconcileLocked);
    }

    private void reconcileLocked() {
        try {
            coordinatorService.reconcileDeployments();
        } catch (RuntimeException failure) {
            log.warn("Native streaming deployment reconciliation failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
