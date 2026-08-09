package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileTransferMetricPointView {
    private LocalDateTime sampledAt;
    private Long transferredBytes;
    private Long bytesPerSecond;
    private Long completedFiles;
    private Long failedFiles;
    private Integer activeFiles;
    private Integer retryCount;
}
