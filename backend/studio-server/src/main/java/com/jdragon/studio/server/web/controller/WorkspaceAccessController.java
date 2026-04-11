package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.WorkspaceAccessOverviewView;
import com.jdragon.studio.dto.model.WorkspaceAccessRequestView;
import com.jdragon.studio.dto.model.request.WorkspaceAccessApplyRequest;
import com.jdragon.studio.infra.service.WorkspaceAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Workspace Access", description = "Access center APIs for users without project context")
@RestController
@RequestMapping("/api/v1/access")
public class WorkspaceAccessController {

    private final WorkspaceAccessService workspaceAccessService;

    public WorkspaceAccessController(WorkspaceAccessService workspaceAccessService) {
        this.workspaceAccessService = workspaceAccessService;
    }

    @Operation(summary = "Load access center overview")
    @GetMapping("/overview")
    public Result<WorkspaceAccessOverviewView> overview() {
        return Result.success(workspaceAccessService.overview());
    }

    @Operation(summary = "Submit project access request")
    @PostMapping("/project-requests")
    public Result<WorkspaceAccessRequestView> apply(@RequestBody WorkspaceAccessApplyRequest request) {
        return Result.success(workspaceAccessService.apply(request));
    }

    @Operation(summary = "Cancel own pending access request")
    @PostMapping("/project-requests/{id}/cancel")
    public Result<WorkspaceAccessRequestView> cancel(@PathVariable("id") Long id) {
        return Result.success(workspaceAccessService.cancel(id));
    }
}
