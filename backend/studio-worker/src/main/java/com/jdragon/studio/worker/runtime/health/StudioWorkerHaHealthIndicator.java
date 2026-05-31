package com.jdragon.studio.worker.runtime.health;

import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.worker.runtime.runner.WorkerLifecycleRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class StudioWorkerHaHealthIndicator implements HealthIndicator {

    private final WorkerLifecycleRunner workerLifecycleRunner;
    private final RunLogStorageService runLogStorageService;

    public StudioWorkerHaHealthIndicator(WorkerLifecycleRunner workerLifecycleRunner,
                                         RunLogStorageService runLogStorageService) {
        this.workerLifecycleRunner = workerLifecycleRunner;
        this.runLogStorageService = runLogStorageService;
    }

    @Override
    public Health health() {
        if (!workerLifecycleRunner.isAcceptingTasks()) {
            return Health.down()
                    .withDetail("workerAcceptingTasks", false)
                    .build();
        }
        if (!runLogStorageService.objectStorageAvailable()) {
            return Health.down()
                    .withDetail("runLogStorage", runLogStorageService.storageType())
                    .withDetail("reason", "object storage is enabled but not fully configured")
                    .build();
        }
        return Health.up()
                .withDetail("workerAcceptingTasks", true)
                .withDetail("runLogStorage", runLogStorageService.storageType())
                .build();
    }
}
