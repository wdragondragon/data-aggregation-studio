package com.jdragon.studio.server.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.JwtTokenService;
import com.jdragon.studio.infra.service.StudioAccessService;
import com.jdragon.studio.infra.service.StudioUserDetailsService;
import com.jdragon.studio.server.web.filter.JwtAuthenticationFilter;
import com.jdragon.studio.server.web.filter.StudioCookieCsrfFilter;
import com.jdragon.studio.server.web.filter.StudioRequestContextFilter;
import com.jdragon.studio.server.web.security.StudioHttpTokenResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NativeDownloadSecurityConfigTest.TestEndpoint.class)
class NativeDownloadSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldPermitOnlyExactNativeDownloadGetWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/unstructured-management/download/native")
                        .param("ticket", "A".repeat(43)))
                .andExpect(status().isOk())
                .andExpect(content().string("native"));

        mockMvc.perform(get("/api/v1/unstructured-management/download"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/unstructured-management/download-tickets"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/unstructured-management/download/native"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/unstructured-management/download/native/extra"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class TestEndpoint {
        @GetMapping("/api/v1/unstructured-management/download/native")
        String nativeDownload() {
            return "native";
        }

        @PostMapping("/api/v1/unstructured-management/download/native")
        String nativeDownloadPost() {
            return "native-post";
        }

        @GetMapping("/api/v1/unstructured-management/download/native/extra")
        String nativeDownloadExtra() {
            return "native-extra";
        }

        @GetMapping("/api/v1/unstructured-management/download")
        String legacyDownload() {
            return "legacy";
        }

        @PostMapping("/api/v1/unstructured-management/download-tickets")
        String createTicket() {
            return "ticket";
        }
    }

    @Configuration
    static class TestFilterConfiguration {
        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(mock(JwtTokenService.class),
                    mock(StudioUserDetailsService.class), mock(StudioHttpTokenResolver.class));
        }

        @Bean
        StudioCookieCsrfFilter studioCookieCsrfFilter(ObjectMapper objectMapper,
                                                       StudioPlatformProperties properties) {
            return new StudioCookieCsrfFilter(mock(StudioHttpTokenResolver.class),
                    objectMapper, properties);
        }

        @Bean
        StudioRequestContextFilter studioRequestContextFilter() {
            return new StudioRequestContextFilter(mock(StudioAccessService.class));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(StudioPlatformProperties.class)
    @Import({SecurityConfig.class, TestEndpoint.class, TestFilterConfiguration.class})
    static class TestApplication {
    }
}
