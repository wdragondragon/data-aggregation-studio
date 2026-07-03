package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Allow-listed backend tool call suggested by the assistant LLM")
public class AssistantBackendToolCall {
    @Schema(description = "Backend tool code")
    private String code;

    @Schema(description = "Call reason")
    private String reason;

    @Schema(description = "Tool parameters")
    private Map<String, Object> params = new LinkedHashMap<String, Object>();
}
