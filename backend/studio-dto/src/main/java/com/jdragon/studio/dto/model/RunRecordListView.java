package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class RunRecordListView extends BaseDefinition {
    private String executionType;
    private Long workflowRunId;
    private Long workflowDefinitionId;
    private Long workflowVersionId;
    private String workflowName;
    private Long collectionTaskId;
    private String collectionTaskName;
    private Long qualityTaskId;
    private String qualityTaskName;
    private String nodeCode;
    private String workerGroupCode;
    private String workerCode;
    private String workerInstanceId;
    private String workerPodName;
    private String workerNodeName;
    private String status;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private RunMetricSummaryView metricSummary;
}
