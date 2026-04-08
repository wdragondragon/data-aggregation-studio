package com.jdragon.studio.dto.model.auth;

import lombok.Data;

import java.util.List;

@Data
public class AuthProjectView {
    private Long projectId;
    private String tenantId;
    private String projectCode;
    private String projectName;
    private Boolean enabled;
    private Boolean defaultProject;
    private List<String> roleCodes;
}
