package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class NotificationView {
    private Long id;
    private String category;
    private String title;
    private String content;
    private String targetType;
    private Long targetId;
    private String targetPath;
    private String targetTenantId;
    private Long targetProjectId;
    private boolean read;
    private LocalDateTime readAt;
    private LocalDateTime archivedAt;
    private LocalDateTime createdAt;
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();
}
