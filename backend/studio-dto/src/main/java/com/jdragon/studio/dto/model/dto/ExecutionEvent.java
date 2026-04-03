package com.jdragon.studio.dto.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ExecutionEvent {
    private String eventType;
    private Long workflowRunId;
    private String nodeCode;
    private String workerCode;
    private LocalDateTime occurredAt;
    private Map<String, Object> payload = new LinkedHashMap<String, Object>();
}

