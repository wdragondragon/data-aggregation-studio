package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.request.WorkflowSaveRequest;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@Tag(name = "Workflows", description = "Workflow orchestration APIs")
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final DispatchService dispatchService;

    public WorkflowController(WorkflowService workflowService, DispatchService dispatchService) {
        this.workflowService = workflowService;
        this.dispatchService = dispatchService;
    }

    @Operation(summary = "List workflows")
    @GetMapping
    public Result<List<WorkflowDefinitionView>> list() {
        return Result.success(workflowService.list());
    }

    @Operation(summary = "Get workflow detail")
    @GetMapping("/{id}")
    public Result<WorkflowDefinitionView> get(@PathVariable("id") Long id) {
        return Result.success(workflowService.get(id));
    }

    @Operation(summary = "Save workflow draft")
    @PostMapping
    public Result<WorkflowDefinitionView> save(@Valid @RequestBody WorkflowSaveRequest request) {
        return Result.success(workflowService.save(request));
    }

    @Operation(summary = "Publish workflow")
    @PostMapping("/{id}/publish")
    public Result<WorkflowDefinitionView> publish(@PathVariable("id") Long id) {
        return Result.success(workflowService.publish(id));
    }

    @Operation(summary = "Trigger workflow execution")
    @PostMapping("/{id}/trigger")
    public Result<WorkflowDefinitionView> trigger(@PathVariable("id") Long id) {
        dispatchService.triggerManualRun(id);
        return Result.success(workflowService.get(id));
    }
}
