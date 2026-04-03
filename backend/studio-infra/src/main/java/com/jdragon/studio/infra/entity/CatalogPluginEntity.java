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
@TableName(value = "catalog_plugin", autoResultMap = true)
public class CatalogPluginEntity extends BaseTenantEntity {
    private String pluginName;
    private String pluginCategory;
    private String assetType;
    private String assetPath;
    private Integer executable;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> template = new LinkedHashMap<String, Object>();
}
