package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DataModelLineageUnresolvedExpressionView {
    private Long collectionTaskId;
    private String collectionTaskName;
    private String sourceAlias;
    private String targetField;
    private String expression;
    private LocalDateTime latestRunAt;
    private String latestRunStatus;
}
