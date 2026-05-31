package com.jdragon.studio.server.web.health;

import com.jdragon.studio.infra.service.RunLogStorageService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class StudioServerHaHealthIndicator implements HealthIndicator {

    private final RunLogStorageService runLogStorageService;

    public StudioServerHaHealthIndicator(RunLogStorageService runLogStorageService) {
        this.runLogStorageService = runLogStorageService;
    }

    @Override
    public Health health() {
        if (!runLogStorageService.objectStorageAvailable()) {
            return Health.down()
                    .withDetail("runLogStorage", runLogStorageService.storageType())
                    .withDetail("reason", "object storage is enabled but not fully configured")
                    .build();
        }
        return Health.up()
                .withDetail("runLogStorage", runLogStorageService.storageType())
                .build();
    }
}
