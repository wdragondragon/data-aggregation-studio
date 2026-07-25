package com.jdragon.studio.server.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeFlinkExecutionRouterTest {

    @Test
    void shouldSendExecutionAndRequestContextToSelectedWorkerEndpoint() throws Exception {
        AtomicReference<String> internalToken = new AtomicReference<String>();
        AtomicReference<String> clusterId = new AtomicReference<String>();
        AtomicReference<JsonNode> requestPayload = new AtomicReference<JsonNode>();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        byte[] response = ("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"OK\"," +
                "\"data\":{\"sql\":\"SELECT * FROM m_7\",\"columns\":[\"id\"]," +
                "\"rows\":[{\"id\":1}],\"warnings\":[],\"summary\":{}}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/flink/sql/execute", exchange -> {
            internalToken.set(exchange.getRequestHeaders().getFirst("X-Studio-Internal-Token"));
            clusterId.set(exchange.getRequestHeaders().getFirst("X-Studio-Target-Cluster-Id"));
            requestPayload.set(objectMapper.readTree(exchange.getRequestBody()));
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

            FlinkQuestionResultView result = fixture.router.execute(executionRequest());

            assertEquals(List.of("id"), result.getColumns());
            assertEquals("internal-token", internalToken.get());
            assertEquals("50", clusterId.get());
            assertEquals("tenant-a", requestPayload.get().path("tenantId").asText());
            assertEquals(101L, requestPayload.get().path("projectId").asLong());
            assertEquals(7L, requestPayload.get().path("execution").path("modelIds").get(0).asLong());
            assertEquals(50L, requestPayload.get().path("execution").path("runtimeClusterId").asLong());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPreserveWorkerBusinessError() throws Exception {
        byte[] response = ("{\"success\":false,\"code\":\"BAD_REQUEST\"," +
                "\"message\":\"Selected model cannot run in cluster C50\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/flink/sql/execute", exchange -> {
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture(new ObjectMapper().findAndRegisterModules());
            when(fixture.encryptionService.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            StudioException error = assertThrows(StudioException.class,
                    () -> fixture.router.execute(executionRequest()));

            assertEquals("BAD_REQUEST", error.getCode());
            assertEquals("Selected model cannot run in cluster C50", error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFailBeforeResolvingEndpointWhenTargetWorkerIsOffline() {
        Fixture fixture = fixture(new ObjectMapper().findAndRegisterModules());
        when(fixture.runtimeClusterService.hasOnlineInstance(fixture.cluster)).thenReturn(false);

        StudioException error = assertThrows(StudioException.class,
                () -> fixture.router.execute(executionRequest()));

        assertEquals("SERVICE_UNAVAILABLE", error.getCode());
        assertTrue(error.getMessage().contains("no online Worker"));
        verify(fixture.endpointMapper, never()).selectOne(any());
        verify(fixture.encryptionService, never()).decrypt(any());
    }

    @Test
    void shouldRejectSuccessfulPayloadWithoutAuthenticatedWorkerMarker() throws Exception {
        byte[] response = ("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"OK\"," +
                "\"data\":{\"sql\":\"SELECT 1\",\"columns\":[],\"rows\":[],\"warnings\":[],\"summary\":{}}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/flink/sql/execute", exchange -> {
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
                    () -> fixture.router.execute(executionRequest()));

            assertEquals("SERVICE_UNAVAILABLE", error.getCode());
            assertTrue(error.getMessage().contains("authenticated Worker response"));
        } finally {
            server.stop(0);
        }
    }

    private void markAuthenticated(com.sun.net.httpserver.HttpExchange exchange) {
        exchange.getResponseHeaders().add(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
    }

    private FlinkSqlExecuteRequest executionRequest() {
        FlinkSqlExecuteRequest request = new FlinkSqlExecuteRequest();
        request.setRuntimeClusterId(50L);
        request.setModelIds(List.of(7L));
        request.setSql("SELECT * FROM m_7");
        request.setMaxRows(100);
        return request;
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
        RuntimeFlinkExecutionRouter router = new RuntimeFlinkExecutionRouter(
                endpointMapper, runtimeClusterService, encryptionService, objectMapper, properties,
                new RuntimeEndpointSecurityService(properties), new RuntimeEndpointHeaderPolicy(),
                new RuntimeEndpointHttpClient(), securityService, projectResourceAccessService);
        return new Fixture(router, endpointMapper, runtimeClusterService, encryptionService, cluster);
    }

    private static final class Fixture {
        private final RuntimeFlinkExecutionRouter router;
        private final RuntimeEndpointMapper endpointMapper;
        private final RuntimeClusterService runtimeClusterService;
        private final EncryptionService encryptionService;
        private final RuntimeClusterEntity cluster;

        private Fixture(RuntimeFlinkExecutionRouter router,
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
