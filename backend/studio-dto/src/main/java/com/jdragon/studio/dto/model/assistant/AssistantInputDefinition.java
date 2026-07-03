package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Assistant knowledge input definition")
public class AssistantInputDefinition {
    @Schema(description = "Input key")
    private String key;

    @Schema(description = "Input display label")
    private String label;

    @Schema(description = "Input type")
    private String type;

    @Schema(description = "Whether the input is required")
    private Boolean required;

    @Schema(description = "Input description")
    private String description;

    @Schema(description = "Input placeholder")
    private String placeholder;

    @Schema(description = "Static options when applicable")
    private List<AssistantInputOption> options = new ArrayList<AssistantInputOption>();
}
