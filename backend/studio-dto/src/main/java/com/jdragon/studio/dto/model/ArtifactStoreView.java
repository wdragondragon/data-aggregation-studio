package com.jdragon.studio.dto.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ArtifactStoreView {
    private Long id;
    private String storeName;
    private String storeCode;
    private String provider;
    private String scopeType;
    private Long configVersion;
    private String endpoint;
    private String uploadUrl;
    private String simpleIndexUrl;
    private String bucket;
    private String region;
    private String rootPrefix;
    private boolean hasUsername;
    private boolean hasSecret;
    private boolean verifySsl;
    private boolean enabled;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
