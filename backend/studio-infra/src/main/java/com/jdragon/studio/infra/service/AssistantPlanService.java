package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantActionDraft;
import com.jdragon.studio.dto.model.assistant.AssistantBackendToolCall;
import com.jdragon.studio.dto.model.assistant.AssistantBackendToolResult;
import com.jdragon.studio.dto.model.assistant.AssistantInputDefinition;
import com.jdragon.studio.dto.model.assistant.AssistantInterfaceDefinition;
import com.jdragon.studio.dto.model.assistant.AssistantKnowledgeCapability;
import com.jdragon.studio.dto.model.assistant.AssistantLlmPlan;
import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import com.jdragon.studio.dto.model.assistant.AssistantPlanResponse;
import com.jdragon.studio.dto.model.assistant.AssistantToolCall;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AssistantPlanService {

    private static final String SCHEDULE_POLICY = "DO_NOT_GENERATE_SCHEDULE";
    private static final int MAX_BACKEND_TOOL_CALLS = 3;

    private final AssistantKnowledgeRegistry knowledgeRegistry;
    private final AssistantLlmPlanner llmPlanner;
    private final AssistantBackendToolRegistry backendToolRegistry;

    @Autowired
    public AssistantPlanService(AssistantKnowledgeRegistry knowledgeRegistry,
                                AssistantLlmPlanner llmPlanner,
                                AssistantBackendToolRegistry backendToolRegistry) {
        this.knowledgeRegistry = knowledgeRegistry;
        this.llmPlanner = llmPlanner;
        this.backendToolRegistry = backendToolRegistry;
    }

    public AssistantPlanService(AssistantKnowledgeRegistry knowledgeRegistry,
                                AssistantLlmPlanner llmPlanner) {
        this(knowledgeRegistry, llmPlanner, null);
    }

    public AssistantPlanResponse plan(AssistantPlanRequest request) {
        String message = knowledgeRegistry.latestUserMessage(request);
        AssistantPlanResponse response = new AssistantPlanResponse();
        response.setCapabilities(knowledgeRegistry.listCapabilities());

        if (!llmPlanner.isEnabled()) {
            response.setPlannerMode("LLM_DISABLED");
            response.setAssistantMessage("LLM is not configured. Set studio.assistant.llm.* to enable the assistant.");
            response.getWarnings().add("LLM planner is disabled or missing baseUrl/apiKey/model.");
            return response;
        }

        AssistantLlmPlan llmPlan = llmPlanner.plan(request, knowledgeRegistry.listCapabilities(), message);
        response.setPlannerMode("LLM");
        response.setLlmPlan(llmPlan);
        if (llmPlan == null) {
            response.setAssistantMessage("LLM did not return a plan.");
            response.getWarnings().add("LLM planner returned null.");
            return response;
        }
        response.getWarnings().addAll(llmPlan.getWarnings());
        response.setBackendToolResults(executeBackendToolCalls(request, llmPlan.getBackendToolCalls()));
        if (!llmPlan.getWarnings().isEmpty() && !hasText(llmPlan.getCapabilityCode())) {
            response.setAssistantMessage("LLM planning failed. Please check model endpoint and API key.");
            return response;
        }

        AssistantKnowledgeCapability capability = knowledgeRegistry.resolveCapabilityByCode(llmPlan.getCapabilityCode());
        if (capability == null) {
            response.setAssistantMessage("LLM returned an unsupported capability: " + llmPlan.getCapabilityCode());
            response.getWarnings().add("Unsupported capability returned by LLM: " + llmPlan.getCapabilityCode());
            return response;
        }

        response.setSelectedCapability(capability);
        response.setSelectedCapabilityCode(capability.getCapabilityCode());
        response.setRequiredInputs(resolveRequiredInputs(capability, request, llmPlan));
        response.setToolCalls(sanitizeToolCalls(capability, llmPlan.getToolCalls()));
        response.setActionDraft(buildDraft(capability, message, request, llmPlan));
        response.setAssistantMessage(resolveAssistantMessage(capability, llmPlan, response.getRequiredInputs()));
        return response;
    }

    private List<AssistantBackendToolResult> executeBackendToolCalls(AssistantPlanRequest request,
                                                                     List<AssistantBackendToolCall> calls) {
        List<AssistantBackendToolResult> results = new ArrayList<AssistantBackendToolResult>();
        if (backendToolRegistry == null || calls == null || calls.isEmpty()) {
            return results;
        }
        Set<String> availableCodes = new LinkedHashSet<String>();
        for (Map<String, Object> summary : backendToolRegistry.listToolSummaries()) {
            Object code = summary.get("code");
            if (code != null) {
                availableCodes.add(String.valueOf(code));
            }
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (AssistantBackendToolCall call : calls) {
            if (call == null || !hasText(call.getCode()) || results.size() >= MAX_BACKEND_TOOL_CALLS) {
                continue;
            }
            String code = call.getCode().trim();
            if (!availableCodes.contains(code) || !seen.add(code)) {
                continue;
            }
            AssistantBackendToolResult result = new AssistantBackendToolResult();
            result.setCode(code);
            result.setParams(call.getParams() == null ? new LinkedHashMap<String, Object>() : call.getParams());
            try {
                result.setData(backendToolRegistry.invoke(code, request, result.getParams()));
                result.setOk(Boolean.TRUE);
            } catch (Exception ex) {
                result.setOk(Boolean.FALSE);
                result.setError(ex.getMessage());
            }
            results.add(result);
        }
        return results;
    }

    private List<AssistantInputDefinition> resolveRequiredInputs(AssistantKnowledgeCapability capability,
                                                                 AssistantPlanRequest request,
                                                                 AssistantLlmPlan llmPlan) {
        Map<String, Object> collectedInputs = request == null ? null : request.getCollectedInputs();
        List<AssistantInputDefinition> missing = knowledgeRegistry.resolveMissingInputs(capability, collectedInputs);
        if (llmPlan.getMissingInputs() == null || llmPlan.getMissingInputs().isEmpty()) {
            return missing;
        }

        Set<String> missingKeys = new LinkedHashSet<String>(llmPlan.getMissingInputs());
        List<AssistantInputDefinition> merged = new ArrayList<AssistantInputDefinition>();
        Set<String> seen = new LinkedHashSet<String>();
        for (AssistantInputDefinition input : missing) {
            if (seen.add(input.getKey())) {
                merged.add(input);
            }
        }
        for (AssistantInputDefinition input : capability.getRequiredInputs()) {
            if (missingKeys.contains(input.getKey()) && seen.add(input.getKey())) {
                merged.add(input);
            }
        }
        return merged;
    }

    private List<AssistantToolCall> sanitizeToolCalls(AssistantKnowledgeCapability capability,
                                                      List<AssistantToolCall> modelCalls) {
        Map<String, AssistantInterfaceDefinition> interfaces = new LinkedHashMap<String, AssistantInterfaceDefinition>();
        for (AssistantInterfaceDefinition interfaceDefinition : capability.getInterfaces()) {
            interfaces.put(interfaceDefinition.getInterfaceCode(), interfaceDefinition);
        }

        List<AssistantToolCall> result = new ArrayList<AssistantToolCall>();
        Set<String> seen = new LinkedHashSet<String>();
        for (AssistantToolCall call : knowledgeRegistry.suggestInitialToolCalls(capability)) {
            if (call != null && seen.add(call.getInterfaceCode())) {
                result.add(call);
            }
        }
        if (modelCalls == null) {
            return result;
        }
        for (AssistantToolCall call : modelCalls) {
            if (call == null || !hasText(call.getInterfaceCode()) || !seen.add(call.getInterfaceCode())) {
                continue;
            }
            AssistantInterfaceDefinition interfaceDefinition = interfaces.get(call.getInterfaceCode());
            if (interfaceDefinition == null || Boolean.TRUE.equals(interfaceDefinition.getMutation())) {
                continue;
            }
            result.add(call);
        }
        return result;
    }

    private AssistantActionDraft buildDraft(AssistantKnowledgeCapability capability,
                                            String message,
                                            AssistantPlanRequest request,
                                            AssistantLlmPlan llmPlan) {
        AssistantActionDraft draft = knowledgeRegistry.createInitialDraft(
                capability,
                message,
                request == null ? null : request.getCollectedInputs());
        draft.setSummary(hasText(llmPlan.getAssistantMessage()) ? llmPlan.getAssistantMessage() : draft.getSummary());
        draft.getPayload().put("planner", "LLM");
        draft.getPayload().put("llmConfidence", llmPlan.getConfidence());
        draft.getPayload().put("llmInferredInputs", llmPlan.getInferredInputs());
        draft.getPayload().put("schedulePolicy", SCHEDULE_POLICY);
        draft.getPreview().put("llmWarnings", llmPlan.getWarnings());
        return draft;
    }

    private String resolveAssistantMessage(AssistantKnowledgeCapability capability,
                                           AssistantLlmPlan llmPlan,
                                           List<AssistantInputDefinition> missingInputs) {
        if (hasText(llmPlan.getAssistantMessage())) {
            return llmPlan.getAssistantMessage();
        }
        if (missingInputs == null || missingInputs.isEmpty()) {
            return "已识别能力：" + capability.getCapabilityName() + "。请先预览配置，再由用户确认保存。";
        }
        return "已识别能力：" + capability.getCapabilityName() + "。还需要补齐 " + missingInputs.size() + " 项信息。";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
