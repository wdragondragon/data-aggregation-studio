package com.jdragon.studio.server.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.server.web.security.StudioHttpTokenResolver;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StudioCookieCsrfFilterTest {

    @Test
    void shouldBeDisabledByDefault() throws Exception {
        MockHttpServletRequest request = cookieRequest("POST");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(false).doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldRejectCrossSiteWriteWithCookieAuthentication() throws Exception {
        MockHttpServletRequest request = cookieRequest("POST");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void shouldAllowSameOriginWriteWithCookieAuthentication() throws Exception {
        MockHttpServletRequest request = cookieRequest("POST");
        request.setScheme("https");
        request.setServerName("studio.example");
        request.setServerPort(443);
        request.addHeader("Host", "studio.example");
        request.addHeader("Sec-Fetch-Site", "same-origin");
        request.addHeader("Origin", "https://studio.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void explicitStudioHeaderMustNotUseCookieCsrfPolicy() throws Exception {
        MockHttpServletRequest request = cookieRequest("POST");
        request.addHeader(StudioConstants.STUDIO_TOKEN_HEADER, "explicit-token");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    private MockHttpServletRequest cookieRequest(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/dashboard/overview");
        request.setCookies(new Cookie(StudioConstants.STUDIO_TOKEN_COOKIE, "cookie-token"));
        return request;
    }

    private StudioCookieCsrfFilter filter(boolean enabled) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getAuth().setCookieCsrfEnabled(enabled);
        return new StudioCookieCsrfFilter(
                new StudioHttpTokenResolver(),
                new ObjectMapper().findAndRegisterModules(),
                properties);
    }
}
