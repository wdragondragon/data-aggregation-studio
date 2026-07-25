package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class RuntimeClusterHeartbeatRequest {
    private String tenantId;
    private String clusterCode;
    private String instanceId;
    private String version;
    private String summary;
}
