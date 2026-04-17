package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataServiceAccessLogView;
import com.jdragon.studio.dto.model.DataServiceApiMetricView;
import com.jdragon.studio.dto.model.DataServiceMetricDashboardView;
import com.jdragon.studio.dto.model.DataServiceMetricOptionsView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.DataServiceMetricQueryRequest;
import com.jdragon.studio.infra.service.DataServiceMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/data-service-metrics")
public class DataServiceMetricsController {

    private final DataServiceMetricsService dataServiceMetricsService;

    public DataServiceMetricsController(DataServiceMetricsService dataServiceMetricsService) {
        this.dataServiceMetricsService = dataServiceMetricsService;
    }

    @GetMapping("/options")
    public Result<DataServiceMetricOptionsView> options() {
        return Result.success(dataServiceMetricsService.options());
    }

    @PostMapping("/dashboard/query")
    public Result<DataServiceMetricDashboardView> queryDashboard(@RequestBody(required = false) DataServiceMetricQueryRequest request) {
        return Result.success(dataServiceMetricsService.queryDashboard(request));
    }

    @PostMapping("/api-stats/query")
    public Result<PageView<DataServiceApiMetricView>> queryApiStats(@RequestBody(required = false) DataServiceMetricQueryRequest request) {
        return Result.success(dataServiceMetricsService.queryApiStats(request));
    }

    @PostMapping("/access-logs/query")
    public Result<PageView<DataServiceAccessLogView>> queryAccessLogs(@RequestBody(required = false) DataServiceMetricQueryRequest request) {
        return Result.success(dataServiceMetricsService.queryAccessLogs(request));
    }
}
