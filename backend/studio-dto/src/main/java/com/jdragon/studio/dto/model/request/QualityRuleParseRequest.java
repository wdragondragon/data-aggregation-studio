package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Quality rule parse request")
public class QualityRuleParseRequest {
    @Schema(description = "Rule granularity")
    private QualityRuleGranularity granularity;

    @Schema(description = "Logic SQL")
    private String logicSql;
}
