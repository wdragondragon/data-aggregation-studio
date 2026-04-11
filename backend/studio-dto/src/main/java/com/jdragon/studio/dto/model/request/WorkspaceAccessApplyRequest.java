package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class WorkspaceAccessApplyRequest {
    private Long projectId;
    private String reason;
}
