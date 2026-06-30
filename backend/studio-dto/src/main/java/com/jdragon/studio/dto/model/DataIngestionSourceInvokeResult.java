package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class DataIngestionSourceInvokeResult {
    private String sourceCode;
    private String sourceName;
    private String targetDatasourceName;
    private String targetModelName;
    private Long receivedCount;
    private Long successCount;
    private Long failedCount;
    private String status;
    private String message;
    private Long jobId;
}
