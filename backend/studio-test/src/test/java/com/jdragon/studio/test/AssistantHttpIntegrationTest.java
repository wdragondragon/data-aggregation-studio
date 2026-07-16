package com.jdragon.studio.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.test.support.StudioHttpIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantHttpIntegrationTest extends StudioHttpIntegrationTestSupport {

    @Test
    void authenticatedAssistantEndpointsShouldWorkOverRealHttp() throws Exception {
        JsonNode loginBody = loginAsAdminHttp();
        String authorization = bearer(loginBody);
        Long projectId = currentProjectId(loginBody);

        JsonNode operationsBody = requireSuccess(get("/api/v1/assistant/operations", authorization, projectId));
        JsonNode operations = operationsBody.path("data");
        assertThat(operations.isArray()).isTrue();
        assertThat(operations).anySatisfy(operation -> {
            assertThat(operation.path("schema").asText()).isEqualTo("studio.operation.v1");
            assertThat(operation.path("path").asText()).isEqualTo("/dashboard");
            assertThat(operation.path("readTools").isArray()).isTrue();
        });
        assertThat(operations).anySatisfy(operation ->
                assertThat(operation.path("path").asText()).isEqualTo("/notifications"));

        JsonNode configBody = requireSuccess(get("/api/v1/assistant/config", authorization, projectId));
        assertThat(configBody.path("data").path("enabled").asBoolean()).isFalse();

        Map<String, Object> toolRequest = new LinkedHashMap<String, Object>();
        toolRequest.put("interfaceCode", "studio.feature.list");
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("path", "/dashboard");
        toolRequest.put("params", params);

        JsonNode toolBody = requireSuccess(postJson("/api/v1/assistant/tools/execute",
                authorization,
                projectId,
                toolRequest));
        JsonNode toolResult = toolBody.path("data");
        assertThat(toolResult.path("schema").asText()).isEqualTo("studio.tool-result.v1");
        assertThat(toolResult.path("interfaceCode").asText()).isEqualTo("studio.feature.list");
        assertThat(toolResult.path("path").asText()).isEqualTo("/dashboard");
        assertThat(toolResult.path("executedBy").asText()).isEqualTo("backend");
        assertThat(toolResult.path("mutation").asBoolean()).isFalse();
        assertThat(toolResult.path("data").has("datasourceCount")).isTrue();

        Map<String, Object> chatRequest = new LinkedHashMap<String, Object>();
        chatRequest.put("message", "what can Studio assistant do?");
        chatRequest.put("assistantMode", "goal");
        chatRequest.put("responseLanguage", "en");
        chatRequest.put("protocolVersion", "studio-assistant.v1");

        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/assistant/chat/stream"))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(chatRequest)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", authorization)
                .header("X-Project-Id", String.valueOf(projectId))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event: progress");
        assertThat(response.body()).contains("llm.disabled");
        assertThat(response.body()).contains("event: error");
        assertThat(response.body()).contains("AI 助手未启用");
        assertThat(response.body()).contains("event: done");
        assertThat(response.body()).doesNotContain("event: delta");
        assertThat(response.body()).doesNotContain("Question Breakdown");
        assertThat(response.body()).doesNotContain("```studio-assistant-protocol");
    }

    @Test
    void operationCatalogListToolsShouldExecuteAcrossRegisteredPathsOverRealHttp() throws Exception {
        JsonNode loginBody = loginAsAdminHttp();
        String authorization = bearer(loginBody);
        Long projectId = currentProjectId(loginBody);

        JsonNode operationsBody = requireSuccess(get("/api/v1/assistant/operations", authorization, projectId));
        JsonNode operations = operationsBody.path("data");
        assertThat(operations.isArray()).isTrue();

        List<String> executedPaths = new ArrayList<String>();
        List<String> failures = new ArrayList<String>();
        for (JsonNode operation : operations) {
            JsonNode listTool = listTool(operation.path("readTools"));
            if (listTool == null) {
                continue;
            }
            String path = operation.path("path").asText();
            Map<String, Object> toolRequest = new LinkedHashMap<String, Object>();
            toolRequest.put("interfaceCode", "studio.feature.list");
            toolRequest.put("params", defaultParams(listTool, path));
            try {
                JsonNode toolBody = requireSuccess(postJson("/api/v1/assistant/tools/execute",
                        authorization,
                        projectId,
                        toolRequest));
                JsonNode toolResult = toolBody.path("data");
                assertThat(toolResult.path("schema").asText()).isEqualTo("studio.tool-result.v1");
                assertThat(toolResult.path("interfaceCode").asText()).isEqualTo("studio.feature.list");
                assertThat(toolResult.path("path").asText()).isEqualTo(path);
                assertThat(toolResult.path("executedBy").asText()).isEqualTo("backend");
                assertThat(toolResult.path("mutation").asBoolean()).isFalse();
                assertThat(toolResult.has("data")).isTrue();
                executedPaths.add(path);
            } catch (Exception | AssertionError ex) {
                failures.add(path + ": " + ex.getMessage());
            }
        }

        assertThat(failures).isEmpty();
        assertThat(executedPaths)
                .containsExactlyInAnyOrder(
                        "/dashboard",
                        "/access-center",
                        "/catalog",
                        "/metadata",
                        "/datasources",
                        "/models",
                        "/statistics",
                        "/field-mapping-rules",
                        "/collection-tasks",
                        "/collection-task-runs",
                        "/run-metrics",
                        "/data-development",
                        "/workflows",
                        "/runs",
                        "/data-services",
                        "/data-ingestion-services",
                        "/protocol-conversions",
                        "/data-ingestion-metrics",
                        "/data-service-metrics",
                        "/quality-rules",
                        "/quality-tasks",
                        "/quality-task-runs",
                        "/quality-metrics",
                        "/notifications",
                        "/system",
                        "/script-environments",
                        "/ops-center",
                        "/alerts");
    }

    @Test
    void operationCatalogMutationActionsShouldRequireConfirmationOverRealHttp() throws Exception {
        JsonNode loginBody = loginAsAdminHttp();
        String authorization = bearer(loginBody);
        Long projectId = currentProjectId(loginBody);

        JsonNode operationsBody = requireSuccess(get("/api/v1/assistant/operations", authorization, projectId));
        JsonNode operations = operationsBody.path("data");
        assertThat(operations.isArray()).isTrue();

        List<String> protectedActions = new ArrayList<String>();
        List<String> failures = new ArrayList<String>();
        for (JsonNode operation : operations) {
            String path = operation.path("path").asText();
            JsonNode featureActions = operation.path("featureActions");
            if (!featureActions.isArray()) {
                continue;
            }
            for (JsonNode featureAction : featureActions) {
                if (!featureAction.path("mutation").asBoolean(false)) {
                    continue;
                }
                String action = featureAction.path("action").asText();
                String resource = featureAction.path("resource").asText("");
                Map<String, Object> toolRequest = new LinkedHashMap<String, Object>();
                toolRequest.put("interfaceCode", "studio.feature.action");
                toolRequest.put("params", actionParams(path, action, resource));
                String actionKey = actionKey(path, resource, action);
                try {
                    JsonNode toolBody = requireSuccess(postJson("/api/v1/assistant/tools/execute",
                            authorization,
                            projectId,
                            toolRequest));
                    JsonNode toolResult = toolBody.path("data");
                    assertThat(toolResult.path("schema").asText()).isEqualTo("studio.tool-result.v1");
                    assertThat(toolResult.path("interfaceCode").asText()).isEqualTo("studio.feature.action");
                    assertThat(toolResult.path("path").asText()).isEqualTo(path);
                    assertThat(toolResult.path("action").asText()).isEqualTo(action);
                    if (hasText(resource)) {
                        assertThat(toolResult.path("resource").asText()).isEqualTo(resource);
                    }
                    assertThat(toolResult.path("executedBy").asText()).isEqualTo("backend");
                    assertThat(toolResult.path("mutation").asBoolean()).isTrue();
                    assertThat(toolResult.path("requiresConfirmation").asBoolean()).isTrue();
                    assertThat(toolResult.path("data").path("requiresConfirmation").asBoolean()).isTrue();
                    assertThat(toolResult.path("data").path("message").asText()).contains("confirmed=true");
                    protectedActions.add(actionKey);
                } catch (Exception | AssertionError ex) {
                    failures.add(actionKey + ": " + ex.getMessage());
                }
            }
        }

        assertThat(failures).isEmpty();
        assertThat(protectedActions)
                .contains(
                        actionKey("/datasources", "", "save"),
                        actionKey("/data-development", "sql", "executeSql"),
                        actionKey("/data-services", "", "publish"),
                        actionKey("/quality-metrics", "issues", "updateIssueStatus"),
                        actionKey("/notifications", "", "markRead"),
                        actionKey("/system", "users", "deleteUser"));
        assertThat(protectedActions.size()).isGreaterThan(50);
    }

    private JsonNode listTool(JsonNode readTools) {
        if (!readTools.isArray()) {
            return null;
        }
        for (JsonNode readTool : readTools) {
            if ("studio.feature.list".equals(readTool.path("tool").asText())) {
                return readTool;
            }
        }
        return null;
    }

    private Map<String, Object> defaultParams(JsonNode listTool, String path) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        JsonNode defaultParams = listTool.path("defaultParams");
        if (defaultParams.isObject()) {
            params.putAll(objectMapper.convertValue(defaultParams, new TypeReference<Map<String, Object>>() {
            }));
        }
        params.putIfAbsent("path", path);
        return params;
    }

    private Map<String, Object> actionParams(String path, String action, String resource) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("path", path);
        params.put("action", action);
        if (hasText(resource)) {
            params.put("resource", resource);
        }
        return params;
    }

    private String actionKey(String path, String resource, String action) {
        return path + "#" + (resource == null ? "" : resource) + "#" + action;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
