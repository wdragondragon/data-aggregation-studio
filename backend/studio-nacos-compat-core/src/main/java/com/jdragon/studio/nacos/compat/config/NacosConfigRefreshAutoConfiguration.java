package com.jdragon.studio.nacos.compat.config;

import com.jdragon.studio.nacos.compat.http.NacosHttpClient;
import com.jdragon.studio.nacos.compat.http.NacosLegacyAuthService;
import com.jdragon.studio.nacos.compat.props.NacosCompatProperties;
import com.jdragon.studio.nacos.compat.props.NacosConfigProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import com.jdragon.studio.nacos.compat.support.NacosClientManager;
import com.jdragon.studio.nacos.compat.support.NacosServerProbeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties({NacosCompatProperties.class, NacosRootProperties.class, NacosConfigProperties.class})
public class NacosConfigRefreshAutoConfiguration {

    @Bean
    public NacosHttpClient nacosCompatHttpClient(NacosCompatProperties compatProperties) {
        return new NacosHttpClient(compatProperties.getProbeTimeout());
    }

    @Bean
    public NacosLegacyAuthService nacosLegacyAuthService(NacosHttpClient httpClient, NacosCompatProperties compatProperties) {
        return new NacosLegacyAuthService(httpClient, compatProperties.getConfigReadTimeout());
    }

    @Bean
    public NacosServerProbeService nacosServerProbeService(NacosHttpClient httpClient, NacosCompatProperties compatProperties) {
        return new NacosServerProbeService(httpClient, compatProperties);
    }

    @Bean
    public NacosClientManager nacosClientManager() {
        return new NacosClientManager();
    }

    @Bean
    public NacosConfigAccessor nacosConfigAccessor(NacosHttpClient httpClient, NacosLegacyAuthService legacyAuthService,
            NacosServerProbeService probeService, NacosClientManager clientManager) {
        return new NacosConfigAccessor(httpClient, legacyAuthService, probeService, clientManager);
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.cloud.nacos.config", name = "enabled", havingValue = "true", matchIfMissing = true)
    public NacosConfigRefreshManager nacosConfigRefreshManager(NacosConfigAccessor accessor,
            ObjectProvider<ContextRefresher> contextRefresherProvider,
            ObjectProvider<RefreshScope> refreshScopeProvider,
            ApplicationEventPublisher applicationEventPublisher,
            org.springframework.core.env.ConfigurableEnvironment environment) {
        return new NacosConfigRefreshManager(accessor, contextRefresherProvider.getIfAvailable(),
                refreshScopeProvider.getIfAvailable(), applicationEventPublisher, environment);
    }

}
