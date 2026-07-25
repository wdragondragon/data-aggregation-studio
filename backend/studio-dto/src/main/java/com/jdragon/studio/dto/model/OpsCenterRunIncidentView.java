package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class OpsCenterRunIncidentView extends BaseDefinition {
    private String executionType;
    private Long workflowRunId;
    private Long workflowDefinitionId;
    private Long workflowVersionId;
    private Long collectionTaskId;
    private Long qualityTaskId;
    private String nodeCode;
    private String status;
    private String message;
    private Long requestedClusterId;
    private Long actualClusterId;
    private String actualClusterCode;
    private String workerGroupCode;
    private String workerCode;
    private String workerInstanceId;
    private String workerPodName;
    private String workerNodeName;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private String logStorageType;
    private String logStatus;
    private String logErrorSummary;
    private Boolean slow;
    private Boolean logAbnormal;
}
