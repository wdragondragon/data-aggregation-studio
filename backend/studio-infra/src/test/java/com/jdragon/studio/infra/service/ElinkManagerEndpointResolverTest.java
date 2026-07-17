package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElinkManagerEndpointResolverTest {

    @Test
    void shouldRoundRobinInstancesAndResolveRelativePathUnderConfiguredPrefix() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getAlert().getElink().setPathPrefix("elink/");
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        ServiceInstance first = instance("http://127.0.0.1:18081/base");
        ServiceInstance second = instance("http://127.0.0.1:18082");
        when(discoveryClient.getInstances("elink-message-integration"))
                .thenReturn(List.of(first, second));
        ElinkManagerEndpointResolver resolver = new ElinkManagerEndpointResolver(
                provider(discoveryClient), properties);

        assertEquals(URI.create("http://127.0.0.1:18081/base/elink/app/allow-users"),
                resolver.resolve("app/allow-users"));
        assertEquals(URI.create("http://127.0.0.1:18082/elink/groups"),
                resolver.resolve("/groups"));
        assertEquals(URI.create("http://127.0.0.1:18081/base/elink/messages"),
                resolver.resolve("/messages"));
    }

    @Test
    void shouldRejectMissingDiscoveryAndPathTraversal() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        @SuppressWarnings("unchecked")
        ObjectProvider<DiscoveryClient> missingProvider = mock(ObjectProvider.class);
        when(missingProvider.getIfAvailable()).thenReturn(null);
        ElinkManagerEndpointResolver missingResolver =
                new ElinkManagerEndpointResolver(missingProvider, properties);

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> missingResolver.resolve("/groups"));
        assertTrue(missing.getMessage().contains("Nacos discovery"));

        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        ServiceInstance instance = instance("http://127.0.0.1:18080");
        when(discoveryClient.getInstances("elink-message-integration"))
                .thenReturn(List.of(instance));
        ElinkManagerEndpointResolver resolver =
                new ElinkManagerEndpointResolver(provider(discoveryClient), properties);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("../internal"));
    }

    private ServiceInstance instance(String uri) {
        ServiceInstance instance = mock(ServiceInstance.class);
        when(instance.getUri()).thenReturn(URI.create(uri));
        return instance;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<DiscoveryClient> provider(DiscoveryClient discoveryClient) {
        ObjectProvider<DiscoveryClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(discoveryClient);
        return provider;
    }
}
