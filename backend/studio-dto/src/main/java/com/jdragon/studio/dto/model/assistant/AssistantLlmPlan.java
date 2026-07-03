package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Structured LLM planning result constrained by assistant knowledge")
public class AssistantLlmPlan {
    @Schema(description = "Matched capability code")
    private String capabilityCode;

    @Schema(description = "LLM confidence score")
    private Double confidence;

    @Schema(description = "Assistant message produced by LLM")
    private String assistantMessage;

    @Schema(description = "Business values inferred from natural language. Do not store generated IDs here.")
    private Map<String, Object> inferredInputs = new LinkedHashMap<String, Object>();

    @Schema(description = "Input keys still requiring user choice or text input")
    private List<String> missingInputs = new ArrayList<String>();

    @Schema(description = "Suggested frontend tool calls")
    private List<AssistantToolCall> toolCalls = new ArrayList<AssistantToolCall>();

    @Schema(description = "Suggested allow-listed backend tool calls")
    private List<AssistantBackendToolCall> backendToolCalls = new ArrayList<AssistantBackendToolCall>();

    @Schema(description = "Planner warnings")
    private List<String> warnings = new ArrayList<String>();
}
