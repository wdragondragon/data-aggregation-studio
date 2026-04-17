package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.QualityRuleOutputType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Quality rule output parameter definition")
public class QualityRuleOutputParamView extends BaseDefinition {
    @Schema(description = "Rule id")
    private Long ruleId;

    @Schema(description = "Output order")
    private Integer outputOrder;

    @Schema(description = "Result field")
    private String resultField;

    @Schema(description = "Output type")
    private QualityRuleOutputType outputType;

    @Schema(description = "Output description")
    private String outputDescription;
}
