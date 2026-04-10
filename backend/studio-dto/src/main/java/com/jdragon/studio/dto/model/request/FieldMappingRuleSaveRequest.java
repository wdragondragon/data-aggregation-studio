package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Field mapping rule save request")
public class FieldMappingRuleSaveRequest {
    @Schema(description = "Rule id")
    private Long id;

    @NotBlank(message = "Mapping name is required")
    @Schema(description = "Mapping name", required = true)
    private String mappingName;

    @NotBlank(message = "Mapping type is required")
    @Schema(description = "Mapping type", required = true)
    private String mappingType;

    @NotBlank(message = "Mapping code is required")
    @Schema(description = "Mapping code / transformer code", required = true)
    private String mappingCode;

    @Schema(description = "Enabled flag")
    private Boolean enabled;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Parameter definitions")
    private List<FieldMappingRuleParamSaveRequest> params = new ArrayList<FieldMappingRuleParamSaveRequest>();
}
