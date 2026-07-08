package com.jdragon.studio.flink.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.flink.connector.AggregationFlinkTableRuntime;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.flink.table.api.DataTypes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkExecutionModeTest {

    @Test
    void buildsDifferentRuntimeOptionsForEmbeddedAndGatewayDdl() {
        AggregationFlinkTableRuntime runtime = runtime();

        String embedded = FlinkTableDdlBuilder.buildCreateTemporaryTableDdl("m_10", runtime,
                FlinkRuntimeConnectorAccess.local("ref-1"));
        String gateway = FlinkTableDdlBuilder.buildCreateTemporaryTableDdl("m_10", runtime,
                FlinkRuntimeConnectorAccess.remote("http://studio-flink:18084", "token-1"));

        assertTrue(embedded.contains("'runtime.ref' = 'ref-1'"));
        assertFalse(embedded.contains("runtime.token"));
        assertTrue(gateway.contains("'runtime.endpoint' = 'http://studio-flink:18084'"));
        assertTrue(gateway.contains("'runtime.token' = 'token-1'"));
        assertFalse(gateway.contains("runtime.ref"));
    }

    @Test
    void routesExecutionClientByMode() {
        FlinkExecutionClientRouter router = new FlinkExecutionClientRouter(Arrays.asList(
                new FakeExecutionClient("embedded"),
                new FakeExecutionClient("gateway")));

        assertInstanceOf(FakeExecutionClient.class, router.select("embedded"));
        assertEquals("gateway", router.select("GATEWAY").executionMode());
    }

    @Test
    void gatewayClientExecutesSessionStatementAndResultFlow() throws Exception {
        List<String> requestBodies = Collections.synchronizedList(new ArrayList<String>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handleGatewayRequest(exchange, requestBodies));
        server.start();
        try {
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getFlink().getGateway().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getFlink().getGateway().setFetchTimeoutSeconds(5);
            GatewayFlinkExecutionClient client = new GatewayFlinkExecutionClient(properties, new ObjectMapper());

            FlinkExecutionResult result = client.execute(new FlinkExecutionRequest(
                    "SELECT COUNT(*) AS cnt FROM `m_10`",
                    Collections.singletonList("CREATE TEMPORARY TABLE `m_10` (`id` INT) WITH ('connector'='blackhole')"),
                    false,
                    10));

            assertEquals(Collections.singletonList("cnt"), result.getColumns());
            assertEquals(1, result.getRows().size());
            assertEquals(3, result.getRows().get(0).get("cnt"));
            assertTrue(requestBodies.get(0).contains("\"properties\""));
            assertTrue(requestBodies.get(0).contains("\"execution.runtime-mode\":\"batch\""));
            assertTrue(requestBodies.stream()
                    .filter(body -> body.contains("\"statement\""))
                    .allMatch(body -> body.contains("\"executionConfig\"")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gatewayClientDoesNotCountNotReadyPollsAsResultPages() throws Exception {
        AtomicInteger queryPolls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handleGatewayNotReadyRequest(exchange, queryPolls));
        server.start();
        try {
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getFlink().getGateway().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getFlink().getGateway().setFetchTimeoutSeconds(5);
            properties.getFlink().getGateway().setMaxResultPages(1);
            GatewayFlinkExecutionClient client = new GatewayFlinkExecutionClient(properties, new ObjectMapper());

            FlinkExecutionResult result = client.execute(new FlinkExecutionRequest(
                    "SELECT COUNT(*) AS cnt FROM `m_10`",
                    Collections.singletonList("CREATE TEMPORARY TABLE `m_10` (`id` INT) WITH ('connector'='blackhole')"),
                    false,
                    10));

            assertEquals(Collections.singletonList("cnt"), result.getColumns());
            assertEquals(1, result.getRows().size());
            assertEquals(7, result.getRows().get(0).get("cnt"));
            assertTrue(queryPolls.get() > 1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gatewayClientDoesNotCountEmptyPayloadPollsAsResultPages() throws Exception {
        AtomicInteger queryPolls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handleGatewayEmptyPayloadRequest(exchange, queryPolls));
        server.start();
        try {
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.getFlink().getGateway().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getFlink().getGateway().setFetchTimeoutSeconds(5);
            properties.getFlink().getGateway().setMaxResultPages(1);
            GatewayFlinkExecutionClient client = new GatewayFlinkExecutionClient(properties, new ObjectMapper());

            FlinkExecutionResult result = client.execute(new FlinkExecutionRequest(
                    "SELECT COUNT(*) AS cnt FROM `m_10`",
                    Collections.singletonList("CREATE TEMPORARY TABLE `m_10` (`id` INT) WITH ('connector'='blackhole')"),
                    false,
                    10));

            assertEquals(Collections.singletonList("cnt"), result.getColumns());
            assertEquals(1, result.getRows().size());
            assertEquals(11, result.getRows().get(0).get("cnt"));
            assertTrue(queryPolls.get() > 1);
        } finally {
            server.stop(0);
        }
    }

    private AggregationFlinkTableRuntime runtime() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setDatasourceId(1L);
        runtime.setModelId(10L);
        runtime.setPluginName("mysql8");
        runtime.setScanMode("bounded");
        runtime.setFieldNames(Arrays.asList("id", "name"));
        runtime.setProducedDataType(DataTypes.ROW(
                DataTypes.FIELD("id", DataTypes.INT()),
                DataTypes.FIELD("name", DataTypes.STRING())));
        return runtime;
    }

    private void handleGatewayRequest(HttpExchange exchange, List<String> requestBodies) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            requestBodies.add(body);
        }
        String response;
        if ("POST".equals(method) && "/v1/sessions".equals(path)) {
            response = "{\"sessionHandle\":\"s1\"}";
        } else if ("POST".equals(method) && path.endsWith("/statements")) {
            response = body.contains("CREATE TEMPORARY TABLE")
                    ? "{\"operationHandle\":\"op-ddl\"}"
                    : "{\"operationHandle\":\"op-query\"}";
        } else if ("GET".equals(method) && path.contains("/op-ddl/")) {
            response = "{\"resultType\":\"EOS\",\"results\":{\"columns\":[],\"data\":[]}}";
        } else if ("GET".equals(method) && path.contains("/op-query/")) {
            response = "{\"resultType\":\"EOS\",\"results\":{\"columns\":[{\"name\":\"cnt\"}],\"data\":[{\"kind\":\"INSERT\",\"fields\":[3]}]}}";
        } else if ("DELETE".equals(method)) {
            response = "{}";
        } else {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
            return;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void handleGatewayNotReadyRequest(HttpExchange exchange, AtomicInteger queryPolls) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response;
        if ("POST".equals(method) && "/v1/sessions".equals(path)) {
            response = "{\"sessionHandle\":\"s1\"}";
        } else if ("POST".equals(method) && path.endsWith("/statements")) {
            response = body.contains("CREATE TEMPORARY TABLE")
                    ? "{\"operationHandle\":\"op-ddl\"}"
                    : "{\"operationHandle\":\"op-query\"}";
        } else if ("GET".equals(method) && path.contains("/op-ddl/")) {
            response = "{\"resultType\":\"EOS\",\"results\":{\"columns\":[],\"data\":[]}}";
        } else if ("GET".equals(method) && path.contains("/op-query/")) {
            int poll = queryPolls.incrementAndGet();
            response = poll <= 3
                    ? "{\"resultType\":\"NOT_READY\",\"nextResultUri\":\"/v1/sessions/s1/operations/op-query/result/0\"}"
                    : "{\"resultType\":\"EOS\",\"results\":{\"columns\":[{\"name\":\"cnt\"}],\"data\":[{\"kind\":\"INSERT\",\"fields\":[7]}]}}";
        } else if ("DELETE".equals(method)) {
            response = "{}";
        } else {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
            return;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void handleGatewayEmptyPayloadRequest(HttpExchange exchange, AtomicInteger queryPolls) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response;
        if ("POST".equals(method) && "/v1/sessions".equals(path)) {
            response = "{\"sessionHandle\":\"s1\"}";
        } else if ("POST".equals(method) && path.endsWith("/statements")) {
            response = body.contains("CREATE TEMPORARY TABLE")
                    ? "{\"operationHandle\":\"op-ddl\"}"
                    : "{\"operationHandle\":\"op-query\"}";
        } else if ("GET".equals(method) && path.contains("/op-ddl/")) {
            response = "{\"resultType\":\"EOS\",\"results\":{\"columns\":[],\"data\":[]}}";
        } else if ("GET".equals(method) && path.contains("/op-query/")) {
            int poll = queryPolls.incrementAndGet();
            response = poll <= 3
                    ? "{\"resultType\":\"PAYLOAD\",\"results\":{\"columns\":[{\"name\":\"cnt\"}],\"data\":[]},\"nextResultUri\":\"/v1/sessions/s1/operations/op-query/result/" + poll + "\"}"
                    : "{\"resultType\":\"EOS\",\"results\":{\"columns\":[{\"name\":\"cnt\"}],\"data\":[{\"kind\":\"INSERT\",\"fields\":[11]}]}}";
        } else if ("DELETE".equals(method)) {
            response = "{}";
        } else {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
            return;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static class FakeExecutionClient implements FlinkExecutionClient {
        private final String mode;

        private FakeExecutionClient(String mode) {
            this.mode = mode;
        }

        @Override
        public String executionMode() {
            return mode;
        }

        @Override
        public FlinkExecutionResult execute(FlinkExecutionRequest request) {
            return new FlinkExecutionResult();
        }
    }
}
