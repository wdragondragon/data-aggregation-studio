package com.jdragon.studio.infra.model;

import lombok.Data;

@Data
public class OpenServiceDashboardBucketSummary {
    private String bucketKey;
    private Long accessCount;
    private Long successCount;
    private Long failureCount;
    private Long rowCount;
    private Long receivedCount;
    private Long writtenCount;
    private Long failedCount;
    private Long cacheEnabledCount;
    private Long cacheHitCount;
    private Long cacheMissCount;
    private Long cacheDisabledCount;
    private Long minResponseTimeMs;
    private Long maxResponseTimeMs;
    private Long avgResponseTimeMs;
    private Long p95ResponseTimeMs;
    private Long p99ResponseTimeMs;
}
