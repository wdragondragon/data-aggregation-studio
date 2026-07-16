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
@TableName(value = "studio_alert_event", autoResultMap = true)
public class AlertEventEntity extends BaseProjectTenantEntity {
    private Long incidentId;
    private Long ruleId;
    private String eventType;
    private String statusFrom;
    private String statusTo;
    private String sourceType;
    private String sourceId;
    private String sourceEventKey;
    private String subjectType;
    private String subjectKey;
    private Long subjectId;
    private String subjectNameSnapshot;
    private String targetPath;
    private String severity;
    private String summary;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> evidenceJson = new LinkedHashMap<String, Object>();

    private Long actorUserId;
    private String actorNameSnapshot;
    private LocalDateTime observedAt;
}
