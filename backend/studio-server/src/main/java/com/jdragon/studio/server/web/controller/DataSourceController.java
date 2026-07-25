package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataSourceListView;
import com.jdragon.studio.dto.model.DataSourceOptionView;
import com.jdragon.studio.dto.model.DatasourceClusterBindingImpactView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.DatasourceConnectionTestRecordView;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryOptionResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.dto.model.request.DatasourceClusterBindingImpactRequest;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.RuntimeClusterSelectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;

@Tag(name = "Datasources", description = "Datasource management APIs")
@RestController
@RequestMapping("/api/v1/datasources")
public class DataSourceController {

    private final DataSourceService dataSourceService;
    private final RuntimeClusterSelectionService runtimeClusterSelectionService;

    public DataSourceController(DataSourceService dataSourceService,
                                RuntimeClusterSelectionService runtimeClusterSelectionService) {
        this.dataSourceService = dataSourceService;
        this.runtimeClusterSelectionService = runtimeClusterSelectionService;
    }

    @Operation(summary = "List datasources")
    @GetMapping
    public Result<List<DataSourceListView>> list() {
        return Result.success(dataSourceService.listSummaries());
    }

    @Operation(summary = "List datasources by page")
    @GetMapping("/page")
    public Result<PageView<DataSourceListView>> listPage(@RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                         @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(dataSourceService.listSummaryPage(pageNo, pageSize));
    }

    @Operation(summary = "List datasource options")
    @GetMapping("/options")
    public Result<List<DataSourceOptionView>> options(@RequestParam("runtimeClusterId") Long runtimeClusterId) {
        runtimeClusterSelectionService.assertExplicitSelection(runtimeClusterId);
        return Result.success(dataSourceService.listBasicOptions(runtimeClusterId));
    }

    @Operation(summary = "Get datasource detail")
    @GetMapping("/{id}")
    public Result<DataSourceDefinition> get(@PathVariable("id") Long id) {
        return Result.success(dataSourceService.get(id));
    }

    @Operation(summary = "Create or update datasource")
    @PostMapping
    public Result<DataSourceDefinition> save(@Valid @RequestBody DataSourceSaveRequest request) {
        return Result.success(dataSourceService.save(request));
    }

    @Operation(summary = "Preview resources affected by datasource cluster applicability changes")
    @PostMapping("/{id}/cluster-binding-impact")
    public Result<DatasourceClusterBindingImpactView> clusterBindingImpact(
            @PathVariable("id") Long id,
            @RequestBody(required = false) DatasourceClusterBindingImpactRequest request) {
        return Result.success(dataSourceService.previewClusterBindingImpact(id,
                request == null ? null : request.getApplicableClusterIds()));
    }

    @Operation(summary = "Test datasource connection")
    @PostMapping("/{id}/test")
    public Result<ConnectionTestResult> test(@PathVariable("id") Long id,
                                             @RequestParam("runtimeClusterId") Long runtimeClusterId) {
        return Result.success(dataSourceService.testConnection(id, runtimeClusterId));
    }

    @Operation(summary = "Test datasource connection with current form payload")
    @PostMapping("/test")
    public Result<ConnectionTestResult> testCurrent(@Valid @RequestBody DataSourceSaveRequest request,
                                                     @RequestParam("runtimeClusterId") Long runtimeClusterId) {
        return Result.success(dataSourceService.testConnection(request, runtimeClusterId));
    }

    @Operation(summary = "Get datasource connection test history")
    @GetMapping("/{id}/connection-history")
    public Result<List<DatasourceConnectionTestRecordView>> connectionHistory(@PathVariable("id") Long id,
                                                                              @RequestParam(value = "days", required = false) Integer days,
                                                                              @RequestParam(value = "limit", required = false) Integer limit,
                                                                              @RequestParam("runtimeClusterId") Long runtimeClusterId) {
        runtimeClusterSelectionService.assertExplicitSelection(runtimeClusterId);
        return Result.success(dataSourceService.connectionHistory(id, days, limit, runtimeClusterId));
    }

    @Operation(summary = "Discover models from datasource")
    @PostMapping("/{id}/discover")
    public Result<ModelDiscoveryResult> discover(@PathVariable("id") Long id,
                                                 @RequestParam(value = "keyword", required = false) String keyword,
                                                 @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                 @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                 @RequestParam("runtimeClusterId") Long runtimeClusterId) {
        return Result.success(dataSourceService.discoverModels(id, keyword, pageNo, pageSize, runtimeClusterId));
    }

    @Operation(summary = "Discover lightweight model options from datasource")
    @PostMapping("/{id}/discover-options")
    public Result<ModelDiscoveryOptionResult> discoverOptions(@PathVariable("id") Long id,
                                                              @RequestParam(value = "keyword", required = false) String keyword,
                                                              @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                              @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                              @RequestParam("runtimeClusterId") Long runtimeClusterId) {
        return Result.success(dataSourceService.discoverModelOptions(id, keyword, pageNo, pageSize, runtimeClusterId));
    }

    @Operation(summary = "Delete datasource")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        dataSourceService.delete(id);
        return Result.success(null);
    }
}

