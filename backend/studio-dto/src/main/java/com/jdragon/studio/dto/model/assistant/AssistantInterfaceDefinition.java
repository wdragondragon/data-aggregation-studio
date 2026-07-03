package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Assistant callable interface definition")
public class AssistantInterfaceDefinition {
    @Schema(description = "Interface code")
    private String interfaceCode;

    @Schema(description = "HTTP method")
    private String method;

    @Schema(description = "HTTP path")
    private String path;

    @Schema(description = "Interface purpose")
    private String purpose;

    @Schema(description = "Whether the interface mutates Studio state")
    private Boolean mutation;

    @Schema(description = "Required input keys")
    private List<String> requiredValues = new ArrayList<String>();

    @Schema(description = "How the response should be used")
    private String responseUsage;
}
