package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "dispatch_task", autoResultMap = true)
public class DispatchTaskEntity extends BaseProjectTenantEntity {
    private String executionType;
    private Long workflowRunId;
    private Long workflowDefinitionId;
    private Long workflowVersionId;
    private Long collectionTaskId;
    private Long qualityTaskId;
    private Long fileTransferTaskId;
    private Long fileTransferRunId;
    private Long triggeredByUserId;
    private Long runRecordId;
    private String nodeCode;
    private String status;
    private Integer terminationRequested;
    private Long targetClusterId;
    private String resourceRevision;
    private String claimToken;
    private String workerBootId;
    private String workerGroupCode;
    private String leaseOwner;
    private String workerInstanceId;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime scheduledFireTime;
    private Integer attempts;
    private Integer maxRetries;

    @JsonIgnore
    private String protectedPayloadCiphertext;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();
}
