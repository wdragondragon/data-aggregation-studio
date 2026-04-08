package com.jdragon.studio.infra.security;

import lombok.Data;

import java.util.List;

@Data
public class StudioRequestContext {
    private Long userId;
    private String username;
    private String displayName;
    private String tenantId;
    private Long projectId;
    private List<String> systemRoleCodes;
    private List<String> effectiveRoleCodes;
}
