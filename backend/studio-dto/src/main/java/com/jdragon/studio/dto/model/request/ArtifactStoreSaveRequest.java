package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class ArtifactStoreSaveRequest {
    private Long id;
    private String storeName;
    private String storeCode;
    private String provider;
    private String scopeType;
    private String endpoint;
    private String uploadUrl;
    private String simpleIndexUrl;
    private String bucket;
    private String region;
    private String rootPrefix;
    private String username;
    private String secret;
    private Boolean clearCredential;
    private Boolean verifySsl;
    private Boolean enabled;
    private String description;
}
