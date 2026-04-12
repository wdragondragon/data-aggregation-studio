package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DataModelLineageContributorView {
    private Long relationId;
    private String sourceType;
    private String sourceTypeLabel;
    private String displayStatus;
    private Long sourceModelId;
    private Long targetModelId;
    private String sourceField;
    private String targetField;
    private Long collectionTaskId;
    private String collectionTaskName;
    private Long latestRunId;
    private String latestRunStatus;
    private LocalDateTime latestRunAt;
    private String taskPath;
    private String runPath;
    private String mappingMode;
    private String expression;
    private Long maintainerUserId;
    private String maintainer;
    private LocalDateTime updatedAt;
    private Boolean editable;
}
