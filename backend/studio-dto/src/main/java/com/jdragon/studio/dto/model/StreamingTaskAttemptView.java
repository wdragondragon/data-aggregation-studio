package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class StreamingTaskAttemptView {
    private Long id;
    private Long runId;
    private Long collectionTaskId;
    private Long generation;
    private Integer attemptNo;
    private Long dispatchTaskId;
    private Long runRecordId;
    private Long runtimeClusterId;
    private String workerInstanceId;
    private String workerBootId;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime heartbeatAt;
    private LocalDateTime retryAfter;
    private Map<String, Object> checkpoint = new LinkedHashMap<String, Object>();
    private String errorCode;
    private String errorSummary;
    private Long committedBatchCount;
}
