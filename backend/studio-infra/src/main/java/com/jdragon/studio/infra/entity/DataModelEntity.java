package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_model", autoResultMap = true)
public class DataModelEntity extends BaseTenantEntity {
    private Long datasourceId;
    private String name;
    private String modelKind;
    private String physicalLocator;
    private Long schemaVersionId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> businessMetadata = new LinkedHashMap<String, Object>();
}
