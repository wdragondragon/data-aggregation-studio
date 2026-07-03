package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.assistant.AssistantBackendToolCall;
import com.jdragon.studio.dto.model.assistant.AssistantBackendToolResult;
import com.jdragon.studio.dto.model.assistant.AssistantKnowledgeCapability;
import com.jdragon.studio.dto.model.assistant.AssistantLlmPlan;
import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssistantLlmPlanner {

    private static final int MAX_BACKEND_TOOL_CALLS = 3;

    private final StudioPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final AssistantSkillMemoryService skillMemoryService;
    private final AssistantBackendToolRegistry backendToolRegistry;

    @Autowired
    public AssistantLlmPlanner(StudioPlatformProperties properties,
                               ObjectMapper objectMapper,
                               AssistantSkillMemoryService skillMemoryService,
                               AssistantBackendToolRegistry backendToolRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.skillMemoryService = skillMemoryService;
        this.backendToolRegistry = backendToolRegistry;
    }

    public AssistantLlmPlanner(StudioPlatformProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null, null);
    }

    public boolean isEnabled() {
        StudioPlatformProperties.LlmProperties llm = properties.getAssistant().getLlm();
        return llm.isEnabled()
                && hasText(llm.getBaseUrl())
                && hasText(llm.getApiKey())
                && hasText(llm.getModel());
    }

    public AssistantLlmPlan plan(AssistantPlanRequest request,
                                 List<AssistantKnowledgeCapability> capabilities,
                                 String latestMessage) {
        if (!isEnabled()) {
            return null;
        }
        try {
            String content = requestCompletion(request, capabilities, latestMessage);
            String json = extractJsonObject(content);
            AssistantLlmPlan plan = objectMapper.readValue(json, AssistantLlmPlan.class);
            if (plan.getWarnings() == null) {
                plan.setWarnings(new ArrayList<String>());
            }
            return plan;
        } catch (Exception ex) {
            AssistantLlmPlan fallback = new AssistantLlmPlan();
            fallback.getWarnings().add("LLM planning failed: " + ex.getMessage());
            return fallback;
        }
    }

    public void streamChat(AssistantPlanRequest request,
                           List<AssistantKnowledgeCapability> capabilities,
                           OutputStream outputStream) {
        if (!isEnabled()) {
            writeProgress(outputStream, "llm.config", "检查 LLM 配置", "LLM 尚未启用，返回配置提示。", "warning");
            writeSse(outputStream, "delta", Collections.singletonMap(
                    "content", "LLM 尚未启用。请配置 STUDIO_ASSISTANT_LLM_ENABLED、STUDIO_ASSISTANT_LLM_BASE_URL、STUDIO_ASSISTANT_LLM_API_KEY 和模型名称后再使用对话助手。"));
            writeSse(outputStream, "done", Collections.<String, Object>emptyMap());
            return;
        }
        try {
            StudioPlatformProperties.LlmProperties llm = properties.getAssistant().getLlm();
            List<Map<String, Object>> assistantSkills = loadAssistantSkills(request);
            List<Map<String, Object>> backendTools = listBackendTools();
            writeProgress(outputStream, "context.prepare", "整理当前上下文", "已读取当前路由、租户、项目、已收集输入和最近对话。", "done");
            writeProgress(outputStream, "knowledge.load", "加载 Studio 能力知识", "已加载 " + (capabilities == null ? 0 : capabilities.size()) + " 个能力包。", "done");
            writeProgress(outputStream, "skills.load", "加载助手技能记忆", "已加载 " + assistantSkills.size() + " 条技能摘要和 " + backendTools.size() + " 个后端白名单工具。", "done");
            String streamedContent = requestStreamingCompletion(request, capabilities, assistantSkills, backendTools, outputStream);
            List<AssistantBackendToolResult> backendToolResults = executeBackendToolCallsFromResponse(request, streamedContent, outputStream);
            if (!backendToolResults.isEmpty()) {
                writeProgress(outputStream, "backend.tools.complete", "后端白名单工具完成",
                        "已返回 " + backendToolResults.size() + " 项工具结果，正在让助手补充说明。", "done");
                requestBackendToolFollowUpCompletion(
                        request,
                        capabilities,
                        assistantSkills,
                        backendTools,
                        streamedContent,
                        backendToolResults,
                        outputStream);
            }
            writeProgress(outputStream, "llm.complete", "模型回复完成", "已接收完整流式回复。", "done");
            writeSse(outputStream, "done", Collections.<String, Object>emptyMap());
        } catch (Exception ex) {
            writeProgress(outputStream, "llm.error", "模型回复失败", ex.getMessage(), "error");
            writeSse(outputStream, "error", Collections.singletonMap("message", "LLM stream failed: " + ex.getMessage()));
            writeSse(outputStream, "done", Collections.<String, Object>emptyMap());
        }
    }

    private String requestCompletion(AssistantPlanRequest request,
                                     List<AssistantKnowledgeCapability> capabilities,
                                     String latestMessage) throws Exception {
        StudioPlatformProperties.LlmProperties llm = properties.getAssistant().getLlm();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(resolveTimeoutSeconds(llm)))
                .build();

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", llm.getModel());
        payload.put("temperature", llm.getTemperature());
        payload.put("max_tokens", llm.getMaxTokens());
        payload.put("messages", buildMessages(request, capabilities, latestMessage,
                loadAssistantSkills(request),
                listBackendTools()));

        String body = objectMapper.writeValueAsString(payload);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(resolveChatCompletionsUrl(llm.getBaseUrl())))
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds(llm)))
                .header("Authorization", "Bearer " + llm.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("LLM HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText();
        if (!hasText(content)) {
            throw new IllegalStateException("LLM response content is empty");
        }
        return content;
    }

    private String requestStreamingCompletion(AssistantPlanRequest request,
                                              List<AssistantKnowledgeCapability> capabilities,
                                              List<Map<String, Object>> assistantSkills,
                                              List<Map<String, Object>> backendTools,
                                              OutputStream outputStream) throws Exception {
        return requestStreamingCompletionWithMessages(
                buildChatMessages(request, capabilities, assistantSkills, backendTools),
                outputStream);
    }

    private void requestBackendToolFollowUpCompletion(AssistantPlanRequest request,
                                                      List<AssistantKnowledgeCapability> capabilities,
                                                      List<Map<String, Object>> assistantSkills,
                                                      List<Map<String, Object>> backendTools,
                                                      String previousAssistantContent,
                                                      List<AssistantBackendToolResult> backendToolResults,
                                                      OutputStream outputStream) throws Exception {
        List<Map<String, String>> messages = buildChatMessages(request, capabilities, assistantSkills, backendTools);
        String visiblePrevious = stripAssistantActionBlocks(previousAssistantContent);
        if (hasText(visiblePrevious)) {
            messages.add(message("assistant", visiblePrevious));
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("backendToolResults", summarizeBackendToolResults(backendToolResults));
        messages.add(message("system", "Backend allow-listed tool results JSON:\n" + objectMapper.writeValueAsString(payload)));
        messages.add(message("user", "请基于后端工具结果继续回答。只输出用户可见内容；除非还需要前端安全接口调用或问答控件，否则不要再输出 assistant-action。"));
        requestStreamingCompletionWithMessages(messages, outputStream);
    }

    private String requestStreamingCompletionWithMessages(List<Map<String, String>> messages,
                                                          OutputStream outputStream) throws Exception {
        StudioPlatformProperties.LlmProperties llm = properties.getAssistant().getLlm();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(resolveTimeoutSeconds(llm)))
                .build();

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", llm.getModel());
        payload.put("temperature", llm.getTemperature());
        payload.put("max_tokens", llm.getMaxTokens());
        payload.put("stream", Boolean.TRUE);
        payload.put("messages", messages);

        String body = objectMapper.writeValueAsString(payload);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(resolveChatCompletionsUrl(llm.getBaseUrl())))
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds(llm)))
                .header("Authorization", "Bearer " + llm.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        writeProgress(outputStream, "llm.connect", "连接语言模型", "使用模型 " + llm.getModel() + " 生成回复。", "running");
        HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("LLM HTTP " + response.statusCode());
        }
        writeProgress(outputStream, "llm.connect", "语言模型已连接", "已建立连接并开始接收回复。", "done");
        writeProgress(outputStream, "llm.stream", "模型开始返回", "已收到 LLM 首包，正在逐步展示内容。", "done");
        StringBuilder contentBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String data = trimmed.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode root = objectMapper.readTree(data);
                JsonNode delta = root.path("choices").path(0).path("delta");
                String content = delta.path("content").asText();
                if (!content.isEmpty()) {
                    contentBuilder.append(content);
                    writeSse(outputStream, "delta", Collections.singletonMap("content", content));
                }
            }
        }
        return contentBuilder.toString();
    }

    private List<Map<String, String>> buildMessages(AssistantPlanRequest request,
                                                    List<AssistantKnowledgeCapability> capabilities,
                                                    String latestMessage,
                                                    List<Map<String, Object>> assistantSkills,
                                                    List<Map<String, Object>> backendTools) throws Exception {
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        messages.add(message("system", systemPrompt()));

        Map<String, Object> userPayload = new LinkedHashMap<String, Object>();
        userPayload.put("latestMessage", latestMessage);
        userPayload.put("conversation", request == null ? null : request.getMessages());
        userPayload.put("collectedInputs", request == null ? null : request.getCollectedInputs());
        userPayload.put("toolResults", request == null ? null : request.getToolResults());
        userPayload.put("context", request == null ? null : request.getContext());
        userPayload.put("capabilities", summarizeCapabilities(capabilities));
        userPayload.put("assistantSkills", assistantSkills);
        userPayload.put("backendTools", backendTools);
        messages.add(message("user", objectMapper.writeValueAsString(userPayload)));
        return messages;
    }

    private List<Map<String, String>> buildChatMessages(AssistantPlanRequest request,
                                                        List<AssistantKnowledgeCapability> capabilities,
                                                        List<Map<String, Object>> assistantSkills,
                                                        List<Map<String, Object>> backendTools) throws Exception {
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        messages.add(message("system", chatSystemPrompt()));
        Map<String, Object> knowledge = new LinkedHashMap<String, Object>();
        knowledge.put("currentContext", request == null ? null : request.getContext());
        knowledge.put("collectedInputs", request == null ? null : request.getCollectedInputs());
        knowledge.put("toolResults", request == null ? null : request.getToolResults());
        knowledge.put("capabilities", summarizeCapabilities(capabilities));
        knowledge.put("assistantSkills", assistantSkills);
        knowledge.put("backendTools", backendTools);
        messages.add(message("system", "Studio assistant knowledge and runtime context JSON:\n" + objectMapper.writeValueAsString(knowledge)));
        if (request != null && request.getMessages() != null) {
            int start = Math.max(0, request.getMessages().size() - 12);
            for (int i = start; i < request.getMessages().size(); i++) {
                if (request.getMessages().get(i) == null || !hasText(request.getMessages().get(i).getContent())) {
                    continue;
                }
                String role = request.getMessages().get(i).getRole();
                if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) {
                    role = "user";
                }
                messages.add(message(role, request.getMessages().get(i).getContent()));
            }
        }
        return messages;
    }

    private String systemPrompt() {
        return ""
                + "You are the planning brain for Data Aggregation Studio's built-in assistant.\n"
                + "Return only one valid JSON object. Do not include markdown fences.\n"
                + "You must not invent datasource IDs, model IDs, field names, or execute any business API.\n"
                + "You may copy IDs or field names only when they are present in collectedInputs or toolResults.\n"
                + "Use only capability codes and interfaceCode values from the provided knowledge capabilities.\n"
                + "You may suggest backendToolCalls only with codes listed in backendTools. Backend tools are allow-listed annotated methods; user text is never a method name.\n"
                + "Backend tools may retrieve assistant skills or safe metadata only. Do not use or request them for business mutations.\n"
                + "Write operations such as save, publish, trigger, delete, or configuration mutation must not be suggested for direct execution.\n"
                + "The frontend executes read-only tools through Studio HTTP APIs, validates selected values, and asks the user to choose ambiguous or missing values.\n"
                + "If a required value is missing, request the read-only interface that can resolve candidates instead of guessing.\n"
                + "Prefer assistantSkills when explaining Studio concepts or resolving known process rules.\n"
                + "Do not create or enable schedule configuration by default. If the user explicitly asks for scheduling/timing, use the exposed schedule-capable Studio action and require frontend confirmation. For collection tasks, use FULL mode unless the user explicitly asks for a different supported mode.\n"
                + "Output schema:\n"
                + "{\n"
                + "  \"capabilityCode\": \"collection.singleTable.create\",\n"
                + "  \"confidence\": 0.0,\n"
                + "  \"assistantMessage\": \"short Chinese message\",\n"
                + "  \"inferredInputs\": {\n"
                + "    \"name\": \"optional task name\",\n"
                + "    \"source\": {\"text\": \"source phrase\", \"datasourceText\": \"source datasource phrase\", \"modelText\": \"source table/model phrase\"},\n"
                + "    \"target\": {\"text\": \"target phrase\", \"datasourceText\": \"target datasource phrase\", \"modelText\": \"target table/model phrase\"},\n"
                + "    \"executionOptions\": {\"collectionMode\": \"FULL\"}\n"
                + "  },\n"
                + "  \"missingInputs\": [\"source.datasourceId\"],\n"
                + "  \"toolCalls\": [{\"interfaceCode\": \"catalog.capabilities\", \"reason\": \"why\"}],\n"
                + "  \"backendToolCalls\": [{\"code\": \"assistant.skills.search\", \"reason\": \"why\", \"params\": {\"query\": \"optional\"}}],\n"
                + "  \"warnings\": []\n"
                + "}";
    }

    private String chatSystemPrompt() {
        return ""
                + "You are Data Aggregation Studio's built-in AI assistant. Reply in concise, natural Chinese.\n"
                + "You can explain Studio concepts, modules, current automation capabilities, and how the guided assistant works.\n"
                + "Use assistantSkills and the provided runtime context as your project knowledge. Do not imply that you scanned source code or databases.\n"
                + "This is not a keyword-triggered wizard. Decide from the whole conversation, current context, toolResults, and capability knowledge whether a Studio API call or a user decision is needed.\n"
                + "When you need project candidates, metadata, preview output, or a user decision before continuing, append exactly one hidden fenced block after your visible answer:\n"
                + "```assistant-action\n"
                + "{\"toolCalls\":[{\"interfaceCode\":\"datasources.options\",\"reason\":\"resolve datasource candidates\",\"params\":{}}]}\n"
                + "```\n"
                + "The action fence syntax must be exact. If you say you will query, read, fetch, inspect, open, preview, execute, or continue with a Studio operation, the same response must include an assistant-action block or uiControls that actually lets the orchestrator continue.\n"
                + "Do not output only '我先查询/我先读取/正在获取' as the final visible answer. For explicit inspect/list/search/open requests against accessible project resources, choose a safe read tool from currentContext.frontendTools or ask with uiControls; do not skip the action block merely because some params are uncertain.\n"
                + "For multi-step tasks, do not narrate every internal read or API step as a visible chat message. The frontend shows backend operations in a collapsible process panel. Visible text should focus on user-facing decisions, final results, or a concise one-sentence status.\n"
                + "If your response only needs to request toolCalls for internal progress, keep the visible answer empty or very short and put the operation only in the assistant-action block.\n"
                + "currentContext.accessibleCapabilities is the primary capability directory for the current user. It is built from pages the user can enter and describes allowed read operations, required params, optional params, and write confirmation policy. Treat it as the user's capability boundary.\n"
                + "currentContext.accessibleFeatures is the legacy visible page list. Use it only as a path/label fallback when accessibleCapabilities is missing.\n"
                + "toolCalls are controlled frontend API calls. Supported interfaceCode values must come from capabilities.interfaces or currentContext.frontendTools. Safe examples include studio.feature.list, studio.feature.get, studio.feature.action, studio.navigation.open, catalog.capabilities, datasources.options, models.datasourceOptions, models.get, catalog.runtimeOptionSchema, and collectionTasks.preview.\n"
                + "Use studio.feature.list with {\"path\":\"/exact-path\", ...filters} to read a visible function's list, overview, options, or metrics. Use studio.feature.get with {\"path\":\"/exact-path\",\"id\":\"...\"} to read a visible function's details. The path must exactly match one item in currentContext.accessibleCapabilities.\n"
                + "Use studio.feature.action only for actions listed in currentContext.frontendActionRegistry or accessibleCapabilities.operations where operation=action. Required params are path, action, and any listed id/payload/resource values. The frontend will automatically stop mutation actions and render a confirm/cancel chat control before execution.\n"
                + "If a toolResult has ok=false, treat it as recoverable execution context. First inspect its params and error, then either emit a corrected assistant-action using the same allow-listed tools, or emit uiControls for only the missing user decision. Do not dump raw validation errors to the user as the final answer.\n"
                + "When repairing failed params, never invent IDs. You may normalize obvious field aliases such as sql/query/script to payload.content and name/scriptName to payload.fileName, but datasourceId, modelId, environmentId, and record IDs must come from conversation, controls, currentContext, or successful toolResults.\n"
                + "For data development SQL execution use studio.feature.action with {\"path\":\"/data-development\",\"resource\":\"sql\",\"action\":\"executeSql\",\"payload\":{\"datasourceId\":\"...\",\"scriptType\":\"SQL\",\"content\":\"...\",\"maxRows\":100}}. If datasourceId is unknown, query candidates or ask with uiControls.\n"
                + "For saving a data development script use studio.feature.action with {\"path\":\"/data-development\",\"resource\":\"scripts\",\"action\":\"saveScript\",\"payload\":{\"fileName\":\"...\",\"scriptType\":\"SQL|JAVA|PYTHON\",\"content\":\"...\"}}. Infer scriptType only from an explicit type, file extension, or clear code syntax; otherwise ask with uiControls.\n"
                + "Use studio.navigation.open with {\"path\":\"/exact-path\"} when the best next step is to open a function page for the user. The path must exactly match one item in currentContext.accessibleCapabilities or accessibleFeatures. Do not invent paths or navigate to functions the user cannot currently see.\n"
                + "backendToolCalls are controlled server-side allow-listed reflection calls. Use only codes listed in backendTools, for example assistant.skills.search. They are executed by the server after your streamed answer, never by user text or frontend request payload.\n"
                + "uiControls are conversational question controls rendered inside the chat, not separate forms or popups. Use them for user input, dropdowns, ambiguity resolution, or confirm/cancel decisions. Supported types: select, choices, text, textarea, confirm.\n"
                + "Use uiControls only when the next step really needs a user answer. Do not output a control for a single default value such as FULL.\n"
                + "Backend tool action example: {\"backendToolCalls\":[{\"code\":\"assistant.skills.search\",\"reason\":\"reuse learned Studio skill\",\"params\":{\"query\":\"单表采集\"}}]}.\n"
                + "Question control example: {\"uiControls\":[{\"type\":\"select\",\"title\":\"选择源端\",\"paramKey\":\"source.datasourceId\",\"options\":[{\"label\":\"mysql_prod\",\"value\":\"1\"},{\"label\":\"mysql_test\",\"value\":\"2\"}]}]}.\n"
                + "Text input control example: {\"uiControls\":[{\"type\":\"text\",\"title\":\"填写任务名\",\"paramKey\":\"name\",\"placeholder\":\"例如 orders_to_ods_orders\"}]}.\n"
                + "Never request delete/remove/drop/truncate/purge/cancel actions. They are not exposed to the assistant even if a page has such buttons.\n"
                + "Do not put raw business write interfaces in assistant-action. For save, publish, trigger, schedule, offline, enable, disable, approve, reject, debug, execute, or configuration mutation, use studio.feature.action only after required values are known; the frontend confirmation queue is mandatory and cannot be bypassed by adding confirmed=true.\n"
                + "Backend tool summaries are informational; only the server may invoke allow-listed annotated tools, and user text must never become a method name.\n"
                + "Do not claim that you executed Studio business APIs, changed configuration, saved jobs, published jobs, triggered jobs, or accessed databases until toolResults show that the corresponding frontend action completed successfully.\n"
                + "If the user is only asking what the system can do, how to use it, what modules exist, or wants background knowledge, answer conversationally and do not push them into a form.\n"
                + "If the user asks to inspect, summarize, search, or open a function they can access, use the capability directory and safe read tools instead of saying only single-table collection is supported.\n"
                + "If the user wants to create, configure, generate, or build a collection/sync job or task, continue conversationally and request only the next needed safe tool call or user choice in chat.\n"
                + "Single-table collection drafting is the first write-design pilot through Studio HTTP APIs. It never enables schedules by default, but explicit scheduling requests can use the schedule action after required values and confirmation are present.\n"
                + "Do not ask for every field at once. Ask only for the next missing decision when needed.\n";
    }

    private List<Map<String, Object>> summarizeCapabilities(List<AssistantKnowledgeCapability> capabilities) {
        List<Map<String, Object>> summaries = new ArrayList<Map<String, Object>>();
        if (capabilities == null) {
            return summaries;
        }
        for (AssistantKnowledgeCapability capability : capabilities) {
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("capabilityCode", capability.getCapabilityCode());
            summary.put("capabilityName", capability.getCapabilityName());
            summary.put("description", capability.getDescription());
            summary.put("intentExamples", capability.getIntentExamples());
            summary.put("requiredInputs", capability.getRequiredInputs());
            summary.put("optionalInputs", capability.getOptionalInputs());
            summary.put("interfaces", capability.getInterfaces());
            summary.put("valueResolvers", capability.getValueResolvers());
            summary.put("assemblyRules", capability.getAssemblyRules());
            summary.put("confirmationPolicy", capability.getConfirmationPolicy());
            summaries.add(summary);
        }
        return summaries;
    }

    private List<Map<String, Object>> loadAssistantSkills(AssistantPlanRequest request) {
        if (skillMemoryService == null) {
            return Collections.emptyList();
        }
        return skillMemoryService.loadRelevantSkills(request);
    }

    private List<Map<String, Object>> listBackendTools() {
        if (backendToolRegistry == null) {
            return Collections.emptyList();
        }
        return backendToolRegistry.listToolSummaries();
    }

    private List<AssistantBackendToolResult> executeBackendToolCallsFromResponse(AssistantPlanRequest request,
                                                                                 String content,
                                                                                 OutputStream outputStream) {
        List<AssistantBackendToolCall> calls = extractBackendToolCalls(content);
        List<AssistantBackendToolResult> results = new ArrayList<AssistantBackendToolResult>();
        if (backendToolRegistry == null || calls.isEmpty()) {
            return results;
        }
        Map<String, Object> available = new LinkedHashMap<String, Object>();
        for (Map<String, Object> item : backendToolRegistry.listToolSummaries()) {
            Object code = item.get("code");
            if (code != null) {
                available.put(String.valueOf(code), item);
            }
        }
        List<String> seen = new ArrayList<String>();
        for (AssistantBackendToolCall call : calls) {
            if (call == null || !hasText(call.getCode()) || results.size() >= MAX_BACKEND_TOOL_CALLS) {
                continue;
            }
            String code = call.getCode().trim();
            if (!available.containsKey(code) || seen.contains(code)) {
                continue;
            }
            seen.add(code);
            Map<String, Object> params = call.getParams() == null ? new LinkedHashMap<String, Object>() : call.getParams();
            writeProgress(outputStream, "backend.tool." + code, "调用后端白名单工具",
                    code + "：" + safeReason(call.getReason()), "running");
            AssistantBackendToolResult result = new AssistantBackendToolResult();
            result.setCode(code);
            result.setParams(params);
            try {
                Object data = backendToolRegistry.invoke(code, request, params);
                result.setData(data);
                result.setOk(Boolean.TRUE);
                writeProgress(outputStream, "backend.tool." + code, "后端白名单工具完成",
                        code + " 返回 " + summarizeBackendToolData(data) + "。", "done");
            } catch (Exception ex) {
                result.setOk(Boolean.FALSE);
                result.setError(ex.getMessage());
                writeProgress(outputStream, "backend.tool." + code, "后端白名单工具失败",
                        code + "：" + ex.getMessage(), "warning");
            }
            results.add(result);
        }
        return results;
    }

    List<AssistantBackendToolCall> extractBackendToolCalls(String content) {
        List<AssistantBackendToolCall> calls = new ArrayList<AssistantBackendToolCall>();
        String text = content == null ? "" : content;
        int searchIndex = 0;
        while (searchIndex >= 0 && searchIndex < text.length()) {
            int fenceStart = findActionFenceStart(text, searchIndex);
            if (fenceStart < 0) {
                break;
            }
            int jsonStart = text.indexOf('\n', fenceStart);
            if (jsonStart < 0) {
                break;
            }
            int fenceEnd = text.indexOf("```", jsonStart + 1);
            if (fenceEnd < 0) {
                break;
            }
            String json = text.substring(jsonStart + 1, fenceEnd).trim();
            appendBackendToolCalls(json, calls);
            searchIndex = fenceEnd + 3;
        }
        appendLooseBackendToolCalls(text, calls);
        return calls;
    }

    private int findActionFenceStart(String text, int fromIndex) {
        int dash = text.indexOf("```assistant-action", fromIndex);
        int underscore = text.indexOf("```assistant_action", fromIndex);
        if (dash < 0) {
            return underscore;
        }
        if (underscore < 0) {
            return dash;
        }
        return Math.min(dash, underscore);
    }

    private void appendBackendToolCalls(String json, List<AssistantBackendToolCall> calls) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode backendToolCalls = root.path("backendToolCalls");
            if (!backendToolCalls.isArray()) {
                return;
            }
            for (JsonNode item : backendToolCalls) {
                AssistantBackendToolCall call = objectMapper.treeToValue(item, AssistantBackendToolCall.class);
                if (!hasBackendToolCall(calls, call)) {
                    calls.add(call);
                }
            }
        } catch (Exception ignored) {
            // Malformed hidden action blocks should not break the visible assistant response.
        }
    }

    private boolean hasBackendToolCall(List<AssistantBackendToolCall> calls, AssistantBackendToolCall candidate) {
        if (candidate == null || !hasText(candidate.getCode())) {
            return true;
        }
        for (AssistantBackendToolCall call : calls) {
            if (call != null && candidate.getCode().equals(call.getCode())) {
                return true;
            }
        }
        return false;
    }

    private void appendLooseBackendToolCalls(String text, List<AssistantBackendToolCall> calls) {
        int searchIndex = 0;
        while (searchIndex >= 0 && searchIndex < text.length()) {
            int marker = text.indexOf("\"backendToolCalls\"", searchIndex);
            if (marker < 0) {
                break;
            }
            int jsonStart = text.lastIndexOf('{', marker);
            if (jsonStart < 0) {
                searchIndex = marker + 1;
                continue;
            }
            int jsonEnd = findJsonObjectEnd(text, jsonStart);
            if (jsonEnd < 0) {
                break;
            }
            appendBackendToolCalls(text.substring(jsonStart, jsonEnd + 1), calls);
            searchIndex = jsonEnd + 1;
        }
    }

    private String stripAssistantActionBlocks(String content) {
        String text = content == null ? "" : content;
        StringBuilder result = new StringBuilder();
        int searchIndex = 0;
        while (searchIndex < text.length()) {
            int fenceStart = findActionFenceStart(text, searchIndex);
            if (fenceStart < 0) {
                result.append(text.substring(searchIndex));
                break;
            }
            result.append(text, searchIndex, fenceStart);
            int fenceEnd = text.indexOf("```", fenceStart + 3);
            if (fenceEnd < 0) {
                break;
            }
            searchIndex = fenceEnd + 3;
        }
        return stripLooseAssistantActionJson(result.toString()).trim();
    }

    private String stripLooseAssistantActionJson(String content) {
        String text = content == null ? "" : content;
        int marker = firstActionJsonMarker(text);
        if (marker < 0) {
            return text;
        }
        int jsonStart = text.lastIndexOf('{', marker);
        if (jsonStart < 0) {
            return text.substring(0, marker);
        }
        int removeStart = trimLooseActionPrefix(text, jsonStart);
        return text.substring(0, removeStart);
    }

    private int firstActionJsonMarker(String text) {
        int toolCalls = text.indexOf("\"toolCalls\"");
        int backendToolCalls = text.indexOf("\"backendToolCalls\"");
        int uiControls = text.indexOf("\"uiControls\"");
        int marker = -1;
        if (toolCalls >= 0) {
            marker = toolCalls;
        }
        if (backendToolCalls >= 0 && (marker < 0 || backendToolCalls < marker)) {
            marker = backendToolCalls;
        }
        if (uiControls >= 0 && (marker < 0 || uiControls < marker)) {
            marker = uiControls;
        }
        return marker;
    }

    private int trimLooseActionPrefix(String text, int jsonStart) {
        int boundary = Math.max(
                Math.max(text.lastIndexOf('\n', jsonStart), text.lastIndexOf('。', jsonStart)),
                Math.max(text.lastIndexOf('！', jsonStart), text.lastIndexOf('？', jsonStart)));
        if (boundary < 0) {
            return jsonStart;
        }
        String tail = text.substring(boundary + 1, jsonStart);
        if (tail.toLowerCase().contains("action")
                || tail.toLowerCase().contains("assistant")
                || tail.contains("```")
                || containsKannadaMarker(tail)) {
            return boundary + 1;
        }
        return jsonStart;
    }

    private boolean containsKannadaMarker(String text) {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value >= '\u0c80' && value <= '\u0cff') {
                return true;
            }
        }
        return false;
    }

    private int findJsonObjectEnd(String text, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < text.length(); index++) {
            char value = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (value == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (value == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private List<Map<String, Object>> summarizeBackendToolResults(List<AssistantBackendToolResult> results) {
        List<Map<String, Object>> summaries = new ArrayList<Map<String, Object>>();
        for (AssistantBackendToolResult result : results) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("code", result.getCode());
            item.put("ok", result.getOk());
            item.put("params", result.getParams());
            item.put("error", result.getError());
            item.put("data", summarizeBackendToolResultData(result.getData()));
            summaries.add(item);
        }
        return summaries;
    }

    private Object summarizeBackendToolResultData(Object data) {
        if (data instanceof List<?>) {
            List<?> list = (List<?>) data;
            return list.size() <= 12 ? list : list.subList(0, 12);
        }
        return data;
    }

    private String summarizeBackendToolData(Object data) {
        if (data instanceof List<?>) {
            return ((List<?>) data).size() + " 条摘要";
        }
        if (data instanceof Map<?, ?>) {
            return ((Map<?, ?>) data).size() + " 个字段";
        }
        return "结果";
    }

    private String safeReason(String reason) {
        return hasText(reason) ? reason.trim() : "LLM 请求读取安全后端上下文";
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<String, String>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String resolveChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + "/chat/completions";
    }

    private long resolveTimeoutSeconds(StudioPlatformProperties.LlmProperties llm) {
        Integer timeoutSeconds = llm.getTimeoutSeconds();
        if (timeoutSeconds == null || timeoutSeconds.intValue() < 1) {
            return 30L;
        }
        return timeoutSeconds.longValue();
    }

    private String extractJsonObject(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
                text = text.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("LLM response is not a JSON object");
        }
        return text.substring(start, end + 1);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void writeSse(OutputStream outputStream, String event, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            String frame = "event: " + event + "\n" + "data: " + json + "\n\n";
            outputStream.write(frame.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (Exception ignored) {
            // The client may have closed the drawer or navigated away.
        }
    }

    private void writeProgress(OutputStream outputStream,
                               String stage,
                               String title,
                               String detail,
                               String status) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("stage", stage);
        event.put("title", title);
        event.put("detail", detail);
        event.put("status", status);
        writeSse(outputStream, "progress", event);
    }
}
