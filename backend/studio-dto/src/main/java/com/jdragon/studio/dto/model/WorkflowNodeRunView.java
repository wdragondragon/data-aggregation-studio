package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkflowNodeRunView {
    private Long runRecordId;
    private Long workflowRunId;
    private Long workflowDefinitionId;
    private String workflowName;
    private String nodeCode;
    private String nodeName;
    private String nodeType;
    private String status;
    private String workerCode;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private Boolean logAvailable;
}
