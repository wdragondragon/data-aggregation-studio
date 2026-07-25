package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEndpointHttpClientTest {

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
}
