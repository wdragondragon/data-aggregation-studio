package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class OpsCenterQueueItemView extends BaseDefinition {
    private String executionType;
    private Long workflowRunId;
    private Long workflowDefinitionId;
    private Long workflowVersionId;
    private Long collectionTaskId;
    private Long qualityTaskId;
    private String targetName;
    private String nodeCode;
    private String status;
    private String workerGroupCode;
    private String leaseOwner;
    private String workerInstanceId;
    private LocalDateTime scheduledFireTime;
    private Long queuedDurationMs;
    private Long scheduleDelayMs;
    private Integer attempts;
    private Integer maxRetries;
}
