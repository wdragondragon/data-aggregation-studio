package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class WorkflowListView extends BaseDefinition {
    private String code;
    private String name;
    private Long versionId;
    private Integer versionNumber;
    private Boolean published;
    private WorkflowScheduleDefinition schedule;
}
