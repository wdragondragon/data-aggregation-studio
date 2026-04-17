package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_service_subscription")
public class DataServiceSubscriptionEntity extends BaseProjectTenantEntity {
    private Long serviceId;
    private String subscriptionName;
    private String tokenHash;
    private Integer enabled;
    private Long createdBy;
    private LocalDateTime lastUsedAt;
}
