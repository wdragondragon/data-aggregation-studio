package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.StudioDashboardView;
import com.jdragon.studio.dto.model.WorkflowRunSummaryView;
import org.springframework.stereotype.Service;

@Service
public class StudioDashboardService {

    private static final int RECENT_WORKFLOW_LIMIT = 5;
    private static final int RECENT_SCRIPT_LIMIT = 4;
    private static final int RECENT_WORKFLOW_RUN_LIMIT = 6;

    private final DataSourceService dataSourceService;
    private final WorkflowService workflowService;
    private final CollectionTaskService collectionTaskService;
    private final DataDevelopmentService dataDevelopmentService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final RunService runService;
    private final WorkflowRunService workflowRunService;

    public StudioDashboardService(DataSourceService dataSourceService,
                                  WorkflowService workflowService,
                                  CollectionTaskService collectionTaskService,
                                  DataDevelopmentService dataDevelopmentService,
                                  DatasourceTypeCapabilityService datasourceTypeCapabilityService,
                                  RunService runService,
                                  WorkflowRunService workflowRunService) {
        this.dataSourceService = dataSourceService;
        this.workflowService = workflowService;
        this.collectionTaskService = collectionTaskService;
        this.dataDevelopmentService = dataDevelopmentService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.runService = runService;
        this.workflowRunService = workflowRunService;
    }

    public StudioDashboardView overview() {
        StudioDashboardView view = new StudioDashboardView();
        view.setDatasourceCount(dataSourceService.countSummaries());
        view.setPublishedWorkflowCount(workflowService.countPublishedSummaries());
        view.setScheduledWorkflowCount(workflowService.countScheduledSummaries());
        view.setOnlineCollectionTaskCount(collectionTaskService.countOnlineSummaries());
        view.setQueuedTaskCount(runService.countQueuedTasks());
        view.setScriptTypeCounts(dataDevelopmentService.scriptTypeCounts());
        view.setExecutableDatasourceTypes(datasourceTypeCapabilityService.executableDatasourceTypes());
        view.setRecentWorkflowDefinitions(workflowService.listRecentSummaries(RECENT_WORKFLOW_LIMIT));
        view.setRecentScripts(dataDevelopmentService.listRecentScriptSummaries(RECENT_SCRIPT_LIMIT));
        PageView<WorkflowRunSummaryView> workflowRuns = workflowRunService.list(null, null, null, null, 1, RECENT_WORKFLOW_RUN_LIMIT);
        view.setRecentWorkflowRuns(workflowRuns.getItems());
        return view;
    }
}
