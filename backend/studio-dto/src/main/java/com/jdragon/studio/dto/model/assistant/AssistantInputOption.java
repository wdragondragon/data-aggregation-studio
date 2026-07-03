package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Assistant input option")
public class AssistantInputOption {
    @Schema(description = "Option label")
    private String label;

    @Schema(description = "Option value")
    private Object value;

    @Schema(description = "Option description")
    private String description;

    @Schema(description = "Extra option metadata")
    private Map<String, Object> metadata = new LinkedHashMap<String, Object>();
}
