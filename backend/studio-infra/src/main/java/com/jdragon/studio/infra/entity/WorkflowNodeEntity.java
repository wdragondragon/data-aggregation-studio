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
@TableName(value = "workflow_node", autoResultMap = true)
public class WorkflowNodeEntity extends BaseProjectTenantEntity {
    private Long workflowVersionId;
    private String nodeCode;
    private String nodeName;
    private String nodeType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configJson = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> fieldMappingsJson = new ArrayList<Map<String, Object>>();
}
