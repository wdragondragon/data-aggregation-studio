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
@TableName(value = "data_dev_script", autoResultMap = true)
public class DataDevelopmentScriptEntity extends BaseProjectTenantEntity {
    private Long runtimeClusterId;
    private Long directoryId;
    private String fileName;
    private String scriptType;
    private Long datasourceId;
    private Long environmentId;
    private String description;
    private String content;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> executionConfigJson = new LinkedHashMap<String, Object>();
}
