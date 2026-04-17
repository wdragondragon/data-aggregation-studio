package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
@TableName(value = "quality_task_definition", autoResultMap = true)
public class QualityTaskDefinitionEntity extends BaseProjectTenantEntity {
    private Long createdBy;
    private String taskName;
    private String taskCode;
    private String status;
    private Long ruleId;
    private String ruleNameSnapshot;
    private String ruleDimension;
    private String granularity;
    private Long datasourceId;
    private String datasourceNameSnapshot;
    private String datasourceTypeCode;
    private Long modelId;
    private String modelNameSnapshot;
    private String modelPhysicalLocator;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String columnName;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String whereClause;
    private String resolvedSqlPreview;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> parameterBindingsJson = new ArrayList<Map<String, Object>>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> ruleSnapshotJson = new LinkedHashMap<String, Object>();
}
