package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OpsCenterServiceEventView {
    private Long id;
    private Long serviceId;
    private String serviceCode;
    private String serviceName;
    private String serviceStatus;
    private Long subscriptionId;
    private String subscriptionName;
    private String requestMethod;
    private LocalDateTime occurredAt;
    private Long durationMs;
    private Boolean success;
    private Integer httpStatus;
    private String errorCode;
    private String errorMessage;
    private Long rowCount;
    private Long receivedCount;
    private Long writtenCount;
    private Long failedCount;
    private Boolean slow;
}
