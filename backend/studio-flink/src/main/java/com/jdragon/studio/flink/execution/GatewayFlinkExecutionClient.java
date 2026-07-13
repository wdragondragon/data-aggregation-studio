package com.jdragon.studio.flink.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class GatewayFlinkExecutionClient implements FlinkExecutionClient {
    private final StudioPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GatewayFlinkExecutionClient(StudioPlatformProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        int timeoutSeconds = positive(properties.getFlink().getGateway().getConnectTimeoutSeconds(), 10);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Override
    public String executionMode() {
        return "gateway";
    }

    @Override
    public FlinkExecutionResult execute(FlinkExecutionRequest request) throws Exception {
        String sessionHandle = null;
        List<String> operations = new ArrayList<String>();
        try {
            Map<String, String> executionConfig = gatewayExecutionConfig(request.isStreamingMode());
            sessionHandle = openSession(executionConfig);
            for (String ddl : request.getCreateTableDdls()) {
                String operation = submitStatement(sessionHandle, ddl, executionConfig);
                operations.add(operation);
                drainOperation(sessionHandle, operation, 0);
            }
            String queryOperation = submitStatement(sessionHandle, request.getSql(), executionConfig);
            operations.add(queryOperation);
            return fetchQueryResult(sessionHandle, queryOperation, request.getMaxRows());
        } catch (StudioException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Flink SQL Gateway execution failed: " + ex.getMessage(), ex);
        } finally {
            if (sessionHandle != null) {
                for (String operation : operations) {
                    closeOperationQuietly(sessionHandle, operation);
                }
                closeSessionQuietly(sessionHandle);
            }
        }
    }

    private String openSession(Map<String, String> executionConfig) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sessionName", "studio-flink-" + UUID.randomUUID());
        if (!executionConfig.isEmpty()) {
            payload.put("properties", executionConfig);
        }
        JsonNode response = post("/v1/sessions", payload);
        String sessionHandle = firstText(response, "sessionHandle", "sessionId");
        if (!hasText(sessionHandle)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Flink SQL Gateway did not return sessionHandle");
        }
        return sessionHandle;
    }

    private Map<String, String> gatewayExecutionConfig(boolean streamingMode) {
        Map<String, String> executionConfig = new LinkedHashMap<String, String>();
        executionConfig.put("execution.runtime-mode", streamingMode ? "streaming" : "batch");
        if (hasText(properties.getFlink().getGateway().getRestAddress())) {
            executionConfig.put("rest.address", properties.getFlink().getGateway().getRestAddress().trim());
        }
        if (properties.getFlink().getGateway().getRestPort() != null) {
            executionConfig.put("rest.port", String.valueOf(properties.getFlink().getGateway().getRestPort()));
        }
        return executionConfig;
    }

    private String submitStatement(String sessionHandle,
                                   String statement,
                                   Map<String, String> executionConfig) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("statement", statement);
        if (executionConfig != null && !executionConfig.isEmpty()) {
            payload.put("executionConfig", executionConfig);
        }
        JsonNode response = post("/v1/sessions/" + url(sessionHandle) + "/statements", payload);
        String operationHandle = firstText(response, "operationHandle", "operationId");
        if (!hasText(operationHandle)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Flink SQL Gateway did not return operationHandle");
        }
        return operationHandle;
    }

    private void drainOperation(String sessionHandle, String operationHandle, int maxRows) throws Exception {
        fetchPages(sessionHandle, operationHandle, maxRows, true);
    }

    private FlinkExecutionResult fetchQueryResult(String sessionHandle, String operationHandle, int maxRows) throws Exception {
        return fetchPages(sessionHandle, operationHandle, maxRows, false);
    }

    private FlinkExecutionResult fetchPages(String sessionHandle,
                                           String operationHandle,
                                           int maxRows,
                                           boolean drainOnly) throws Exception {
        FlinkExecutionResult result = new FlinkExecutionResult();
        String nextUri = "/v1/sessions/" + url(sessionHandle) + "/operations/" + url(operationHandle) + "/result/0";
        long deadline = System.currentTimeMillis()
                + positive(properties.getFlink().getGateway().getFetchTimeoutSeconds(),
                positive(properties.getFlink().getQueryTimeoutSeconds(), 30)) * 1000L;
        int pages = 0;
        int maxPages = positive(properties.getFlink().getGateway().getMaxResultPages(), 1000);
        while (pages < maxPages) {
            if (System.currentTimeMillis() > deadline) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Flink SQL Gateway operation timed out");
            }
            JsonNode page = get(nextUri);
            throwIfGatewayError(page);
            GatewayResultPage parsed = parseResultPage(page);
            if (isNotReady(parsed.getResultType()) || isWaitingForRows(parsed)) {
                if (hasText(parsed.getNextResultUri())) {
                    nextUri = parsed.getNextResultUri();
                }
                sleepQuietly(200L);
                continue;
            }
            pages++;
            if (!drainOnly) {
                mergeColumns(result, parsed);
                for (Map<String, Object> row : parsed.getRows()) {
                    if (maxRows > 0 && result.getRows().size() >= maxRows) {
                        return result;
                    }
                    result.addRow(row);
                }
            }
            if ("EOS".equalsIgnoreCase(parsed.getResultType())) {
                return result;
            }
            if (hasText(parsed.getNextResultUri())) {
                nextUri = parsed.getNextResultUri();
                continue;
            }
            sleepQuietly(200L);
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST,
                "Flink SQL Gateway result pages exceeded " + maxPages);
    }

    private boolean isNotReady(String resultType) {
        return "NOT_READY".equalsIgnoreCase(resultType);
    }

    private boolean isWaitingForRows(GatewayResultPage page) {
        if (page == null || "EOS".equalsIgnoreCase(page.getResultType())) {
            return false;
        }
        return page.getRows().isEmpty() && hasText(page.getNextResultUri());
    }

    private void throwIfGatewayError(JsonNode page) {
        JsonNode errors = page == null ? null : page.get("errors");
        if (errors == null || errors.isMissingNode() || errors.isNull()) {
            return;
        }
        String message;
        if (errors.isArray() && errors.size() > 0) {
            message = errors.get(0).asText("Flink SQL Gateway returned errors");
        } else {
            message = errors.asText("Flink SQL Gateway returned errors");
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Flink SQL Gateway returned error: " + safeBody(message));
    }

    private GatewayResultPage parseResultPage(JsonNode page) {
        GatewayResultPage result = new GatewayResultPage();
        result.setResultType(firstText(page, "resultType", "resultKind"));
        result.setNextResultUri(firstText(page, "nextResultUri", "nextUri"));
        JsonNode results = page.path("results");
        if (results.isMissingNode() || results.isNull()) {
            results = page;
        }
        result.setColumns(parseColumns(firstExisting(results, "columns", "schema")));
        result.setRows(parseRows(firstExisting(results, "data", "rows"), result.getColumns()));
        return result;
    }

    private List<String> parseColumns(JsonNode columnsNode) {
        List<String> columns = new ArrayList<String>();
        if (columnsNode == null || columnsNode.isMissingNode() || columnsNode.isNull()) {
            return columns;
        }
        if (columnsNode.isArray()) {
            for (JsonNode column : columnsNode) {
                if (column.isTextual()) {
                    columns.add(column.asText());
                } else {
                    String name = firstText(column, "name", "columnName", "column");
                    if (hasText(name)) {
                        columns.add(name);
                    }
                }
            }
        }
        return columns;
    }

    private List<Map<String, Object>> parseRows(JsonNode rowsNode, List<String> columns) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (rowsNode == null || rowsNode.isMissingNode() || rowsNode.isNull() || !rowsNode.isArray()) {
            return rows;
        }
        for (JsonNode rowNode : rowsNode) {
            JsonNode fieldsNode = rowNode.has("fields") ? rowNode.get("fields") : rowNode;
            if (fieldsNode.isArray()) {
                rows.add(arrayRow(fieldsNode, columns));
            } else if (fieldsNode.isObject()) {
                rows.add(objectRow(fieldsNode));
            } else {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put(columns == null || columns.isEmpty() ? "value" : columns.get(0), jsonValue(fieldsNode));
                rows.add(row);
            }
        }
        return rows;
    }

    private Map<String, Object> arrayRow(JsonNode fieldsNode, List<String> columns) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fieldsNode.size(); i++) {
            String column = columns != null && i < columns.size() ? columns.get(i) : "f" + i;
            row.put(column, jsonValue(fieldsNode.get(i)));
        }
        return row;
    }

    private Map<String, Object> objectRow(JsonNode fieldsNode) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        Iterator<Map.Entry<String, JsonNode>> iterator = fieldsNode.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            row.put(entry.getKey(), jsonValue(entry.getValue()));
        }
        return row;
    }

    private Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return objectMapper.convertValue(node, Object.class);
    }

    private void mergeColumns(FlinkExecutionResult result, GatewayResultPage page) {
        if (!result.getColumns().isEmpty() || page.getColumns().isEmpty()) {
            return;
        }
        result.setColumns(page.getColumns());
    }

    private JsonNode post(String path, Object body) throws Exception {
        return send("POST", path, body);
    }

    private JsonNode get(String pathOrUri) throws Exception {
        return send("GET", pathOrUri, null);
    }

    private JsonNode delete(String path) throws Exception {
        return send("DELETE", path, null);
    }

    private JsonNode send(String method, String pathOrUri, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolveGatewayUri(pathOrUri))
                .timeout(Duration.ofSeconds(positive(properties.getFlink().getGateway().getFetchTimeoutSeconds(), 30)))
                .header("Accept", "application/json");
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.GET();
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Flink SQL Gateway HTTP " + response.statusCode() + ": " + gatewayErrorMessage(response.body()));
        }
        if (!hasText(response.body())) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(response.body());
    }

    private URI resolveGatewayUri(String pathOrUri) {
        if (pathOrUri.startsWith("http://") || pathOrUri.startsWith("https://")) {
            return URI.create(pathOrUri);
        }
        String baseUrl = properties.getFlink().getGateway().getBaseUrl();
        if (!hasText(baseUrl)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "studio.flink.gateway.base-url is required");
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String path = pathOrUri.startsWith("/") ? pathOrUri : "/" + pathOrUri;
        return URI.create(baseUrl + path);
    }

    private void closeOperationQuietly(String sessionHandle, String operationHandle) {
        try {
            delete("/v1/sessions/" + url(sessionHandle) + "/operations/" + url(operationHandle));
        } catch (Exception ignored) {
        }
    }

    private void closeSessionQuietly(String sessionHandle) {
        try {
            delete("/v1/sessions/" + url(sessionHandle));
        } catch (Exception ignored) {
        }
    }

    private JsonNode firstExisting(JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull() && hasText(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    private String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private int positive(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String safeBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 1000 ? body.substring(0, 1000) : body;
    }

    private String gatewayErrorMessage(String body) {
        String message = extractGatewayErrorText(body);
        String businessMessage = extractHttpPushdownMessage(message);
        if (hasText(businessMessage)) {
            return businessMessage;
        }
        return safeBody(message);
    }

    private String extractGatewayErrorText(String body) {
        if (!hasText(body)) {
            return "";
        }
        String text = body.trim();
        try {
            JsonNode node = objectMapper.readTree(text);
            JsonNode errors = node.get("errors");
            if (errors != null && !errors.isNull()) {
                if (errors.isArray() && errors.size() > 0) {
                    return errors.get(0).asText(text);
                }
                return errors.asText(text);
            }
            String message = firstText(node, "message", "error", "detail");
            if (hasText(message)) {
                return message;
            }
        } catch (Exception ignored) {
        }
        return text;
    }

    private String extractHttpPushdownMessage(String message) {
        if (!hasText(message)) {
            return null;
        }
        String normalized = message.replace("\\r", "\n")
                .replace("\\n", "\n")
                .replace("\\t", " ");
        int start = normalized.indexOf("HTTP 下推字段");
        if (start < 0) {
            return null;
        }
        int end = normalized.length();
        for (String delimiter : new String[]{"\n", "\r", " at ", "Caused by", "org.apache.flink"}) {
            int index = normalized.indexOf(delimiter, start);
            if (index > start && index < end) {
                end = index;
            }
        }
        String result = normalized.substring(start, end).trim();
        while (result.endsWith("\"") || result.endsWith("'") || result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Flink SQL Gateway operation interrupted", ex);
        }
    }

    private static class GatewayResultPage {
        private String resultType;
        private String nextResultUri;
        private List<String> columns = new ArrayList<String>();
        private List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

        String getResultType() {
            return resultType;
        }

        void setResultType(String resultType) {
            this.resultType = resultType;
        }

        String getNextResultUri() {
            return nextResultUri;
        }

        void setNextResultUri(String nextResultUri) {
            this.nextResultUri = nextResultUri;
        }

        List<String> getColumns() {
            return columns;
        }

        void setColumns(List<String> columns) {
            this.columns = columns == null ? new ArrayList<String>() : new ArrayList<String>(columns);
        }

        List<Map<String, Object>> getRows() {
            return rows;
        }

        void setRows(List<Map<String, Object>> rows) {
            this.rows = rows == null ? new ArrayList<Map<String, Object>>() : new ArrayList<Map<String, Object>>(rows);
        }
    }
}
