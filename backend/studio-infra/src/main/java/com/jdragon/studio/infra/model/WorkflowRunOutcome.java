package com.jdragon.studio.infra.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkflowRunOutcome {
    private Long workflowRunId;
    private LocalDateTime observedAt;
    private Integer failed;
}
