package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Native streaming collection task options")
public class CollectionTaskStreamingOptions {
    private String groupId;
    private String offsetReset = "latest";
    private Boolean resetOffset = Boolean.FALSE;
    private Integer pollTimeoutMs = 1000;
    private Integer maxBatchRecords = 1000;
    private Long maxBatchBytes = 16L * 1024L * 1024L;
    private Integer batchRetryCount = 3;
    private Long stopTimeoutMs = 60000L;
    private Integer maxConsecutiveFailures = 10;
    private Long retryInitialDelayMs = 5000L;
    private Long retryMaxDelayMs = 300000L;
}
