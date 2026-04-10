package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Field mapping rule")
public class FieldMappingRuleView extends BaseDefinition {
    @Schema(description = "Mapping name")
    private String mappingName;

    @Schema(description = "Mapping type")
    private String mappingType;

    @Schema(description = "Mapping code / transformer code")
    private String mappingCode;

    @Schema(description = "Enabled flag")
    private Boolean enabled;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Creator user id")
    private Long createdBy;

    @Schema(description = "Creator display name")
    private String createdByName;

    @Schema(description = "Parameter definitions")
    private List<FieldMappingRuleParamView> params = new ArrayList<FieldMappingRuleParamView>();
}
