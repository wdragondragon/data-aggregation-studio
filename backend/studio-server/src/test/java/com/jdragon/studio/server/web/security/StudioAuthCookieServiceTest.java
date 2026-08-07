package com.jdragon.studio.server.web.security;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StudioAuthCookieServiceTest {

    private StudioAuthCookieService cookieService;

    @BeforeEach
    void setUp() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getAuth().setCookiePath("/dfs/data-aggregation-studio");
        properties.getAuth().setCookieSameSite("Lax");
        JwtTokenService jwtTokenService = mock(JwtTokenService.class);
        when(jwtTokenService.getTokenExpirationSeconds()).thenReturn(43200L);
        cookieService = new StudioAuthCookieService(properties, jwtTokenService);
    }

    @Test
    void shouldWriteHyphenatedHttpOnlyCookieForForwardedHttpsRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieService.writeTokenCookie(request, response, "signed-jwt");

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(setCookie.startsWith("studio-token=signed-jwt"));
        assertTrue(setCookie.contains("Path=/dfs/data-aggregation-studio"));
        assertTrue(setCookie.contains("Max-Age=43200"));
        assertTrue(setCookie.contains("Secure"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
    }

    @Test
    void shouldClearCookieWithTheSamePath() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieService.clearTokenCookie(request, response);

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(setCookie.startsWith("studio-token="));
        assertTrue(setCookie.contains("Path=/dfs/data-aggregation-studio"));
        assertTrue(setCookie.contains("Max-Age=0"));
        assertTrue(setCookie.contains("HttpOnly"));
    }
}
