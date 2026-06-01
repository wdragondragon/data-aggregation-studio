package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.OpsCenterLogEventView;
import com.jdragon.studio.dto.model.OpsCenterOptionsView;
import com.jdragon.studio.dto.model.OpsCenterOverviewView;
import com.jdragon.studio.dto.model.OpsCenterQueueItemView;
import com.jdragon.studio.dto.model.OpsCenterRunIncidentView;
import com.jdragon.studio.dto.model.OpsCenterServiceEventView;
import com.jdragon.studio.dto.model.OpsCenterWorkerGroupView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.OpsCenterQueryRequest;
import com.jdragon.studio.infra.service.OpsCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Ops Center", description = "Unified operational dashboard APIs")
@RestController
@RequestMapping("/api/v1/ops-center")
public class OpsCenterController {

    private final OpsCenterService opsCenterService;

    public OpsCenterController(OpsCenterService opsCenterService) {
        this.opsCenterService = opsCenterService;
    }

    @Operation(summary = "List ops center filter options")
    @GetMapping("/options")
    public Result<OpsCenterOptionsView> options() {
        return Result.success(opsCenterService.options());
    }

    @Operation(summary = "Query unified operational overview")
    @PostMapping("/overview/query")
    public Result<OpsCenterOverviewView> queryOverview(@RequestBody(required = false) OpsCenterQueryRequest request) {
        return Result.success(opsCenterService.overview(request));
    }

    @Operation(summary = "Query run incidents")
    @PostMapping("/runs/query")
    public Result<PageView<OpsCenterRunIncidentView>> queryRuns(@RequestBody(required = false) OpsCenterQueryRequest request) {
        return Result.success(opsCenterService.queryRuns(request));
    }

    @Operation(summary = "Query dispatch queue")
    @PostMapping("/queue/query")
    public Result<PageView<OpsCenterQueueItemView>> queryQueue(@RequestBody(required = false) OpsCenterQueryRequest request) {
        return Result.success(opsCenterService.queryQueue(request));
    }

    @Operation(summary = "Query worker groups")
    @PostMapping("/workers/query")
    public Result<PageView<OpsCenterWorkerGroupView>> queryWorkers(@RequestBody(required = false) OpsCenterQueryRequest request) {
        return Result.success(opsCenterService.queryWorkers(request));
    }

    @Operation(summary = "Query data service operational events")
    @PostMapping("/service-events/query")
    public Result<PageView<OpsCenterServiceEventView>> queryServiceEvents(@RequestBody(required = false) OpsCenterQueryRequest request) {
        return Result.success(opsCenterService.queryServiceEvents(request));
    }

    @Operation(summary = "Query data ingestion operational events")
    @PostMapping("/ingestion-events/query")
    public Result<PageView<OpsCenterServiceEventView>> queryIngestionEvents(@RequestBody(required = false) OpsCenterQueryRequest request) {
        return Result.success(opsCenterService.queryIngestionEvents(request));
    }

    @Operation(summary = "Query run log events")
    @PostMapping("/log-events/query")
    public Result<PageView<OpsCenterLogEventView>> queryLogEvents(@RequestBody(required = false) OpsCenterQueryRequest request) {
        return Result.success(opsCenterService.queryLogEvents(request));
    }
}
