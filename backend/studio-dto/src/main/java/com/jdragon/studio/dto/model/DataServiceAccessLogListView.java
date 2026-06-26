package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DataServiceAccessLogListView {
    private Long id;
    private Long serviceId;
    private String serviceCode;
    private String serviceName;
    private String serviceStatus;
    private Long subscriptionId;
    private String subscriptionName;
    private String requestId;
    private String requestMethod;
    private LocalDateTime occurredAt;
    private Long durationMs;
    private Boolean success;
    private Integer httpStatus;
    private String errorCode;
    private String errorMessage;
    private String clientIp;
    private String userAgent;
    private Boolean cacheEnabled;
    private Boolean cacheHit;
    private Long rowCount;
}
