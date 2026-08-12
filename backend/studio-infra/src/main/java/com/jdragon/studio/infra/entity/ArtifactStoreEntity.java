package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("so_pf_artifact_store")
public class ArtifactStoreEntity extends BaseTenantEntity {
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
    private String usernameCiphertext;
    private String secretCiphertext;
    private Integer verifySsl;
    private Integer enabled;
    private String description;
}
