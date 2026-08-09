package com.jdragon.studio.dto.model.dto;

import com.jdragon.studio.dto.enums.DispatchExecutionType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ExecutionEvent {
    private String eventType;
    private Long runRecordId;
    private Long workflowDefinitionId;
    private Long workflowVersionId;
    private Long workflowRunId;
    private Long collectionTaskId;
    private Long qualityTaskId;
    private Long fileTransferTaskId;
    private Long fileTransferRunId;
    private Long projectId;
    private DispatchExecutionType executionType;
    private String nodeCode;
    private Long requestedClusterId;
    private Long actualClusterId;
    private String actualClusterCode;
    private String workerGroupCode;
    private String workerCode;
    private String workerInstanceId;
    private String workerBootId;
    private String workerPodName;
    private String workerNodeName;
    private String logFilePath;
    private Long logSizeBytes;
    private String logCharset;
    private String logStorageType;
    private String logObjectBucket;
    private String logObjectKey;
    private Integer logChunkCount;
    private String logStatus;
    private String logErrorSummary;
    private Long triggeredByUserId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime occurredAt;
    private Map<String, Object> payload = new LinkedHashMap<String, Object>();
}

