package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FileTransferMetricDashboardView;
import com.jdragon.studio.dto.model.request.FileTransferMetricQueryRequest;
import com.jdragon.studio.infra.service.FileTransferMetricService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/file-transfer-metrics")
public class FileTransferMetricController {

    private final FileTransferMetricService metricService;

    public FileTransferMetricController(FileTransferMetricService metricService) {
        this.metricService = metricService;
    }

    @PostMapping("/dashboard")
    public Result<FileTransferMetricDashboardView> dashboard(
            @RequestBody(required = false) FileTransferMetricQueryRequest request) {
        return Result.success(metricService.dashboard(request));
    }
}
