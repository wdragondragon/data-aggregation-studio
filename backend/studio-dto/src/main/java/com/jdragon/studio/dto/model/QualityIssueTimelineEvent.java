package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class QualityIssueTimelineEvent {
    private Long id;
    private String eventType;
    private String title;
    private String message;
    private Long actorUserId;
    private String actorName;
    private LocalDateTime createdAt;
    private Map<String, Object> metadata = new LinkedHashMap<String, Object>();
}
