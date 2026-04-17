package com.jdragon.studio.infra.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DataServiceAccessCounterSummary {
    private LocalDateTime bucketStart;
    private Long accessCount;
    private Long successCount;
    private Long failureCount;
    private Long cacheEnabledCount;
    private Long cacheHitCount;
    private Long cacheMissCount;
    private Long cacheDisabledCount;
    private Long rowCount;
}
