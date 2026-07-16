package com.jdragon.studio.infra.model;

import lombok.Data;

@Data
public class AlertProjectSummaryAggregate {
    private Long projectId;
    private Long enabledRuleCount;
    private Long openIncidentCount;
    private Long criticalIncidentCount;
    private Long failedDeliveryCount;
}
