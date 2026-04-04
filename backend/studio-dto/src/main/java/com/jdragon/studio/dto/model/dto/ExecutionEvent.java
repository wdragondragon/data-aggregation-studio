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
    private DispatchExecutionType executionType;
    private String nodeCode;
    private String workerCode;
    private String logFilePath;
    private Long logSizeBytes;
    private String logCharset;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime occurredAt;
    private Map<String, Object> payload = new LinkedHashMap<String, Object>();
}

