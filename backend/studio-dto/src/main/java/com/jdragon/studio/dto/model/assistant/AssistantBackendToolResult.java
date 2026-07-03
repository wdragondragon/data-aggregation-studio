package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Result of an allow-listed backend assistant tool")
public class AssistantBackendToolResult {
    @Schema(description = "Backend tool code")
    private String code;

    @Schema(description = "Whether invocation succeeded")
    private Boolean ok;

    @Schema(description = "Tool parameters")
    private Map<String, Object> params = new LinkedHashMap<String, Object>();

    @Schema(description = "Tool result data")
    private Object data;

    @Schema(description = "Failure message")
    private String error;
}
