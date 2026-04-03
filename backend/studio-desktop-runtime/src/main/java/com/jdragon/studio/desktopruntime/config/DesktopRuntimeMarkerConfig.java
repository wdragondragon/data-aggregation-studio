package com.jdragon.studio.desktopruntime.config;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
public class DesktopRuntimeMarkerConfig {

    private final StudioPlatformProperties properties;

    public DesktopRuntimeMarkerConfig(StudioPlatformProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void markDesktopRuntime() {
        properties.setDesktopRuntime(true);
    }
}
