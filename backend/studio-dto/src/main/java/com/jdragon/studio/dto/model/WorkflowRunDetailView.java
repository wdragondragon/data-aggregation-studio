package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class WorkflowRunDetailView extends WorkflowRunSummaryView {
    private WorkflowDefinitionView workflow;
    private List<WorkflowNodeRunView> nodeRuns = new ArrayList<WorkflowNodeRunView>();
}
