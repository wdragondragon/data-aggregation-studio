package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.QualityRuleParamType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Quality rule input parameter save request")
public class QualityRuleInputParamSaveRequest {
    @Schema(description = "Parameter id")
    private Long id;

    @Schema(description = "Parameter order")
    private Integer paramOrder;

    @Schema(description = "Parameter name")
    private String paramName;

    @Schema(description = "Parameter type")
    private QualityRuleParamType paramType;

    @Schema(description = "Parameter meaning")
    private String paramMeaning;
}
