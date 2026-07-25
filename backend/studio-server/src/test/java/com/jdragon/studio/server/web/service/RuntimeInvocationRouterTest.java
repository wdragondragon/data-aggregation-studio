package com.jdragon.studio.server.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import com.jdragon.studio.dto.model.DataServiceDefinitionView;
import com.jdragon.studio.dto.model.ProtocolConversionServiceView;
import com.jdragon.studio.dto.model.request.DataIngestionDebugRequest;
import com.jdragon.studio.dto.model.request.DataServiceDebugRequest;
import com.jdragon.studio.dto.model.request.ProtocolConversionDebugRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeEndpointEntity;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RuntimeEndpointMapper;
import com.jdragon.studio.infra.mapper.RuntimeValidationMapper;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.RuntimeClusterService;
import com.jdragon.studio.infra.service.RuntimeEndpointHeaderPolicy;
import com.jdragon.studio.infra.service.RuntimeEndpointHttpClient;
import com.jdragon.studio.infra.service.RuntimeEndpointSecurityService;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class RuntimeInvocationRouterTest {

    @Test
    void shouldRejectChunkedFormBeforeCallingWorker() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.setRuntimeInvocationMaxBodyBytes(1024);
        when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
        when(fixture.encryption.decrypt("endpoint-ciphertext"))
                .thenReturn("http://127.0.0.1:1");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PATCH", "/openapi/protocol-conversions/orders/public") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent(("value=" + "x".repeat(1025)).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.router.routeIfRemote("protocol-conversions", "orders", "public", "REST",
                request, response);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void shouldForwardOnlyHashedIdempotencyMetadataAndFingerprintBusinessAuthentication() throws Exception {
        java.util.List<String> fingerprints = new java.util.concurrent.CopyOnWriteArrayList<String>();
        AtomicReference<String> keyHash = new AtomicReference<String>();
        AtomicReference<String> publicKey = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-ingestion-services/orders/public", exchange -> {
            keyHash.set(exchange.getRequestHeaders().getFirst(
                    RuntimeInternalHeaders.IDEMPOTENCY_KEY_HASH_HEADER));
            fingerprints.add(exchange.getRequestHeaders().getFirst(
                    RuntimeInternalHeaders.IDEMPOTENCY_FINGERPRINT_HEADER));
            publicKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            String[][] attempts = new String[][]{
                    {"subscription-a", "198.51.100.10", "trace-1"},
                    {"subscription-a", "198.51.100.20", "trace-2"},
                    {"subscription-b", "198.51.100.20", "trace-3"}
            };
            for (String[] attempt : attempts) {
                MockHttpServletRequest request = new MockHttpServletRequest(
                        "POST", "/openapi/data-ingestion-services/orders/public");
                request.addHeader("Idempotency-Key", "opaque-public-key");
                request.addHeader("X-Data-Ingestion-Token", attempt[0]);
                request.addHeader("Authorization", "Bearer business-token");
                request.addHeader("Traceparent", attempt[2]);
                request.setRemoteAddr(attempt[1]);
                request.setContentType("application/json");
                request.setContent("{\"id\":1}".getBytes(StandardCharsets.UTF_8));

                fixture.router.routeIfRemote("data-ingestion-services", "orders", "public", "REST",
                        request, new MockHttpServletResponse());
            }

            assertEquals(64, keyHash.get().length());
            assertFalse(keyHash.get().contains("opaque-public-key"));
            assertNull(publicKey.get());
            assertEquals(3, fingerprints.size());
            assertEquals(fingerprints.get(0), fingerprints.get(1));
            assertNotEquals(fingerprints.get(1), fingerprints.get(2));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRequireIdempotencyKeyForProtectedWritesWhenConfigured() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-ingestion-services/orders/public", exchange -> {
            invocationCount.incrementAndGet();
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            fixture.properties.getRuntimeInvocationIdempotency().setMode(
                    StudioPlatformProperties.RuntimeInvocationIdempotencyMode.REQUIRED_WRITE);
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "POST", "/openapi/data-ingestion-services/orders/public");
            request.setContentType("application/json");
            request.setContent("{}".getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();

            fixture.router.routeIfRemote("data-ingestion-services", "orders", "public", "REST",
                    request, response);

            assertEquals(400, response.getStatus());
            assertTrue(response.getContentAsString().contains("IDEMPOTENCY_KEY_REQUIRED"));
            assertEquals(0, invocationCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturn404InsteadOfFallingBackToServerExecutionWhenServiceIsMissing() throws Exception {
        Fixture fixture = fixture();
        when(fixture.dataServiceMapper.selectOne(any())).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean routed = fixture.router.routeIfRemote("data-services", "missing", "public", "REST",
                new MockHttpServletRequest("GET", "/openapi/data-services/missing/public"), response);

        assertTrue(routed);
        assertEquals(404, response.getStatus());
        assertTrue(response.getContentAsString().contains("NOT_FOUND"));
        verify(fixture.endpointMapper, never()).selectOne(any());
    }

    @Test
    void shouldRejectUnsafeEndpointBeforeReadingPayloadOrDecryptingTransportSecrets() throws Exception {
        Fixture fixture = fixture(false);
        RuntimeEndpointEntity endpoint = remoteEndpoint();
        endpoint.setHeadersCiphertext("headers-ciphertext");
        endpoint.setTokenCiphertext("token-ciphertext");
        when(fixture.endpointMapper.selectOne(any())).thenReturn(endpoint);
        when(fixture.encryption.decrypt("endpoint-ciphertext")).thenReturn("http://127.0.0.1:19090");
        MockHttpServletRequest request = spy(new MockHttpServletRequest("POST", "/api/public/orders"));
        request.setContent("business-secret".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean routed = fixture.router.routeIfRemote(
                "data-services", "orders", "public", "REST", request, response);

        assertTrue(routed);
        assertEquals(503, response.getStatus());
        verify(request, never()).getInputStream();
        verify(fixture.encryption, never()).decrypt("headers-ciphertext");
        verify(fixture.encryption, never()).decrypt("token-ciphertext");
    }

    @Test
    void shouldPreserveRemoteStatusBodyAndContentType() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            byte[] body = "<result>accepted</result>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml;charset=UTF-8");
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(202, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
            endpoint.setMode("HTTP");
            endpoint.setEnabled(1);
            endpoint.setEndpointCiphertext("endpoint-ciphertext");
            endpoint.setConnectTimeoutMillis(3000);
            endpoint.setReadTimeoutMillis(3000);
            when(fixture.endpointMapper.selectOne(any())).thenReturn(endpoint);
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/public/orders");
            request.setContent("request".getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean routed = fixture.router.routeIfRemote("data-services", "orders", "public", "REST", request, response);

            assertTrue(routed);
            assertEquals(202, response.getStatus());
            assertEquals("application/xml;charset=UTF-8", response.getContentType());
            assertEquals("<result>accepted</result>", response.getContentAsString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFailExplicitlyWhenRemoteResponseExceedsLimit() throws Exception {
        byte[] body = new byte[2048];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            fixture.properties.getRuntimeEndpoint().setMaxResponseBytes(1024);
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            MockHttpServletResponse response = new MockHttpServletResponse();

            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    new MockHttpServletRequest("GET", "/api/public/orders"), response);

            assertEquals(502, response.getStatus());
            assertTrue(response.getContentAsString().contains("exceeds the configured limit"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNotExposeRemoteAuthenticationOrInternalResponseHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Set-Cookie", "session=remote-secret");
            exchange.getResponseHeaders().add("Set-Cookie2", "legacy=remote-secret");
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=runtime");
            exchange.getResponseHeaders().add("X-Studio", "internal-root-marker");
            exchange.getResponseHeaders().add("X-Studio-Worker-Id", "worker-secret");
            exchange.getResponseHeaders().add("X-Business-Trace", "trace-1");
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            MockHttpServletResponse response = new MockHttpServletResponse();

            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    new MockHttpServletRequest("GET", "/api/public/orders"), response);

            assertEquals("trace-1", response.getHeader("X-Business-Trace"));
            assertFalse(response.containsHeader("Set-Cookie"));
            assertFalse(response.containsHeader("Set-Cookie2"));
            assertFalse(response.containsHeader("WWW-Authenticate"));
            assertFalse(response.containsHeader("X-Studio"));
            assertFalse(response.containsHeader("X-Studio-Worker-Id"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldStripConnectionDeclaredRequestHeaders() throws Exception {
        AtomicReference<String> dynamicHop = new AtomicReference<String>();
        AtomicReference<String> businessTrace = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            dynamicHop.set(exchange.getRequestHeaders().getFirst("X-Request-Hop"));
            businessTrace.set(exchange.getRequestHeaders().getFirst("X-Business-Trace"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/orders");
            request.addHeader("Connection", "keep-alive, X-Request-Hop");
            request.addHeader("X-Request-Hop", "secret");
            request.addHeader("X-Business-Trace", "trace-1");

            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    request, new MockHttpServletResponse());

            assertEquals(null, dynamicHop.get());
            assertEquals("trace-1", businessTrace.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNotAllowCallerHeadersToOverrideManagedTransportHeaders() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<String>();
        AtomicReference<String> accessToken = new AtomicReference<String>();
        AtomicReference<String> internalToken = new AtomicReference<String>();
        AtomicReference<String> targetClusterId = new AtomicReference<String>();
        AtomicReference<String> studioRootHeader = new AtomicReference<String>();
        AtomicReference<String> host = new AtomicReference<String>();
        AtomicReference<String> contentLength = new AtomicReference<String>();
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            accessToken.set(exchange.getRequestHeaders().getFirst("X-SLB-Access-Token"));
            internalToken.set(exchange.getRequestHeaders().getFirst(RuntimeInvocationRouter.INTERNAL_TOKEN_HEADER));
            targetClusterId.set(exchange.getRequestHeaders().getFirst(RuntimeInvocationRouter.TARGET_CLUSTER_ID_HEADER));
            studioRootHeader.set(exchange.getRequestHeaders().getFirst("X-Studio"));
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            RuntimeEndpointEntity endpoint = remoteEndpoint();
            endpoint.setHeadersCiphertext("headers-ciphertext");
            when(fixture.endpointMapper.selectOne(any())).thenReturn(endpoint);
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            when(fixture.encryption.decrypt("headers-ciphertext")).thenReturn("{"
                    + "\"Authorization\":\"Bearer managed-secret\","
                    + "\"X-SLB-Access-Token\":\"managed-token\","
                    + "\"X-Studio-Internal-Token\":\"configured-attacker\","
                    + "\"X-Studio\":\"configured-root-attacker\","
                    + "\"Host\":\"configured.invalid\","
                    + "\"Content-Length\":\"999\"}");
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/public/orders");
            request.setContent("ok".getBytes(StandardCharsets.UTF_8));
            request.addHeader("Connection", "Authorization, X-SLB-Access-Token");
            request.addHeader("Authorization", "Bearer caller-secret");
            request.addHeader("X-SLB-Access-Token", "caller-token");
            request.addHeader(RuntimeInvocationRouter.INTERNAL_TOKEN_HEADER, "caller-internal");
            request.addHeader(RuntimeInvocationRouter.TARGET_CLUSTER_ID_HEADER, "999");
            request.addHeader("X-Studio", "caller-root");
            request.addHeader("Host", "caller.invalid");
            request.addHeader("Content-Length", "999");

            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    request, new MockHttpServletResponse());

            assertEquals("Bearer managed-secret", authorization.get());
            assertEquals("managed-token", accessToken.get());
            assertEquals("internal-token", internalToken.get());
            assertEquals("50", targetClusterId.get());
            assertEquals(null, studioRootHeader.get());
            assertFalse("caller.invalid".equals(host.get()));
            assertFalse("configured.invalid".equals(host.get()));
            assertFalse("999".equals(contentLength.get()));
            assertEquals("ok", requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFailClosedWhenStoredEndpointHeadersAreInvalid() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            invocationCount.incrementAndGet();
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            RuntimeEndpointEntity endpoint = remoteEndpoint();
            endpoint.setHeadersCiphertext("headers-ciphertext");
            when(fixture.endpointMapper.selectOne(any())).thenReturn(endpoint);
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            when(fixture.encryption.decrypt("headers-ciphertext")).thenReturn("{invalid-json");
            MockHttpServletResponse response = new MockHttpServletResponse();

            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    new MockHttpServletRequest("GET", "/api/public/orders"), response);

            assertEquals(503, response.getStatus());
            assertEquals(0, invocationCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldStripConnectionDeclaredResponseHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Connection", "close, X-Response-Hop");
            exchange.getResponseHeaders().add("X-Response-Hop", "secret");
            exchange.getResponseHeaders().add("X-Business-Trace", "trace-1");
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            MockHttpServletResponse response = new MockHttpServletResponse();

            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    new MockHttpServletRequest("GET", "/api/public/orders"), response);

            assertFalse(response.containsHeader("Connection"));
            assertFalse(response.containsHeader("X-Response-Hop"));
            assertEquals("trace-1", response.getHeader("X-Business-Trace"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldMapOnlyMarkedInternalAuthenticationFailuresTo503() throws Exception {
        for (int remoteStatus : new int[]{401, 403}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
                byte[] body = "internal authentication failed".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER,
                        RuntimeInternalHeaders.INTERNAL_AUTHENTICATION);
                exchange.sendResponseHeaders(remoteStatus, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try {
                Fixture fixture = fixture();
                when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
                when(fixture.encryption.decrypt("endpoint-ciphertext"))
                        .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
                MockHttpServletResponse response = new MockHttpServletResponse();

                fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                        new MockHttpServletRequest("GET", "/api/public/orders"), response);

                assertEquals(503, response.getStatus());
                assertTrue(response.getContentAsString().contains("internal authentication"));
                assertFalse(response.containsHeader(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER));
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void shouldPreserveAuthenticatedRuntimeBusinessAuthenticationFailures() throws Exception {
        for (int remoteStatus : new int[]{401, 403}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
                byte[] body = ("business-auth-" + remoteStatus).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=UTF-8");
                exchange.getResponseHeaders().add(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                        RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
                exchange.sendResponseHeaders(remoteStatus, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try {
                Fixture fixture = fixture();
                when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
                when(fixture.encryption.decrypt("endpoint-ciphertext"))
                        .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
                MockHttpServletResponse response = new MockHttpServletResponse();

                fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                        new MockHttpServletRequest("GET", "/api/public/orders"), response);

                assertEquals(remoteStatus, response.getStatus());
                assertEquals("business-auth-" + remoteStatus, response.getContentAsString());
                assertFalse(response.containsHeader(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER));
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void shouldMapUnmarkedTransportAuthenticationFailureTo503() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            byte[] body = "SLB access token rejected".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            MockHttpServletResponse response = new MockHttpServletResponse();

            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    new MockHttpServletRequest("GET", "/api/public/orders"), response);

            assertEquals(503, response.getStatus());
            assertFalse(response.getContentAsString().contains("SLB access token"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectSuccessfulResponseWithoutAuthenticatedWorkerMarker() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            byte[] body = "misdirected upstream".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            MockHttpServletResponse response = new MockHttpServletResponse();

            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    new MockHttpServletRequest("GET", "/api/public/orders"), response);

            assertEquals(503, response.getStatus());
            assertTrue(response.getContentAsString().contains("authenticated Worker response"));
            assertFalse(response.getContentAsString().contains("misdirected upstream"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFilterInternalLocationAndPreserveExternalLocation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            String mode = exchange.getRequestURI().getQuery();
            String location;
            if ("external=true".equals(mode)) {
                location = "https://login.example.test/oauth";
            } else if ("scheme-relative=true".equals(mode)) {
                location = "//127.0.0.1:" + server.getAddress().getPort() + "/login";
            } else if ("private=true".equals(mode)) {
                location = "http://10.46.0.12/login";
            } else if ("relative=true".equals(mode)) {
                location = "/public/login";
            } else {
                location = "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/internal/runtime/secret";
            }
            exchange.getResponseHeaders().add("Location", location);
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            MockHttpServletResponse internalResponse = new MockHttpServletResponse();
            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    new MockHttpServletRequest("GET", "/api/public/orders"), internalResponse);
            assertEquals(302, internalResponse.getStatus());
            assertFalse(internalResponse.containsHeader("Location"));

            MockHttpServletRequest externalRequest = new MockHttpServletRequest("GET", "/api/public/orders");
            externalRequest.setQueryString("external=true");
            MockHttpServletResponse externalResponse = new MockHttpServletResponse();
            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    externalRequest, externalResponse);
            assertEquals(302, externalResponse.getStatus());
            assertEquals("https://login.example.test/oauth", externalResponse.getHeader("Location"));

            MockHttpServletRequest schemeRelativeRequest = new MockHttpServletRequest("GET", "/api/public/orders");
            schemeRelativeRequest.setQueryString("scheme-relative=true");
            MockHttpServletResponse schemeRelativeResponse = new MockHttpServletResponse();
            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    schemeRelativeRequest, schemeRelativeResponse);
            assertFalse(schemeRelativeResponse.containsHeader("Location"));

            MockHttpServletRequest privateRequest = new MockHttpServletRequest("GET", "/api/public/orders");
            privateRequest.setQueryString("private=true");
            MockHttpServletResponse privateResponse = new MockHttpServletResponse();
            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    privateRequest, privateResponse);
            assertFalse(privateResponse.containsHeader("Location"));

            MockHttpServletRequest relativeRequest = new MockHttpServletRequest("GET", "/api/public/orders");
            relativeRequest.setQueryString("relative=true");
            MockHttpServletResponse relativeResponse = new MockHttpServletResponse();
            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    relativeRequest, relativeResponse);
            assertEquals("/public/login", relativeResponse.getHeader("Location"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRouteEachSameCodeServiceOnceThroughWorkerEndpoint() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger();
        java.util.List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/", exchange -> {
            invocationCount.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            fixture.properties.setRuntimeClusterCode("C50");
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            for (String kind : serviceKinds()) {
                MockHttpServletResponse response = new MockHttpServletResponse();

                boolean routed = fixture.router.routeIfRemote(kind, "orders", "public", "REST",
                        new MockHttpServletRequest("GET", "/api/public/orders"), response);

                assertTrue(routed);
                assertEquals(200, response.getStatus());
            }

            assertEquals(3, invocationCount.get());
            assertTrue(paths.contains("/internal/runtime/data-services/orders/public"));
            assertTrue(paths.contains("/internal/runtime/data-ingestion-services/orders/public"));
            assertTrue(paths.contains("/internal/runtime/protocol-conversions/orders/public"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRouteSameCodeManagementDebugThroughWorkerEndpoint() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger();
        java.util.List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/debug/", exchange -> {
            invocationCount.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = ("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"OK\"," +
                    "\"data\":null,\"timestamp\":null}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            fixture.properties.setRuntimeClusterCode("C50");
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            RuntimeInvocationRouter.DebugRoute<java.util.Map<String, Object>> dataServiceRoute =
                    fixture.router.routeDataServiceDebug(dataServiceView(), new DataServiceDebugRequest());
            RuntimeInvocationRouter.DebugRoute<com.jdragon.studio.dto.model.DataIngestionInvokeResult> ingestionRoute =
                    fixture.router.routeDataIngestionDebug(dataIngestionView(), new DataIngestionDebugRequest());
            RuntimeInvocationRouter.DebugRoute<com.jdragon.studio.dto.model.ProtocolConversionDebugResult> conversionRoute =
                    fixture.router.routeProtocolConversionDebug(protocolConversionView(),
                            new ProtocolConversionDebugRequest());

            assertTrue(dataServiceRoute.isHandled());
            assertTrue(ingestionRoute.isHandled());
            assertTrue(conversionRoute.isHandled());
            assertEquals(200, dataServiceRoute.getStatus());
            assertEquals(200, ingestionRoute.getStatus());
            assertEquals(200, conversionRoute.getStatus());
            assertEquals(3, invocationCount.get());
            assertTrue(paths.contains("/internal/runtime/debug/data-services/31"));
            assertTrue(paths.contains("/internal/runtime/debug/data-ingestion-services/32"));
            assertTrue(paths.contains("/internal/runtime/debug/protocol-conversions/33"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturn503WhenSameCodeServiceTargetsHaveNoAvailableEndpoint() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.setRuntimeClusterCode("C50");
        when(fixture.endpointMapper.selectOne(any())).thenReturn(null);

        for (String kind : serviceKinds()) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean routed = fixture.router.routeIfRemote(kind, "orders", "public", "REST",
                    new MockHttpServletRequest("GET", "/api/public/orders"), response);

            assertTrue(routed);
            assertEquals(503, response.getStatus());
            assertEquals("application/json;charset=UTF-8", response.getContentType());
            assertTrue(response.getContentAsString().contains("no available HTTP endpoint"));
        }
        verify(fixture.endpointMapper, times(3)).selectOne(any());
    }

    @Test
    void shouldReturn503WithoutResolvingEndpointWhenSameCodeServiceClusterIsOffline() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.setRuntimeClusterCode("C50");
        when(fixture.runtimeClusterService.hasOnlineInstance(any(RuntimeClusterEntity.class))).thenReturn(false);

        for (String kind : serviceKinds()) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean routed = fixture.router.routeIfRemote(kind, "orders", "public", "REST",
                    new MockHttpServletRequest("GET", "/api/public/orders"), response);

            assertTrue(routed);
            assertEquals(503, response.getStatus());
            assertTrue(response.getContentAsString().contains("no online instance"));
        }
        verify(fixture.endpointMapper, never()).selectOne(any());
        verify(fixture.encryption, never()).decrypt(any());
    }

    @Test
    void shouldRejectClusterFromAnotherTenant() throws Exception {
        Fixture fixture = fixture();
        RuntimeClusterEntity foreignCluster = new RuntimeClusterEntity();
        foreignCluster.setId(50L);
        foreignCluster.setTenantId("tenant-b");
        foreignCluster.setCode("C50");
        foreignCluster.setEnabled(1);
        when(fixture.clusterMapper.selectById(50L)).thenReturn(foreignCluster);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean routed = fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                new MockHttpServletRequest("GET", "/api/public/orders"), response);

        assertTrue(routed);
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("disabled or missing"));
    }

    @Test
    void shouldSelectOnlyHttpEndpointWithStableOrdering() throws Exception {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "runtime-endpoint-test");
        assistant.setCurrentNamespace("runtime-endpoint-test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, RuntimeEndpointEntity.class);
        Fixture fixture = fixture();
        when(fixture.endpointMapper.selectOne(any())).thenReturn(null);

        fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                new MockHttpServletRequest("GET", "/api/public/orders"), new MockHttpServletResponse());

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RuntimeEndpointEntity>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(fixture.endpointMapper).selectOne(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment().toLowerCase();
        assertTrue(sqlSegment.contains("mode"));
        assertTrue(sqlSegment.contains("order by"));
        assertTrue(sqlSegment.contains("asc"));
    }

    @Test
    void shouldForwardDirectClientAddressWhenForwardedHeaderIsMissing() throws Exception {
        AtomicReference<String> forwardedFor = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            forwardedFor.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/orders");
            request.setRemoteAddr("198.51.100.21");

            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    request, new MockHttpServletResponse());

            assertEquals("198.51.100.21", forwardedFor.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldMarkConfiguredHeadersAsTransportOnly() throws Exception {
        AtomicReference<String> transportHeaderNames = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/data-services/orders/public", exchange -> {
            transportHeaderNames.set(exchange.getRequestHeaders().getFirst(
                    RuntimeInvocationRouter.TRANSPORT_HEADER_NAMES_HEADER));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            RuntimeEndpointEntity endpoint = remoteEndpoint();
            endpoint.setHeadersCiphertext("headers-ciphertext");
            when(fixture.endpointMapper.selectOne(any())).thenReturn(endpoint);
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            when(fixture.encryption.decrypt("headers-ciphertext"))
                    .thenReturn("{\"Cookie\":\"session=transport-secret\",\"X-SLB-Access-Token\":\"secret\"}");

            fixture.router.routeIfRemote("data-services", "orders", "public", "REST",
                    new MockHttpServletRequest("GET", "/api/public/orders"), new MockHttpServletResponse());

            assertEquals("Cookie,X-SLB-Access-Token", transportHeaderNames.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturn503WhenManagementDebugTargetHasNoAvailableEndpoint() {
        Fixture fixture = fixture();
        when(fixture.endpointMapper.selectOne(any())).thenReturn(null);

        RuntimeInvocationRouter.DebugRoute<java.util.Map<String, Object>> route =
                fixture.router.routeDataServiceDebug(dataServiceView(), new DataServiceDebugRequest());

        assertTrue(route.isHandled());
        assertEquals(503, route.getStatus());
        assertEquals("SERVICE_UNAVAILABLE", route.getResult().getCode());
        assertTrue(route.getResult().getMessage().contains("no available HTTP endpoint"));
    }

    @Test
    void shouldRouteManagementDebugWithSeparatedTransportAuthentication() throws Exception {
        AtomicReference<String> transportAuthorization = new AtomicReference<String>();
        AtomicReference<String> internalToken = new AtomicReference<String>();
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/debug/data-services/31", exchange -> {
            transportAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            internalToken.set(exchange.getRequestHeaders().getFirst(RuntimeInvocationRouter.INTERNAL_TOKEN_HEADER));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"success\":true,\"code\":\"SUCCESS\",\"message\":\"OK\"," +
                    "\"data\":{\"accepted\":true},\"timestamp\":null}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            RuntimeEndpointEntity endpoint = remoteEndpoint();
            endpoint.setTokenCiphertext("token-ciphertext");
            when(fixture.endpointMapper.selectOne(any())).thenReturn(endpoint);
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            when(fixture.encryption.decrypt("token-ciphertext")).thenReturn("transport-secret");
            DataServiceDebugRequest request = new DataServiceDebugRequest();
            request.getHeaders().put("Authorization", "business-secret");

            RuntimeInvocationRouter.DebugRoute<java.util.Map<String, Object>> route =
                    fixture.router.routeDataServiceDebug(dataServiceView(), request);

            assertTrue(route.isHandled());
            assertEquals(200, route.getStatus());
            assertTrue(route.getResult().isSuccess());
            assertEquals(Boolean.TRUE, route.getResult().getData().get("accepted"));
            assertEquals("Bearer transport-secret", transportAuthorization.get());
            assertEquals("internal-token", internalToken.get());
            assertTrue(requestBody.get().contains("business-secret"));
            assertFalse(requestBody.get().contains("transport-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPreserveRemoteDebugFailureWithoutRetry() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/debug/data-services/31", exchange -> {
            invocationCount.incrementAndGet();
            byte[] body = ("{\"success\":false,\"code\":\"INTERNAL_SERVER_ERROR\"," +
                    "\"message\":\"remote debug failed\",\"data\":null,\"timestamp\":null}")
                    .getBytes(StandardCharsets.UTF_8);
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            RuntimeInvocationRouter.DebugRoute<java.util.Map<String, Object>> route =
                    fixture.router.routeDataServiceDebug(dataServiceView(), new DataServiceDebugRequest());

            assertTrue(route.isHandled());
            assertEquals(500, route.getStatus());
            assertFalse(route.getResult().isSuccess());
            assertEquals("remote debug failed", route.getResult().getMessage());
            assertEquals(1, invocationCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldExposeInternalAuthenticationFailureAs503() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/debug/data-services/31", exchange -> {
            byte[] body = ("{\"success\":false,\"code\":\"UNAUTHORIZED\"," +
                    "\"message\":\"invalid internal token\",\"data\":null,\"timestamp\":null}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER,
                    RuntimeInternalHeaders.INTERNAL_AUTHENTICATION);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            RuntimeInvocationRouter.DebugRoute<java.util.Map<String, Object>> route =
                    fixture.router.routeDataServiceDebug(dataServiceView(), new DataServiceDebugRequest());

            assertEquals(503, route.getStatus());
            assertEquals("SERVICE_UNAVAILABLE", route.getResult().getCode());
            assertTrue(route.getResult().getMessage().contains("internal authentication"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPreserveAuthenticatedRuntimeBusinessAuthenticationFailureForDebug() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/debug/data-services/31", exchange -> {
            byte[] body = ("{\"success\":false,\"code\":\"UNAUTHORIZED\"," +
                    "\"message\":\"business token rejected\",\"data\":null,\"timestamp\":null}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                    RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            RuntimeInvocationRouter.DebugRoute<java.util.Map<String, Object>> route =
                    fixture.router.routeDataServiceDebug(dataServiceView(), new DataServiceDebugRequest());

            assertEquals(401, route.getStatus());
            assertEquals("UNAUTHORIZED", route.getResult().getCode());
            assertEquals("business token rejected", route.getResult().getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFailDebugExplicitlyWhenRemoteResponseExceedsLimit() throws Exception {
        byte[] body = new byte[2048];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/runtime/debug/data-services/31", exchange -> {
            markAuthenticated(exchange);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = fixture();
            fixture.properties.getRuntimeEndpoint().setMaxResponseBytes(1024);
            when(fixture.endpointMapper.selectOne(any())).thenReturn(remoteEndpoint());
            when(fixture.encryption.decrypt("endpoint-ciphertext"))
                    .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

            RuntimeInvocationRouter.DebugRoute<java.util.Map<String, Object>> route =
                    fixture.router.routeDataServiceDebug(dataServiceView(), new DataServiceDebugRequest());

            assertTrue(route.isHandled());
            assertEquals(502, route.getStatus());
            assertTrue(route.getResult().getMessage().contains("exceeds the configured limit"));
        } finally {
            server.stop(0);
        }
    }

    private RuntimeEndpointEntity remoteEndpoint() {
        RuntimeEndpointEntity endpoint = new RuntimeEndpointEntity();
        endpoint.setRuntimeClusterId(50L);
        endpoint.setMode("HTTP");
        endpoint.setEnabled(1);
        endpoint.setEndpointCiphertext("endpoint-ciphertext");
        endpoint.setConnectTimeoutMillis(3000);
        endpoint.setReadTimeoutMillis(3000);
        return endpoint;
    }

    private DataServiceDefinitionView dataServiceView() {
        DataServiceDefinitionView view = new DataServiceDefinitionView();
        view.setId(31L);
        view.setTenantId("tenant-a");
        view.setProjectId(101L);
        view.setRuntimeClusterId(50L);
        return view;
    }

    private DataIngestionServiceView dataIngestionView() {
        DataIngestionServiceView view = new DataIngestionServiceView();
        view.setId(32L);
        view.setTenantId("tenant-a");
        view.setProjectId(101L);
        view.setRuntimeClusterId(50L);
        return view;
    }

    private ProtocolConversionServiceView protocolConversionView() {
        ProtocolConversionServiceView view = new ProtocolConversionServiceView();
        view.setId(33L);
        view.setTenantId("tenant-a");
        view.setProjectId(101L);
        view.setRuntimeClusterId(50L);
        return view;
    }

    private String[] serviceKinds() {
        return new String[]{"data-services", "data-ingestion-services", "protocol-conversions"};
    }

    private void markAuthenticated(com.sun.net.httpserver.HttpExchange exchange) {
        exchange.getResponseHeaders().add(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
    }

    private Fixture fixture() {
        return fixture(true);
    }

    private Fixture fixture(boolean allowLocalEndpoint) {
        DataServiceDefinitionMapper dataServiceMapper = mock(DataServiceDefinitionMapper.class);
        DataServiceDefinitionEntity definition = new DataServiceDefinitionEntity();
        definition.setId(31L);
        definition.setTenantId("tenant-a");
        definition.setProjectId(101L);
        definition.setRuntimeClusterId(50L);
        when(dataServiceMapper.selectOne(any())).thenReturn(definition);
        DataIngestionServiceMapper ingestionMapper = mock(DataIngestionServiceMapper.class);
        DataIngestionServiceEntity ingestion = new DataIngestionServiceEntity();
        ingestion.setId(32L);
        ingestion.setTenantId("tenant-a");
        ingestion.setProjectId(101L);
        ingestion.setRuntimeClusterId(50L);
        when(ingestionMapper.selectOne(any())).thenReturn(ingestion);
        ProtocolConversionServiceMapper conversionMapper = mock(ProtocolConversionServiceMapper.class);
        ProtocolConversionServiceEntity conversion = new ProtocolConversionServiceEntity();
        conversion.setId(33L);
        conversion.setTenantId("tenant-a");
        conversion.setProjectId(101L);
        conversion.setRuntimeClusterId(50L);
        when(conversionMapper.selectOne(any())).thenReturn(conversion);
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(50L);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C50");
        cluster.setEnabled(1);
        when(clusterMapper.selectById(50L)).thenReturn(cluster);
        RuntimeEndpointMapper endpointMapper = mock(RuntimeEndpointMapper.class);
        EncryptionService encryption = mock(EncryptionService.class);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeClusterCode("OMS");
        properties.setInternalApiToken("internal-token");
        if (allowLocalEndpoint) {
            properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
        }
        RuntimeEndpointSecurityService endpointSecurityService = new RuntimeEndpointSecurityService(properties) {
            @Override
            protected InetAddress[] resolveHost(String host) throws UnknownHostException {
                if ("login.example.test".equals(host)) {
                    return new InetAddress[]{InetAddress.getByAddress(host,
                            new byte[]{(byte) 198, 51, 100, 20})};
                }
                return super.resolveHost(host);
            }

            @Override
            protected boolean isLocalInterfaceAddress(InetAddress address) {
                return false;
            }
        };
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        when(runtimeClusterService.hasOnlineInstance(any(RuntimeClusterEntity.class))).thenReturn(true);
        RuntimeInvocationRouter router = new RuntimeInvocationRouter(dataServiceMapper, ingestionMapper,
                conversionMapper, clusterMapper, endpointMapper, encryption,
                new ObjectMapper().findAndRegisterModules(), properties, endpointSecurityService,
                new RuntimeEndpointHeaderPolicy(),
                new RuntimeEndpointHttpClient(),
                runtimeClusterService);
        RuntimeValidationMapper runtimeValidationMapper = mock(RuntimeValidationMapper.class);
        when(runtimeValidationMapper.selectCount(any())).thenReturn(0L);
        router.setRuntimeValidationMapper(runtimeValidationMapper);
        return new Fixture(router, dataServiceMapper, clusterMapper, endpointMapper,
                encryption, properties, runtimeClusterService);
    }

    private static class Fixture {
        private final RuntimeInvocationRouter router;
        private final DataServiceDefinitionMapper dataServiceMapper;
        private final RuntimeClusterMapper clusterMapper;
        private final RuntimeEndpointMapper endpointMapper;
        private final EncryptionService encryption;
        private final StudioPlatformProperties properties;
        private final RuntimeClusterService runtimeClusterService;
        private Fixture(RuntimeInvocationRouter router, DataServiceDefinitionMapper dataServiceMapper,
                         RuntimeClusterMapper clusterMapper,
                         RuntimeEndpointMapper endpointMapper, EncryptionService encryption,
                         StudioPlatformProperties properties,
                         RuntimeClusterService runtimeClusterService) {
            this.router = router;
            this.dataServiceMapper = dataServiceMapper;
            this.clusterMapper = clusterMapper;
            this.endpointMapper = endpointMapper;
            this.encryption = encryption;
            this.properties = properties;
            this.runtimeClusterService = runtimeClusterService;
        }
    }
}
