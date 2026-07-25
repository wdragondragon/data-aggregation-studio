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
@TableName(value = "run_record", autoResultMap = true)
public class RunRecordEntity extends BaseProjectTenantEntity {
    private String executionType;
    private Long workflowRunId;
    private Long workflowDefinitionId;
    private Long workflowVersionId;
    private Long collectionTaskId;
    private Long qualityTaskId;
    private Long triggeredByUserId;
    private String nodeCode;
    private String status;
    private Long requestedClusterId;
    private Long actualClusterId;
    private String actualClusterCode;
    private String workerGroupCode;
    private String workerCode;
    private String workerInstanceId;
    private String workerBootId;
    private String workerPodName;
    private String workerNodeName;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long collectedRecords;
    private Long readSucceedRecords;
    private Long readFailedRecords;
    private Long writeSucceedRecords;
    private Long writeFailedRecords;
    private Long failedRecords;
    private Long successRecords;
    private Long transformerTotalRecords;
    private Long transformerSuccessRecords;
    private Long transformerFailedRecords;
    private Long transformerFilterRecords;
    private String logFilePath;
    private Long logSizeBytes;
    private String logCharset;
    private String logStorageType;
    private String logObjectBucket;
    private String logObjectKey;
    private Integer logChunkCount;
    private String logStatus;
    private String logErrorSummary;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resultJson = new LinkedHashMap<String, Object>();
}
