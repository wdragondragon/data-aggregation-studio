package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class RuntimeClusterProjectAuthorizationRequest {
    private Long projectId;
    private Long runtimeClusterId;
    private Boolean enabled;
    private Boolean preferred;
    private Boolean allowManualOverride;
}
