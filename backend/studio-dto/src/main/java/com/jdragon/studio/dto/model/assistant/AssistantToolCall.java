package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Assistant tool call suggestion")
public class AssistantToolCall {
    @Schema(description = "Tool call id")
    private String id;

    @Schema(description = "Interface code to call")
    private String interfaceCode;

    @Schema(description = "Call reason")
    private String reason;

    @Schema(description = "Call params")
    private Map<String, Object> params = new LinkedHashMap<String, Object>();
}
