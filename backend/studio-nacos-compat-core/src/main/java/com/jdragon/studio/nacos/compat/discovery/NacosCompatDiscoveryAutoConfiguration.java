package com.jdragon.studio.nacos.compat.discovery;

import com.jdragon.studio.nacos.compat.config.NacosConfigRefreshAutoConfiguration;
import com.jdragon.studio.nacos.compat.props.NacosCompatProperties;
import com.jdragon.studio.nacos.compat.props.NacosDiscoveryProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.commons.util.InetUtilsProperties;
import org.springframework.cloud.commons.util.UtilAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration(after = {NacosConfigRefreshAutoConfiguration.class, UtilAutoConfiguration.class})
@EnableConfigurationProperties({NacosCompatProperties.class, NacosRootProperties.class, NacosDiscoveryProperties.class})
@ConditionalOnProperty(prefix = "spring.cloud.nacos.discovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NacosCompatDiscoveryAutoConfiguration {

    @Bean
    public NacosDiscoveryAccessor nacosDiscoveryAccessor(com.jdragon.studio.nacos.compat.http.NacosHttpClient httpClient,
            com.jdragon.studio.nacos.compat.http.NacosLegacyAuthService legacyAuthService,
            com.jdragon.studio.nacos.compat.support.NacosServerProbeService probeService,
            com.jdragon.studio.nacos.compat.support.NacosClientManager clientManager,
            NacosCompatProperties compatProperties) {
        return new NacosDiscoveryAccessor(httpClient, legacyAuthService, probeService, clientManager, compatProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public InetUtils nacosCompatInetUtils(ObjectProvider<InetUtilsProperties> propertiesProvider) {
        InetUtilsProperties properties = propertiesProvider.orderedStream()
                .findFirst()
                .orElseGet(InetUtilsProperties::new);
        return new InetUtils(properties);
    }

    @Bean
    @ConditionalOnMissingBean(ServiceRegistry.class)
    public ServiceRegistry<NacosRegistration> nacosCompatServiceRegistry(NacosDiscoveryAccessor accessor,
            NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties,
            NacosCompatProperties compatProperties) {
        return new NacosCompatServiceRegistry(accessor, rootProperties, discoveryProperties, compatProperties);
    }

    @Bean
    @ConditionalOnMissingBean(DiscoveryClient.class)
    public NacosCompatDiscoveryClient nacosCompatDiscoveryClient(NacosDiscoveryAccessor accessor,
            NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties) {
        return new NacosCompatDiscoveryClient(accessor, rootProperties, discoveryProperties);
    }

    @Bean
    @ConditionalOnBean(NacosCompatDiscoveryClient.class)
    @ConditionalOnMissingBean(ReactiveDiscoveryClient.class)
    public ReactiveDiscoveryClient nacosCompatReactiveDiscoveryClient(NacosCompatDiscoveryClient discoveryClient) {
        return new NacosCompatReactiveDiscoveryClient(discoveryClient);
    }

    @Bean
    @ConditionalOnWebApplication
    public NacosRegistrationLifecycle nacosRegistrationLifecycle(ServiceRegistry<NacosRegistration> serviceRegistry,
            NacosDiscoveryProperties discoveryProperties, Environment environment, InetUtils inetUtils) {
        return new NacosRegistrationLifecycle(serviceRegistry, discoveryProperties, environment, inetUtils);
    }

}
