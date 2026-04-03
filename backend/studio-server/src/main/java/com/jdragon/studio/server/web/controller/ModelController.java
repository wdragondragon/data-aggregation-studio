package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.infra.service.DataModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @Operation(summary = "List models by datasource")
    @GetMapping("/datasource/{datasourceId}")
    public Result<List<DataModelDefinition>> listByDatasource(@PathVariable("datasourceId") Long datasourceId) {
        return Result.success(dataModelService.listByDatasource(datasourceId));
    }

    @Operation(summary = "Sync models from datasource")
    @PostMapping("/datasource/{datasourceId}/sync")
    public Result<List<DataModelDefinition>> sync(@PathVariable("datasourceId") Long datasourceId) {
        return Result.success(dataModelService.syncFromDatasource(datasourceId));
    }

    @Operation(summary = "Preview datasource model")
    @GetMapping("/{modelId}/preview")
    public Result<List<Map<String, Object>>> preview(@PathVariable("modelId") Long modelId,
                                                     @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Result.success(dataModelService.preview(modelId, limit));
    }
}
