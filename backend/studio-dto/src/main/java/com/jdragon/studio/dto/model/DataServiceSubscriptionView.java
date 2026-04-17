package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataServiceSubscriptionView extends BaseDefinition {
    private Long serviceId;
    private String subscriptionName;
    private String token;
    private String tokenMasked;
    private Boolean enabled;
    private Long createdBy;
    private LocalDateTime lastUsedAt;
}
