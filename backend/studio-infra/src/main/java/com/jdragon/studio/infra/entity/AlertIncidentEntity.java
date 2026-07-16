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
@TableName(value = "studio_alert_incident", autoResultMap = true)
public class AlertIncidentEntity extends BaseProjectTenantEntity {
    private Long ruleId;
    private String ruleNameSnapshot;
    private String ruleType;
    private String signature;
    private String subjectType;
    private String subjectKey;
    private Long subjectId;
    private String subjectNameSnapshot;
    private String targetPath;
    private String severity;
    private String status;
    private String summary;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> currentEvidenceJson = new LinkedHashMap<String, Object>();

    private Integer occurrenceCount;
    private Integer notificationCount;
    private Integer reopenCount;
    private Integer conditionActive;
    private Integer closedWhileActive;
    private LocalDateTime firstTriggeredAt;
    private LocalDateTime lastTriggeredAt;
    private LocalDateTime lastNotifiedAt;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime recoveredAt;
    private LocalDateTime closedAt;
    private Long acknowledgedBy;
    private Long closedBy;

    @Version
    private Integer version;
}
