package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.AlertChannelEntity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElinkAlertSenderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSendPersonalTextThroughDiscoveredManagerAndAcceptOfficialSuccessResponse() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<String>();
        AtomicReference<byte[]> requestBody = new AtomicReference<byte[]>();
        try (TestServer server = TestServer.start(exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200,
                    "{\"errcode\":0,\"errmsg\":\"ok\",\"invaliduser\":\"missing-user\",\"jobid\":\"job-1\"}");
        })) {
            AlertChannelService channelService = mock(AlertChannelService.class);
            AlertChannelEntity channel = new AlertChannelEntity();
            when(channelService.elinkTargetType(channel)).thenReturn("PERSONAL");
            when(channelService.elinkUserIds(channel)).thenReturn(List.of("user-1", "user-2"));
            ElinkAlertSender sender = sender(channelService, discovery(server.port()));
            Map<String, Object> payload = payload("告警".repeat(1000));

            ElinkAlertSender.SendResult result = sender.send(channel, payload);

            assertTrue(result.isSuccess());
            assertEquals(200, result.getHttpStatus());
            assertEquals("/elink/messages", requestPath.get());
            JsonNode request = objectMapper.readTree(requestBody.get());
            assertEquals("text", request.path("msgType").asText());
            assertEquals("user-1", request.path("userIds").get(0).asText());
            assertTrue(request.path("content").asText().getBytes(StandardCharsets.UTF_8).length <= 2048);
            assertTrue(result.getResponseExcerpt().contains("\"errcode\":0"));
            assertTrue(result.getResponseExcerpt().contains("\"jobId\":\"job-1\""));
            assertTrue(result.getResponseExcerpt().contains("\"invaliduser\":\"missing-user\""));
        }
    }

    @Test
    void shouldSendGroupTextAndPreserveManagerBusinessError() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<String>();
        try (TestServer server = TestServer.start(exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{\"success\":false,\"errcode\":40014,"
                    + "\"errmsg\":\"token expired\",\"errorMessage\":\"eLink 原始发送错误\"}");
        })) {
            AlertChannelService channelService = mock(AlertChannelService.class);
            AlertChannelEntity channel = new AlertChannelEntity();
            when(channelService.elinkTargetType(channel)).thenReturn("GROUP");
            when(channelService.elinkGroupId(channel)).thenReturn(77L);
            ElinkAlertSender sender = sender(channelService, discovery(server.port()));

            ElinkAlertSender.SendResult result = sender.send(channel, payload("queue backlog"));

            assertFalse(result.isSuccess());
            assertFalse(result.isRetryable());
            assertEquals(200, result.getHttpStatus());
            assertEquals("eLink 原始发送错误", result.getErrorMessage());
            assertEquals("/elink/groups/77/messages", requestPath.get());
        }
    }

    @Test
    void shouldRetryWhenNoManagerInstanceIsAvailable() {
        AlertChannelService channelService = mock(AlertChannelService.class);
        AlertChannelEntity channel = new AlertChannelEntity();
        when(channelService.elinkTargetType(channel)).thenReturn("PERSONAL");
        when(channelService.elinkUserIds(channel)).thenReturn(List.of("user-1"));
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        when(discoveryClient.getInstances(any())).thenReturn(List.of());

        ElinkAlertSender.SendResult result = sender(channelService, provider(discoveryClient))
                .send(channel, payload("failed"));

        assertFalse(result.isSuccess());
        assertTrue(result.isRetryable());
        assertTrue(result.getErrorMessage().contains("No eLink service instance"));
    }

    @Test
    void shouldPreserveManagerErrorMessageForRetryableHttpFailure() throws Exception {
        try (TestServer server = TestServer.start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 502, "{\"status\":502,\"error\":\"Bad Gateway\"," +
                    "\"message\":\"eLink 原始发送错误\"}");
        })) {
            AlertChannelService channelService = mock(AlertChannelService.class);
            AlertChannelEntity channel = new AlertChannelEntity();
            when(channelService.elinkTargetType(channel)).thenReturn("PERSONAL");
            when(channelService.elinkUserIds(channel)).thenReturn(List.of("user-1"));

            ElinkAlertSender.SendResult result = sender(channelService, discovery(server.port()))
                    .send(channel, payload("failed"));

            assertFalse(result.isSuccess());
            assertTrue(result.isRetryable());
            assertEquals(502, result.getHttpStatus());
            assertEquals("eLink 原始发送错误", result.getErrorMessage());
            assertTrue(result.getResponseExcerpt().contains("\"status\":502"));
        }
    }

    @Test
    void shouldTreatExplicitManagerFailureAsFailureEvenWhenErrcodeIsZero() throws Exception {
        try (TestServer server = TestServer.start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{\"success\":false,\"errcode\":0," +
                    "\"errorMessage\":\"manager rejected delivery\"}");
        })) {
            AlertChannelService channelService = mock(AlertChannelService.class);
            AlertChannelEntity channel = new AlertChannelEntity();
            when(channelService.elinkTargetType(channel)).thenReturn("PERSONAL");
            when(channelService.elinkUserIds(channel)).thenReturn(List.of("user-1"));

            ElinkAlertSender.SendResult result = sender(channelService, discovery(server.port()))
                    .send(channel, payload("failed"));

            assertFalse(result.isSuccess());
            assertFalse(result.isRetryable());
            assertEquals("manager rejected delivery", result.getErrorMessage());
        }
    }

    private ElinkAlertSender sender(AlertChannelService channelService,
                                    ObjectProvider<DiscoveryClient> discoveryProvider) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getAlert().getElink().setRequestTimeoutSeconds(2);
        return new ElinkAlertSender(channelService, discoveryProvider, properties, objectMapper);
    }

    private ObjectProvider<DiscoveryClient> discovery(int port) {
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        ServiceInstance instance = mock(ServiceInstance.class);
        when(instance.getUri()).thenReturn(URI.create("http://127.0.0.1:" + port));
        when(discoveryClient.getInstances("elink-message-integration")).thenReturn(List.of(instance));
        return provider(discoveryClient);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<DiscoveryClient> provider(DiscoveryClient discoveryClient) {
        ObjectProvider<DiscoveryClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(discoveryClient);
        return provider;
    }

    private Map<String, Object> payload(String summary) {
        Map<String, Object> rule = new LinkedHashMap<String, Object>();
        rule.put("name", "worker offline");
        rule.put("severity", "CRITICAL");
        Map<String, Object> subject = new LinkedHashMap<String, Object>();
        subject.put("name", "worker-group-a");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("rule", rule);
        payload.put("subject", subject);
        payload.put("summary", summary);
        payload.put("occurredAt", "2026-07-16T22:00:00");
        return payload;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;

        private TestServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        private static TestServer start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            server.createContext("/", handler);
            server.setExecutor(executor);
            server.start();
            return new TestServer(server, executor);
        }

        private int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
