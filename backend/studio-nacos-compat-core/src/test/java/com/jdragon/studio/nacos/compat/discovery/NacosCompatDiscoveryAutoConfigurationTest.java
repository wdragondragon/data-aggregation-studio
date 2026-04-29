package com.jdragon.studio.nacos.compat.discovery;

import com.jdragon.studio.nacos.compat.config.NacosConfigRefreshAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.client.discovery.composite.CompositeDiscoveryClient;
import org.springframework.cloud.client.discovery.composite.reactive.ReactiveCompositeDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;
import org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.reactive.SimpleReactiveDiscoveryProperties;
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

    @Test
    void shouldCreateCompatDiscoveryClientWhenOnlySimpleDiscoveryClientExists() {
        this.contextRunner.withUserConfiguration(SimpleDiscoveryClientConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean("simpleDiscoveryClient");
                    assertThat(context).hasSingleBean(NacosCompatDiscoveryClient.class);
                    assertThat(context).getBeans(DiscoveryClient.class).containsKeys("simpleDiscoveryClient",
                            "nacosCompatDiscoveryClient");
                });
    }

    @Test
    void shouldCreateCompatReactiveDiscoveryClientWhenOnlySimpleReactiveDiscoveryClientExists() {
        this.contextRunner.withUserConfiguration(SimpleReactiveDiscoveryClientConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean("simpleReactiveDiscoveryClient");
                    assertThat(context).hasSingleBean(NacosCompatReactiveDiscoveryClient.class);
                    assertThat(context).getBeans(ReactiveDiscoveryClient.class).containsKeys("simpleReactiveDiscoveryClient",
                            "nacosCompatReactiveDiscoveryClient");
                });
    }

    @Test
    void shouldCreateCompatDiscoveryClientWhenOnlyFrameworkCompositeDiscoveryClientsExist() {
        this.contextRunner.withUserConfiguration(FrameworkCompositeDiscoveryClientConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean("compositeDiscoveryClient");
                    assertThat(context).hasSingleBean(NacosCompatDiscoveryClient.class);
                    assertThat(context).getBeans(DiscoveryClient.class).containsKeys("simpleDiscoveryClient",
                            "compositeDiscoveryClient", "nacosCompatDiscoveryClient");
                });
    }

    @Test
    void shouldCreateCompatReactiveDiscoveryClientWhenOnlyFrameworkCompositeReactiveDiscoveryClientsExist() {
        this.contextRunner.withUserConfiguration(FrameworkCompositeReactiveDiscoveryClientConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean("reactiveCompositeDiscoveryClient");
                    assertThat(context).hasSingleBean(NacosCompatReactiveDiscoveryClient.class);
                    assertThat(context).getBeans(ReactiveDiscoveryClient.class).containsKeys("simpleReactiveDiscoveryClient",
                            "reactiveCompositeDiscoveryClient", "nacosCompatReactiveDiscoveryClient");
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

    @Configuration(proxyBeanMethods = false)
    static class SimpleDiscoveryClientConfiguration {

        @Bean
        SimpleDiscoveryClient simpleDiscoveryClient() {
            return new SimpleDiscoveryClient(new SimpleDiscoveryProperties());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SimpleReactiveDiscoveryClientConfiguration {

        @Bean
        SimpleReactiveDiscoveryClient simpleReactiveDiscoveryClient() {
            return new SimpleReactiveDiscoveryClient(new SimpleReactiveDiscoveryProperties());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FrameworkCompositeDiscoveryClientConfiguration {

        @Bean
        SimpleDiscoveryClient simpleDiscoveryClient() {
            return new SimpleDiscoveryClient(new SimpleDiscoveryProperties());
        }

        @Bean
        CompositeDiscoveryClient compositeDiscoveryClient(SimpleDiscoveryClient simpleDiscoveryClient) {
            return new CompositeDiscoveryClient(List.of(simpleDiscoveryClient));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FrameworkCompositeReactiveDiscoveryClientConfiguration {

        @Bean
        SimpleReactiveDiscoveryClient simpleReactiveDiscoveryClient() {
            return new SimpleReactiveDiscoveryClient(new SimpleReactiveDiscoveryProperties());
        }

        @Bean
        ReactiveCompositeDiscoveryClient reactiveCompositeDiscoveryClient(
                SimpleReactiveDiscoveryClient simpleReactiveDiscoveryClient) {
            return new ReactiveCompositeDiscoveryClient(List.of(simpleReactiveDiscoveryClient));
        }
    }
}
