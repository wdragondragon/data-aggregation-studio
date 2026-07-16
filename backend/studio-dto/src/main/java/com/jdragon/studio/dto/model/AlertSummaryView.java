package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class AlertSummaryView {
    private long enabledRuleCount;
    private long openIncidentCount;
    private long acknowledgedIncidentCount;
    private long criticalIncidentCount;
    private long failedDeliveryCount;
}
