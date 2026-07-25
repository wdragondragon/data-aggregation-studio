package com.jdragon.studio.dto.model.request;

import lombok.Data;
import java.util.Map;

@Data
public class RuntimeEndpointSaveRequest {
    private Long id;
    private Long runtimeClusterId;
    private String mode;
    private String endpointUrl;
    /** Null preserves stored headers during an update; an empty map clears them. */
    private Map<String, String> headers;
    private String token;
    private Boolean clearToken;
    private Integer connectTimeoutMillis;
    private Integer readTimeoutMillis;
    private Boolean enabled;
}
