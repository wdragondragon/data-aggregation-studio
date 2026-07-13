package com.jdragon.studio.flink.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AggregationRuntimeRegistryTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void runtimeHandleKeepsLegacySerialVersionUid() {
        assertEquals(-4997852490667400347L,
                ObjectStreamClass.lookup(AggregationRuntimeHandle.class).getSerialVersionUID());
    }

    @Test
    void resolvesRuntimeAsJsonFriendlyPayloadAndUpdatesAudit() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setDatasourceId(1L);
        runtime.setModelId(10L);
        runtime.setPluginName("mysql8");
        BaseDataSourceDTO dto = new BaseDataSourceDTO();
        dto.setHost("127.0.0.1");
        dto.setPassword("secret");
        runtime.setDataSourceDTO(dto);

        String ref = AggregationFlinkRuntimeRegistry.register(runtime, 300);
        try {
            AggregationFlinkTableRuntimePayload payload = AggregationFlinkRuntimeRegistry.resolvePayload(ref);
            AggregationFlinkTableRuntime resolved = payload.toRuntime();
            assertEquals("mysql8", resolved.getPluginName());
            assertEquals("secret", resolved.getDataSourceDTO().getPassword());

            resolved.setPushedFilters(Collections.singletonList("biz_date = '2026-07-05'"));
            resolved.addResolvedSourceSql("SELECT * FROM t WHERE biz_date = '2026-07-05'");
            AggregationFlinkRuntimeRegistry.updateAudit(ref, AggregationFlinkTableRuntimePayload.fromRuntime(resolved));

            AggregationFlinkTableRuntime updated = AggregationFlinkRuntimeRegistry.required(ref);
            assertEquals(Collections.singletonList("biz_date = '2026-07-05'"), updated.getPushedFilters());
            assertEquals(Collections.singletonList("SELECT * FROM t WHERE biz_date = '2026-07-05'"),
                    updated.getResolvedSourceSql());
        } finally {
            AggregationFlinkRuntimeRegistry.remove(ref);
        }
    }

    @Test
    void rejectsMissingRuntimeToken() {
        assertThrows(IllegalStateException.class, () -> AggregationFlinkRuntimeRegistry.required("missing"));
    }

    @Test
    void ignoresPayloadFieldsAddedByNewerStudioVersions() throws Exception {
        JsonNode payload = OBJECT_MAPPER.valueToTree(new AggregationFlinkTableRuntimePayload());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("futureRuntimeField", "future-value");

        AggregationFlinkTableRuntimePayload resolved = OBJECT_MAPPER.treeToValue(
                payload, AggregationFlinkTableRuntimePayload.class);

        assertEquals("bounded", resolved.getScanMode());
    }

    @Test
    void serializesRemoteRuntimeStateAndKeepsAuditWorking() throws Exception {
        AggregationFlinkTableRuntime baseRuntime = new AggregationFlinkTableRuntime();
        baseRuntime.setPluginName("http");
        Map<String, Object> modelMetadata = new LinkedHashMap<String, Object>();
        modelMetadata.put("readerOptions", Collections.singletonMap("header",
                "{\"Authorization\":\"Bearer remote-secret\"}"));
        baseRuntime.setModelMetadata(modelMetadata);
        BaseDataSourceDTO dataSourceDTO = new BaseDataSourceDTO();
        dataSourceDTO.setPassword("remote-password");
        baseRuntime.setDataSourceDTO(dataSourceDTO);
        baseRuntime.setHttpFilterAlwaysFalse(true);
        AtomicReference<JsonNode> auditRequest = new AtomicReference<JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/flink/runtime/resolve", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, successBody(AggregationFlinkTableRuntimePayload.fromRuntime(baseRuntime)));
        });
        server.createContext("/api/flink/runtime/audit", exchange -> {
            auditRequest.set(OBJECT_MAPPER.readTree(exchange.getRequestBody()));
            respond(exchange, successBody(Boolean.TRUE));
        });
        server.start();
        try {
            AggregationRuntimeHandle handle = AggregationRuntimeHandle.remote(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "runtime-token");
            AggregationFlinkTableRuntime planned = AggregationRuntimeResolver.resolve(handle);
            assertEquals(false, planned.isHttpFilterAlwaysFalse());
            planned.setPushedFilters(Collections.singletonList("customer_id = 'C001'"));
            planned.setHttpPushdownFilters(Collections.singletonList(
                    httpFilter("customer_id", "C001")));
            planned.setHttpFilterAlwaysFalse(true);
            AggregationRuntimeResolver.captureRuntimeState(handle, planned);

            AggregationRuntimeHandle restored = roundTrip(handle);
            AggregationFlinkTableRuntime resolved = AggregationRuntimeResolver.resolve(restored);
            assertEquals(Collections.singletonList("customer_id = 'C001'"), resolved.getPushedFilters());
            assertEquals(true, resolved.isHttpFilterAlwaysFalse());
            assertEquals(Collections.singletonList("C001"),
                    resolved.getHttpPushdownFilters().get(0).get("values"));

            resolved.addResolvedSourceSql("SELECT * FROM remote_http");
            AggregationRuntimeResolver.updateAudit(restored, resolved);
            assertEquals("runtime-token", auditRequest.get().path("token").asText());
            assertEquals("C001", auditRequest.get().path("runtime")
                    .path("httpPushdownFilters").path(0).path("values").path(0).asText());
            assertEquals(true, auditRequest.get().path("runtime").path("httpFilterAlwaysFalse").asBoolean());
            assertEquals("SELECT * FROM remote_http", auditRequest.get().path("runtime")
                    .path("resolvedSourceSql").path(0).asText());
            assertEquals(false, auditRequest.get().toString().contains("remote-secret"));
            assertEquals(false, auditRequest.get().toString().contains("remote-password"));
        } finally {
            server.stop(0);
        }
    }

    private Map<String, Object> httpFilter(String field, String value) {
        Map<String, Object> filter = new LinkedHashMap<String, Object>();
        filter.put("field", field);
        filter.put("location", "param");
        filter.put("requestParamName", field);
        filter.put("operator", "=");
        filter.put("values", Collections.singletonList(value));
        return filter;
    }

    private AggregationRuntimeHandle roundTrip(AggregationRuntimeHandle handle) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(handle);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (AggregationRuntimeHandle) input.readObject();
        }
    }

    private byte[] successBody(Object data) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("code", "SUCCESS");
        body.put("data", data);
        return OBJECT_MAPPER.writeValueAsBytes(body);
    }

    private void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
