package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.RunMetricDashboardView;
import com.jdragon.studio.dto.model.RunMetricOptionsView;
import com.jdragon.studio.dto.model.request.RunMetricDashboardQueryRequest;
import com.jdragon.studio.infra.service.RunMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Run Metrics", description = "Collection task run metric monitoring APIs")
@RestController
@RequestMapping("/api/v1/run-metrics")
public class RunMetricsController {

    private final RunMetricsService runMetricsService;

    public RunMetricsController(RunMetricsService runMetricsService) {
        this.runMetricsService = runMetricsService;
    }

    @Operation(summary = "List metric dashboard filter options")
    @GetMapping("/options")
    public Result<RunMetricOptionsView> options() {
        return Result.success(runMetricsService.options());
    }

    @Operation(summary = "Query collection task run metric dashboard")
    @PostMapping("/query")
    public Result<RunMetricDashboardView> query(@RequestBody(required = false) RunMetricDashboardQueryRequest request) {
        return Result.success(runMetricsService.query(request));
    }
}
