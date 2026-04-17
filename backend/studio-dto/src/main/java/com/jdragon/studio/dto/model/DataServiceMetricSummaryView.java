package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DataServiceMetricSummaryView {
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
    private Boolean counterBacked;
}
