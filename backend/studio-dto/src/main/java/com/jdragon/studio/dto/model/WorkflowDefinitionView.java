package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
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

