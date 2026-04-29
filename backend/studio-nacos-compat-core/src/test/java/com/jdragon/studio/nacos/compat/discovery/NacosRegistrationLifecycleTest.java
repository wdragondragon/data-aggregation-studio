package com.jdragon.studio.nacos.compat.discovery;

import com.jdragon.studio.nacos.compat.props.NacosDiscoveryProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NacosRegistrationLifecycleTest {

    @Test
    void shouldUseInetUtilsInsteadOfSpringCloudClientIpAddressWhenDiscoveryIpMissing() {
        ServiceRegistry<NacosRegistration> serviceRegistry = mock(ServiceRegistry.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setService("dfs-service");
        Environment environment = new MockEnvironment().withProperty("spring.cloud.client.ip-address", "192.168.84.1");
        InetUtils inetUtils = mockInetUtils("172.16.20.21");

        NacosRegistrationLifecycle lifecycle = new NacosRegistrationLifecycle(serviceRegistry, discoveryProperties,
                environment, inetUtils);

        lifecycle.onApplicationEvent(webServerEvent(15001));

        ArgumentCaptor<NacosRegistration> captor = ArgumentCaptor.forClass(NacosRegistration.class);
        verify(serviceRegistry).register(captor.capture());
        assertEquals("172.16.20.21", captor.getValue().getHost());
    }

    @Test
    void shouldPreferExplicitDiscoveryIpOverEnvironmentAddress() {
        ServiceRegistry<NacosRegistration> serviceRegistry = mock(ServiceRegistry.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setService("dfs-service");
        discoveryProperties.setIp("10.20.30.40");
        Environment environment = new MockEnvironment().withProperty("spring.cloud.client.ip-address", "172.16.20.21");
        InetUtils inetUtils = mockInetUtils("172.16.20.21");

        NacosRegistrationLifecycle lifecycle = new NacosRegistrationLifecycle(serviceRegistry, discoveryProperties,
                environment, inetUtils);

        lifecycle.onApplicationEvent(webServerEvent(15001));

        ArgumentCaptor<NacosRegistration> captor = ArgumentCaptor.forClass(NacosRegistration.class);
        verify(serviceRegistry).register(captor.capture());
        assertEquals("10.20.30.40", captor.getValue().getHost());
    }

    @Test
    void shouldFallbackToInetUtilsWhenIpv6TypeHasNoIpv6Address() {
        ServiceRegistry<NacosRegistration> serviceRegistry = mock(ServiceRegistry.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setService("dfs-service");
        discoveryProperties.setIpType("IPv6");
        Environment environment = new MockEnvironment();
        InetUtils inetUtils = mockInetUtils("10.30.40.50");

        NacosRegistrationLifecycle lifecycle = new TestableNacosRegistrationLifecycle(serviceRegistry,
                discoveryProperties, environment, inetUtils, null);

        lifecycle.onApplicationEvent(webServerEvent(15001));

        ArgumentCaptor<NacosRegistration> captor = ArgumentCaptor.forClass(NacosRegistration.class);
        verify(serviceRegistry).register(captor.capture());
        assertEquals("10.30.40.50", captor.getValue().getHost());
    }

    @Test
    void shouldUseConfiguredNetworkInterfaceBeforeInetUtils() {
        ServiceRegistry<NacosRegistration> serviceRegistry = mock(ServiceRegistry.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setService("dfs-service");
        discoveryProperties.setNetworkInterface("eth0");
        Environment environment = new MockEnvironment();
        InetUtils inetUtils = mockInetUtils("10.30.40.50");

        NacosRegistrationLifecycle lifecycle = new TestableNacosRegistrationLifecycle(serviceRegistry,
                discoveryProperties, environment, inetUtils, "192.168.10.11");

        lifecycle.onApplicationEvent(webServerEvent(15001));

        ArgumentCaptor<NacosRegistration> captor = ArgumentCaptor.forClass(NacosRegistration.class);
        verify(serviceRegistry).register(captor.capture());
        assertEquals("192.168.10.11", captor.getValue().getHost());
    }

    @Test
    void shouldRejectUnsupportedIpType() {
        ServiceRegistry<NacosRegistration> serviceRegistry = mock(ServiceRegistry.class);
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setService("dfs-service");
        discoveryProperties.setIpType("IPv5");
        Environment environment = new MockEnvironment();
        InetUtils inetUtils = mockInetUtils("10.30.40.50");

        NacosRegistrationLifecycle lifecycle = new NacosRegistrationLifecycle(serviceRegistry, discoveryProperties,
                environment, inetUtils);

        assertThrows(IllegalArgumentException.class, () -> lifecycle.onApplicationEvent(webServerEvent(15001)));
    }

    private WebServerInitializedEvent webServerEvent(int port) {
        WebServer webServer = mock(WebServer.class);
        when(webServer.getPort()).thenReturn(port);
        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        when(event.getWebServer()).thenReturn(webServer);
        return event;
    }

    private InetUtils mockInetUtils(String ipAddress) {
        InetUtils.HostInfo hostInfo = new InetUtils.HostInfo();
        hostInfo.setIpAddress(ipAddress);
        InetUtils inetUtils = mock(InetUtils.class);
        when(inetUtils.findFirstNonLoopbackHostInfo()).thenReturn(hostInfo);
        return inetUtils;
    }

    private static final class TestableNacosRegistrationLifecycle extends NacosRegistrationLifecycle {

        private final String networkInterfaceHost;

        private TestableNacosRegistrationLifecycle(ServiceRegistry<NacosRegistration> serviceRegistry,
                NacosDiscoveryProperties discoveryProperties, Environment environment, InetUtils inetUtils,
                String networkInterfaceHost) {
            super(serviceRegistry, discoveryProperties, environment, inetUtils);
            this.networkInterfaceHost = networkInterfaceHost;
        }

        @Override
        protected String resolveNetworkInterfaceHost(String networkInterfaceName) {
            return this.networkInterfaceHost;
        }

        @Override
        protected String findIpv6Address() {
            return null;
        }
    }
}
