package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AlertOptionsView {
    private List<AlertOptionView> ruleTypes = new ArrayList<AlertOptionView>();
    private List<String> severities = new ArrayList<String>();
    private List<String> incidentStatuses = new ArrayList<String>();
    private List<String> deliveryStatuses = new ArrayList<String>();
    private boolean elinkChannelEnabled;
    private boolean canManage;
    private boolean canHandleIncidents;
    private boolean canViewTenantSummary;
}
