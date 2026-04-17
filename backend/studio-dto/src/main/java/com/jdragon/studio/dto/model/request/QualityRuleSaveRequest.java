package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.QualityRuleDimension;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityRuleScopeType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Quality rule save request")
public class QualityRuleSaveRequest {
    @Schema(description = "Rule id")
    private Long id;

    @NotBlank(message = "Rule name is required")
    @Schema(description = "Rule name", required = true)
    private String ruleName;

    @NotBlank(message = "Rule code is required")
    @Schema(description = "Rule code", required = true)
    private String ruleCode;

    @NotNull(message = "Scope type is required")
    @Schema(description = "Scope type", required = true)
    private QualityRuleScopeType scopeType;

    @NotNull(message = "Rule dimension is required")
    @Schema(description = "Rule dimension", required = true)
    private QualityRuleDimension ruleDimension;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Supported datasource types")
    private List<String> supportedDatasourceTypes = new ArrayList<String>();

    @NotNull(message = "Granularity is required")
    @Schema(description = "Rule granularity", required = true)
    private QualityRuleGranularity granularity;

    @NotBlank(message = "Logic SQL is required")
    @Schema(description = "Logic SQL", required = true)
    private String logicSql;

    @Schema(description = "Enabled flag")
    private Boolean enabled;

    @Schema(description = "Input parameters")
    private List<QualityRuleInputParamSaveRequest> inputParams = new ArrayList<QualityRuleInputParamSaveRequest>();

    @Schema(description = "Output parameters")
    private List<QualityRuleOutputParamSaveRequest> outputParams = new ArrayList<QualityRuleOutputParamSaveRequest>();
}
