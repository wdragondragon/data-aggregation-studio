package com.jdragon.studio.nacos.compat.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NacosCompatReactiveDiscoveryClientTest {

    @Test
    void shouldDeferBlockingInstanceLookupUntilSubscription() {
        NacosCompatDiscoveryClient discoveryClient = mock(NacosCompatDiscoveryClient.class);
        when(discoveryClient.getInstances("dfs-service")).thenReturn(List.of(mock(ServiceInstance.class)));
        NacosCompatReactiveDiscoveryClient reactiveDiscoveryClient =
                new NacosCompatReactiveDiscoveryClient(discoveryClient);

        Flux<ServiceInstance> instances = reactiveDiscoveryClient.getInstances("dfs-service");

        verifyNoInteractions(discoveryClient);
        assertTrue(instances.collectList().block().size() == 1);
        verify(discoveryClient).getInstances("dfs-service");
    }

    @Test
    void shouldRunBlockingServiceLookupOnBoundedElasticThread() {
        NacosCompatDiscoveryClient discoveryClient = mock(NacosCompatDiscoveryClient.class);
        AtomicReference<Thread> lookupThread = new AtomicReference<>();
        Thread callerThread = Thread.currentThread();
        when(discoveryClient.getServices()).thenAnswer(invocation -> {
            lookupThread.set(Thread.currentThread());
            return List.of("dfs-service");
        });
        NacosCompatReactiveDiscoveryClient reactiveDiscoveryClient =
                new NacosCompatReactiveDiscoveryClient(discoveryClient);

        List<String> services = reactiveDiscoveryClient.getServices().collectList().block();

        assertTrue(services.contains("dfs-service"));
        assertNotEquals(callerThread, lookupThread.get());
        assertTrue(lookupThread.get().getName().contains("boundedElastic"));
    }
}
