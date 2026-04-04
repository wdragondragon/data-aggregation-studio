package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.dto.model.RunListView;
import com.jdragon.studio.dto.model.RunRecordView;
import com.jdragon.studio.infra.service.RunService;
import com.jdragon.studio.server.web.service.RunLogProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Tag(name = "Runs", description = "Workflow run and dispatch APIs")
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunService runService;
    private final RunLogProxyService runLogProxyService;

    public RunController(RunService runService, RunLogProxyService runLogProxyService) {
        this.runService = runService;
        this.runLogProxyService = runLogProxyService;
    }

    @Operation(summary = "List queued tasks and run records")
    @GetMapping
    public Result<RunListView> list(@RequestParam(value = "collectionTaskId", required = false) Long collectionTaskId,
                                    @RequestParam(value = "workflowDefinitionId", required = false) Long workflowDefinitionId,
                                    @RequestParam(value = "startTime", required = false)
                                    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                                    @RequestParam(value = "endTime", required = false)
                                    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return Result.success(runService.list(collectionTaskId, workflowDefinitionId, startTime, endTime));
    }

    @Operation(summary = "Get run record detail")
    @GetMapping("/{id}")
    public Result<RunRecordView> get(@PathVariable("id") Long id) {
        return Result.success(runService.get(id));
    }

    @Operation(summary = "Get run log tail")
    @GetMapping("/{id}/log")
    public Result<RunLogView> log(@PathVariable("id") Long id) {
        return Result.success(runLogProxyService.viewLog(id));
    }

    @Operation(summary = "Download full run log")
    @GetMapping("/{id}/log/download")
    public Result<RunLogView> download(@PathVariable("id") Long id) {
        return Result.success(runLogProxyService.downloadLog(id));
    }
}
