package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("so_pf_managed_file")
public class ManagedFileEntity extends BaseProjectTenantEntity {
    private String originalFileName;
    private String policyCode;
    private String contentType;
    private Long plaintextSize;
    private Long ciphertextSize;
    private String sha256;
    private String objectBucket;
    private String objectKey;
    private String encryptionAlgorithm;
    private Integer encryptionVersion;
    private String encryptionIv;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime boundAt;
    private LocalDateTime lastReferencedAt;
    private Long uploadedBy;
    private String errorMessage;
    private Integer deleteRetryCount;
    private LocalDateTime nextDeleteAttemptAt;
    private LocalDateTime deletedAt;
}
