package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "quality_rule", autoResultMap = true)
public class QualityRuleEntity extends BaseProjectTenantEntity {
    private Long createdBy;
    private String ruleName;
    private String ruleCode;
    private String scopeType;
    private String ruleDimension;
    private String description;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> supportedDatasourceTypesJson = new ArrayList<String>();

    private String granularity;
    private String logicSql;
    private Integer enabled;
}
