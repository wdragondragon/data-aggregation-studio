package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class FileTransferRunView extends BaseDefinition {
    private Long runRecordId;
    private Long taskId;
    private String taskName;
    private String triggerType;
    private String direction;
    private String channel;
    private String status;
    private Long runtimeClusterId;
    private Long sourceRuntimeClusterId;
    private String sourceRuntimeClusterName;
    private Long sourceDatasourceId;
    private String sourceDatasourceName;
    private Long targetRuntimeClusterId;
    private String targetRuntimeClusterName;
    private Long targetDatasourceId;
    private String targetDatasourceName;
    private Long totalFiles;
    private Long successFiles;
    private Long skippedFiles;
    private Long failedFiles;
    private Long conflictFiles;
    private Long resumedFiles;
    private Long postActionFailedFiles;
    private Long totalBytes;
    private Long transferredBytes;
    private Long failedBytes;
    private Long resumedBytes;
    private Long currentBytesPerSecond;
    private Long peakBytesPerSecond;
    private Integer activeFiles;
    private Integer retryCount;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Map<String, Object> resolvedSpec = new LinkedHashMap<String, Object>();
}
