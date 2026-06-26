package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
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
}
