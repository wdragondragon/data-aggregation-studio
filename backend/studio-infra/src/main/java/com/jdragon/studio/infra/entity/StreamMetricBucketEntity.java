package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stream_metric_bucket")
public class StreamMetricBucketEntity extends BaseProjectTenantEntity {
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
