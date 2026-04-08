package com.jdragon.studio.dto.model.auth;

import lombok.Data;

import java.util.List;

@Data
public class AuthProfileView {
    private String token;
    private Long userId;
    private String username;
    private String displayName;
    private String currentTenantId;
    private Long currentProjectId;
    private List<String> systemRoleCodes;
    private List<String> effectiveRoleCodes;
    private List<AuthTenantView> tenants;
    private List<AuthProjectView> projects;
}
