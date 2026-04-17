package com.jdragon.studio.infra.runner;

import com.jdragon.studio.infra.service.StudioSchemaUpgradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "studio.schema.auto-upgrade-on-startup", havingValue = "true", matchIfMissing = true)
public class StudioSchemaAutoUpgradeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StudioSchemaAutoUpgradeRunner.class);

    private final StudioSchemaUpgradeService studioSchemaUpgradeService;

    public StudioSchemaAutoUpgradeRunner(StudioSchemaUpgradeService studioSchemaUpgradeService) {
        this.studioSchemaUpgradeService = studioSchemaUpgradeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Running studio schema auto-upgrade before serving requests.");
        studioSchemaUpgradeService.upgrade();
    }
}
