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
@TableName(value = "meta_field_definition", autoResultMap = true)
public class MetaFieldDefinitionEntity extends BaseTenantEntity {
    private Long schemaVersionId;
    private String fieldKey;
    private String fieldName;
    private String description;
    private String scope;
    private String valueType;
    private String componentType;
    private Integer requiredFlag;
    private Integer sensitiveFlag;
    private Integer sortOrder;
    private String validationRule;
    private String placeholder;
    private String defaultValue;
    private Integer searchableFlag;
    private Integer sortableFlag;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> queryOperators = new ArrayList<String>();

    private String queryDefaultOperator;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> options = new ArrayList<String>();
}
