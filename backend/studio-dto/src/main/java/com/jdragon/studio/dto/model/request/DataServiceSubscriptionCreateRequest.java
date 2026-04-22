package com.jdragon.studio.dto.model.request;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class DataServiceSubscriptionCreateRequest {
    @NotBlank
    private String subscriptionName;
}

