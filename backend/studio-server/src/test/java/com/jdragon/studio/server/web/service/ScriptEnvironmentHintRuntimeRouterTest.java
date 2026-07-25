package com.jdragon.studio.server.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.JavaImportHintResponse;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScriptEnvironmentHintRuntimeRouterTest {

    @Test
    void shouldRouteDefaultLocalHintThroughAuthenticatedWorkerHttpEndpoint() throws Exception {
        AtomicReference<String> internalToken = new AtomicReference<String>();
        AtomicReference<JsonNode> payload = new AtomicReference<JsonNode>();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        byte[] response = ("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"OK\"," +
                "\"data\":{\"environmentId\":31,\"environmentVersion\":4," +
                "\"classes\":[{\"simpleName\":\"Customer\"," +
                "\"qualifiedName\":\"demo.Customer\",\"source\":\"DEPENDENCY\"}]}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/script-environments/java/import-hints", exchange -> {
            internalToken.set(exchange.getRequestHeaders().getFirst("X-Studio-Internal-Token"));
            payload.set(objectMapper.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().add(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                    RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture(objectMapper);
            when(fixture.encryptionService.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            JavaImportHintResponse result = fixture.router.importHints(50L, 31L, "cust", 20);

            assertEquals(31L, result.getEnvironmentId());
            assertEquals("demo.Customer", result.getClasses().get(0).getQualifiedName());
            assertEquals("internal-token", internalToken.get());
            assertEquals(50L, payload.get().path("targetClusterId").asLong());
            assertEquals("DEFAULT-LOCAL", payload.get().path("targetClusterCode").asText());
            assertEquals("tenant-a", payload.get().path("tenantId").asText());
            assertEquals(101L, payload.get().path("projectId").asLong());
            assertEquals(31L, payload.get().path("environmentId").asLong());
            assertEquals("cust", payload.get().path("keyword").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFailBeforeEndpointLookupWhenTargetWorkerIsOffline() {
        Fixture fixture = fixture(new ObjectMapper().findAndRegisterModules());
        when(fixture.runtimeClusterService.hasOnlineInstance(fixture.cluster)).thenReturn(false);

        StudioException error = assertThrows(StudioException.class,
                () -> fixture.router.importHints(50L, 31L, null, 20));

        assertEquals(StudioErrorCode.SERVICE_UNAVAILABLE, error.getCode());
        assertTrue(error.getMessage().contains("no online Worker"));
        verify(fixture.endpointMapper, never()).selectOne(any());
        verify(fixture.encryptionService, never()).decrypt(any());
    }

    @Test
    void shouldPreserveRevokedProjectAuthorization() {
        Fixture fixture = fixture(new ObjectMapper().findAndRegisterModules());
        when(fixture.runtimeClusterService.requireAuthorized(101L, 50L))
                .thenThrow(new StudioException(StudioErrorCode.FORBIDDEN,
                        "Runtime cluster is not authorized for the current project"));

        StudioException error = assertThrows(StudioException.class,
                () -> fixture.router.importHints(50L, 31L, null, 20));

        assertEquals(StudioErrorCode.FORBIDDEN, error.getCode());
        verify(fixture.endpointMapper, never()).selectOne(any());
    }

    @Test
    void shouldRejectHintPayloadWithoutAuthenticatedWorkerMarker() throws Exception {
        byte[] response = ("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"OK\"," +
                "\"data\":{\"environmentId\":31,\"classes\":[]}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/script-environments/java/import-hints", exchange -> {
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
                    () -> fixture.router.importHints(50L, 31L, null, 20));

            assertEquals(StudioErrorCode.SERVICE_UNAVAILABLE, error.getCode());
            assertTrue(error.getMessage().contains("authenticated Worker response"));
        } finally {
            server.stop(0);
        }
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
        cluster.setCode("DEFAULT-LOCAL");
        cluster.setEnabled(1);
        when(projectResourceAccessService.requireCurrentProjectId()).thenReturn(101L);
        when(runtimeClusterService.requireAuthorized(101L, 50L)).thenReturn(cluster);
        when(runtimeClusterService.hasOnlineInstance(cluster)).thenReturn(true);
        when(securityService.currentTenantId()).thenReturn("tenant-a");
        when(securityService.currentUserId()).thenReturn(9L);
        when(securityService.currentUsername()).thenReturn("admin");

        RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
        endpoint.setId(1L);
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
        ScriptEnvironmentHintRuntimeRouter router = new ScriptEnvironmentHintRuntimeRouter(
                endpointMapper, runtimeClusterService, encryptionService, objectMapper, properties,
                new RuntimeEndpointSecurityService(properties), new RuntimeEndpointHeaderPolicy(),
                new RuntimeEndpointHttpClient(), securityService, projectResourceAccessService);
        return new Fixture(router, endpointMapper, runtimeClusterService,
                encryptionService, cluster);
    }

    private static final class Fixture {
        private final ScriptEnvironmentHintRuntimeRouter router;
        private final RuntimeEndpointMapper endpointMapper;
        private final RuntimeClusterService runtimeClusterService;
        private final EncryptionService encryptionService;
        private final RuntimeClusterEntity cluster;

        private Fixture(ScriptEnvironmentHintRuntimeRouter router,
                        RuntimeEndpointMapper endpointMapper,
                        RuntimeClusterService runtimeClusterService,
                        EncryptionService encryptionService,
                        RuntimeClusterEntity cluster) {
            this.router = router;
            this.endpointMapper = endpointMapper;
            this.runtimeClusterService = runtimeClusterService;
            this.encryptionService = encryptionService;
            this.cluster = cluster;
        }
    }
}
