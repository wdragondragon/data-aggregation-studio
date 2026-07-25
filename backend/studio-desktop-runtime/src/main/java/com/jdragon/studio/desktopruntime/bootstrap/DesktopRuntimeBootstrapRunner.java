package com.jdragon.studio.desktopruntime.bootstrap;

import com.jdragon.studio.infra.service.StudioInitializationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "studio.desktop-runtime", havingValue = "true")
public class DesktopRuntimeBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DesktopRuntimeBootstrapRunner.class);

    private final StudioInitializationService studioInitializationService;
    private final DesktopRuntimeClusterBootstrapService runtimeClusterBootstrapService;

    public DesktopRuntimeBootstrapRunner(StudioInitializationService studioInitializationService,
                                         DesktopRuntimeClusterBootstrapService runtimeClusterBootstrapService) {
        this.studioInitializationService = studioInitializationService;
        this.runtimeClusterBootstrapService = runtimeClusterBootstrapService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Ensuring desktop runtime bootstrap data before serving requests.");
        studioInitializationService.initialize(false);
        DesktopRuntimeClusterBootstrapService.BootstrapResult result =
                runtimeClusterBootstrapService.ensureSingleClusterRuntime();
        log.info("Desktop runtime execution plane is ready: {}", result);
    }
}
