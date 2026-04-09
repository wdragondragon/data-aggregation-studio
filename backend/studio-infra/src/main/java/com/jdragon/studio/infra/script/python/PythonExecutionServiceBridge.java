package com.jdragon.studio.infra.script.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.infra.script.java.JavaDataScriptServices;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PythonExecutionServiceBridge implements AutoCloseable {

    private static final String TOKEN_HEADER = "X-Studio-Python-Token";

    private final ObjectMapper objectMapper;
    private final JavaDataScriptServices services;
    private final HttpServer server;
    private final ExecutorService executorService;
    private final String token;
    private final String baseUrl;

    public PythonExecutionServiceBridge(ObjectMapper objectMapper,
                                        JavaDataScriptServices services) throws IOException {
        this.objectMapper = objectMapper;
        this.services = services;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executorService = Executors.newSingleThreadExecutor();
        this.server.createContext("/invoke", new InvokeHandler());
        this.server.setExecutor(executorService);
        this.server.start();
        this.token = UUID.randomUUID().toString().replace("-", "");
        this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public PythonBridgeConnectionInfo buildConnectionInfo() {
        PythonBridgeConnectionInfo info = new PythonBridgeConnectionInfo();
        info.setBaseUrl(baseUrl);
        info.setToken(token);
        return info;
    }

    @Override
    public void close() {
        server.stop(0);
        executorService.shutdownNow();
    }

    private final class InvokeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            PythonExecutionBridgeResponse response = new PythonExecutionBridgeResponse();
            int statusCode = 200;
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    statusCode = 405;
                    response.setSuccess(false);
                    response.setError("Only POST is supported");
                    writeResponse(exchange, statusCode, response);
                    return;
                }
                Headers headers = exchange.getRequestHeaders();
                String headerToken = firstHeader(headers, TOKEN_HEADER);
                if (headerToken == null || !token.equals(headerToken)) {
                    statusCode = 403;
                    response.setSuccess(false);
                    response.setError("Invalid token");
                    writeResponse(exchange, statusCode, response);
                    return;
                }
                PythonExecutionBridgeRequest request;
                try (InputStream inputStream = exchange.getRequestBody()) {
                    request = objectMapper.readValue(inputStream, PythonExecutionBridgeRequest.class);
                }
                response.setSuccess(true);
                response.setData(invoke(request));
            } catch (Exception ex) {
                response.setSuccess(false);
                response.setError(ex.getMessage());
            }
            writeResponse(exchange, statusCode, response);
        }
    }

    private Object invoke(PythonExecutionBridgeRequest request) {
        String action = request == null ? null : request.getAction();
        Map<String, Object> payload = request == null || request.getPayload() == null
                ? Collections.<String, Object>emptyMap()
                : request.getPayload();
        if ("list_datasources".equals(action)) {
            List<DataSourceDefinition> datasources = services.listDatasources();
            return datasources == null ? Collections.emptyList() : datasources;
        }
        if ("get_datasource".equals(action)) {
            return services.getDatasource(asLong(payload.get("datasourceId")));
        }
        if ("list_models".equals(action)) {
            List<DataModelDefinition> models = services.listModels(asLong(payload.get("datasourceId")));
            return models == null ? Collections.emptyList() : models;
        }
        if ("execute_sql".equals(action)) {
            SqlExecutionResultView result = services.executeSql(
                    asLong(payload.get("datasourceId")),
                    asText(payload.get("sql")),
                    asInteger(payload.get("maxRows")));
            return result == null ? new LinkedHashMap<String, Object>() : result;
        }
        throw new IllegalArgumentException("Unsupported python bridge action: " + action);
    }

    private void writeResponse(HttpExchange exchange,
                               int statusCode,
                               PythonExecutionBridgeResponse response) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private String firstHeader(Headers headers, String key) {
        if (headers == null) {
            return null;
        }
        List<String> values = headers.get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0);
        return value == null ? null : value.trim();
    }

    private Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Long.parseLong(((String) value).trim());
        }
        return null;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Integer.parseInt(((String) value).trim());
        }
        return null;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.trim().isEmpty() ? null : text;
    }
}
