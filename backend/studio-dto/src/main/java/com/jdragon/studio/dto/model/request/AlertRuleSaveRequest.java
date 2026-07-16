package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AlertRuleSaveRequest {
    private Long id;
    @NotBlank
    private String name;
    private String description;
    @NotBlank
    private String ruleType;
    @NotBlank
    private String subjectType;
    private Long subjectId;
    @NotBlank
    private String severity;
    private Boolean enabled;
    private Map<String, Object> condition = new LinkedHashMap<String, Object>();
    private Integer silenceMinutes;
    private Boolean recoveryNotificationEnabled;
    private Boolean inAppEnabled;
    private List<Long> recipientUserIds = new ArrayList<Long>();
    private Boolean notifyResourceOwner;
    private Boolean notifyProjectAdmins;
    private List<Long> webhookChannelIds = new ArrayList<Long>();
}
