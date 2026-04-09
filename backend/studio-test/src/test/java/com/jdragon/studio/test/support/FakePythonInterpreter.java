package com.jdragon.studio.test.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class FakePythonInterpreter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println(format("ERROR", "Invalid fake python runner arguments"));
            System.exit(1);
            return;
        }

        Path userScriptPath = Paths.get(args[1]);
        Path contextPath = Paths.get(args[2]);
        Path resultPath = Paths.get(args[3]);

        JsonNode context = OBJECT_MAPPER.readTree(contextPath.toFile());
        String script = new String(Files.readAllBytes(userScriptPath), StandardCharsets.UTF_8);
        Map<String, Object> resultJson = new LinkedHashMap<String, Object>();
        Map<String, Object> arguments = OBJECT_MAPPER.convertValue(
                context.path("arguments"),
                new TypeReference<Map<String, Object>>() {
                }
        );

        System.out.println(format("INFO", "Fake Python interpreter started for " + context.path("username").asText("unknown")));

        if (script.contains("raise RuntimeError(")) {
            writeFailure(resultPath, "Python runtime error requested by test");
            System.out.println(format("ERROR", "Python runtime error requested by test"));
            System.exit(1);
            return;
        }

        if (script.contains("tenant_id")) {
            resultJson.put("tenantId", context.path("tenantId").asText());
        }
        if (script.contains("context.arguments") || script.contains("\"arguments\"")) {
            resultJson.put("arguments", arguments);
        }
        if (script.contains("context.username") || script.contains("context.username")) {
            resultJson.put("username", context.path("username").asText());
        }
        if (script.contains("list_datasources()")) {
            resultJson.put("datasourceCount", invokeBridgeArraySize(context, "list_datasources", new LinkedHashMap<String, Object>()));
        }
        if (script.contains("list_models(")) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("datasourceId", firstDatasourceId(context));
            resultJson.put("modelCount", invokeBridgeArraySize(context, "list_models", payload));
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", true);
        result.put("status", "SUCCESS");
        result.put("message", script.contains("Python script executed successfully")
                ? "Python script executed successfully"
                : "Python script finished");
        result.put("resultJson", resultJson);
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(resultPath.toFile(), result);
        System.out.println(format("INFO", "Fake Python interpreter finished"));
    }

    private static Long firstDatasourceId(JsonNode context) throws Exception {
        JsonNode bridge = context.path("bridge");
        String baseUrl = bridge.path("baseUrl").asText();
        String token = bridge.path("token").asText();
        URL url = new URL(baseUrl + "/invoke");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("X-Studio-Python-Token", token);
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(singletonAction("list_datasources", new LinkedHashMap<String, Object>()));
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(body);
        }
        try (InputStream inputStream = connection.getInputStream()) {
            JsonNode response = OBJECT_MAPPER.readTree(inputStream);
            JsonNode data = response.path("data");
            if (data.isArray() && data.size() > 0) {
                return data.get(0).path("id").isNumber() ? data.get(0).path("id").asLong() : Long.valueOf(data.get(0).path("id").asText());
            }
        }
        return null;
    }

    private static int invokeBridgeArraySize(JsonNode context, String action, Map<String, Object> payload) throws Exception {
        JsonNode bridge = context.path("bridge");
        String baseUrl = bridge.path("baseUrl").asText();
        String token = bridge.path("token").asText();
        URL url = new URL(baseUrl + "/invoke");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("X-Studio-Python-Token", token);
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(singletonAction(action, payload));
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(body);
        }
        try (InputStream inputStream = connection.getInputStream()) {
            JsonNode response = OBJECT_MAPPER.readTree(inputStream);
            JsonNode data = response.path("data");
            return data.isArray() ? data.size() : 0;
        }
    }

    private static Map<String, Object> singletonAction(String action, Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("action", action);
        request.put("payload", payload);
        return request;
    }

    private static void writeFailure(Path resultPath, String message) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", false);
        result.put("status", "FAILED");
        result.put("message", message);
        Map<String, Object> resultJson = new LinkedHashMap<String, Object>();
        resultJson.put("exceptionType", "RuntimeError");
        result.put("resultJson", resultJson);
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(resultPath.toFile(), result);
    }

    private static String format(String level, String message) {
        return FORMATTER.format(LocalDateTime.now()) + " [" + level + "] " + message;
    }
}
