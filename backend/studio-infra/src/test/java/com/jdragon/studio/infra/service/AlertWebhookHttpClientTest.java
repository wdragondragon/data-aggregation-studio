package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AlertWebhookHttpClientTest {

    @Test
    void shouldUsePinnedValidatedAddressAndBoundResponseSize() throws Exception {
        try (TestServer server = TestServer.start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        })) {
            AlertWebhookHttpClient.Response response = new AlertWebhookHttpClient().post(
                    target(server.port()), Collections.<String, String>emptyMap(), "{}".getBytes(StandardCharsets.UTF_8),
                    1, 2, 1024);
            assertEquals(200, response.getStatusCode());
            assertEquals(1024, response.bodyAsText().length());
        }
    }

    @Test
    void shouldApplyDeadlineWhileReadingWebhookBody() throws Exception {
        try (TestServer server = TestServer.start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write('x');
                output.flush();
                Thread.sleep(3000L);
                output.write('y');
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        })) {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(Exception.class,
                    () -> new AlertWebhookHttpClient().post(target(server.port()),
                            Collections.<String, String>emptyMap(), "{}".getBytes(StandardCharsets.UTF_8),
                            1, 1, 1024)));
        }
    }

    private AlertWebhookSecurityService.ValidatedWebhookTarget target(int port) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getAlert().getWebhook().setAllowHttp(true);
        properties.getAlert().getWebhook().setAllowedHosts(Collections.singletonList("127.0.0.1"));
        return new AlertWebhookSecurityService(properties)
                .validateAndResolve("http://127.0.0.1:" + port + "/alert");
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;

        private TestServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        private static TestServer start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            server.createContext("/alert", handler);
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
