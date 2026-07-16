package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_alert_channel")
public class AlertChannelEntity extends BaseProjectTenantEntity {
    private String name;
    private String channelType;
    private String endpointCiphertext;
    private String headersCiphertext;
    private String signingSecretCiphertext;
    private Integer enabled;
    private LocalDateTime lastTestedAt;
    private String lastTestStatus;
    private String lastTestMessage;
    private Long createdBy;
    private Long updatedBy;
}
