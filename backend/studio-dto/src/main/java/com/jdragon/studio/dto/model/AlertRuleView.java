package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AlertRuleView {
    private Long id;
    private String name;
    private String description;
    private String ruleType;
    private String subjectType;
    private Long subjectId;
    private String subjectName;
    private String severity;
    private boolean enabled;
    private Map<String, Object> condition = new LinkedHashMap<String, Object>();
    private Integer silenceMinutes;
    private boolean recoveryNotificationEnabled;
    private boolean inAppEnabled;
    private List<Long> recipientUserIds = new ArrayList<Long>();
    private boolean notifyResourceOwner;
    private boolean notifyProjectAdmins;
    private List<Long> webhookChannelIds = new ArrayList<Long>();
    private LocalDateTime activationAt;
    private LocalDateTime lastEvaluatedAt;
    private String lastEvaluationStatus;
    private String lastEvaluationError;
    private LocalDateTime lastTriggeredAt;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
