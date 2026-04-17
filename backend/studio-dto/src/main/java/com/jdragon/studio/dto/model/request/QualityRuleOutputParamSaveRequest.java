package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.QualityRuleOutputType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Quality rule output parameter save request")
public class QualityRuleOutputParamSaveRequest {
    @Schema(description = "Output parameter id")
    private Long id;

    @Schema(description = "Output order")
    private Integer outputOrder;

    @Schema(description = "Result field")
    private String resultField;

    @Schema(description = "Output type")
    private QualityRuleOutputType outputType;

    @Schema(description = "Output description")
    private String outputDescription;
}
