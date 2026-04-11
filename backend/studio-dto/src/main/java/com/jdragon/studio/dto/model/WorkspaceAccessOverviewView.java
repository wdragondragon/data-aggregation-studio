package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class WorkspaceAccessOverviewView {
    private List<WorkspaceAccessTenantGroupView> tenantGroups = new ArrayList<WorkspaceAccessTenantGroupView>();
    private List<WorkspaceAccessRequestView> requests = new ArrayList<WorkspaceAccessRequestView>();
}
