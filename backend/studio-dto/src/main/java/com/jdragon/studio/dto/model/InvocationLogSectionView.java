package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class InvocationLogSectionView {
    private String sectionKey;
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
    private Long sizeBytes;
    private String archiveStatus;
    private String archiveError;
}
