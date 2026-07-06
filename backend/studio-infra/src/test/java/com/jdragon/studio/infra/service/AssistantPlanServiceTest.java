package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import com.jdragon.studio.dto.model.assistant.AssistantPlanResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantPlanServiceTest {

    @Test
    void planShouldRemainLegacyDisabledAndNotGenerateOperations() {
        AssistantPlanService service = new AssistantPlanService(new AssistantKnowledgeRegistry());
        AssistantPlanRequest request = new AssistantPlanRequest();
        request.setMessage("创建 ods_order 到 dw_order 的单表采集");

        AssistantPlanResponse response = service.plan(request);

        assertEquals("LEGACY_DISABLED", response.getPlannerMode());
        assertTrue(response.getAssistantMessage().contains("旧版 /assistant/plan 已停用"));
        assertNull(response.getSelectedCapabilityCode());
        assertNull(response.getSelectedCapability());
        assertNull(response.getActionDraft());
        assertNull(response.getLlmPlan());
        assertTrue(response.getRequiredInputs().isEmpty());
        assertTrue(response.getToolCalls().isEmpty());
        assertTrue(response.getBackendToolResults().isEmpty());
        assertTrue(response.getWarnings().stream().anyMatch(item -> item.contains("/assistant/chat/stream")));
        assertTrue(response.getCapabilities().stream().anyMatch(item ->
                AssistantKnowledgeRegistry.CAPABILITY_COLLECTION_SINGLE_TABLE_CREATE.equals(item.getCapabilityCode())));
    }

    @Test
    void planShouldReturnEnglishLegacyDisabledMessageWhenRequested() {
        AssistantPlanService service = new AssistantPlanService(new AssistantKnowledgeRegistry());
        AssistantPlanRequest request = new AssistantPlanRequest();
        request.setResponseLanguage("en");

        AssistantPlanResponse response = service.plan(request);

        assertEquals("LEGACY_DISABLED", response.getPlannerMode());
        assertTrue(response.getAssistantMessage().contains("Legacy /assistant/plan is disabled"));
        assertTrue(response.getToolCalls().isEmpty());
        assertNull(response.getActionDraft());
    }
}
