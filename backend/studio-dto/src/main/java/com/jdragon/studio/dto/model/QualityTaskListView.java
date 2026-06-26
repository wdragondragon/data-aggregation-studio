package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.QualityRuleDimension;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityTaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class QualityTaskListView extends BaseDefinition {
    private Long createdBy;
    private String taskName;
    private String taskCode;
    private QualityTaskStatus status;
    private Long ruleId;
    private String ruleName;
    private QualityRuleDimension ruleDimension;
    private QualityRuleGranularity granularity;
    private Long datasourceId;
    private String datasourceName;
    private String datasourceTypeCode;
    private Long modelId;
    private String modelName;
    private String modelPhysicalLocator;
    private String columnName;
}
