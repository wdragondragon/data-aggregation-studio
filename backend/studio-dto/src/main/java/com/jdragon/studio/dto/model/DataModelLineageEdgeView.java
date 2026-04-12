package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DataModelLineageEdgeView {
    private String edgeId;
    private String sourceNodeId;
    private String targetNodeId;
    private Boolean selfLoop;
    private String sourceField;
    private String targetField;
    private String label;
    private String sourceType;
    private String sourceTypeLabel;
    private String displayStatus;
    private String latestRunStatus;
    private Long latestRunId;
    private LocalDateTime latestRunAt;
    private Integer contributorCount;
}
