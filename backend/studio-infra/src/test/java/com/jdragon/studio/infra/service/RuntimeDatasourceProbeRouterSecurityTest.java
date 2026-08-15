package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.enums.RuntimeDatasourceProbeMode;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeEndpointEntity;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RuntimeEndpointMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeDatasourceProbeRouterSecurityTest {

    @Test
    void shouldPropagateOperationIdOnlyForCorrelatedFileRequests() throws Exception {
        byte[] body = ("{\"success\":true,\"data\":{\"path\":\"/\"," +
                "\"entries\":[],\"hasMore\":false}}")
                .getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> operationId = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/datasource/file-browser", exchange -> {
            operationId.set(exchange.getRequestHeaders().getFirst(
                    RuntimeInternalHeaders.OPERATION_ID_HEADER));
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture(true);
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            fixture.router.browse(datasource(), 46L, "/", null, 20, "operation-123");

            assertEquals("operation-123", operationId.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectUnsafeOperationIdValues() {
        assertNull(RuntimeInternalHeaders.normalizeOperationId("invalid\r\noperation"));
        assertNull(RuntimeInternalHeaders.normalizeOperationId("x".repeat(65)));
        assertEquals("operation-123", RuntimeInternalHeaders.normalizeOperationId(" operation-123 "));
    }

    @Test
    void shouldRejectUnsafeEndpointBeforeDecryptingTransportSecrets() {
        Fixture fixture = fixture(false);
        fixture.endpoint.setHeadersCiphertext("headers-ciphertext");
        fixture.endpoint.setTokenCiphertext("token-ciphertext");
        when(fixture.encryption.decrypt("endpoint-ciphertext")).thenReturn("http://127.0.0.1:19090");

        ConnectionTestResult result = fixture.router.test(datasource(), 46L);

        assertFalse(result.isSuccess());
        assertEquals(DataSourceConnectionStatus.UNKNOWN, result.getStatus());
        verify(fixture.encryption, never()).decrypt("headers-ciphertext");
        verify(fixture.encryption, never()).decrypt("token-ciphertext");
    }

    @Test
    void shouldParseSuccessfulBoundedProbeResponse() throws Exception {
        byte[] body = ("{\"success\":true,\"data\":{\"success\":true," +
                "\"status\":\"AVAILABLE\",\"message\":\"ok\"}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(body);
        try {
            Fixture fixture = fixture(true);
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            ConnectionTestResult result = fixture.router.test(datasource(), 46L);

            assertTrue(result.isSuccess());
            assertEquals(DataSourceConnectionStatus.AVAILABLE, result.getStatus());
            assertEquals("ok", result.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPropagateExplicitRuntimeAndCallerIdentity() throws Exception {
        byte[] body = ("{\"success\":true,\"data\":{\"success\":true," +
                "\"status\":\"AVAILABLE\",\"message\":\"ok\"}}")
                .getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/datasource/probe", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        StudioRequestContext context = new StudioRequestContext();
        context.setTenantId("tenant-a");
        context.setProjectId(20L);
        context.setUserId(99L);
        context.setUsername("admin");
        StudioRequestContextHolder.setContext(context);
        try {
            Fixture fixture = fixture(true);
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            DataSourceDefinition datasource = datasource();
            datasource.getTechnicalMetadata().put("password", "plain-history-secret");
            ConnectionTestResult result = fixture.router.test(
                    datasource, 46L, RuntimeDatasourceProbeMode.STORED);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = new ObjectMapper().readValue(requestBody.get(), Map.class);
            assertEquals(46, ((Number) payload.get("targetClusterId")).intValue());
            assertEquals("C46", payload.get("targetClusterCode"));
            assertEquals("tenant-a", payload.get("tenantId"));
            assertEquals(20, ((Number) payload.get("projectId")).intValue());
            assertEquals(99, ((Number) payload.get("userId")).intValue());
            assertEquals("admin", payload.get("username"));
            assertEquals("STORED", payload.get("mode"));
            @SuppressWarnings("unchecked")
            Map<String, Object> datasourcePayload = (Map<String, Object>) payload.get("datasource");
            assertEquals(301, ((Number) datasourcePayload.get("id")).intValue());
            assertFalse(datasourcePayload.containsKey("technicalMetadata"));
            assertFalse(requestBody.get().contains("plain-history-secret"));
        } finally {
            StudioRequestContextHolder.clear();
            server.stop(0);
        }
    }

    @Test
    void shouldRouteServerAdjacentClusterThroughWorkerEndpoint() throws Exception {
        byte[] body = ("{\"success\":true,\"data\":{\"success\":true," +
                "\"status\":\"AVAILABLE\",\"message\":\"worker\"}}")
                .getBytes(StandardCharsets.UTF_8);
        AtomicInteger invocationCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/datasource/probe", exchange -> {
            invocationCount.incrementAndGet();
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture(true);
            fixture.properties.setRuntimeClusterCode("C46");
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            ConnectionTestResult result = fixture.router.test(datasource(), 46L);

            assertTrue(result.isSuccess());
            assertEquals("worker", result.getMessage());
            assertEquals(1, invocationCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSanitizeConfiguredHeadersForDatasourceProbes() throws Exception {
        byte[] body = ("{\"success\":true,\"data\":{\"success\":true," +
                "\"status\":\"AVAILABLE\",\"message\":\"ok\"}}")
                .getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> host = new AtomicReference<String>();
        AtomicReference<String> contentLength = new AtomicReference<String>();
        AtomicReference<String> dynamicHop = new AtomicReference<String>();
        AtomicReference<String> reservedStudioHeader = new AtomicReference<String>();
        AtomicReference<String> targetClusterHeader = new AtomicReference<String>();
        AtomicReference<String> allowedHeader = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/datasource/probe", exchange -> {
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            dynamicHop.set(exchange.getRequestHeaders().getFirst("X-Remove-Me"));
            reservedStudioHeader.set(exchange.getRequestHeaders().getFirst("X-Studio-Custom"));
            targetClusterHeader.set(exchange.getRequestHeaders().getFirst("X-Studio-Target-Cluster-Id"));
            allowedHeader.set(exchange.getRequestHeaders().getFirst("X-SLB-Access-Token"));
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture(true);
            fixture.endpoint.setHeadersCiphertext("headers-ciphertext");
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            when(fixture.encryption.decrypt("headers-ciphertext")).thenReturn("{"
                    + "\"Connection\":\"X-Remove-Me\","
                    + "\"X-Remove-Me\":\"dynamic\","
                    + "\"Host\":\"spoofed.example\","
                    + "\"Content-Length\":\"999\","
                    + "\"X-Studio-Custom\":\"reserved\","
                    + "\"X-Studio-Target-Cluster-Id\":\"999\","
                    + "\"X-SLB-Access-Token\":\"slb-token\"}");

            ConnectionTestResult result = fixture.router.test(datasource(), 46L);

            assertTrue(result.isSuccess());
            assertTrue(host.get().startsWith("127.0.0.1:"));
            assertFalse("999".equals(contentLength.get()));
            assertNull(dynamicHop.get());
            assertNull(reservedStudioHeader.get());
            assertEquals("46", targetClusterHeader.get());
            assertEquals("slb-token", allowedHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectProbeResponseThatExceedsConfiguredLimit() throws Exception {
        byte[] body = new byte[2048];
        HttpServer server = server(body);
        try {
            Fixture fixture = fixture(true);
            fixture.properties.getRuntimeEndpoint().setMaxResponseBytes(1024);
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            ConnectionTestResult result = fixture.router.test(datasource(), 46L);

            assertFalse(result.isSuccess());
            assertEquals(DataSourceConnectionStatus.UNKNOWN, result.getStatus());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPreserveWorkerDatasourceBusinessError() throws Exception {
        byte[] body = ("{\"success\":false,\"code\":\"BAD_REQUEST\"," +
                "\"message\":\"SQL syntax is invalid near ORDER\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/datasource/discover", exchange -> {
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture(true);
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            StudioException error = assertThrows(StudioException.class,
                    () -> fixture.router.discover(datasource(), 46L, null, 1, 20));

            assertEquals("BAD_REQUEST", error.getCode());
            assertEquals("SQL syntax is invalid near ORDER", error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectSuccessfulEnvelopeWithoutAuthenticatedWorkerMarker() throws Exception {
        byte[] body = ("{\"success\":true,\"data\":{\"success\":true," +
                "\"status\":\"AVAILABLE\",\"message\":\"untrusted\"}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/datasource/probe", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture(true);
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            ConnectionTestResult result = fixture.router.test(datasource(), 46L);

            assertFalse(result.isSuccess());
            assertEquals(DataSourceConnectionStatus.UNKNOWN, result.getStatus());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(byte[] responseBody) throws Exception {
        AtomicInteger invocationCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/datasource/probe", exchange -> {
            invocationCount.incrementAndGet();
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
        return server;
    }

    private void markAuthenticated(com.sun.net.httpserver.HttpExchange exchange) {
        exchange.getResponseHeaders().add(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
    }

    private DataSourceDefinition datasource() {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(301L);
        datasource.setTenantId("tenant-a");
        datasource.setProjectId(10L);
        return datasource;
    }

    private Fixture fixture(boolean allowLocalEndpoint) {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        EncryptionService encryption = mock(EncryptionService.class);
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(46L);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C46");
        cluster.setEnabled(1);
        when(clusterMapper.selectOne(any())).thenReturn(cluster);
        RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
        endpoint.setRuntimeClusterId(46L);
        endpoint.setMode("HTTP");
        endpoint.setEnabled(1);
        endpoint.setEndpointCiphertext("endpoint-ciphertext");
        endpoint.setConnectTimeoutMillis(3000);
        endpoint.setReadTimeoutMillis(3000);
        when(endpointMapper.selectOne(any())).thenReturn(endpoint);
        when(runtimeClusterService.hasOnlineInstance(cluster)).thenReturn(true);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeClusterCode("OMS");
        properties.setInternalApiToken("internal-token");
        if (allowLocalEndpoint) {
            properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
        }
        RuntimeDatasourceProbeRouter router = new RuntimeDatasourceProbeRouter(
                clusterMapper, endpointMapper, encryption, new ObjectMapper(), properties,
                runtimeClusterService, new RuntimeEndpointSecurityService(properties),
                new RuntimeEndpointHeaderPolicy(), new RuntimeEndpointHttpClient());
        WorkerAuthorizationService authorizationService = mock(WorkerAuthorizationService.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        when(authorizationService.isRuntimeClusterAuthorizedForProject("tenant-a", 10L, 46L))
                .thenReturn(true);
        when(authorizationService.isRuntimeClusterAuthorizedForProject("tenant-a", 20L, 46L))
                .thenReturn(true);
        when(bindingMapper.selectCount(any())).thenReturn(1L);
        router.setRuntimeIdentityServices(authorizationService, bindingMapper);
        return new Fixture(router, endpoint, encryption, properties);
    }

    private static final class Fixture {
        private final RuntimeDatasourceProbeRouter router;
        private final RuntimeEndpointEntity endpoint;
        private final EncryptionService encryption;
        private final StudioPlatformProperties properties;

        private Fixture(RuntimeDatasourceProbeRouter router, RuntimeEndpointEntity endpoint,
                        EncryptionService encryption, StudioPlatformProperties properties) {
            this.router = router;
            this.endpoint = endpoint;
            this.encryption = encryption;
            this.properties = properties;
        }
    }
}
