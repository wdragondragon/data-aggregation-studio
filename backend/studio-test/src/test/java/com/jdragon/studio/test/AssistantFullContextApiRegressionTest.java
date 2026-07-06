package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantFullContextApiRegressionTest extends StudioApiRegressionTestSupport {

    @Test
    void authenticatedChatStreamShouldWorkWithInitializedStudioContext() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("message", "what can Studio assistant do?");
        payload.put("assistantMode", "goal");
        payload.put("responseLanguage", "en");
        payload.put("protocolVersion", "studio-assistant.v1");

        MvcResult streamResult = mockMvc.perform(post("/api/v1/assistant/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, adminAuthorizationHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(streamResult))
                .andExpect(status().isOk())
                .andReturn();

        String content = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("event: progress");
        assertThat(content).contains("llm.disabled");
        assertThat(content).contains("event: error");
        assertThat(content).contains("AI 助手未启用");
        assertThat(content).contains("event: done");
        assertThat(content).doesNotContain("event: delta");
        assertThat(content).doesNotContain("I will proceed in goal mode");
        assertThat(content).doesNotContain("Question Breakdown");
        assertThat(content).doesNotContain("```studio-assistant-protocol");
    }

    @Test
    void authenticatedOperationsAndToolGatewayShouldWorkWithInitializedStudioContext() throws Exception {
        JsonNode loginBody = loginAsAdmin();
        String authorization = adminAuthorizationHeader(loginBody);
        Long projectId = currentProjectId(loginBody);

        JsonNode operationsBody = readBody(mockMvc.perform(get("/api/v1/assistant/operations")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header("X-Project-Id", projectId))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(operationsBody.path("success").asBoolean()).isTrue();
        JsonNode operations = operationsBody.path("data");
        assertThat(operations.isArray()).isTrue();
        assertThat(operations).anySatisfy(operation -> {
            assertThat(operation.path("schema").asText()).isEqualTo("studio.operation.v1");
            assertThat(operation.path("path").asText()).isEqualTo("/dashboard");
            assertThat(operation.path("readTools").isArray()).isTrue();
        });
        assertThat(operations).anySatisfy(operation ->
                assertThat(operation.path("path").asText()).isEqualTo("/notifications"));

        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("interfaceCode", "studio.feature.list");
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("path", "/dashboard");
        request.put("params", params);

        JsonNode toolBody = readBody(mockMvc.perform(post("/api/v1/assistant/tools/execute")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn());

        JsonNode toolResult = toolBody.path("data");
        assertThat(toolBody.path("success").asBoolean()).isTrue();
        assertThat(toolResult.path("schema").asText()).isEqualTo("studio.tool-result.v1");
        assertThat(toolResult.path("interfaceCode").asText()).isEqualTo("studio.feature.list");
        assertThat(toolResult.path("path").asText()).isEqualTo("/dashboard");
        assertThat(toolResult.path("executedBy").asText()).isEqualTo("backend");
        assertThat(toolResult.path("mutation").asBoolean()).isFalse();
        assertThat(toolResult.path("data").has("datasourceCount")).isTrue();
    }
}
