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
@TableName(value = "stream_task_attempt", autoResultMap = true)
public class StreamTaskAttemptEntity extends BaseProjectTenantEntity {
    private Long runId;
    private Long collectionTaskId;
    private Long generation;
    private Integer attemptNo;
    private Long dispatchTaskId;
    private Long runRecordId;
    private Long runtimeClusterId;
    private String workerInstanceId;
    private String workerBootId;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime heartbeatAt;
    private LocalDateTime retryAfter;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> checkpointJson = new LinkedHashMap<String, Object>();

    private String errorCode;
    private String errorSummary;
    private Long committedBatchCount;
}
