package com.jdragon.studio.flink.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class FlinkTextToSqlService {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StudioPlatformProperties properties;

    FlinkTextToSqlService(StudioPlatformProperties properties) {
        this.properties = properties;
    }

    String generateSql(String question, FlinkQuestionContext context, int maxRows, List<String> warnings) {
        StudioPlatformProperties.LlmProperties llm = properties.getAssistant().getLlm();
        if (!llm.isEnabled() || llm.getApiKey() == null || llm.getApiKey().trim().isEmpty()) {
            warnings.add("LLM is disabled; generated a safe default query.");
            return fallbackSql(context.getModels(), maxRows);
        }
        try {
            String content = requestCompletion(question, context, llm, maxRows);
            String sql = extractSql(content);
            if (sql == null || sql.trim().isEmpty()) {
                warnings.add("LLM response did not contain SQL; generated a safe default query.");
                return fallbackSql(context.getModels(), maxRows);
            }
            return sql;
        } catch (Exception ex) {
            warnings.add("LLM request failed: " + ex.getMessage() + "; generated a safe default query.");
            return fallbackSql(context.getModels(), maxRows);
        }
    }

    private String requestCompletion(String question,
                                     FlinkQuestionContext context,
                                     StudioPlatformProperties.LlmProperties llm,
                                     int maxRows) throws Exception {
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        messages.add(message("system", "You generate Apache Flink SQL only. Return JSON: {\"sql\":\"...\",\"explanation\":\"...\"}. "
                + "Only SELECT or WITH SELECT is allowed. Use only provided table names. Add LIMIT " + maxRows + " unless already limited."));
        messages.add(message("user", "Question:\n" + question + "\n\nContext:\n" + context.getPromptContext()));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", llm.getModel());
        body.put("messages", messages);
        body.put("temperature", llm.getTemperature());
        body.put("max_tokens", llm.getMaxTokens());
        String payload = objectMapper.writeValueAsString(body);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(llm.getTimeoutSeconds() == null ? 30 : llm.getTimeoutSeconds()))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveChatCompletionsUrl(llm.getBaseUrl())))
                .timeout(Duration.ofSeconds(llm.getTimeoutSeconds() == null ? 30 : llm.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + llm.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("LLM HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    private String extractSql(String content) throws Exception {
        if (content == null) {
            return null;
        }
        String trimmed = stripFence(content.trim());
        if (trimmed.startsWith("{")) {
            JsonNode node = objectMapper.readTree(trimmed);
            return node.path("sql").asText(null);
        }
        return trimmed;
    }

    private String fallbackSql(List<DataModelDefinition> models, int maxRows) {
        DataModelDefinition model = models.get(0);
        return "SELECT * FROM `" + FlinkSqlExecutionService.tableNameFor(model) + "` LIMIT " + maxRows;
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String resolveChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl == null || baseUrl.trim().isEmpty()
                ? "https://api.openai.com/v1"
                : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        return normalized + "/chat/completions";
    }

    private String stripFence(String value) {
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return value.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return value;
    }
}
