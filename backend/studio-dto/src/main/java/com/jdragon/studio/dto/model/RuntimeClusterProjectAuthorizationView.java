package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class RuntimeClusterProjectAuthorizationView {
    private Long projectId;
    private Long runtimeClusterId;
    private boolean enabled;
    private boolean preferred;
    private boolean allowManualOverride;
}
