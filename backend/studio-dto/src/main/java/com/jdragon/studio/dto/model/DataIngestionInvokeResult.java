package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class DataIngestionInvokeResult {
    private String requestId;
    private String serviceCode;
    private Long receivedCount;
    private Long successCount;
    private Long failedCount;
    private String status;
}
