package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class UnstructuredPermissionView {
    private Long datasourceId;
    private String path;
    private List<String> effectivePermissions = new ArrayList<String>();
    private boolean ownerOrAdmin;
}
