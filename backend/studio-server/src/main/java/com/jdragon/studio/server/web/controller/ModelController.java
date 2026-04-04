package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.request.DataModelQueryRequest;
import com.jdragon.studio.dto.model.request.DataModelSaveRequest;
import com.jdragon.studio.dto.model.request.ModelSyncRequest;
import com.jdragon.studio.infra.service.DataModelService;
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

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@Tag(name = "Models", description = "Datasource model APIs")
@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private final DataModelService dataModelService;

    public ModelController(DataModelService dataModelService) {
        this.dataModelService = dataModelService;
    }

    @Operation(summary = "List all datasource models")
    @GetMapping
    public Result<List<DataModelDefinition>> list() {
        return Result.success(dataModelService.list());
    }

    @Operation(summary = "List models by datasource")
    @GetMapping("/datasource/{datasourceId}")
    public Result<List<DataModelDefinition>> listByDatasource(@PathVariable("datasourceId") Long datasourceId) {
        return Result.success(dataModelService.listByDatasource(datasourceId));
    }

    @Operation(summary = "Get datasource model detail")
    @GetMapping("/{modelId}")
    public Result<DataModelDefinition> get(@PathVariable("modelId") Long modelId) {
        return Result.success(dataModelService.get(modelId));
    }

    @Operation(summary = "Query models by dynamic metadata conditions")
    @PostMapping("/query")
    public Result<List<DataModelDefinition>> query(@RequestBody(required = false) DataModelQueryRequest request) {
        return Result.success(dataModelService.query(request));
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
}
