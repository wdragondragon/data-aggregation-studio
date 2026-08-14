package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeEndpointHttpClientTest {

    @Test
    void streamsSuccessfulResponseDirectlyToCallerOutput() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] payload = new byte[256 * 1024 + 19];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index % 239);
        }
        server.createContext("/download", exchange -> {
            exchange.getResponseHeaders().add("X-Studio-Runtime-Response", "authenticated");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        RuntimeEndpointHttpClient client = new RuntimeEndpointHttpClient();
        try {
            RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target = target(server, "/download");
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            RuntimeEndpointHttpClient.StreamingResponse response = client.executeStreaming(
                    target, "GET", Collections.emptyMap(), null,
                    1000, 1000, output);

            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.getErrorBody()).isEmpty();
            assertThat(output.toByteArray()).containsExactly(payload);
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void treatsStreamingReadTimeoutAsIdleTimeoutInsteadOfTotalDuration() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] payload = "streaming-idle-timeout".getBytes(StandardCharsets.UTF_8);
        server.createContext("/slow-download", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            for (byte item : payload) {
                exchange.getResponseBody().write(item);
                exchange.getResponseBody().flush();
                try {
                    TimeUnit.MILLISECONDS.sleep(40L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            exchange.close();
        });
        server.start();

        RuntimeEndpointHttpClient client = new RuntimeEndpointHttpClient();
        try {
            RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                    target(server, "/slow-download");
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            RuntimeEndpointHttpClient.StreamingResponse response = client.executeStreaming(
                    target, "GET", Collections.emptyMap(), null,
                    1000, 250, output);

            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(output.toByteArray()).containsExactly(payload);
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void rejectsOversizedStreamingErrorBodyWithoutWritingItToDownloadOutput() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] errorBody = new byte[1024 * 1024 + 1];
        server.createContext("/download-error", exchange -> {
            exchange.sendResponseHeaders(500, errorBody.length);
            exchange.getResponseBody().write(errorBody);
            exchange.close();
        });
        server.start();

        RuntimeEndpointHttpClient client = new RuntimeEndpointHttpClient();
        try {
            RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                    target(server, "/download-error");
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            assertThatThrownBy(() -> client.executeStreaming(
                    target, "GET", Collections.emptyMap(), null,
                    1000, 1000, output))
                    .isInstanceOf(RuntimeEndpointSecurityService.ResponseTooLargeException.class);
            assertThat(output.size()).isZero();
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void streamsFixedLengthRequestBodyWithoutChangingContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<byte[]> received = new AtomicReference<byte[]>();
        AtomicReference<String> contentLength = new AtomicReference<String>();
        server.createContext("/upload", exchange -> {
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            received.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        RuntimeEndpointHttpClient client = new RuntimeEndpointHttpClient();
        try {
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
            RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                    new RuntimeEndpointSecurityService(properties).validateRequestTarget(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/upload");
            byte[] payload = new byte[256 * 1024 + 17];
            for (int index = 0; index < payload.length; index++) {
                payload[index] = (byte) (index % 251);
            }

            RuntimeEndpointHttpClient.Response response = client.execute(
                    target, "POST", Collections.emptyMap(),
                    new ByteArrayInputStream(payload), payload.length,
                    1000, 1000, 1024);

            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(contentLength.get()).isEqualTo(String.valueOf(payload.length));
            assertThat(received.get()).containsExactly(payload);
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void reusesPooledConnectionForValidatedRuntimeHost() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Integer> remotePorts = Collections.synchronizedList(new ArrayList<Integer>());
        server.createContext("/runtime", exchange -> {
            remotePorts.add(exchange.getRemoteAddress().getPort());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        RuntimeEndpointHttpClient client = new RuntimeEndpointHttpClient();
        try {
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
            RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                    new RuntimeEndpointSecurityService(properties).validateRequestTarget(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/runtime");

            RuntimeEndpointHttpClient.Response first = client.execute(
                    target, "GET", Collections.emptyMap(), null, 1000, 1000, 1024);
            RuntimeEndpointHttpClient.Response second = client.execute(
                    target, "GET", Collections.emptyMap(), null, 1000, 1000, 1024);

            assertThat(first.getStatusCode()).isEqualTo(200);
            assertThat(second.getStatusCode()).isEqualTo(200);
            assertThat(remotePorts).hasSize(2);
            assertThat(remotePorts.get(1)).isEqualTo(remotePorts.get(0));
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void shouldDiscardSilentlyClosedRedirectConnectionBeforeNextRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runtime", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://login.example.test/oauth");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        RuntimeEndpointHttpClient client = new RuntimeEndpointHttpClient();
        try {
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
            RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                    new RuntimeEndpointSecurityService(properties).validateRequestTarget(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/runtime");

            RuntimeEndpointHttpClient.Response first = client.execute(
                    target, "GET", Collections.emptyMap(), null, 1000, 1000, 1024);
            RuntimeEndpointHttpClient.Response second = client.execute(
                    target, "GET", Collections.emptyMap(), null, 1000, 1000, 1024);

            assertThat(first.getStatusCode()).isEqualTo(302);
            assertThat(second.getStatusCode()).isEqualTo(302);
            assertThat(second.getHeaders().get("Location"))
                    .containsExactly("https://login.example.test/oauth");
        } finally {
            client.close();
            server.stop(0);
        }
    }

    private RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target(HttpServer server,
                                                                            String path) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getRuntimeEndpoint().getAllowedHosts().add("127.0.0.1");
        return new RuntimeEndpointSecurityService(properties).validateRequestTarget(
                "http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
}
