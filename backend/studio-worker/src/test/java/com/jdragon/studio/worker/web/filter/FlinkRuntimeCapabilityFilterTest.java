package com.jdragon.studio.worker.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.flink.connector.AggregationFlinkRuntimeRegistry;
import com.jdragon.studio.flink.connector.AggregationFlinkTableRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkRuntimeCapabilityFilterTest {

    @Test
    void shouldRejectInvalidCapabilityBeforeInvokingControllerChain() throws Exception {
        FlinkRuntimeCapabilityFilter filter = new FlinkRuntimeCapabilityFilter(
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = request("missing-capability");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (servletRequest, servletResponse) -> invoked.set(true));

        assertEquals(401, response.getStatus());
        assertFalse(invoked.get());
        assertTrue(response.getContentAsString().contains("Invalid or expired Flink runtime capability"));
    }

    @Test
    void shouldAllowRegisteredShortLivedCapability() throws Exception {
        String capability = AggregationFlinkRuntimeRegistry.register(new AggregationFlinkTableRuntime(), 30);
        try {
            FlinkRuntimeCapabilityFilter filter = new FlinkRuntimeCapabilityFilter(
                    new ObjectMapper().findAndRegisterModules());
            MockHttpServletRequest request = request(capability);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean(false);

            filter.doFilter(request, response, (servletRequest, servletResponse) -> invoked.set(true));

            assertTrue(invoked.get());
            assertEquals(200, response.getStatus());
        } finally {
            AggregationFlinkRuntimeRegistry.remove(capability);
        }
    }

    private MockHttpServletRequest request(String capability) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/flink/runtime/resolve");
        request.setServletPath("/api/flink/runtime/resolve");
        request.addHeader(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER, capability);
        return request;
    }
}
