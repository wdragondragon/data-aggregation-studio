package com.jdragon.studio.server.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeEndpointEntity;
import com.jdragon.studio.infra.mapper.RuntimeEndpointMapper;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.RuntimeClusterService;
import com.jdragon.studio.infra.service.RuntimeEndpointHeaderPolicy;
import com.jdragon.studio.infra.service.RuntimeEndpointHttpClient;
import com.jdragon.studio.infra.service.RuntimeEndpointSecurityService;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantScriptRuntimeRouterTest {

    @Test
    void shouldSendRegisteredScriptAndCallerContextToExplicitWorker() throws Exception {
        AtomicReference<String> internalToken = new AtomicReference<String>();
        AtomicReference<JsonNode> payload = new AtomicReference<JsonNode>();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        byte[] response = ("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"OK\"," +
                "\"data\":{\"schema\":\"studio.script-result.v1\",\"success\":true," +
                "\"entrypointId\":\"field-mapping-suggester\",\"data\":{\"mappedCount\":2}}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/assistant/scripts/execute", exchange -> {
            internalToken.set(exchange.getRequestHeaders().getFirst("X-Studio-Internal-Token"));
            payload.set(objectMapper.readTree(exchange.getRequestBody()));
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture(objectMapper);
            when(fixture.encryptionService.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            AssistantScriptRuntimeRouter.ExecutionResult result = fixture.router.execute(params(50L));

            assertEquals(50L, result.getRuntimeClusterId());
            assertEquals("studio.script-result.v1", result.getData().get("schema"));
            assertEquals("internal-token", internalToken.get());
            assertEquals(50L, payload.get().path("targetClusterId").asLong());
            assertEquals("C50", payload.get().path("targetClusterCode").asText());
            assertEquals("tenant-a", payload.get().path("tenantId").asText());
            assertEquals(101L, payload.get().path("projectId").asLong());
            assertEquals(9L, payload.get().path("userId").asLong());
            assertEquals("field-mapping-suggester", payload.get().path("entrypointId").asText());
            assertEquals("id", payload.get().path("input").path("sourceFields").get(0).asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectMissingRuntimeClusterIdEvenWhenProjectCouldHaveOneChoice() {
        Fixture fixture = fixture(new ObjectMapper().findAndRegisterModules());

        StudioException error = assertThrows(StudioException.class,
                () -> fixture.router.execute(params(null)));

        assertEquals("BAD_REQUEST", error.getCode());
        assertEquals("runtimeClusterId is required for assistant script execution", error.getMessage());
        verify(fixture.runtimeClusterService, never()).requireAuthorized(any(), any());
        verify(fixture.endpointMapper, never()).selectOne(any());
    }

    @Test
    void shouldRejectFractionalRuntimeClusterIdBeforeAuthorization() {
        Fixture fixture = fixture(new ObjectMapper().findAndRegisterModules());
        Map<String, Object> params = params(null);
        params.put("runtimeClusterId", 50.5D);

        StudioException error = assertThrows(StudioException.class,
                () -> fixture.router.execute(params));

        assertEquals("BAD_REQUEST", error.getCode());
        verify(fixture.runtimeClusterService, never()).requireAuthorized(any(), any());
        verify(fixture.endpointMapper, never()).selectOne(any());
    }

    @Test
    void shouldRejectSuccessfulPayloadWithoutAuthenticatedWorkerMarker() throws Exception {
        byte[] response = ("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"OK\"," +
                "\"data\":{\"schema\":\"studio.script-result.v1\",\"success\":true}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/assistant/scripts/execute", exchange -> {
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture(new ObjectMapper().findAndRegisterModules());
            when(fixture.encryptionService.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            StudioException error = assertThrows(StudioException.class,
                    () -> fixture.router.execute(params(50L)));

            assertEquals("SERVICE_UNAVAILABLE", error.getCode());
        } finally {
            server.stop(0);
        }
    }

    private void markAuthenticated(com.sun.net.httpserver.HttpExchange exchange) {
        exchange.getResponseHeaders().add(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
    }

    private Map<String, Object> params(Long runtimeClusterId) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("entrypointId", "field-mapping-suggester");
        params.put("input", Map.of("sourceFields", List.of("id"), "targetFields", List.of("ID")));
        if (runtimeClusterId != null) params.put("runtimeClusterId", runtimeClusterId);
        return params;
    }

    private Fixture fixture(ObjectMapper objectMapper) {
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(50L);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C50");
        cluster.setEnabled(1);
        when(projectResourceAccessService.requireCurrentProjectId()).thenReturn(101L);
        when(runtimeClusterService.requireAuthorized(101L, 50L)).thenReturn(cluster);
        when(runtimeClusterService.hasOnlineInstance(cluster)).thenReturn(true);
        when(securityService.currentTenantId()).thenReturn("tenant-a");
        when(securityService.currentUserId()).thenReturn(9L);
        when(securityService.currentUsername()).thenReturn("admin");

        RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
        endpoint.setTenantId("tenant-a");
        endpoint.setRuntimeClusterId(50L);
        endpoint.setMode("HTTP");
        endpoint.setEnabled(1);
        endpoint.setEndpointCiphertext("endpoint-ciphertext");
        endpoint.setConnectTimeoutMillis(3000);
        endpoint.setReadTimeoutMillis(3000);
        when(endpointMapper.selectOne(any())).thenReturn(endpoint);

        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("internal-token");
        properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
        AssistantScriptRuntimeRouter router = new AssistantScriptRuntimeRouter(
                endpointMapper, runtimeClusterService, encryptionService, objectMapper, properties,
                new RuntimeEndpointSecurityService(properties), new RuntimeEndpointHeaderPolicy(),
                new RuntimeEndpointHttpClient(), securityService, projectResourceAccessService);
        return new Fixture(router, endpointMapper, runtimeClusterService, encryptionService);
    }

    private static final class Fixture {
        private final AssistantScriptRuntimeRouter router;
        private final RuntimeEndpointMapper endpointMapper;
        private final RuntimeClusterService runtimeClusterService;
        private final EncryptionService encryptionService;

        private Fixture(AssistantScriptRuntimeRouter router,
                        RuntimeEndpointMapper endpointMapper,
                        RuntimeClusterService runtimeClusterService,
                        EncryptionService encryptionService) {
            this.router = router;
            this.endpointMapper = endpointMapper;
            this.runtimeClusterService = runtimeClusterService;
            this.encryptionService = encryptionService;
        }
    }
}
