package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.QualityAssetDetailView;
import com.jdragon.studio.dto.model.QualityAssetRiskView;
import com.jdragon.studio.dto.model.QualityIssueAssigneeOptionView;
import com.jdragon.studio.dto.model.QualityIssueDetailView;
import com.jdragon.studio.dto.model.QualityIssueView;
import com.jdragon.studio.dto.model.QualityMetricDashboardView;
import com.jdragon.studio.dto.model.QualityMetricOptionsView;
import com.jdragon.studio.dto.model.RunMetricFilterOptionView;
import com.jdragon.studio.dto.model.request.QualityAssetQueryRequest;
import com.jdragon.studio.dto.model.request.QualityIssueAssignRequest;
import com.jdragon.studio.dto.model.request.QualityIssueCommentRequest;
import com.jdragon.studio.dto.model.request.QualityIssueQueryRequest;
import com.jdragon.studio.dto.model.request.QualityIssueSeverityRequest;
import com.jdragon.studio.dto.model.request.QualityIssueStatusRequest;
import com.jdragon.studio.dto.model.request.QualityMetricDashboardQueryRequest;
import com.jdragon.studio.infra.service.QualityIssueService;
import com.jdragon.studio.infra.service.QualityMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "Quality Metrics", description = "Quality metric dashboard and issue center APIs")
@RestController
@RequestMapping("/api/v1/quality-metrics")
public class QualityMetricsController {

    private final QualityMetricsService qualityMetricsService;
    private final QualityIssueService qualityIssueService;

    public QualityMetricsController(QualityMetricsService qualityMetricsService,
                                    QualityIssueService qualityIssueService) {
        this.qualityMetricsService = qualityMetricsService;
        this.qualityIssueService = qualityIssueService;
    }

    @Operation(summary = "List quality metric filter options")
    @GetMapping("/options")
    public Result<QualityMetricOptionsView> options() {
        return Result.success(qualityMetricsService.options());
    }

    @Operation(summary = "Query quality metric model filter options")
    @GetMapping("/model-options")
    public Result<PageView<RunMetricFilterOptionView>> modelOptions(@RequestParam(value = "datasourceId", required = false) Long datasourceId,
                                                                    @RequestParam(value = "keyword", required = false) String keyword,
                                                                    @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                                    @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(qualityMetricsService.modelOptions(datasourceId, keyword, pageNo, pageSize));
    }

    @Operation(summary = "List quality issue assignee options")
    @GetMapping("/assignee-options")
    public Result<List<QualityIssueAssigneeOptionView>> assigneeOptions() {
        return Result.success(qualityIssueService.listAssigneeOptions());
    }

    @Operation(summary = "Query quality metric dashboard")
    @PostMapping("/dashboard/query")
    public Result<QualityMetricDashboardView> queryDashboard(@RequestBody(required = false) QualityMetricDashboardQueryRequest request) {
        return Result.success(qualityMetricsService.queryDashboard(request));
    }

    @Operation(summary = "Query quality asset risk list")
    @PostMapping("/assets/query")
    public Result<List<QualityAssetRiskView>> queryAssets(@RequestBody(required = false) QualityAssetQueryRequest request) {
        return Result.success(qualityMetricsService.queryAssets(request));
    }

    @Operation(summary = "Query quality asset risk page")
    @PostMapping("/assets/page")
    public Result<PageView<QualityAssetRiskView>> queryAssetsPage(@RequestBody(required = false) QualityAssetQueryRequest request) {
        return Result.success(qualityMetricsService.queryAssetsPage(request));
    }

    @Operation(summary = "Get quality asset detail")
    @GetMapping("/assets/{assetId}")
    public Result<QualityAssetDetailView> getAssetDetail(@PathVariable("assetId") String assetId,
                                                         @RequestParam(value = "startTime", required = false)
                                                         @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                                                         @RequestParam(value = "endTime", required = false)
                                                         @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return Result.success(qualityMetricsService.getAssetDetail(assetId, startTime, endTime));
    }

    @Operation(summary = "Query quality issues")
    @PostMapping("/issues/query")
    public Result<List<QualityIssueView>> queryIssues(@RequestBody(required = false) QualityIssueQueryRequest request) {
        return Result.success(qualityIssueService.query(request));
    }

    @Operation(summary = "Query quality issues page")
    @PostMapping("/issues/page")
    public Result<PageView<QualityIssueView>> queryIssuesPage(@RequestBody(required = false) QualityIssueQueryRequest request) {
        return Result.success(qualityIssueService.queryPage(request));
    }

    @Operation(summary = "Get quality issue detail")
    @GetMapping("/issues/{id}")
    public Result<QualityIssueDetailView> getIssue(@PathVariable("id") Long id) {
        return Result.success(qualityIssueService.get(id));
    }

    @Operation(summary = "Assign quality issue")
    @PostMapping("/issues/{id}/assign")
    public Result<QualityIssueDetailView> assign(@PathVariable("id") Long id,
                                                 @RequestBody(required = false) QualityIssueAssignRequest request) {
        return Result.success(qualityIssueService.assign(id, request));
    }

    @Operation(summary = "Update quality issue status")
    @PostMapping("/issues/{id}/status")
    public Result<QualityIssueDetailView> updateStatus(@PathVariable("id") Long id,
                                                       @Valid @RequestBody QualityIssueStatusRequest request) {
        return Result.success(qualityIssueService.updateStatus(id, request));
    }

    @Operation(summary = "Update quality issue severity")
    @PostMapping("/issues/{id}/severity")
    public Result<QualityIssueDetailView> updateSeverity(@PathVariable("id") Long id,
                                                         @Valid @RequestBody QualityIssueSeverityRequest request) {
        return Result.success(qualityIssueService.updateSeverity(id, request));
    }

    @Operation(summary = "Add quality issue comment")
    @PostMapping("/issues/{id}/comment")
    public Result<QualityIssueDetailView> addComment(@PathVariable("id") Long id,
                                                     @Valid @RequestBody QualityIssueCommentRequest request) {
        return Result.success(qualityIssueService.addComment(id, request));
    }
}

