package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.QualityRuleDimension;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityRuleScopeType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class QualityRuleListView extends BaseDefinition {
    private String ruleName;
    private String ruleCode;
    private QualityRuleScopeType scopeType;
    private QualityRuleDimension ruleDimension;
    private QualityRuleGranularity granularity;
    private Boolean enabled;
    private Long createdBy;
    private String createdByName;
    private Boolean editable;
    private Boolean deletable;
}
