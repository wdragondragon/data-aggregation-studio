package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "studio_alert_rule", autoResultMap = true)
public class AlertRuleEntity extends BaseProjectTenantEntity {
    private String name;
    private String description;
    private String ruleType;
    private String subjectType;
    private Long subjectId;
    private String subjectNameSnapshot;
    private String severity;
    private Integer enabled;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> conditionJson = new LinkedHashMap<String, Object>();

    private Integer silenceMinutes;
    private Integer recoveryNotificationEnabled;
    private Integer inAppEnabled;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> recipientUserIdsJson = new ArrayList<Long>();

    private Integer notifyResourceOwner;
    private Integer notifyProjectAdmins;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> webhookChannelIdsJson = new ArrayList<Long>();

    private LocalDateTime activationAt;
    private LocalDateTime lastEvaluatedAt;
    private String lastEvaluationStatus;
    private String lastEvaluationError;
    private LocalDateTime lastTriggeredAt;
    private Long createdBy;
    private Long updatedBy;
}
