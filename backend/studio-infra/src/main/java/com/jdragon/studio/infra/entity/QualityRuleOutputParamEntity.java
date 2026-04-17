package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quality_rule_output_param")
public class QualityRuleOutputParamEntity extends BaseStudioEntity {
    private Long ruleId;
    private Integer outputOrder;
    private String resultField;
    private String outputType;
    private String outputDescription;
}
