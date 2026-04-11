package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkspaceAccessRequestView {
    private Long requestId;
    private Long projectId;
    private String tenantId;
    private String tenantName;
    private String projectName;
    private String requestType;
    private String status;
    private String reason;
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
}
