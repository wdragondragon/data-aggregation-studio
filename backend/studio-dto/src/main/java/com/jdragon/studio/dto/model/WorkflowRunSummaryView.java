package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkflowRunSummaryView {
    private Long workflowRunId;
    private Long workflowDefinitionId;
    private Long workflowVersionId;
    private String workflowName;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private Integer totalNodes;
    private Integer successNodes;
    private Integer failedNodes;
    private Integer runningNodes;
    private Integer queuedNodes;
    private Integer notRunNodes;
    private String summaryMessage;
}
