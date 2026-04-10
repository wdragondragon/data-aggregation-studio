package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Field mapping rule parameter save request")
public class FieldMappingRuleParamSaveRequest {
    @Schema(description = "Parameter id")
    private Long id;

    @Schema(description = "Parameter name", required = true)
    private String paramName;

    @Schema(description = "Parameter order", required = true)
    private Integer paramOrder;

    @Schema(description = "Component type", required = true)
    private String componentType;

    @Schema(description = "Component value config JSON")
    private String paramValueJson;

    @Schema(description = "Description")
    private String description;
}
