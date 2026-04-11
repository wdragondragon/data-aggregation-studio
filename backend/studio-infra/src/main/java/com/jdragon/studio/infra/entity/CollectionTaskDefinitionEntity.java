package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "collection_task_definition", autoResultMap = true)
public class CollectionTaskDefinitionEntity extends BaseProjectTenantEntity {
    private Long createdBy;
    private String name;
    private String taskType;
    private String status;
    private Integer sourceCount;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> sourceBindingsJson = new ArrayList<Map<String, Object>>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> targetBindingJson = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> fieldMappingsJson = new ArrayList<Map<String, Object>>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> executionOptionsJson = new LinkedHashMap<String, Object>();
}
