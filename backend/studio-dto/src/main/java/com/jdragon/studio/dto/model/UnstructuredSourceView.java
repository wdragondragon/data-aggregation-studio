package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class UnstructuredSourceView {
    private Long id;
    private String name;
    private String typeCode;
    private Long runtimeClusterId;
    private Long createdBy;
    private boolean aclManageable;
    private List<String> effectivePermissions = new ArrayList<String>();
}
