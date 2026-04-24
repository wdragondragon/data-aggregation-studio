package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class WorkflowDefinitionView extends BaseDefinition {
    private String code;
    private String name;
    private Long versionId;
    private Integer versionNumber;
    private Boolean published;
    private WorkflowScheduleDefinition schedule;
    private List<WorkflowNodeDefinition> nodes = new ArrayList<WorkflowNodeDefinition>();
    private List<WorkflowEdgeDefinition> edges = new ArrayList<WorkflowEdgeDefinition>();
}

