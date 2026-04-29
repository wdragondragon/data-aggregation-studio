package com.jdragon.studio.nacos.compat.discovery;

import com.jdragon.studio.nacos.compat.http.NacosHttpClient;
import com.jdragon.studio.nacos.compat.http.NacosHttpResponse;
import com.jdragon.studio.nacos.compat.http.NacosLegacyAuthService;
import com.jdragon.studio.nacos.compat.model.NacosServerGeneration;
import com.jdragon.studio.nacos.compat.model.NacosServerInfo;
import com.jdragon.studio.nacos.compat.props.NacosCompatProperties;
import com.jdragon.studio.nacos.compat.props.NacosDiscoveryProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import com.jdragon.studio.nacos.compat.support.NacosClientManager;
import com.jdragon.studio.nacos.compat.support.NacosServerProbeService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NacosDiscoveryAccessorTest {

    @Test
    void shouldThrowWhenLegacyInstancePayloadIsNotJson() {
        NacosHttpClient httpClient = mock(NacosHttpClient.class);
        when(httpClient.get(eq("127.0.0.1:8848"), eq("/nacos/v1/ns/instance/list"), any(Map.class), any(Map.class),
                eq(Duration.ofSeconds(3))))
                .thenReturn(new NacosHttpResponse(200, "<html>instances</html>"));
        NacosLegacyAuthService authService = mock(NacosLegacyAuthService.class);
        when(authService.getAccessToken("127.0.0.1:8848", "nacos", "Wjoms_2020")).thenReturn(null);
        NacosServerProbeService probeService = mock(NacosServerProbeService.class);
        when(probeService.probe("127.0.0.1:8848")).thenReturn(
                new NacosServerInfo(NacosServerGeneration.LEGACY, "1.3.2",
                        NacosServerProbeService.LEGACY_STATE_PATH, "127.0.0.1:8848"));

        NacosDiscoveryAccessor accessor = new NacosDiscoveryAccessor(httpClient, authService, probeService,
                mock(NacosClientManager.class), new NacosCompatProperties());

        NacosRootProperties rootProperties = new NacosRootProperties();
        rootProperties.setUsername("nacos");
        rootProperties.setPassword("Wjoms_2020");
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");
        discoveryProperties.setNamespace("ZCYY");
        discoveryProperties.setGroup("ZCYY_GROUP");

        assertThrows(IllegalStateException.class,
                () -> accessor.getInstances(rootProperties, discoveryProperties, "dfs-service"));
    }

    @Test
    void shouldThrowWhenLegacyServicePayloadIsNotJson() {
        NacosHttpClient httpClient = mock(NacosHttpClient.class);
        when(httpClient.get(eq("127.0.0.1:8848"), eq("/nacos/v1/ns/service/list"), any(Map.class), any(Map.class),
                eq(Duration.ofSeconds(3))))
                .thenReturn(new NacosHttpResponse(200, "<html>services</html>"));
        NacosLegacyAuthService authService = mock(NacosLegacyAuthService.class);
        when(authService.getAccessToken("127.0.0.1:8848", "nacos", "Wjoms_2020")).thenReturn(null);
        NacosServerProbeService probeService = mock(NacosServerProbeService.class);
        when(probeService.probe("127.0.0.1:8848")).thenReturn(
                new NacosServerInfo(NacosServerGeneration.LEGACY, "1.3.2",
                        NacosServerProbeService.LEGACY_STATE_PATH, "127.0.0.1:8848"));

        NacosDiscoveryAccessor accessor = new NacosDiscoveryAccessor(httpClient, authService, probeService,
                mock(NacosClientManager.class), new NacosCompatProperties());

        NacosRootProperties rootProperties = new NacosRootProperties();
        rootProperties.setUsername("nacos");
        rootProperties.setPassword("Wjoms_2020");
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");
        discoveryProperties.setNamespace("ZCYY");
        discoveryProperties.setGroup("ZCYY_GROUP");

        assertThrows(IllegalStateException.class, () -> accessor.getServices(rootProperties, discoveryProperties));
    }

    @Test
    void shouldThrowWhenLegacyBeatReportsMissingInstance() {
        NacosHttpClient httpClient = mock(NacosHttpClient.class);
        when(httpClient.putForm(eq("127.0.0.1:8848"), eq("/nacos/v1/ns/instance/beat"), any(Map.class), any(Map.class),
                any(Map.class), eq(Duration.ofSeconds(3))))
                .thenReturn(new NacosHttpResponse(200, "{\"code\":20404,\"message\":\"not found\"}"));
        NacosLegacyAuthService authService = mock(NacosLegacyAuthService.class);
        when(authService.getAccessToken("127.0.0.1:8848", "nacos", "Wjoms_2020")).thenReturn(null);
        NacosServerProbeService probeService = mock(NacosServerProbeService.class);

        NacosDiscoveryAccessor accessor = new NacosDiscoveryAccessor(httpClient, authService, probeService,
                mock(NacosClientManager.class), new NacosCompatProperties());
        NacosRootProperties rootProperties = new NacosRootProperties();
        rootProperties.setUsername("nacos");
        rootProperties.setPassword("Wjoms_2020");
        NacosDiscoveryProperties discoveryProperties = new NacosDiscoveryProperties();
        discoveryProperties.setServerAddr("127.0.0.1:8848");
        discoveryProperties.setNamespace("ZCYY");
        discoveryProperties.setGroup("ZCYY_GROUP");
        NacosRegistration registration = new NacosRegistration("instance-1", "dfs-service", "10.0.0.10", 15001,
                false, Map.of(), "ZCYY", "ZCYY_GROUP", "DEFAULT", 1.0D, true);

        assertThrows(IllegalStateException.class,
                () -> accessor.beatLegacy(rootProperties, discoveryProperties, registration));
    }
}
