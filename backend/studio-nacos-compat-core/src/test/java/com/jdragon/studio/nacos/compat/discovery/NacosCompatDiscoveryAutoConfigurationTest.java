package com.jdragon.studio.nacos.compat.discovery;

import com.jdragon.studio.nacos.compat.config.NacosConfigRefreshAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NacosCompatDiscoveryAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NacosConfigRefreshAutoConfiguration.class,
                    NacosCompatDiscoveryAutoConfiguration.class))
            .withPropertyValues("spring.cloud.nacos.config.enabled=false");

    @Test
    void shouldNotCreateCompatDiscoveryClientWhenAnotherDiscoveryClientExists() {
        this.contextRunner.withUserConfiguration(CustomDiscoveryClientConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(NacosCompatDiscoveryClient.class);
                    assertThat(context).hasSingleBean(DiscoveryClient.class);
                });
    }

    @Test
    void shouldNotCreateCompatReactiveDiscoveryClientWhenAnotherReactiveDiscoveryClientExists() {
        this.contextRunner.withUserConfiguration(CustomReactiveDiscoveryClientConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(NacosCompatReactiveDiscoveryClient.class);
                    assertThat(context).hasSingleBean(ReactiveDiscoveryClient.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomDiscoveryClientConfiguration {

        @Bean
        DiscoveryClient customDiscoveryClient() {
            return new DiscoveryClient() {
                @Override
                public String description() {
                    return "custom";
                }

                @Override
                public List<ServiceInstance> getInstances(String serviceId) {
                    return List.of();
                }

                @Override
                public List<String> getServices() {
                    return List.of();
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomReactiveDiscoveryClientConfiguration {

        @Bean
        ReactiveDiscoveryClient customReactiveDiscoveryClient() {
            return new ReactiveDiscoveryClient() {
                @Override
                public String description() {
                    return "custom-reactive";
                }

                @Override
                public Flux<ServiceInstance> getInstances(String serviceId) {
                    return Flux.empty();
                }

                @Override
                public Flux<String> getServices() {
                    return Flux.empty();
                }
            };
        }
    }
}
