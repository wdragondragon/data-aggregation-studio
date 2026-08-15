package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "file_transfer_run", autoResultMap = true)
public class FileTransferRunEntity extends BaseProjectTenantEntity {
    private Long runRecordId;
    private Long taskId;
    private String taskNameSnapshot;
    private String triggerType;
    private String direction;
    private String channel;
    private String status;
    private Boolean queueVisible;
    private Long runtimeClusterId;
    private Long sourceRuntimeClusterId;
    private Long sourceDatasourceId;
    private Long targetRuntimeClusterId;
    private Long targetDatasourceId;
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

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resolvedSpecJson = new LinkedHashMap<String, Object>();
}
