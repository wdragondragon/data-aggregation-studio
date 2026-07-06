package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import com.jdragon.studio.dto.model.assistant.AssistantPlanResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AssistantPlanService {

    private static final String LEGACY_DISABLED_MODE = "LEGACY_DISABLED";

    private final AssistantKnowledgeRegistry knowledgeRegistry;

    @Autowired
    public AssistantPlanService(AssistantKnowledgeRegistry knowledgeRegistry) {
        this.knowledgeRegistry = knowledgeRegistry;
    }

    public AssistantPlanResponse plan(AssistantPlanRequest request) {
        AssistantPlanResponse response = new AssistantPlanResponse();
        response.setCapabilities(knowledgeRegistry.listCapabilities());
        response.setPlannerMode(LEGACY_DISABLED_MODE);
        response.setAssistantMessage(resolveLegacyDisabledMessage(request));
        response.getWarnings().add("Legacy /assistant/plan is disabled; use /assistant/chat/stream with studio-assistant.v1 plan/loop/actions/controls.");
        return response;
    }

    private String resolveLegacyDisabledMessage(AssistantPlanRequest request) {
        String language = request == null ? "" : request.getResponseLanguage();
        if (language != null && language.trim().toLowerCase().startsWith("en")) {
            return "Legacy /assistant/plan is disabled. Use the Web streaming assistant endpoint with studio-assistant.v1; no Studio operation was generated.";
        }
        return "旧版 /assistant/plan 已停用。请使用 Web 流式助手的 studio-assistant.v1 协议；本轮未生成任何 Studio 操作。";
    }
}
