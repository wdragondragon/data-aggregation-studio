package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Assistant planning request")
public class AssistantPlanRequest {
    @Schema(description = "Latest user message")
    private String message;

    @Schema(description = "Assistant interaction mode: chat, plan, or goal")
    private String assistantMode = "chat";

    @Schema(description = "Preferred response language: zh or en")
    private String responseLanguage = "zh";

    @Schema(description = "Custom assistant protocol version")
    private String protocolVersion = "studio-assistant.v1";

    @Schema(description = "Conversation messages")
    private List<AssistantChatMessage> messages = new ArrayList<AssistantChatMessage>();

    @Schema(description = "Current UI/runtime context")
    private Map<String, Object> context = new LinkedHashMap<String, Object>();

    @Schema(description = "Inputs already collected from the user")
    private Map<String, Object> collectedInputs = new LinkedHashMap<String, Object>();

    @Schema(description = "Tool results returned by the frontend")
    private List<Map<String, Object>> toolResults = new ArrayList<Map<String, Object>>();
}
