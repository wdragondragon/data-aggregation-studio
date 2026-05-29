package com.jdragon.studio.infra.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DataIngestionAccessCounterSummary {
    private LocalDateTime bucketStart;
    private Long accessCount;
    private Long successCount;
    private Long failureCount;
    private Long receivedCount;
    private Long writtenCount;
    private Long failedCount;
}
