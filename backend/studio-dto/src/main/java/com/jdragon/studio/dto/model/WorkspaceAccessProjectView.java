package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class WorkspaceAccessProjectView {
    private Long projectId;
    private String tenantId;
    private String tenantName;
    private String projectCode;
    private String projectName;
    private String description;
    private Boolean enabled;
    private Long pendingRequestId;
    private String pendingRequestStatus;
}
