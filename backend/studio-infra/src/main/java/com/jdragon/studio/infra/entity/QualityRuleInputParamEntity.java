package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quality_rule_input_param")
public class QualityRuleInputParamEntity extends BaseStudioEntity {
    private Long ruleId;
    private Integer paramOrder;
    private String paramName;
    private String paramType;
    private String paramMeaning;
}
