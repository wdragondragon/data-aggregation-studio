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
@TableName(value = "stream_task_run", autoResultMap = true)
public class StreamTaskRunEntity extends BaseProjectTenantEntity {
    private Long collectionTaskId;
    private Long generation;
    private Long runtimeClusterId;
    private String status;
    private String deliverySemantics;
    private String groupId;
    private Long startedBy;
    private LocalDateTime startedAt;
    private LocalDateTime stopRequestedAt;
    private Long stoppedBy;
    private LocalDateTime stoppedAt;
    private String stopReason;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> finalCheckpointJson = new LinkedHashMap<String, Object>();
}
