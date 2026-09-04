package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ManagedFileAuditView {
    private Long id;
    private Long fileId;
    private String action;
    private String outcome;
    private Long actorUserId;
    private String actorName;
    private String ownerType;
    private Long ownerId;
    private String fieldKey;
    private String detail;
    private LocalDateTime createdAt;
}
