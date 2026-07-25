package com.jdragon.studio.flink.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

final class AggregationRemoteRuntimeClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private AggregationRemoteRuntimeClient() {
    }

    static AggregationFlinkTableRuntime resolve(String endpoint, String token) {
        try {
            JsonNode response = post(endpoint, "/api/flink/runtime/resolve", token,
                    new LinkedHashMap<String, Object>());
            JsonNode data = response.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw new IllegalStateException("runtime resolve returned empty data");
            }
            return OBJECT_MAPPER.treeToValue(data, AggregationFlinkTableRuntimePayload.class).toRuntime();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to resolve DataAggregation Flink runtime from studio-flink: "
                    + ex.getMessage(), ex);
        }
    }

    static void updateAudit(String endpoint, String token, AggregationFlinkTableRuntime runtime) {
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("runtime", AggregationFlinkTableRuntimePayload.auditFromRuntime(runtime));
            post(endpoint, "/api/flink/runtime/audit", token, body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to update DataAggregation Flink runtime audit: "
                    + ex.getMessage(), ex);
        }
    }

    private static JsonNode post(String endpoint, String path, String token, Object body) throws Exception {
        String payload = OBJECT_MAPPER.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(resolveUri(endpoint, path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER, token)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        JsonNode json = OBJECT_MAPPER.readTree(response.body());
        String code = json.path("code").asText("SUCCESS");
        if (!"SUCCESS".equalsIgnoreCase(code) && !"0".equals(code)) {
            throw new IllegalStateException(json.path("message").asText("runtime endpoint returned " + code));
        }
        return json;
    }

    private static URI resolveUri(String endpoint, String path) {
        String base = endpoint == null ? "" : endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }
}
