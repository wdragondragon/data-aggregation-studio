package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "stream_task_deploy", autoResultMap = true)
public class StreamTaskDeployEntity extends BaseProjectTenantEntity {
    private Long collectionTaskId;
    private Long runtimeClusterId;
    private Long generation;
    private String desiredState;
    private String observedState;
    private Long currentRunId;
    private Long currentAttemptId;
    private Integer consecutiveFailureCount;
    private LocalDateTime nextRetryAt;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> lastCheckpointJson = new LinkedHashMap<String, Object>();

    private LocalDateTime lastCheckpointAt;
    private String lastErrorCode;
    private String lastErrorSummary;

    @Version
    private Integer version;
}
