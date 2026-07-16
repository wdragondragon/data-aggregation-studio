package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AlertEventView {
    private Long id;
    private Long incidentId;
    private Long ruleId;
    private String eventType;
    private String statusFrom;
    private String statusTo;
    private String sourceType;
    private String sourceId;
    private String subjectType;
    private String subjectKey;
    private Long subjectId;
    private String subjectName;
    private String targetPath;
    private String severity;
    private String summary;
    private Map<String, Object> evidence = new LinkedHashMap<String, Object>();
    private Long actorUserId;
    private String actorName;
    private LocalDateTime observedAt;
    private LocalDateTime createdAt;
}
