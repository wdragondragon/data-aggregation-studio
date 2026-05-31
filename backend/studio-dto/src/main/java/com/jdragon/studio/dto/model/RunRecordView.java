package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class RunRecordView extends BaseDefinition {
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
    private String logFilePath;
    private Long logSizeBytes;
    private String logCharset;
    private String logStorageType;
    private String logStatus;
    private String logErrorSummary;
    private RunMetricSummaryView metricSummary;
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();
    private Map<String, Object> resultJson = new LinkedHashMap<String, Object>();
}
