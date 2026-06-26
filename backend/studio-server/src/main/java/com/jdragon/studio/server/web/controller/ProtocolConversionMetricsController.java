package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataServiceMetricOptionsView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.ProtocolConversionAccessLogListView;
import com.jdragon.studio.dto.model.ProtocolConversionTraceView;
import com.jdragon.studio.dto.model.request.ProtocolConversionMetricQueryRequest;
import com.jdragon.studio.infra.service.ProtocolConversionMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/protocol-conversion-metrics")
public class ProtocolConversionMetricsController {

    private final ProtocolConversionMetricsService protocolConversionMetricsService;

    public ProtocolConversionMetricsController(ProtocolConversionMetricsService protocolConversionMetricsService) {
        this.protocolConversionMetricsService = protocolConversionMetricsService;
    }

    @GetMapping("/options")
    public Result<DataServiceMetricOptionsView> options() {
        return Result.success(protocolConversionMetricsService.options());
    }

    @PostMapping("/access-logs/query")
    public Result<PageView<ProtocolConversionAccessLogListView>> queryAccessLogs(@RequestBody(required = false) ProtocolConversionMetricQueryRequest request) {
        return Result.success(protocolConversionMetricsService.queryAccessLogs(request));
    }

    @GetMapping("/access-logs/{id}/trace")
    public Result<ProtocolConversionTraceView> accessLogTrace(@PathVariable("id") Long id) {
        return Result.success(protocolConversionMetricsService.accessLogTrace(id));
    }
}
