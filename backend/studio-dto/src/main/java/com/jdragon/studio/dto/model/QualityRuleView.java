package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.QualityRuleDimension;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityRuleScopeType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class QualityRuleView extends BaseDefinition {
    private String ruleName;
    private String ruleCode;
    private QualityRuleScopeType scopeType;
    private QualityRuleDimension ruleDimension;
    private String description;
    private List<String> supportedDatasourceTypes = new ArrayList<String>();
    private QualityRuleGranularity granularity;
    private String logicSql;
    private Boolean enabled;
    private Long createdBy;
    private String createdByName;
    private Boolean editable;
    private Boolean deletable;
    private List<QualityRuleInputParamView> inputParams = new ArrayList<QualityRuleInputParamView>();
    private List<QualityRuleOutputParamView> outputParams = new ArrayList<QualityRuleOutputParamView>();
}
