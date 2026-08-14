package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileTransferRunItemView {
    private Long id;
    private Long runId;
    private String coreItemId;
    private String direction;
    private String channel;
    private Long runtimeClusterId;
    private Long sourceRuntimeClusterId;
    private String sourceRuntimeClusterName;
    private Long sourceDatasourceId;
    private String sourceDatasourceName;
    private String sourcePath;
    private Long targetRuntimeClusterId;
    private String targetRuntimeClusterName;
    private Long targetDatasourceId;
    private String targetDatasourceName;
    private String targetPath;
    private String temporaryPath;
    private String status;
    private Long fileSize;
    private Long transferredBytes;
    private Long observedBytes;
    private Boolean live;
    private String resumePhase;
    private Long resumeCheckedBytes;
    private Long resumeTotalBytes;
    private Long resumedBytes;
    private Long currentBytesPerSecond;
    private String sourceChecksum;
    private String targetChecksum;
    private Integer attempts;
    private String errorCode;
    private String errorMessage;
    private String conflictAction;
    private String sourceAction;
    private String postActionStatus;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime updatedAt;
}
