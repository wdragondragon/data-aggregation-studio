package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RunLogChunkView {
    private Long id;
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
