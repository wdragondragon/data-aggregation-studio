package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class StreamingTaskRunView {
    private Long id;
    private Long collectionTaskId;
    private Long generation;
    private Long runtimeClusterId;
    private String status;
    private String deliverySemantics;
    private String groupId;
    private Long startedBy;
    private LocalDateTime startedAt;
    private LocalDateTime stopRequestedAt;
    private Long stoppedBy;
    private LocalDateTime stoppedAt;
    private String stopReason;
    private Map<String, Object> finalCheckpoint = new LinkedHashMap<String, Object>();
}
