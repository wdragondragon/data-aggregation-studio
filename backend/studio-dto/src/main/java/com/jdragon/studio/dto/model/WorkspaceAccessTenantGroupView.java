package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class WorkspaceAccessTenantGroupView {
    private String tenantId;
    private String tenantCode;
    private String tenantName;
    private Boolean enabled;
    private List<WorkspaceAccessProjectView> projects = new ArrayList<WorkspaceAccessProjectView>();
}
