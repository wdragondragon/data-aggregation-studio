package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.QualityRuleParamType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Quality rule input parameter definition")
public class QualityRuleInputParamView extends BaseDefinition {
    @Schema(description = "Rule id")
    private Long ruleId;

    @Schema(description = "Parameter order")
    private Integer paramOrder;

    @Schema(description = "Parameter name")
    private String paramName;

    @Schema(description = "Parameter type")
    private QualityRuleParamType paramType;

    @Schema(description = "Parameter meaning")
    private String paramMeaning;
}
