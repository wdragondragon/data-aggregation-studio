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
@TableName(value = "stream_task_event", autoResultMap = true)
public class StreamTaskEventEntity extends BaseProjectTenantEntity {
    private Long collectionTaskId;
    private Long deploymentId;
    private Long runId;
    private Long attemptId;
    private Long generation;
    private String eventType;
    private String fromState;
    private String toState;
    private String message;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detailsJson = new LinkedHashMap<String, Object>();

    private Long actorId;
    private LocalDateTime occurredAt;
}
