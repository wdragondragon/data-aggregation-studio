package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataIngestionAccessLogView;
import com.jdragon.studio.dto.model.DataIngestionApiMetricView;
import com.jdragon.studio.dto.model.DataIngestionMetricDashboardView;
import com.jdragon.studio.dto.model.DataServiceMetricOptionsView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.DataIngestionMetricQueryRequest;
import com.jdragon.studio.infra.service.DataIngestionMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/data-ingestion-metrics")
public class DataIngestionMetricsController {

    private final DataIngestionMetricsService dataIngestionMetricsService;

    public DataIngestionMetricsController(DataIngestionMetricsService dataIngestionMetricsService) {
        this.dataIngestionMetricsService = dataIngestionMetricsService;
    }

    @GetMapping("/options")
    public Result<DataServiceMetricOptionsView> options() {
        return Result.success(dataIngestionMetricsService.options());
    }

    @PostMapping("/dashboard/query")
    public Result<DataIngestionMetricDashboardView> queryDashboard(@RequestBody(required = false) DataIngestionMetricQueryRequest request) {
        return Result.success(dataIngestionMetricsService.queryDashboard(request));
    }

    @PostMapping("/api-stats/query")
    public Result<PageView<DataIngestionApiMetricView>> queryApiStats(@RequestBody(required = false) DataIngestionMetricQueryRequest request) {
        return Result.success(dataIngestionMetricsService.queryApiStats(request));
    }

    @PostMapping("/access-logs/query")
    public Result<PageView<DataIngestionAccessLogView>> queryAccessLogs(@RequestBody(required = false) DataIngestionMetricQueryRequest request) {
        return Result.success(dataIngestionMetricsService.queryAccessLogs(request));
    }
}
