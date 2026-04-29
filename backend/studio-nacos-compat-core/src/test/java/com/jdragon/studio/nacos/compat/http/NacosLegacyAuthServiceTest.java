package com.jdragon.studio.nacos.compat.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NacosLegacyAuthServiceTest {

    @Test
    void shouldThrowWhenLoginResponseIsNotJson() {
        NacosHttpClient httpClient = mock(NacosHttpClient.class);
        when(httpClient.postForm(eq("127.0.0.1:8848"), eq("/nacos/v1/auth/login"), any(Map.class), any(Map.class),
                any(Map.class), eq(Duration.ofSeconds(3))))
                .thenReturn(new NacosHttpResponse(200, "<html>login</html>"));

        NacosLegacyAuthService authService = new NacosLegacyAuthService(httpClient, Duration.ofSeconds(3));

        assertThrows(IllegalStateException.class,
                () -> authService.getAccessToken("127.0.0.1:8848", "nacos", "Wjoms_2020"));
    }

    @Test
    void shouldThrowWhenLoginResponseMissingAccessToken() {
        NacosHttpClient httpClient = mock(NacosHttpClient.class);
        when(httpClient.postForm(eq("127.0.0.1:8848"), eq("/nacos/v1/auth/login"), any(Map.class), any(Map.class),
                any(Map.class), eq(Duration.ofSeconds(3))))
                .thenReturn(new NacosHttpResponse(200, "{\"tokenTtl\":18000}"));

        NacosLegacyAuthService authService = new NacosLegacyAuthService(httpClient, Duration.ofSeconds(3));

        assertThrows(IllegalStateException.class,
                () -> authService.getAccessToken("127.0.0.1:8848", "nacos", "Wjoms_2020"));
    }
}
