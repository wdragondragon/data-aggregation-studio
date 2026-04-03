package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.infra.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Schedules", description = "Workflow schedule APIs")
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final WorkflowService workflowService;

    public ScheduleController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Operation(summary = "List workflow schedules")
    @GetMapping
    public Result<List<WorkflowDefinitionView>> list() {
        return Result.success(workflowService.list());
    }
}
