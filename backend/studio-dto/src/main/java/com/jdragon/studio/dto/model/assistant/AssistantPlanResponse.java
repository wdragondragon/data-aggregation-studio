package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Assistant planning response")
public class AssistantPlanResponse {
    @Schema(description = "Assistant response message")
    private String assistantMessage;

    @Schema(description = "Selected capability code")
    private String selectedCapabilityCode;

    @Schema(description = "All known capabilities")
    private List<AssistantKnowledgeCapability> capabilities = new ArrayList<AssistantKnowledgeCapability>();

    @Schema(description = "Selected capability")
    private AssistantKnowledgeCapability selectedCapability;

    @Schema(description = "Inputs still required from the user")
    private List<AssistantInputDefinition> requiredInputs = new ArrayList<AssistantInputDefinition>();

    @Schema(description = "Suggested frontend tool calls")
    private List<AssistantToolCall> toolCalls = new ArrayList<AssistantToolCall>();

    @Schema(description = "Allow-listed backend tool results")
    private List<AssistantBackendToolResult> backendToolResults = new ArrayList<AssistantBackendToolResult>();

    @Schema(description = "Draft action assembled by the assistant")
    private AssistantActionDraft actionDraft;

    @Schema(description = "Planner mode")
    private String plannerMode;

    @Schema(description = "Structured LLM planning result")
    private AssistantLlmPlan llmPlan;

    @Schema(description = "Planner warnings")
    private List<String> warnings = new ArrayList<String>();
}
