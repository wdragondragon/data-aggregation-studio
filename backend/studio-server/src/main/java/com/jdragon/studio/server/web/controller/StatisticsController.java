package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataModelStatisticsChartView;
import com.jdragon.studio.dto.model.DataModelStatisticsOptionsView;
import com.jdragon.studio.dto.model.request.DataModelStatisticsChartRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsOptionsRequest;
import com.jdragon.studio.infra.service.DataModelStatisticsWorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Statistics", description = "Statistics workspace APIs")
@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    private final DataModelStatisticsWorkspaceService dataModelStatisticsWorkspaceService;

    public StatisticsController(DataModelStatisticsWorkspaceService dataModelStatisticsWorkspaceService) {
        this.dataModelStatisticsWorkspaceService = dataModelStatisticsWorkspaceService;
    }

    @Operation(summary = "List statistics workspace options")
    @PostMapping("/options")
    public Result<DataModelStatisticsOptionsView> options(@RequestBody(required = false) DataModelStatisticsOptionsRequest request) {
        return Result.success(dataModelStatisticsWorkspaceService.options(request));
    }

    @Operation(summary = "Query statistics chart data")
    @PostMapping("/charts/query")
    public Result<DataModelStatisticsChartView> queryChart(@RequestBody DataModelStatisticsChartRequest request) {
        return Result.success(dataModelStatisticsWorkspaceService.queryChart(request));
    }
}
