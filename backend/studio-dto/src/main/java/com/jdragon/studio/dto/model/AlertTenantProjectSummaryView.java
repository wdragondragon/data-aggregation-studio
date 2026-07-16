package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class AlertTenantProjectSummaryView {
    private Long projectId;
    private String projectName;
    private long enabledRuleCount;
    private long openIncidentCount;
    private long criticalIncidentCount;
    private long failedDeliveryCount;
}
