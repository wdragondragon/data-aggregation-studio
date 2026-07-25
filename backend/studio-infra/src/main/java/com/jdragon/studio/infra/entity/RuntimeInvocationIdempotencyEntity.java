package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_runtime_idempotency")
public class RuntimeInvocationIdempotencyEntity extends BaseProjectTenantEntity {
    private Long runtimeClusterId;
    private String resourceType;
    private Long resourceId;
    private String keyHash;
    private String requestFingerprint;
    private String status;
    private String ownerTokenHash;
    private String ownerInstanceId;
    private String ownerBootId;
    private Integer responseStatus;
    private String responseContentType;
    private String responseBodyCiphertext;
    private LocalDateTime completedAt;
    @Version
    private Integer version;
}
