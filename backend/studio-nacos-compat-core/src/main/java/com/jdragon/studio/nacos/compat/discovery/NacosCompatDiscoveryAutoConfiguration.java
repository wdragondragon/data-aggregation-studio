package com.jdragon.studio.nacos.compat.discovery;

import com.jdragon.studio.nacos.compat.config.NacosConfigRefreshAutoConfiguration;
import com.jdragon.studio.nacos.compat.props.NacosCompatProperties;
import com.jdragon.studio.nacos.compat.props.NacosDiscoveryProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.client.discovery.composite.CompositeDiscoveryClient;
import org.springframework.cloud.client.discovery.composite.CompositeDiscoveryClientAutoConfiguration;
import org.springframework.cloud.client.discovery.composite.reactive.ReactiveCompositeDiscoveryClient;
import org.springframework.cloud.client.discovery.composite.reactive.ReactiveCompositeDiscoveryClientAutoConfiguration;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClientAutoConfiguration;
import org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryClientAutoConfiguration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.commons.util.InetUtilsProperties;
import org.springframework.cloud.commons.util.UtilAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Set;

@AutoConfiguration(after = {NacosConfigRefreshAutoConfiguration.class, UtilAutoConfiguration.class,
        CompositeDiscoveryClientAutoConfiguration.class, ReactiveCompositeDiscoveryClientAutoConfiguration.class,
        SimpleDiscoveryClientAutoConfiguration.class, SimpleReactiveDiscoveryClientAutoConfiguration.class})
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
    @Conditional(MissingNonFrameworkDiscoveryClientCondition.class)
    public NacosCompatDiscoveryClient nacosCompatDiscoveryClient(NacosDiscoveryAccessor accessor,
            NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties) {
        return new NacosCompatDiscoveryClient(accessor, rootProperties, discoveryProperties);
    }

    @Bean
    @ConditionalOnBean(NacosCompatDiscoveryClient.class)
    @Conditional(MissingNonFrameworkReactiveDiscoveryClientCondition.class)
    public ReactiveDiscoveryClient nacosCompatReactiveDiscoveryClient(NacosCompatDiscoveryClient discoveryClient) {
        return new NacosCompatReactiveDiscoveryClient(discoveryClient);
    }

    @Bean
    @ConditionalOnWebApplication
    public NacosRegistrationLifecycle nacosRegistrationLifecycle(ServiceRegistry<NacosRegistration> serviceRegistry,
            NacosDiscoveryProperties discoveryProperties, Environment environment, InetUtils inetUtils) {
        return new NacosRegistrationLifecycle(serviceRegistry, discoveryProperties, environment, inetUtils);
    }

    static final class MissingNonFrameworkDiscoveryClientCondition implements Condition {

        private static final Set<String> FRAMEWORK_BEAN_NAMES = Set.of("simpleDiscoveryClient",
                "compositeDiscoveryClient");

        private static final Set<String> FRAMEWORK_TYPES = Set.of(SimpleDiscoveryClient.class.getName(),
                CompositeDiscoveryClient.class.getName());

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !hasNonFrameworkBean(context, DiscoveryClient.class, FRAMEWORK_BEAN_NAMES, FRAMEWORK_TYPES);
        }
    }

    static final class MissingNonFrameworkReactiveDiscoveryClientCondition implements Condition {

        private static final Set<String> FRAMEWORK_BEAN_NAMES = Set.of("simpleReactiveDiscoveryClient",
                "reactiveCompositeDiscoveryClient");

        private static final Set<String> FRAMEWORK_TYPES = Set.of(SimpleReactiveDiscoveryClient.class.getName(),
                ReactiveCompositeDiscoveryClient.class.getName());

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !hasNonFrameworkBean(context, ReactiveDiscoveryClient.class, FRAMEWORK_BEAN_NAMES, FRAMEWORK_TYPES);
        }
    }

    private static boolean hasNonFrameworkBean(ConditionContext context, Class<?> beanType,
            Set<String> frameworkBeanNames, Set<String> frameworkTypes) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        if (beanFactory == null) {
            return false;
        }
        String[] beanNames = beanFactory.getBeanNamesForType(beanType, true, false);
        for (String beanName : beanNames) {
            if (isFrameworkDiscoveryBean(beanFactory, beanName, frameworkBeanNames, frameworkTypes)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isFrameworkDiscoveryBean(ConfigurableListableBeanFactory beanFactory, String beanName,
            Set<String> frameworkBeanNames, Set<String> frameworkTypes) {
        if (frameworkBeanNames.contains(beanName)) {
            return true;
        }
        Class<?> type = beanFactory.getType(beanName, false);
        return type != null && frameworkTypes.contains(type.getName());
    }

}
