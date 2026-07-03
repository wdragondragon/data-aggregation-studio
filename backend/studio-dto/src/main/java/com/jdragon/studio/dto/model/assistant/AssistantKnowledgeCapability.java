package com.jdragon.studio.dto.model.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Assistant knowledge capability")
public class AssistantKnowledgeCapability {
    @Schema(description = "Capability code")
    private String capabilityCode;

    @Schema(description = "Capability name")
    private String capabilityName;

    @Schema(description = "Capability description")
    private String description;

    @Schema(description = "Intent examples")
    private List<String> intentExamples = new ArrayList<String>();

    @Schema(description = "Required inputs")
    private List<AssistantInputDefinition> requiredInputs = new ArrayList<AssistantInputDefinition>();

    @Schema(description = "Optional inputs")
    private List<AssistantInputDefinition> optionalInputs = new ArrayList<AssistantInputDefinition>();

    @Schema(description = "Callable interfaces")
    private List<AssistantInterfaceDefinition> interfaces = new ArrayList<AssistantInterfaceDefinition>();

    @Schema(description = "Input value resolvers")
    private List<AssistantValueResolver> valueResolvers = new ArrayList<AssistantValueResolver>();

    @Schema(description = "Assembly rules")
    private List<String> assemblyRules = new ArrayList<String>();

    @Schema(description = "Confirmation policy")
    private List<String> confirmationPolicy = new ArrayList<String>();
}
