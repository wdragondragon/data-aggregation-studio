package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.assistant.AssistantKnowledgeCapability;
import com.jdragon.studio.dto.model.assistant.AssistantLlmPlan;
import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import com.jdragon.studio.dto.model.assistant.AssistantPlanResponse;
import com.jdragon.studio.dto.model.assistant.AssistantToolCall;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantPlanServiceTest {

    @Test
    void planShouldUseLlmAndFilterMutationToolCalls() {
        AssistantKnowledgeRegistry registry = new AssistantKnowledgeRegistry();
        AssistantPlanService service = new AssistantPlanService(registry, new FakeLlmPlanner());
        AssistantPlanRequest request = new AssistantPlanRequest();
        request.setMessage("创建 ods_order 到 dw_order 的单表采集");

        AssistantPlanResponse response = service.plan(request);

        assertEquals("LLM", response.getPlannerMode());
        assertEquals(AssistantKnowledgeRegistry.CAPABILITY_COLLECTION_SINGLE_TABLE_CREATE, response.getSelectedCapabilityCode());
        assertEquals("PREVIEW_THEN_CONFIRM", response.getActionDraft().getConfirmationLevel());
        assertEquals("LLM", response.getActionDraft().getPayload().get("planner"));
        assertEquals("DO_NOT_GENERATE_SCHEDULE", response.getActionDraft().getPayload().get("schedulePolicy"));

        Set<String> toolCodes = response.getToolCalls().stream()
                .map(AssistantToolCall::getInterfaceCode)
                .collect(Collectors.toSet());
        assertTrue(toolCodes.contains("catalog.capabilities"));
        assertTrue(toolCodes.contains("datasources.options"));
        assertFalse(toolCodes.contains("collectionTasks.save"));
    }

    private static class FakeLlmPlanner extends AssistantLlmPlanner {

        FakeLlmPlanner() {
            super(new StudioPlatformProperties(), new ObjectMapper());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public AssistantLlmPlan plan(AssistantPlanRequest request,
                                     List<AssistantKnowledgeCapability> capabilities,
                                     String latestMessage) {
            AssistantLlmPlan plan = new AssistantLlmPlan();
            plan.setCapabilityCode(AssistantKnowledgeRegistry.CAPABILITY_COLLECTION_SINGLE_TABLE_CREATE);
            plan.setConfidence(Double.valueOf(0.9D));
            plan.setAssistantMessage("已识别单表采集需求。");
            plan.getInferredInputs().put("name", "ods_order_to_dw_order");
            AssistantToolCall readCall = new AssistantToolCall();
            readCall.setInterfaceCode("models.datasourceOptions");
            readCall.setReason("Resolve model candidates.");
            AssistantToolCall writeCall = new AssistantToolCall();
            writeCall.setInterfaceCode("collectionTasks.save");
            writeCall.setReason("Should be filtered.");
            plan.getToolCalls().add(readCall);
            plan.getToolCalls().add(writeCall);
            return plan;
        }
    }
}
