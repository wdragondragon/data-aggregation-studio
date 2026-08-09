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
@TableName(value = "file_transfer_run_item", autoResultMap = true)
public class FileTransferRunItemEntity extends BaseProjectTenantEntity {
    private Long runId;
    private String coreItemId;
    private String direction;
    private String channel;
    private Long runtimeClusterId;
    private Long sourceRuntimeClusterId;
    private Long sourceDatasourceId;
    private String sourcePath;
    private Long targetRuntimeClusterId;
    private Long targetDatasourceId;
    private String targetPath;
    private String temporaryPath;
    private String status;
    private Long fileSize;
    private Long transferredBytes;
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

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> sourceSnapshotJson = new LinkedHashMap<String, Object>();
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> checkpointJson = new LinkedHashMap<String, Object>();
}
