package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_external_user_binding")
public class StudioExternalUserBindingEntity extends BaseTenantEntity {
    private String providerCode;
    private String externalUserId;
    private String externalAccount;
    private Long studioUserId;
    private LocalDateTime lastSeenAt;
}
