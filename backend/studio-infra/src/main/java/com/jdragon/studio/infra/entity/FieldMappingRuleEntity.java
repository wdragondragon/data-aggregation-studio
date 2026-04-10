package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("field_mapping_rule")
public class FieldMappingRuleEntity extends BaseStudioEntity {
    private String mappingName;
    private String mappingType;
    private String mappingCode;
    private Integer enabled;
    private String description;
    private Long createdBy;
}
