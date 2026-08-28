package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class StreamingTaskEventView {
    private Long id;
    private Long collectionTaskId;
    private Long deploymentId;
    private Long runId;
    private Long attemptId;
    private Long generation;
    private String eventType;
    private String fromState;
    private String toState;
    private String message;
    private Map<String, Object> details = new LinkedHashMap<String, Object>();
    private Long actorId;
    private LocalDateTime occurredAt;
}
