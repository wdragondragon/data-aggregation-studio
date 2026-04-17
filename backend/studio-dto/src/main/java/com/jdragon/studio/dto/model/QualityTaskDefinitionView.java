package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.QualityRuleDimension;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityTaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class QualityTaskDefinitionView extends BaseDefinition {
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
    private String whereClause;
    private String resolvedSqlPreview;
    private List<QualityTaskParamBinding> parameterBindings = new ArrayList<QualityTaskParamBinding>();
    private List<QualityRuleOutputParamView> outputParams = new ArrayList<QualityRuleOutputParamView>();
    private List<QualityTaskAlertConfig> alertConfigs = new ArrayList<QualityTaskAlertConfig>();
    private CollectionTaskScheduleDefinition schedule;
    private Map<String, Object> ruleSnapshot = new LinkedHashMap<String, Object>();
}
