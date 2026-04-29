package com.jdragon.studio.nacos.compat.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NacosHttpClientTest {

    @Test
    void shouldTryNextServerAddressWhenFirstAddressFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nacos/v1/console/server/state", exchange -> {
            byte[] body = "{\"version\":\"1.3.2\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            NacosHttpClient httpClient = new NacosHttpClient(Duration.ofMillis(300));

            NacosHttpResponse response = httpClient.get("127.0.0.1:1,127.0.0.1:" + port,
                    "/nacos/v1/console/server/state", Map.of(), Map.of(), Duration.ofSeconds(2));

            assertEquals(200, response.statusCode());
            assertEquals("{\"version\":\"1.3.2\"}", response.body());
        }
        finally {
            server.stop(0);
        }
    }
}
