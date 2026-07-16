package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.model.AlertSignal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AlertSignalPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final StudioPlatformProperties properties;

    public AlertSignalPublisher(ApplicationEventPublisher applicationEventPublisher,
                                StudioPlatformProperties properties) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.properties = properties;
    }

    public void publish(AlertSignal signal) {
        if (signal == null || properties.getAlert() == null || !properties.getAlert().isEnabled()) {
            return;
        }
        applicationEventPublisher.publishEvent(signal);
    }
}
