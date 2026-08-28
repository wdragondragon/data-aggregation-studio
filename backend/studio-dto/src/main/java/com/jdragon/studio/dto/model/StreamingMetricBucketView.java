package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class StreamingMetricBucketView {
    private Long id;
    private Long collectionTaskId;
    private Long runId;
    private Long attemptId;
    private LocalDateTime bucketStart;
    private Long recordsRead;
    private Long writeSucceedRecords;
    private Long writeFailedRecords;
    private Long dirtyRecords;
    private Long bytesRead;
    private Long batchCount;
    private Long retryCount;
    private Long currentLag;
    private Long maxLag;
    private LocalDateTime lastMessageAt;
    private LocalDateTime lastCheckpointAt;
    private Long rebalanceCount;
}
