package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class WorkflowScheduleDefinition {
    private String cronExpression;
    private Boolean enabled;
    private String timezone;
}

