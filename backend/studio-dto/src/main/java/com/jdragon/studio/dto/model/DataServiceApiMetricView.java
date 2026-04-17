package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DataServiceApiMetricView {
    private Long serviceId;
    private String serviceName;
    private String serviceCode;
    private String status;
    private Long subscriptionId;
    private String subscriptionName;
    private Long accessCount;
    private Long successCount;
    private Long failureCount;
    private Double successRate;
    private Long minResponseTimeMs;
    private Long maxResponseTimeMs;
    private Long avgResponseTimeMs;
    private Long p95ResponseTimeMs;
    private Long p99ResponseTimeMs;
    private LocalDateTime lastAccessAt;
    private Long cacheEnabledCount;
    private Long cacheHitCount;
    private Long cacheMissCount;
    private Long cacheDisabledCount;
    private Double cacheHitRate;
}
