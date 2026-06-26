package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Field mapping rule list row")
public class FieldMappingRuleListView extends BaseDefinition {
    @Schema(description = "Mapping name")
    private String mappingName;

    @Schema(description = "Mapping type")
    private String mappingType;

    @Schema(description = "Mapping code / transformer code")
    private String mappingCode;

    @Schema(description = "Enabled flag")
    private Boolean enabled;

    @Schema(description = "Creator display name")
    private String createdByName;
}
