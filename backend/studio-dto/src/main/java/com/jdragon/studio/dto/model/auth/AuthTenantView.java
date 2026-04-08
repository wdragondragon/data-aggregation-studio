package com.jdragon.studio.dto.model.auth;

import lombok.Data;

import java.util.List;

@Data
public class AuthTenantView {
    private String tenantId;
    private String tenantCode;
    private String tenantName;
    private Boolean enabled;
    private List<String> roleCodes;
}
