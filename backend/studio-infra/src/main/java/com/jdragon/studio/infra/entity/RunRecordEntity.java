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
public class RunRecordEntity extends BaseTenantEntity {
    private String executionType;
    private Long workflowRunId;
    private Long workflowDefinitionId;
    private Long workflowVersionId;
    private Long collectionTaskId;
    private String nodeCode;
    private String status;
    private String workerCode;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String logFilePath;
    private Long logSizeBytes;
    private String logCharset;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resultJson = new LinkedHashMap<String, Object>();
}
