package com.jdragon.studio.nacos.compat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class NacosConfigRefreshManager {

    private final NacosConfigAccessor accessor;

    private final ContextRefresher contextRefresher;

    private final RefreshScope refreshScope;

    private final ApplicationEventPublisher applicationEventPublisher;

    private final ConfigurableEnvironment environment;

    private final List<Closeable> subscriptions = new ArrayList<>();

    public NacosConfigRefreshManager(NacosConfigAccessor accessor, ContextRefresher contextRefresher,
            RefreshScope refreshScope, ApplicationEventPublisher applicationEventPublisher,
            ConfigurableEnvironment environment) {
        this.accessor = accessor;
        this.contextRefresher = contextRefresher;
        this.refreshScope = refreshScope;
        this.applicationEventPublisher = applicationEventPublisher;
        this.environment = environment;
    }

    @PostConstruct
    public void start() {
        int subscriptionCount = 0;
        for (PropertySource<?> propertySource : this.environment.getPropertySources()) {
            if (propertySource instanceof NacosCompatConfigPropertySource nacosPropertySource
                    && nacosPropertySource.getResource().isRefreshEnabled()) {
                this.subscriptions.add(this.accessor.watchConfig(nacosPropertySource.getResource(),
                        content -> refreshPropertySource(nacosPropertySource.getName(), nacosPropertySource.getResource(), content)));
                subscriptionCount++;
            }
        }
        log.info("Nacos config refresh subscriptions started: {}", subscriptionCount);
    }

    private void refreshPropertySource(String propertySourceName, NacosConfigDataResource resource, String content) {
        try {
            NacosCompatConfigPropertySource replacement = new NacosCompatConfigPropertySource(propertySourceName,
                    NacosConfigContentSupport.load(propertySourceName, resource.getFileExtension(), content), content, resource);
            MutablePropertySources propertySources = this.environment.getPropertySources();
            propertySources.replace(propertySourceName, replacement);
            triggerRefresh(replacement);
            log.info("Refreshed nacos config dataId={}, group={}", resource.getDataId(), resource.getGroup());
        }
        catch (Exception ex) {
            log.error("Refresh nacos config failed for dataId={}, group={}", resource.getDataId(), resource.getGroup(), ex);
        }
    }

    private void triggerRefresh(NacosCompatConfigPropertySource replacement) {
        if (this.contextRefresher != null) {
            this.contextRefresher.refresh();
            return;
        }
        if (this.applicationEventPublisher != null) {
            this.applicationEventPublisher.publishEvent(
                    new EnvironmentChangeEvent(replacement.getSource().keySet()));
        }
        if (this.refreshScope != null) {
            this.refreshScope.refreshAll();
            return;
        }
        log.warn("Nacos config changed but no refresh mechanism is available for propertySource={}", replacement.getName());
    }

    @PreDestroy
    public void destroy() {
        for (Closeable subscription : this.subscriptions) {
            try {
                subscription.close();
            }
            catch (IOException ex) {
                log.warn("Close nacos config subscription failed: {}", ex.getMessage());
            }
        }
        this.subscriptions.clear();
    }

}
