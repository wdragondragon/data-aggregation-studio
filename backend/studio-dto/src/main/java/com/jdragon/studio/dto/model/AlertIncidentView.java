package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AlertIncidentView {
    private Long id;
    private Long ruleId;
    private String ruleName;
    private String ruleType;
    private String subjectType;
    private String subjectKey;
    private Long subjectId;
    private String subjectName;
    private String targetPath;
    private String severity;
    private String status;
    private String summary;
    private Map<String, Object> evidence = new LinkedHashMap<String, Object>();
    private Integer occurrenceCount;
    private Integer notificationCount;
    private Integer reopenCount;
    private boolean conditionActive;
    private boolean closedWhileActive;
    private LocalDateTime firstTriggeredAt;
    private LocalDateTime lastTriggeredAt;
    private LocalDateTime lastNotifiedAt;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime recoveredAt;
    private LocalDateTime closedAt;
    private Long acknowledgedBy;
    private Long closedBy;
    private List<AlertEventView> recentEvents = new ArrayList<AlertEventView>();
    private List<AlertDeliveryView> recentDeliveries = new ArrayList<AlertDeliveryView>();
}
