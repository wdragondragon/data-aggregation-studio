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
@TableName(value = "workflow_definition_version", autoResultMap = true)
public class WorkflowVersionEntity extends BaseTenantEntity {
    private Long definitionId;
    private Integer versionNumber;
    private Integer published;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> graphJson = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> scheduleJson = new LinkedHashMap<String, Object>();
}
