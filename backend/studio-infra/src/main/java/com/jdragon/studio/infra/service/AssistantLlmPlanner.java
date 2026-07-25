package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Locale;

@Service
public class AssistantLlmPlanner {

    private static final int MAX_BACKEND_TOOL_CALLS = 3;
    private static final String PROTOCOL_VERSION = "studio-assistant.v1";

    private final StudioPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final List<AssistantSkillProvider> skillProviders;
    private final AssistantBackendToolRegistry backendToolRegistry;

    @Autowired
    public AssistantLlmPlanner(StudioPlatformProperties properties,
                               ObjectMapper objectMapper,
                               List<AssistantSkillProvider> skillProviders,
                               AssistantBackendToolRegistry backendToolRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.skillProviders = skillProviders == null ? Collections.<AssistantSkillProvider>emptyList() : skillProviders;
        this.backendToolRegistry = backendToolRegistry;
    }

    public AssistantLlmPlanner(StudioPlatformProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Collections.<AssistantSkillProvider>emptyList(), null);
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
            AssistantLlmPlan warningPlan = new AssistantLlmPlan();
            warningPlan.getWarnings().add("LLM planning failed: " + ex.getMessage());
            return warningPlan;
        }
    }

    public void streamChat(AssistantPlanRequest request,
                           List<AssistantKnowledgeCapability> capabilities,
                           OutputStream outputStream) {
        AssistantPlanRequest safeRequest = request == null ? new AssistantPlanRequest() : request;
        if (!isEnabled()) {
            writeProgress(outputStream, "llm.disabled", "AI 助手未启用", "LLM 未启用或配置不完整，本轮不会执行 Studio 操作。", "warning");
            writeSse(outputStream, "error", Collections.singletonMap(
                    "message", "AI 助手未启用。请启用并完整配置 LLM 后再使用 Web 端 AI 助手。"));
            writeSse(outputStream, "done", Collections.<String, Object>emptyMap());
            return;
        }
        List<Map<String, Object>> assistantSkills = loadAssistantSkills(safeRequest);
        List<Map<String, Object>> backendTools = listBackendTools();
        writeProgress(outputStream, "context.prepare", "整理当前上下文", "已读取当前路由、模式、语言、最近对话和工具结果。", "done");
        writeProgress(outputStream, "knowledge.load", "加载 Studio 内置技能", "已加载 " + assistantSkills.size() + " 条 portable skill 和 " + backendTools.size() + " 个后端工具。", "done");
        try {
            writeProgress(outputStream, "protocol.prepare", "注入助手协议", "已启用 " + PROTOCOL_VERSION + "，LLM 只输出协议化内部动作。", "done");
            String streamedContent = requestStreamingCompletion(safeRequest, capabilities, assistantSkills, backendTools, outputStream);
            List<AssistantBackendToolResult> backendToolResults = executeBackendToolCallsFromResponse(safeRequest, streamedContent, outputStream);
            if (!backendToolResults.isEmpty()) {
                writeProgress(outputStream, "backend.tools.complete", "后端白名单工具完成",
                        "已返回 " + backendToolResults.size() + " 项工具结果，正在让助手补充说明。", "done");
                requestBackendToolFollowUpCompletion(
                        safeRequest,
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
            writeProgress(outputStream, "llm.error", "模型回复失败", ex.getMessage(), "warning");
            writeSse(outputStream, "delta", Collections.singletonMap("content",
                    llmFailureReply(ex)));
            writeSse(outputStream, "done", Collections.<String, Object>emptyMap());
        }
    }

    private String llmFailureReply(Exception ex) {
        String message = ex == null ? "" : ex.getMessage();
        if (!hasText(message)) {
            message = "未知错误";
        }
        return "LLM 请求失败，本轮未执行任何 Studio 操作。请稍后重试或检查助手模型配置。错误信息：" + message;
    }

    private String requestCompletion(AssistantPlanRequest request,
                                     List<AssistantKnowledgeCapability> capabilities,
                                     String latestMessage) throws Exception {
        StudioPlatformProperties.LlmProperties llm = properties.getAssistant().getLlm();
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(resolveTimeoutSeconds(llm)))
                .build();

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", llm.getModel());
        payload.put("temperature", llm.getTemperature());
        payload.put("max_tokens", llm.getMaxTokens());
        payload.put("messages", buildMessages(request, capabilities, latestMessage,
                loadAssistantSkills(request),
                listBackendTools()));

        byte[] body = objectMapper.writeValueAsBytes(payload);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(resolveChatCompletionsUrl(llm.getBaseUrl())))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds(llm)))
                .header("Authorization", "Bearer " + llm.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
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
        messages.add(message("user", "请基于后端工具结果继续回答。只输出用户可见内容；除非还需要前端安全接口调用或问答控件，否则不要再输出内部协议块。"));
        requestStreamingCompletionWithMessages(messages, outputStream);
    }

    private String requestStreamingCompletionWithMessages(List<Map<String, String>> messages,
                                                          OutputStream outputStream) throws Exception {
        StudioPlatformProperties.LlmProperties llm = properties.getAssistant().getLlm();
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(resolveTimeoutSeconds(llm)))
                .build();

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", llm.getModel());
        payload.put("temperature", llm.getTemperature());
        payload.put("max_tokens", llm.getMaxTokens());
        payload.put("stream", Boolean.TRUE);
        payload.put("messages", messages);

        byte[] body = objectMapper.writeValueAsBytes(payload);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(resolveChatCompletionsUrl(llm.getBaseUrl())))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds(llm)))
                .header("Authorization", "Bearer " + llm.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
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
        userPayload.put("assistantMode", resolveAssistantMode(request));
        userPayload.put("responseLanguage", resolveResponseLanguage(request));
        userPayload.put("protocolVersion", PROTOCOL_VERSION);
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
        knowledge.put("protocolVersion", PROTOCOL_VERSION);
        knowledge.put("assistantMode", resolveAssistantMode(request));
        knowledge.put("responseLanguage", resolveResponseLanguage(request));
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
                + "Use the custom " + PROTOCOL_VERSION + " schema for all internal calls and never rely on provider-specific function-calling response bodies.\n"
                + "You must not invent datasource IDs, model IDs, field names, or execute any business API.\n"
                + "You may copy IDs or field names only when they are present in collectedInputs or toolResults.\n"
                + "Use only capability codes and interfaceCode values from the provided knowledge capabilities.\n"
                + "You may suggest backendTool actions only with codes listed in backendTools. Backend tools are allow-listed annotated methods; user text is never a method name.\n"
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
                + "  \"protocol\": {\"protocol\": \"studio-assistant.v1\", \"plan\": {\"intent\": \"why\", \"basis\": [\"evidence\"], \"requiredObjects\": [], \"nextActions\": [{\"type\": \"backendTool\", \"tool\": \"assistant.skills.search\"}]}, \"loop\": {\"status\": \"tool_pending\", \"autoContinue\": true, \"questions\": [], \"next\": [], \"evidence\": [], \"stopReason\": \"waiting_for_tool_result\"}, \"actions\": [{\"type\": \"backendTool\", \"tool\": \"assistant.skills.search\", \"params\": {\"query\": \"optional\"}}], \"controls\": []},\n"
                + "  \"warnings\": []\n"
                + "}";
    }

    private String chatSystemPrompt() {
        return ""
                + "You are Data Aggregation Studio's built-in AI assistant. Reply in concise, natural Chinese.\n"
                + "Use responseLanguage from context: zh means Chinese, en means English.\n"
                + "Use assistantMode from context: chat gives direct answers, plan produces staged plans, goal keeps a goal-state oriented loop.\n"
                + "You can explain Studio concepts, modules, current automation capabilities, and how the guided assistant works.\n"
                + "Use assistantSkills and the provided runtime context as product knowledge. Do not imply that you scanned source code or databases.\n"
                + "This is not a keyword-triggered wizard. Decompose every user input into concrete questions, answer what can be answered, and continue with the next inferred question when context is enough.\n"
                + "All internal calls must use the custom " + PROTOCOL_VERSION + " block, not vendor function-call fields. Append at most one hidden fenced block after visible content when needed:\n"
                + "```studio-assistant-protocol\n"
                + "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Read datasource candidates\",\"basis\":[\"User asked to inspect datasource context\",\"assistantCapabilities exposes /datasources\"],\"requiredObjects\":[{\"type\":\"feature\",\"path\":\"/datasources\"}],\"nextActions\":[{\"type\":\"tool\",\"tool\":\"studio.feature.list\",\"path\":\"/datasources\",\"reason\":\"Read controlled Studio datasource data\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"Which datasource records are visible?\",\"status\":\"needs_tool\",\"output\":\"pending tool result\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"read controlled Studio data\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"actions\":[{\"type\":\"frontendTool\",\"tool\":\"studio.feature.list\",\"reason\":\"resolve datasource candidates\",\"params\":{\"path\":\"/datasources\",\"pageNo\":1,\"pageSize\":20}}],\"controls\":[]}\n"
                + "```\n"
                + "Legacy assistant-action blocks and loose action JSON are ignored for execution; use studio-assistant-protocol.\n"
                + "Inside studio-assistant-protocol, never use legacy/provider execution fields such as toolCalls, backendToolCalls, or uiControls. Put executable calls only in actions and conversational question controls only in controls.\n"
                + "The protocol plan and loop are mandatory whenever actions or controls are present. plan must include intent, non-empty basis, explicit requiredObjects array, and non-empty nextActions. Use plan.intent for the understood user intent, plan.basis for evidence used by the LLM, plan.requiredObjects for business objects involved, and plan.nextActions for the decided next steps.\n"
                + "loop must include status, boolean autoContinue, questions array, next array, evidence array, and stopReason. Use loop.questions to expose the decomposed input, loop.next to describe the machine-continuable step, autoContinue=true when the frontend should feed toolResults back without waiting for the user, and stopReason only when the loop must pause for a tool result, user decision, or answer completion.\n"
                + "Every protocol block must explicitly include actions and controls arrays. Every action item must include type, tool, and params; use params:{} when no parameters are needed. Every control item must include type, title, and paramKey, with explicit options for select, choices, and confirm controls.\n"
                + "After toolResults are fed back, you decide whether the task is complete or another operation is needed. If the results are sufficient, return the final user-facing answer and, when useful for traceability, include a " + PROTOCOL_VERSION + " block with loop.status=completed, autoContinue=false, actions=[], controls=[], and stopReason=answer_complete. Do not let the frontend or gateway decide completion for you.\n"
                + "If you say you will query, read, fetch, inspect, open, preview, execute, or continue with a Studio operation, the same response must include a protocol action or controls that lets the orchestrator continue.\n"
                + "Do not output only '我先查询/我先读取/正在获取' as the final visible answer. For explicit inspect/list/search/open requests against Studio resources declared in the assistant capability catalog, choose a safe read tool from currentContext.frontendTools or ask with controls; do not skip the protocol block merely because some params are uncertain.\n"
                + "For multi-step tasks, do not narrate every internal read or API step as a visible chat message. The frontend shows backend operations in a collapsible process panel. Visible text should focus on user-facing decisions, final results, or a concise one-sentence status.\n"
                + "If your response only needs to request internal progress actions, keep the visible answer empty or very short and put the operation only in the protocol block.\n"
                + "currentContext.assistantCapabilities is the backend-exported Studio assistant capability directory, not a user-permission boundary. Use it to learn available product functions and operations.\n"
                + "Actions are controlled Studio API calls or local assistant orchestration calls. Supported frontend tool values include assistant.context.observe, assistant.context.read, assistant.context.search, assistant.memory.select, studio.feature.list, studio.feature.get, studio.feature.action, studio.navigation.open, and assistant.script.execute.\n"
                + "Do not use legacy narrow tool names such as catalog.capabilities, datasources.options, models.datasourceOptions, models.get, catalog.runtimeOptionSchema, or collectionTasks.preview in new Web planning. Express those reads or previews through the operation catalog with studio.feature.list, studio.feature.get, or studio.feature.action.\n"
                + "Use assistant.context.observe when you need the current page, selected business object, recent candidates, or active chat controls before deciding the next operation. It never calls a business API.\n"
                + "Use assistant.context.read with {\"path\":\"/exact-path\",\"kind\":\"optional\",\"limit\":10} when you need the current business context for a feature: active object, selected object, visible page objects, recent candidates, filters, and pagination. It reads only Web context and assistant memory; it never calls a business API.\n"
                + "Use assistant.context.search with {\"path\":\"/exact-path\",\"keyword\":\"...\",\"kind\":\"optional\"} when the user references an object by name/partial name and the object may already be in current page context, recent candidates, or assistant memory. It returns candidates only; choose or ask before selecting.\n"
                + "currentContext.pageContext is the live Web page snapshot: source/path/summary/activeObject/selectedObjects/visibleObjects/relatedObjects/filters/pagination. When the user says 当前/这个/页面选中/current page/selected object, prefer currentContext.pageContext.activeObject as the selected business object before re-reading lists; cite it in plan.basis and plan.requiredObjects, and use its id/path/name/physicalLocator only when it matches the requested Studio capability.\n"
                + "Use assistant.memory.select when the user says to use/select/default a datasource, model, physical table, rule, task, service, run, or other recent candidate. It only updates conversational working memory and must reference an existing candidate by id, name, physicalLocator, or ordinal; never invent an ID.\n"
                + "Use studio.feature.list with {\"path\":\"/exact-path\", ...filters} to read a function's list, overview, options, or metrics. Use studio.feature.get with {\"path\":\"/exact-path\",\"id\":\"...\"} to read details.\n"
                + "Use studio.feature.action only for actions listed in currentContext.frontendActionRegistry or assistantCapabilities.operations where operation=action. Required params are path, action, and any listed id/payload/resource values. The frontend will automatically stop mutation actions and render a confirm/cancel chat control before execution.\n"
                + "Distinguish business semantics before choosing tools: datasource lists are /datasources list; real physical table/view discovery inside a selected datasource is /datasources discover with {id}; registered data models are /models list/get. If the user says 当前/这个/默认/选中的数据源有什么表、有哪些真实表、物理表、库表 or 表发现, first use currentContext.pageContext.activeObject when it is a datasource on /datasources, otherwise use the selected datasource from currentContext.assistantMemory or ask the user to choose one; do not call /datasources list again and do not call /models unless the user explicitly says 已登记模型 or 数据模型.\n"
                + "For broad requests such as 全部表/所有真实表/不要分页/完整列表, reason from the API result shape and user wording. You may omit pageNo/pageSize when the declared action supports unpaged discovery, request a larger safe page size, or iterate with hasMore by emitting the next protocol action after toolResults. Do not hard-code one page unless the user asked for a page.\n"
                + "Use assistant.script.execute only for registered assistant script skill entrypoints from assistantSkills, with params {\"entrypointId\":\"...\",\"input\":{...},\"runtimeClusterId\":optional}. The script runs on a Studio Worker; include runtimeClusterId when the project has no deterministic single or preferred runtime cluster. Never emit shell commands, arbitrary Python code, local file paths, or provider-specific tool calls for script skills.\n"
                + "If a toolResult has ok=false, treat it as recoverable execution context. First inspect its params and error, then either emit a corrected studio-assistant-protocol block using the same allow-listed tools, or emit controls for only the missing user decision. Do not dump raw validation errors to the user as the final answer.\n"
                + "When repairing failed params, never invent IDs and never copy protocol aliases forward. You may interpret user wording or failed params as evidence, but the repaired protocol must use the canonical parameter names declared in currentContext.frontendTools/currentContext.frontendActionRegistry, such as payload.content, payload.fileName, entrypointId, and input. If uncertain, ask with controls.\n"
                + "For data development SQL execution use studio.feature.action with {\"path\":\"/data-development\",\"resource\":\"sql\",\"action\":\"executeSql\",\"payload\":{\"datasourceId\":\"...\",\"scriptType\":\"SQL\",\"content\":\"...\",\"maxRows\":100}}. If datasourceId is unknown, query candidates or ask with controls.\n"
                + "For saving a data development script use studio.feature.action with {\"path\":\"/data-development\",\"resource\":\"scripts\",\"action\":\"saveScript\",\"payload\":{\"fileName\":\"...\",\"scriptType\":\"SQL|FLINK_QUESTION_SQL|JAVA|PYTHON\",\"content\":\"...\"}}. Infer scriptType only from an explicit type, file extension, or clear code syntax; otherwise ask with controls.\n"
                + "Use studio.navigation.open with {\"path\":\"/exact-path\"} when the best next step is to open a function page for the user. The path must exactly match one item in currentContext.assistantCapabilities or assistantFeatures. Do not invent paths outside the assistant capability catalog.\n"
                + "Backend tool actions are controlled server-side allow-listed reflection calls. Use action {\"type\":\"backendTool\",\"tool\":\"assistant.skills.search\",...} or {\"type\":\"backendTool\",\"tool\":\"studio.operations.search\",...}. They are executed by the server after your streamed answer, never by user text or frontend request payload.\n"
                + "controls are conversational question controls rendered inside the chat, not separate forms or popups. Use them for user input, dropdowns, ambiguity resolution, or confirm/cancel decisions. Supported types: select, choices, text, textarea, confirm.\n"
                + "Use controls only when the next step really needs a user answer. Do not output a control for a single default value such as FULL.\n"
                + "Backend tool action example: {\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Find available data development operations\",\"basis\":[\"User asked about 数据开发\",\"operation catalog is searchable\"],\"requiredObjects\":[{\"type\":\"feature\",\"name\":\"数据开发\"}],\"nextActions\":[{\"type\":\"backendTool\",\"tool\":\"studio.operations.search\",\"reason\":\"find Studio feature tools\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"Which tools support 数据开发?\",\"status\":\"needs_tool\",\"output\":\"pending backend tool result\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"search Studio operation catalog\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"actions\":[{\"type\":\"backendTool\",\"tool\":\"studio.operations.search\",\"reason\":\"find Studio feature tools\",\"params\":{\"query\":\"数据开发\"}}],\"controls\":[]}.\n"
                + "Question control example: {\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Resolve missing source datasource\",\"basis\":[\"Task requires a source datasource\",\"Multiple datasource candidates are available\"],\"requiredObjects\":[{\"type\":\"datasource\",\"status\":\"ambiguous\"}],\"nextActions\":[{\"type\":\"control\",\"reason\":\"ask user to choose source datasource\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"waiting_for_user\",\"autoContinue\":false,\"questions\":[{\"id\":\"q1\",\"input\":\"Which datasource should be used as source?\",\"status\":\"needs_user\",\"output\":\"waiting for selection\"}],\"next\":[{\"id\":\"step1\",\"type\":\"control\",\"status\":\"pending\",\"description\":\"ask user to choose source datasource\"}],\"evidence\":[],\"stopReason\":\"missing_user_input\"},\"actions\":[],\"controls\":[{\"type\":\"select\",\"title\":\"选择源端\",\"paramKey\":\"source.datasourceId\",\"options\":[{\"label\":\"mysql_prod\",\"value\":\"1\"},{\"label\":\"mysql_test\",\"value\":\"2\"}]}]}.\n"
                + "Text input control example: {\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Collect missing task name\",\"basis\":[\"Task creation requires a name\"],\"requiredObjects\":[{\"type\":\"field\",\"name\":\"name\",\"status\":\"missing\"}],\"nextActions\":[{\"type\":\"control\",\"reason\":\"ask user for task name\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"waiting_for_user\",\"autoContinue\":false,\"questions\":[{\"id\":\"q1\",\"input\":\"What task name should be used?\",\"status\":\"needs_user\",\"output\":\"waiting for text input\"}],\"next\":[{\"id\":\"step1\",\"type\":\"control\",\"status\":\"pending\",\"description\":\"ask user for task name\"}],\"evidence\":[],\"stopReason\":\"missing_user_input\"},\"actions\":[],\"controls\":[{\"type\":\"text\",\"title\":\"填写任务名\",\"paramKey\":\"name\",\"placeholder\":\"例如 orders_to_ods_orders\"}]}.\n"
                + "Never request delete/remove/drop/truncate/purge/cancel actions. They are not exposed to the assistant even if a page has such buttons.\n"
                + "Do not put raw business write interfaces in protocol actions. For save, publish, trigger, schedule, offline, enable, disable, approve, reject, debug, execute, or configuration mutation, use studio.feature.action only after required values are known; the frontend confirmation queue is mandatory and cannot be bypassed by adding confirmed=true.\n"
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
        if (skillProviders.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> skills = new ArrayList<Map<String, Object>>();
        for (AssistantSkillProvider provider : skillProviders) {
            if (provider == null) {
                continue;
            }
            try {
                List<Map<String, Object>> provided = provider.assistantSkills(request);
                if (provided != null) {
                    skills.addAll(provided);
                }
            } catch (Exception ignored) {
                // A skill provider must not block the assistant response path.
            }
        }
        return skills;
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
            int fenceStart = findProtocolFenceStart(text, searchIndex);
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
            appendProtocolBackendToolCalls(json, calls);
            searchIndex = fenceEnd + 3;
        }
        return calls;
    }

    private int findProtocolFenceStart(String text, int fromIndex) {
        int protocolDash = text.indexOf("```studio-assistant-protocol", fromIndex);
        int protocolUnderscore = text.indexOf("```studio_assistant_protocol", fromIndex);
        int result = -1;
        if (protocolDash >= 0) {
            result = protocolDash;
        }
        if (protocolUnderscore >= 0 && (result < 0 || protocolUnderscore < result)) {
            result = protocolUnderscore;
        }
        return result;
    }

    private int findActionFenceStart(String text, int fromIndex) {
        int protocolDash = text.indexOf("```studio-assistant-protocol", fromIndex);
        int protocolUnderscore = text.indexOf("```studio_assistant_protocol", fromIndex);
        int dash = text.indexOf("```assistant-action", fromIndex);
        int underscore = text.indexOf("```assistant_action", fromIndex);
        int result = -1;
        if (protocolDash >= 0) {
            result = protocolDash;
        }
        if (protocolUnderscore >= 0 && (result < 0 || protocolUnderscore < result)) {
            result = protocolUnderscore;
        }
        if (dash >= 0 && (result < 0 || dash < result)) {
            result = dash;
        }
        if (underscore >= 0 && (result < 0 || underscore < result)) {
            result = underscore;
        }
        return result;
    }

    private void appendBackendToolActions(JsonNode root, List<AssistantBackendToolCall> calls) {
        try {
            JsonNode actions = root.path("actions");
            if (actions.isArray()) {
                for (JsonNode item : actions) {
                    if (!item.isObject()) {
                        continue;
                    }
                    String type = item.path("type").asText("");
                    if (!"backendTool".equals(type)) {
                        continue;
                    }
                    String code = item.path("tool").asText("");
                    JsonNode params = item.path("params");
                    if (!hasText(code) || !params.isObject()) {
                        continue;
                    }
                    AssistantBackendToolCall call = new AssistantBackendToolCall();
                    call.setCode(code);
                    call.setReason(item.path("reason").asText(""));
                    call.setParams(objectMapper.convertValue(params, new TypeReference<Map<String, Object>>() {
                    }));
                    if (!hasBackendToolCall(calls, call)) {
                        calls.add(call);
                    }
                }
            }
        } catch (Exception ignored) {
            // Malformed hidden action blocks should not break the visible assistant response.
        }
    }

    private void appendProtocolBackendToolCalls(String json, List<AssistantBackendToolCall> calls) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!PROTOCOL_VERSION.equals(root.path("protocol").asText(""))
                    || hasForbiddenProtocolExecutionFields(root)
                    || !hasCompleteProtocolPlan(root.path("plan"))
                    || !hasCompleteProtocolLoop(root.path("loop"))
                    || !hasCompleteProtocolActions(root.path("actions"))
                    || !hasCompleteProtocolControls(root.path("controls"))) {
                return;
            }
            appendBackendToolActions(root, calls);
        } catch (Exception ignored) {
            // Malformed hidden action blocks should not break the visible assistant response.
        }
    }

    private boolean hasForbiddenProtocolExecutionFields(JsonNode root) {
        return root.path("toolCalls").isArray()
                || root.path("backendToolCalls").isArray()
                || root.path("uiControls").isArray();
    }

    private boolean hasCompleteProtocolPlan(JsonNode plan) {
        if (plan == null || !plan.isObject()) {
            return false;
        }
        return hasText(plan.path("intent").asText(""))
                && hasNonEmptyArray(plan.path("basis"))
                && plan.path("requiredObjects").isArray()
                && hasNonEmptyArray(plan.path("nextActions"));
    }

    private boolean hasCompleteProtocolLoop(JsonNode loop) {
        if (loop == null || !loop.isObject()) {
            return false;
        }
        return hasText(loop.path("status").asText(""))
                && loop.path("autoContinue").isBoolean()
                && loop.path("questions").isArray()
                && loop.path("next").isArray()
                && loop.path("evidence").isArray()
                && hasText(loop.path("stopReason").asText(""));
    }

    private boolean hasCompleteProtocolActions(JsonNode actions) {
        if (actions == null || !actions.isArray()) {
            return false;
        }
        for (JsonNode action : actions) {
            if (action == null || !action.isObject()) {
                return false;
            }
            String type = action.path("type").asText("");
            if (!"frontendTool".equals(type) && !"backendTool".equals(type)) {
                return false;
            }
            if (!hasText(action.path("tool").asText("")) || !action.path("params").isObject()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasCompleteProtocolControls(JsonNode controls) {
        if (controls == null || !controls.isArray()) {
            return false;
        }
        for (JsonNode control : controls) {
            if (control == null || !control.isObject()) {
                return false;
            }
            String type = control.path("type").asText("");
            if (!isSupportedProtocolControlType(type)
                    || !hasText(control.path("title").asText(""))
                    || !hasText(control.path("paramKey").asText(""))) {
                return false;
            }
            if (("select".equals(type) || "choices".equals(type) || "confirm".equals(type))
                    && validProtocolControlOptionCount(control.path("options")) < 2) {
                return false;
            }
        }
        return true;
    }

    private boolean isSupportedProtocolControlType(String type) {
        return "select".equals(type)
                || "choices".equals(type)
                || "text".equals(type)
                || "textarea".equals(type)
                || "confirm".equals(type);
    }

    private int validProtocolControlOptionCount(JsonNode options) {
        if (options == null || !options.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode option : options) {
            if (option != null
                    && option.isObject()
                    && !option.path("value").isMissingNode()
                    && hasText(option.path("label").asText(""))) {
                count += 1;
            }
        }
        return count;
    }

    private boolean hasNonEmptyArray(JsonNode value) {
        return value != null && value.isArray() && value.size() > 0;
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
        int actions = text.indexOf("\"actions\"");
        int controls = text.indexOf("\"controls\"");
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
        if (actions >= 0 && (marker < 0 || actions < marker)) {
            marker = actions;
        }
        if (controls >= 0 && (marker < 0 || controls < marker)) {
            marker = controls;
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

    private String resolveAssistantMode(AssistantPlanRequest request) {
        String mode = request == null ? "" : request.getAssistantMode();
        if (!hasText(mode) && request != null && request.getContext() != null) {
            Object value = request.getContext().get("assistantMode");
            mode = value == null ? "" : String.valueOf(value);
        }
        mode = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if ("plan".equals(mode) || "goal".equals(mode)) {
            return mode;
        }
        return "chat";
    }

    private String resolveResponseLanguage(AssistantPlanRequest request) {
        String language = request == null ? "" : request.getResponseLanguage();
        if (!hasText(language) && request != null && request.getContext() != null) {
            Object value = request.getContext().get("responseLanguage");
            language = value == null ? "" : String.valueOf(value);
        }
        language = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (language.startsWith("en")) {
            return "en";
        }
        return "zh";
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
