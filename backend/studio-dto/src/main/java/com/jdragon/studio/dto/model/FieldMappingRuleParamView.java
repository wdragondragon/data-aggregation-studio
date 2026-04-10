package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Field mapping rule parameter definition")
public class FieldMappingRuleParamView extends BaseDefinition {
    @Schema(description = "Rule id")
    private Long ruleId;

    @Schema(description = "Parameter name")
    private String paramName;

    @Schema(description = "Parameter order")
    private Integer paramOrder;

    @Schema(description = "Component type")
    private String componentType;

    @Schema(description = "Component value config JSON")
    private String paramValueJson;

    @Schema(description = "Description")
    private String description;
}
