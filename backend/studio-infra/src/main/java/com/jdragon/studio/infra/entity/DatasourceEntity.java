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
@TableName(value = "datasource_definition", autoResultMap = true)
public class DatasourceEntity extends BaseProjectTenantEntity {
    private String name;
    private String typeCode;
    private Long schemaVersionId;
    private Integer enabled;
    private Integer executable;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> businessMetadata = new LinkedHashMap<String, Object>();
}
