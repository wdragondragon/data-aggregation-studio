package com.jdragon.studio.nacos.compat.support;

import com.jdragon.studio.nacos.compat.http.NacosHttpClient;
import com.jdragon.studio.nacos.compat.http.NacosHttpResponse;
import com.jdragon.studio.nacos.compat.model.NacosCompatMode;
import com.jdragon.studio.nacos.compat.props.NacosCompatProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NacosServerProbeServiceTest {

    @Test
    void shouldRejectInvalidJsonWhenAutoDetectingModernServer() {
        NacosHttpClient httpClient = mock(NacosHttpClient.class);
        when(httpClient.get(eq("127.0.0.1:8848"), eq(NacosServerProbeService.MODERN_SERVER_STATE_PATH), any(Map.class),
                any(Map.class), eq(Duration.ofSeconds(3))))
                .thenReturn(new NacosHttpResponse(200, "<html>state</html>"));
        when(httpClient.get(eq("127.0.0.1:8848"), eq(NacosServerProbeService.MODERN_CONSOLE_STATE_PATH), any(Map.class),
                any(Map.class), eq(Duration.ofSeconds(3))))
                .thenReturn(new NacosHttpResponse(200, "<html>state</html>"));
        when(httpClient.get(eq("127.0.0.1:8848"), eq(NacosServerProbeService.LEGACY_STATE_PATH), any(Map.class),
                any(Map.class), eq(Duration.ofSeconds(3))))
                .thenReturn(new NacosHttpResponse(404, "not found"));

        NacosCompatProperties compatProperties = new NacosCompatProperties();
        compatProperties.setMode(NacosCompatMode.AUTO);
        compatProperties.setProbeTimeout(Duration.ofSeconds(3));

        NacosServerProbeService probeService = new NacosServerProbeService(httpClient, compatProperties);

        assertThrows(IllegalStateException.class, () -> probeService.probe("127.0.0.1:8848"));
    }

    @Test
    void shouldReProbeWhenCachedResultExpires() {
        NacosHttpClient httpClient = mock(NacosHttpClient.class);
        when(httpClient.get(eq("127.0.0.1:8848"), eq(NacosServerProbeService.MODERN_SERVER_STATE_PATH), any(Map.class),
                any(Map.class), eq(Duration.ofSeconds(3))))
                .thenReturn(new NacosHttpResponse(200, "{\"version\":\"3.0.0\"}"))
                .thenReturn(new NacosHttpResponse(404, "not found"));
        when(httpClient.get(eq("127.0.0.1:8848"), eq(NacosServerProbeService.MODERN_CONSOLE_STATE_PATH), any(Map.class),
                any(Map.class), eq(Duration.ofSeconds(3))))
                .thenReturn(new NacosHttpResponse(404, "not found"));
        when(httpClient.get(eq("127.0.0.1:8848"), eq(NacosServerProbeService.LEGACY_STATE_PATH), any(Map.class),
                any(Map.class), eq(Duration.ofSeconds(3))))
                .thenReturn(new NacosHttpResponse(200, "{\"version\":\"1.3.2\"}"));

        NacosCompatProperties compatProperties = new NacosCompatProperties();
        compatProperties.setMode(NacosCompatMode.AUTO);
        compatProperties.setProbeTimeout(Duration.ofSeconds(3));
        compatProperties.setProbeCacheTtl(Duration.ZERO);

        NacosServerProbeService probeService = new NacosServerProbeService(httpClient, compatProperties);

        assertEquals("MODERN", probeService.probe("127.0.0.1:8848").generation().name());
        assertEquals("LEGACY", probeService.probe("127.0.0.1:8848").generation().name());
    }
}
