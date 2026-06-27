package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class StudioDashboardView {
    private Long datasourceCount = 0L;
    private Long publishedWorkflowCount = 0L;
    private Long scheduledWorkflowCount = 0L;
    private Long onlineCollectionTaskCount = 0L;
    private Long queuedTaskCount = 0L;
    private Map<String, Long> scriptTypeCounts = new LinkedHashMap<String, Long>();
    private List<String> executableDatasourceTypes = new ArrayList<String>();
    private List<WorkflowListView> recentWorkflowDefinitions = new ArrayList<WorkflowListView>();
    private List<WorkflowRunSummaryView> recentWorkflowRuns = new ArrayList<WorkflowRunSummaryView>();
    private List<DataDevelopmentScriptListView> recentScripts = new ArrayList<DataDevelopmentScriptListView>();
}
