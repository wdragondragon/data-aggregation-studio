package com.jdragon.studio.worker.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InternalApiTokenFilterTest {

    @Test
    void shouldDistinguishRejectedTransportAuthenticationFromAuthenticatedRuntimeResponses() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("internal-secret");
        InternalApiTokenFilter filter = new InternalApiTokenFilter(
                new ObjectMapper().registerModule(new JavaTimeModule()), properties);

        MockHttpServletRequest rejectedRequest = request("wrong-secret");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        StudioRequestContextHolder.setContext(staleContext());
        filter.doFilter(rejectedRequest, rejectedResponse, (request, response) -> {
            throw new AssertionError("Rejected internal requests must not reach the controller");
        });

        assertEquals(401, rejectedResponse.getStatus());
        assertEquals(RuntimeInternalHeaders.INTERNAL_AUTHENTICATION,
                rejectedResponse.getHeader(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER));
        assertNull(rejectedResponse.getHeader(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER));
        assertNull(StudioRequestContextHolder.getContext());

        MockHttpServletRequest authenticatedRequest = request("internal-secret");
        MockHttpServletResponse authenticatedResponse = new MockHttpServletResponse();
        StudioRequestContextHolder.setContext(staleContext());
        filter.doFilter(authenticatedRequest, authenticatedResponse, (request, response) -> {
            assertNull(StudioRequestContextHolder.getContext());
            StudioRequestContextHolder.setContext(staleContext());
            ((MockHttpServletResponse) response).setStatus(401);
        });

        assertEquals(401, authenticatedResponse.getStatus());
        assertEquals(RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED,
                authenticatedResponse.getHeader(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER));
        assertNull(StudioRequestContextHolder.getContext());
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/runtime/health");
        request.setServletPath("/internal/runtime/health");
        request.addHeader("X-Studio-Internal-Token", token);
        return request;
    }

    private StudioRequestContext staleContext() {
        StudioRequestContext context = new StudioRequestContext();
        context.setTenantId("stale-tenant");
        context.setProjectId(999L);
        return context;
    }
}
