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
    private String nodeCode;
    private String workerCode;
    private String status;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String logFilePath;
    private Long logSizeBytes;
    private String logCharset;
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();
    private Map<String, Object> resultJson = new LinkedHashMap<String, Object>();
}
