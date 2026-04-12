package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataModelIndexQueueStatusView;
import com.jdragon.studio.dto.model.DataModelLineageEdgeDetailView;
import com.jdragon.studio.dto.model.DataModelLineageView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.DataModelStatisticsView;
import com.jdragon.studio.dto.enums.LineageLevel;
import com.jdragon.studio.dto.model.request.DataModelManualLineageSaveRequest;
import com.jdragon.studio.dto.model.request.DataModelQueryRequest;
import com.jdragon.studio.dto.model.request.DataModelSaveRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsRequest;
import com.jdragon.studio.dto.model.request.ModelSyncRequest;
import com.jdragon.studio.infra.service.DataModelLineageService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataModelIndexRebuildQueueService;
import com.jdragon.studio.infra.service.DataModelStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@Tag(name = "Models", description = "Datasource model APIs")
@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private final DataModelService dataModelService;
    private final DataModelIndexRebuildQueueService dataModelIndexRebuildQueueService;
    private final DataModelStatisticsService dataModelStatisticsService;
    private final DataModelLineageService dataModelLineageService;

    public ModelController(DataModelService dataModelService,
                           DataModelIndexRebuildQueueService dataModelIndexRebuildQueueService,
                           DataModelStatisticsService dataModelStatisticsService,
                           DataModelLineageService dataModelLineageService) {
        this.dataModelService = dataModelService;
        this.dataModelIndexRebuildQueueService = dataModelIndexRebuildQueueService;
        this.dataModelStatisticsService = dataModelStatisticsService;
        this.dataModelLineageService = dataModelLineageService;
    }

    @Operation(summary = "List all datasource models")
    @GetMapping
    public Result<PageView<DataModelDefinition>> list(@RequestParam(value = "datasourceType", required = false) String datasourceType,
                                                      @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                      @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(dataModelService.listPage(datasourceType, pageNo, pageSize));
    }

    @Operation(summary = "List models by datasource")
    @GetMapping("/datasource/{datasourceId}")
    public Result<PageView<DataModelDefinition>> listByDatasource(@PathVariable("datasourceId") Long datasourceId,
                                                                  @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                                  @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(dataModelService.listByDatasourcePage(datasourceId, pageNo, pageSize));
    }

    @Operation(summary = "Get datasource model detail")
    @GetMapping("/{modelId}")
    public Result<DataModelDefinition> get(@PathVariable("modelId") Long modelId) {
        return Result.success(dataModelService.get(modelId));
    }

    @Operation(summary = "Query models by dynamic metadata conditions")
    @PostMapping("/query")
    public Result<PageView<DataModelDefinition>> query(@RequestBody(required = false) DataModelQueryRequest request,
                                                       @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                       @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(dataModelService.queryPage(request, pageNo, pageSize));
    }

    @Operation(summary = "Statistic models by dynamic metadata conditions")
    @PostMapping("/statistics")
    public Result<DataModelStatisticsView> statistics(@RequestBody DataModelStatisticsRequest request) {
        return Result.success(dataModelStatisticsService.statistics(request));
    }

    @Operation(summary = "Sync models from datasource")
    @PostMapping("/datasource/{datasourceId}/sync")
    public Result<List<DataModelDefinition>> sync(@PathVariable("datasourceId") Long datasourceId) {
        return Result.success(dataModelService.syncFromDatasource(datasourceId));
    }

    @Operation(summary = "Sync selected models from datasource")
    @PostMapping("/datasource/{datasourceId}/sync-selected")
    public Result<List<DataModelDefinition>> syncSelected(@PathVariable("datasourceId") Long datasourceId,
                                                          @RequestBody(required = false) ModelSyncRequest request) {
        return Result.success(dataModelService.syncFromDatasource(datasourceId, request == null ? null : request.getPhysicalLocators()));
    }

    @Operation(summary = "Create or update datasource model")
    @PostMapping
    public Result<DataModelDefinition> save(@Valid @RequestBody DataModelSaveRequest request) {
        return Result.success(dataModelService.save(request));
    }

    @Operation(summary = "Preview datasource model")
    @GetMapping("/{modelId}/preview")
    public Result<List<Map<String, Object>>> preview(@PathVariable("modelId") Long modelId,
                                                     @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Result.success(dataModelService.preview(modelId, limit));
    }

    @Operation(summary = "Get model lineage")
    @GetMapping("/{modelId}/lineage")
    public Result<DataModelLineageView> lineage(@PathVariable("modelId") Long modelId,
                                                @RequestParam("level") LineageLevel level) {
        return Result.success(dataModelLineageService.getModelLineage(modelId, level));
    }

    @Operation(summary = "Get model lineage edge detail")
    @GetMapping("/{modelId}/lineage/edges/{edgeId}")
    public Result<DataModelLineageEdgeDetailView> lineageEdgeDetail(@PathVariable("modelId") Long modelId,
                                                                    @PathVariable("edgeId") String edgeId,
                                                                    @RequestParam("level") LineageLevel level) {
        return Result.success(dataModelLineageService.getEdgeDetail(modelId, level, edgeId));
    }

    @Operation(summary = "Create manual model lineage")
    @PostMapping("/{modelId}/lineage/manual")
    public Result<Void> createManualLineage(@PathVariable("modelId") Long modelId,
                                            @Valid @RequestBody DataModelManualLineageSaveRequest request) {
        dataModelLineageService.saveManualLineage(modelId, null, request);
        return Result.success(null);
    }

    @Operation(summary = "Update manual model lineage")
    @PutMapping("/{modelId}/lineage/manual/{relationId}")
    public Result<Void> updateManualLineage(@PathVariable("modelId") Long modelId,
                                            @PathVariable("relationId") Long relationId,
                                            @Valid @RequestBody DataModelManualLineageSaveRequest request) {
        dataModelLineageService.saveManualLineage(modelId, relationId, request);
        return Result.success(null);
    }

    @Operation(summary = "Delete manual model lineage")
    @DeleteMapping("/{modelId}/lineage/manual/{relationId}")
    public Result<Void> deleteManualLineage(@PathVariable("modelId") Long modelId,
                                            @PathVariable("relationId") Long relationId) {
        dataModelLineageService.deleteManualLineage(modelId, relationId);
        return Result.success(null);
    }

    @Operation(summary = "Delete datasource model")
    @DeleteMapping("/{modelId}")
    public Result<Void> delete(@PathVariable("modelId") Long modelId) {
        dataModelService.delete(modelId);
        return Result.success(null);
    }

    @Operation(summary = "Rebuild model dynamic query index")
    @PostMapping("/index/rebuild")
    public Result<Integer> rebuildIndex(@RequestParam(value = "datasourceId", required = false) Long datasourceId) {
        return Result.success(dataModelService.rebuildSearchIndex(datasourceId));
    }

    @Operation(summary = "Get model dynamic query index queue status")
    @GetMapping("/index/queue-status")
    public Result<DataModelIndexQueueStatusView> indexQueueStatus() {
        return Result.success(dataModelIndexRebuildQueueService.currentStatus());
    }
}
