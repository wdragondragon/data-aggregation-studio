package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QualityTaskWorkflowOptionView {
    private Long id;
    private Long projectId;
    private LocalDateTime updatedAt;
    private String taskName;
    private String taskCode;
    private Long ruleId;
    private String ruleName;
    private String ruleDimension;
    private String granularity;
    private Long datasourceId;
    private String datasourceName;
    private Long modelId;
    private String modelName;
    private String columnName;
}
