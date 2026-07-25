package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class OpsCenterLogEventView extends BaseDefinition {
    private String executionType;
    private Long workflowRunId;
    private Long workflowDefinitionId;
    private Long collectionTaskId;
    private Long qualityTaskId;
    private String nodeCode;
    private String status;
    private Long requestedClusterId;
    private Long actualClusterId;
    private String actualClusterCode;
    private String workerGroupCode;
    private String workerInstanceId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String logStorageType;
    private String logStatus;
    private String logErrorSummary;
}
