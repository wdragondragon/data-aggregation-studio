package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.WorkflowRunDetailView;
import com.jdragon.studio.dto.model.WorkflowRunSummaryView;
import com.jdragon.studio.infra.service.WorkflowRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
@Tag(name = "Workflow Runs", description = "Workflow run log and detail APIs")
@RestController
@RequestMapping("/api/v1/workflow-runs")
public class WorkflowRunController {

    private final WorkflowRunService workflowRunService;

    public WorkflowRunController(WorkflowRunService workflowRunService) {
        this.workflowRunService = workflowRunService;
    }

    @Operation(summary = "List workflow runs")
    @GetMapping
    public Result<PageView<WorkflowRunSummaryView>> list(@RequestParam(value = "workflowDefinitionId", required = false) Long workflowDefinitionId,
                                                         @RequestParam(value = "status", required = false) String status,
                                                         @RequestParam(value = "startTime", required = false)
                                                         @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                                                         @RequestParam(value = "endTime", required = false)
                                                         @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
                                                         @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                         @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        return Result.success(workflowRunService.list(workflowDefinitionId, status, startTime, endTime, pageNo, pageSize));
    }

    @Operation(summary = "Get workflow run detail")
    @GetMapping("/{workflowRunId}")
    public Result<WorkflowRunDetailView> get(@PathVariable("workflowRunId") Long workflowRunId) {
        return Result.success(workflowRunService.get(workflowRunId));
    }
}
