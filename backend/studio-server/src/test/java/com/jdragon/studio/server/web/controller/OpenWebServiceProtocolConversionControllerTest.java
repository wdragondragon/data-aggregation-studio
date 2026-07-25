package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.server.web.service.RuntimeInvocationRouter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenWebServiceProtocolConversionControllerTest {

    @Test
    void shouldLeaveSoapBodyUnreadForRemoteRouter() throws Exception {
        RuntimeInvocationRouter router = mock(RuntimeInvocationRouter.class);
        OpenWebServiceProtocolConversionController controller =
                new OpenWebServiceProtocolConversionController(router);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/openapi/ws/protocol-conversions/demo/public");
        request.setContentType("application/soap+xml;charset=UTF-8");
        request.setContent("<soap>payload</soap>".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> routedBody = new AtomicReference<String>();
        when(router.routeIfRemote(eq("protocol-conversions"), eq("demo"), eq("public"), eq("SOAP"),
                any(), any())).thenAnswer(invocation -> {
            MockHttpServletRequest routedRequest = invocation.getArgument(4);
            routedBody.set(new String(routedRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            return true;
        });

        controller.invoke("demo", "public", request, response);

        assertEquals("<soap>payload</soap>", routedBody.get());
    }
}
