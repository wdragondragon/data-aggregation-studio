package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("run_log_chunk")
public class RunLogChunkEntity extends BaseProjectTenantEntity {
    private Long collectionTaskId;
    private Long runRecordId;
    private Long streamAttemptId;
    private Integer sequenceNo;
    private String status;
    private String localPath;
    private String storageType;
    private String objectBucket;
    private String objectKey;
    private Long sizeBytes;
    private String checksumSha256;
    private LocalDateTime chunkStartedAt;
    private LocalDateTime chunkEndedAt;
    private LocalDateTime uploadedAt;
}
