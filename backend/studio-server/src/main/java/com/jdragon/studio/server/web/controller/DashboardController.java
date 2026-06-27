package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.StudioDashboardView;
import com.jdragon.studio.infra.service.StudioDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Dashboard", description = "Studio dashboard overview APIs")
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final StudioDashboardService studioDashboardService;

    public DashboardController(StudioDashboardService studioDashboardService) {
        this.studioDashboardService = studioDashboardService;
    }

    @Operation(summary = "Get Studio dashboard overview")
    @GetMapping("/overview")
    public Result<StudioDashboardView> overview() {
        return Result.success(studioDashboardService.overview());
    }
}
