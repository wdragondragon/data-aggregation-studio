package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.dto.model.assistant.AssistantBackendToolCall;
import com.jdragon.studio.dto.model.assistant.AssistantKnowledgeCapability;
import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantLlmPlannerTest {

    @Test
    void extractBackendToolCallsShouldIgnoreLegacyAndLooseActionJson() {
        AssistantLlmPlanner planner = new AssistantLlmPlanner(new StudioPlatformProperties(), new ObjectMapper());
        String content = "先读取技能。\n"
                + "```assistant-action\n"
                + "{\"backendToolCalls\":[{\"code\":\"assistant.skills.search\",\"reason\":\"检索\",\"params\":{\"query\":\"单表采集\"}}]}\n"
                + "```\n"
                + "畸形前缀" + "\u0cbe\u0caf\u0c95ction"
                + "{\"backendToolCalls\":[{\"code\":\"assistant.skills.search\",\"params\":{\"query\":\"重复\"}}]}";

        List<AssistantBackendToolCall> calls = planner.extractBackendToolCalls(content);

        assertTrue(calls.isEmpty());
    }

    @Test
    void extractBackendToolCallsShouldRequireCompleteStudioAssistantProtocol() {
        AssistantLlmPlanner planner = new AssistantLlmPlanner(new StudioPlatformProperties(), new ObjectMapper());
        String content = "继续读取内置技能。\n"
                + "```studio-assistant-protocol\n"
                + "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Find workflow skills\",\"basis\":[\"User asked about workflow\"],\"requiredObjects\":[{\"type\":\"feature\",\"path\":\"/workflows\"}],\"nextActions\":[{\"type\":\"backendTool\",\"tool\":\"assistant.skills.search\"}]},\"actions\":[{\"type\":\"backendTool\",\"tool\":\"assistant.skills.search\",\"reason\":\"reuse skill\",\"params\":{\"query\":\"workflow\"}}],\"controls\":[]}\n"
                + "```";

        List<AssistantBackendToolCall> calls = planner.extractBackendToolCalls(content);

        assertTrue(calls.isEmpty());
    }

    @Test
    void extractBackendToolCallsShouldSupportPlannedStudioAssistantProtocolActions() {
        AssistantLlmPlanner planner = new AssistantLlmPlanner(new StudioPlatformProperties(), new ObjectMapper());
        String content = "继续读取内置技能。\n"
                + "```studio-assistant-protocol\n"
                + "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Find workflow skills\",\"basis\":[\"User asked about workflow\"],\"requiredObjects\":[{\"type\":\"feature\",\"path\":\"/workflows\"}],\"nextActions\":[{\"type\":\"backendTool\",\"tool\":\"assistant.skills.search\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"Which skills match workflow?\",\"status\":\"needs_tool\",\"output\":\"pending backend tool result\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"search skills\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"actions\":[{\"type\":\"backendTool\",\"tool\":\"assistant.skills.search\",\"reason\":\"reuse skill\",\"params\":{\"query\":\"workflow\"}}],\"controls\":[]}\n"
                + "```";

        List<AssistantBackendToolCall> calls = planner.extractBackendToolCalls(content);

        assertEquals(1, calls.size());
        assertEquals("assistant.skills.search", calls.get(0).getCode());
        assertEquals("workflow", calls.get(0).getParams().get("query"));
    }

    @Test
    void extractBackendToolCallsShouldIgnoreForbiddenProtocolBackendToolCallsField() {
        AssistantLlmPlanner planner = new AssistantLlmPlanner(new StudioPlatformProperties(), new ObjectMapper());
        String content = "继续读取内置技能。\n"
                + "```studio-assistant-protocol\n"
                + "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Find operation tools\",\"basis\":[\"User asked about 数据开发\"],\"requiredObjects\":[{\"type\":\"feature\",\"name\":\"数据开发\"}],\"nextActions\":[{\"type\":\"backendTool\",\"tool\":\"studio.operations.search\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"Which operations match 数据开发?\",\"status\":\"needs_tool\",\"output\":\"pending backend tool result\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"search operations\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"backendToolCalls\":[{\"code\":\"studio.operations.search\",\"reason\":\"find operations\",\"params\":{\"query\":\"数据开发\"}}],\"actions\":[],\"controls\":[]}\n"
                + "```";

        List<AssistantBackendToolCall> calls = planner.extractBackendToolCalls(content);

        assertTrue(calls.isEmpty());
    }

    @Test
    void extractBackendToolCallsShouldRequireExplicitBackendActionTypeAndParams() {
        AssistantLlmPlanner planner = new AssistantLlmPlanner(new StudioPlatformProperties(), new ObjectMapper());
        String content = "继续读取内置技能。\n"
                + "```studio-assistant-protocol\n"
                + "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Find workflow skills\",\"basis\":[\"User asked about workflow\"],\"requiredObjects\":[{\"type\":\"feature\",\"path\":\"/workflows\"}],\"nextActions\":[{\"type\":\"backendTool\",\"tool\":\"assistant.skills.search\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"Which skills match workflow?\",\"status\":\"needs_tool\",\"output\":\"pending backend tool result\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"search skills\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"actions\":[{\"tool\":\"assistant.skills.search\",\"reason\":\"missing type\",\"params\":{\"query\":\"workflow\"}},{\"type\":\"backendTool\",\"tool\":\"studio.operations.search\",\"reason\":\"missing params\"}],\"controls\":[]}\n"
                + "```";

        List<AssistantBackendToolCall> calls = planner.extractBackendToolCalls(content);

        assertTrue(calls.isEmpty());
    }

    @Test
    void extractBackendToolCallsShouldRejectCodeAliasForBackendActionTool() {
        AssistantLlmPlanner planner = new AssistantLlmPlanner(new StudioPlatformProperties(), new ObjectMapper());
        String content = "继续读取内置技能。\n"
                + "```studio-assistant-protocol\n"
                + "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Find workflow skills\",\"basis\":[\"User asked about workflow\"],\"requiredObjects\":[{\"type\":\"feature\",\"path\":\"/workflows\"}],\"nextActions\":[{\"type\":\"backendTool\",\"tool\":\"assistant.skills.search\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"Which skills match workflow?\",\"status\":\"needs_tool\",\"output\":\"pending backend tool result\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"search skills\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"actions\":[{\"type\":\"backendTool\",\"code\":\"assistant.skills.search\",\"reason\":\"legacy alias must be rejected\",\"params\":{\"query\":\"workflow\"}}],\"controls\":[]}\n"
                + "```";

        List<AssistantBackendToolCall> calls = planner.extractBackendToolCalls(content);

        assertTrue(calls.isEmpty());
    }

    @Test
    void extractBackendToolCallsShouldRequireExplicitControlShape() {
        AssistantLlmPlanner planner = new AssistantLlmPlanner(new StudioPlatformProperties(), new ObjectMapper());
        String content = "继续读取内置技能。\n"
                + "```studio-assistant-protocol\n"
                + "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Find workflow skills\",\"basis\":[\"User asked about workflow\"],\"requiredObjects\":[{\"type\":\"feature\",\"path\":\"/workflows\"}],\"nextActions\":[{\"type\":\"backendTool\",\"tool\":\"assistant.skills.search\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"waiting_for_user\",\"autoContinue\":false,\"questions\":[{\"id\":\"q1\",\"input\":\"Which datasource should be used?\",\"status\":\"needs_user\",\"output\":\"waiting for selection\"}],\"next\":[{\"id\":\"step1\",\"type\":\"control\",\"status\":\"pending\",\"description\":\"ask user\"}],\"evidence\":[],\"stopReason\":\"missing_user_input\"},\"actions\":[{\"type\":\"backendTool\",\"tool\":\"assistant.skills.search\",\"reason\":\"reuse skill\",\"params\":{\"query\":\"workflow\"}}],\"controls\":[{\"title\":\"Invalid control\",\"paramKey\":\"datasourceId\",\"options\":[{\"label\":\"A\",\"value\":\"1\"},{\"label\":\"B\",\"value\":\"2\"}]}]}\n"
                + "```";

        List<AssistantBackendToolCall> calls = planner.extractBackendToolCalls(content);

        assertTrue(calls.isEmpty());
    }

    @Test
    void streamChatShouldReturnDisabledErrorWhenLlmDisabled() {
        AssistantLlmPlanner planner = new AssistantLlmPlanner(
                new StudioPlatformProperties(),
                new ObjectMapper(),
                Collections.singletonList(new AssistantBuiltInSkillRegistry()),
                null);
        AssistantPlanRequest request = new AssistantPlanRequest();
        request.setMessage("what can Studio assistant do?");
        request.setAssistantMode("goal");
        request.setResponseLanguage("en");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        planner.streamChat(request, Collections.<AssistantKnowledgeCapability>emptyList(), outputStream);

        String response = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(response.contains("event: progress"));
        assertTrue(response.contains("llm.disabled"));
        assertTrue(response.contains("event: error"));
        assertTrue(response.contains("AI 助手未启用"));
        assertFalse(response.contains("event: delta"));
        assertFalse(response.contains("studio-assistant-protocol"));
        assertFalse(response.contains("studio.feature.action"));
        assertFalse(response.contains("studio.navigation.open"));
        assertTrue(response.contains("event: done"));
    }

    @Test
    void streamChatShouldAttemptLlmAndNotEmitLocalProtocolWhenLlmEnabledButUnavailable() {
        AssistantLlmPlanner planner = new AssistantLlmPlanner(
                llmEnabledProperties(),
                new ObjectMapper(),
                Arrays.asList(new AssistantBuiltInSkillRegistry(), new AssistantStudioOperationRegistry()),
                null);
        AssistantPlanRequest request = new AssistantPlanRequest();
        request.setMessage("看看有哪些数据源");
        request.setAssistantMode("chat");
        request.setResponseLanguage("zh");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        planner.streamChat(request, Collections.<AssistantKnowledgeCapability>emptyList(), outputStream);

        String response = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(response.contains("llm.connect"));
        assertTrue(response.contains("llm.error"));
        assertTrue(response.contains("LLM 请求失败，本轮未执行任何 Studio 操作"));
        assertFalse(response.contains("assistant.fast-path"));
        assertFalse(response.contains("assistant.builtin-protocol"));
        assertFalse(response.contains("studio-assistant-protocol"));
        assertFalse(response.contains("studio.feature.list"));
        assertTrue(response.contains("event: done"));
    }

    @Test
    void streamChatShouldNotSummarizeToolResultsLocallyWhenLlmEnabledButUnavailable() {
        AssistantLlmPlanner planner = new AssistantLlmPlanner(
                llmEnabledProperties(),
                new ObjectMapper(),
                Arrays.asList(new AssistantBuiltInSkillRegistry(), new AssistantStudioOperationRegistry()),
                null);
        AssistantPlanRequest request = new AssistantPlanRequest();
        request.setMessage("接口结果已返回，请结合 toolResults 继续回答或判断下一步是否还需要调用接口。");
        request.setAssistantMode("chat");
        request.setResponseLanguage("zh");
        request.setToolResults(Collections.singletonList(toolResult(
                "studio.feature.list",
                map("path", "/datasources"),
                map("total", Integer.valueOf(2),
                        "items", Arrays.asList(
                                map("name", "mysql1", "typeCode", "MYSQL8"),
                                map("name", "oracle1", "typeCode", "ORACLE"))))));
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        planner.streamChat(request, Collections.<AssistantKnowledgeCapability>emptyList(), outputStream);

        String response = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(response.contains("llm.connect"));
        assertTrue(response.contains("llm.error"));
        assertTrue(response.contains("LLM 请求失败，本轮未执行任何 Studio 操作"));
        assertFalse(response.contains("assistant.fast-path"));
        assertFalse(response.contains("已读取数据源列表"));
        assertFalse(response.contains("共 2 条"));
        assertFalse(response.contains("mysql1"));
        assertFalse(response.contains("studio-assistant-protocol"));
        assertTrue(response.contains("event: done"));
    }

    @Test
    void streamChatShouldTellLlmToUsePageContextActiveObjectForCurrentSelection() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<String>("");
        AtomicReference<String> requestContentLength = new AtomicReference<String>("");
        AtomicReference<String> requestTransferEncoding = new AtomicReference<String>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requestBody.set(new String(body, StandardCharsets.UTF_8));
            requestContentLength.set(exchange.getRequestHeaders().getFirst("Content-length"));
            requestTransferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-encoding"));
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":\"收到\"}}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(response);
            }
        });
        server.start();
        try {
            StudioPlatformProperties properties = llmEnabledProperties();
            properties.getAssistant().getLlm().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            AssistantLlmPlanner planner = new AssistantLlmPlanner(
                    properties,
                    objectMapper,
                    Arrays.asList(new AssistantBuiltInSkillRegistry(), new AssistantStudioOperationRegistry()),
                    null);
            AssistantPlanRequest request = new AssistantPlanRequest();
            request.setMessage("当前页面选中的数据源里有哪些真实表");
            request.setAssistantMode("goal");
            request.setResponseLanguage("zh");
            request.setContext(map(
                    "pageContext", map(
                            "source", "datasources-view",
                            "path", "/datasources",
                            "summary", "当前数据源页，最近选中或操作的数据源是 mock_mysql。",
                            "activeObject", map(
                                    "type", "datasource",
                                    "path", "/datasources",
                                    "id", Integer.valueOf(11),
                                    "name", "mock_mysql"))));
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            planner.streamChat(request, Collections.<AssistantKnowledgeCapability>emptyList(), outputStream);

            JsonNode payload = objectMapper.readTree(requestBody.get());
            assertEquals(String.valueOf(requestBody.get().getBytes(StandardCharsets.UTF_8).length), requestContentLength.get());
            assertFalse("chunked".equalsIgnoreCase(requestTransferEncoding.get()));
            String systemPrompt = payload.path("messages").path(0).path("content").asText();
            String runtimeContext = payload.path("messages").path(1).path("content").asText();
            assertTrue(systemPrompt.contains("currentContext.pageContext"));
            assertTrue(systemPrompt.contains("pageContext.activeObject"));
            assertTrue(systemPrompt.contains("first use currentContext.pageContext.activeObject"));
            assertTrue(systemPrompt.contains("assistant.context.read"));
            assertTrue(systemPrompt.contains("current business context"));
            assertTrue(systemPrompt.contains("Supported frontend tool values include assistant.context.observe, assistant.context.read, assistant.context.search, assistant.memory.select, studio.feature.list, studio.feature.get, studio.feature.action, studio.navigation.open, and assistant.script.execute."));
            assertTrue(systemPrompt.contains("Do not use legacy narrow tool names"));
            assertTrue(systemPrompt.contains("the repaired protocol must use the canonical parameter names declared in currentContext.frontendTools/currentContext.frontendActionRegistry"));
            assertTrue(systemPrompt.contains("payload.content"));
            assertTrue(systemPrompt.contains("entrypointId"));
            assertFalse(systemPrompt.contains("sql/query/script to payload.content"));
            assertFalse(systemPrompt.contains("name/scriptName to payload.fileName"));
            assertTrue(runtimeContext.contains("\"pageContext\""));
            assertTrue(runtimeContext.contains("\"activeObject\""));
            assertTrue(runtimeContext.contains("\"id\":11"));
        } finally {
            server.stop(0);
        }
    }

    private StudioPlatformProperties llmEnabledProperties() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getAssistant().getLlm().setEnabled(true);
        properties.getAssistant().getLlm().setBaseUrl("http://127.0.0.1:9/v1");
        properties.getAssistant().getLlm().setApiKey("test");
        properties.getAssistant().getLlm().setModel("test-model");
        properties.getAssistant().getLlm().setTimeoutSeconds(Integer.valueOf(1));
        return properties;
    }

    private Map<String, Object> toolResult(String interfaceCode,
                                           Map<String, Object> params,
                                           Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", interfaceCode + "-test");
        result.put("interfaceCode", interfaceCode);
        result.put("ok", Boolean.TRUE);
        result.put("params", params);
        result.put("data", data);
        return result;
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
