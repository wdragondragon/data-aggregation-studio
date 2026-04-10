package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("field_mapping_rule_param")
public class FieldMappingRuleParamEntity extends BaseStudioEntity {
    private Long ruleId;
    private String paramName;
    private Integer paramOrder;
    private String componentType;
    private String paramValueJson;
    private String description;
}
