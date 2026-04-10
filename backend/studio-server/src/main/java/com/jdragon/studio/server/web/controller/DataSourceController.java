package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.infra.service.DataSourceService;
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

@Tag(name = "Datasources", description = "Datasource management APIs")
@RestController
@RequestMapping("/api/v1/datasources")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Operation(summary = "List datasources")
    @GetMapping
    public Result<List<DataSourceDefinition>> list() {
        return Result.success(dataSourceService.list());
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

    @Operation(summary = "Test datasource connection")
    @PostMapping("/{id}/test")
    public Result<ConnectionTestResult> test(@PathVariable("id") Long id) {
        return Result.success(dataSourceService.testConnection(id));
    }

    @Operation(summary = "Test datasource connection with current form payload")
    @PostMapping("/test")
    public Result<ConnectionTestResult> testCurrent(@Valid @RequestBody DataSourceSaveRequest request) {
        return Result.success(dataSourceService.testConnection(request));
    }

    @Operation(summary = "Discover models from datasource")
    @PostMapping("/{id}/discover")
    public Result<ModelDiscoveryResult> discover(@PathVariable("id") Long id,
                                                 @RequestParam(value = "keyword", required = false) String keyword,
                                                 @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                 @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(dataSourceService.discoverModels(id, keyword, pageNo, pageSize));
    }

    @Operation(summary = "Delete datasource")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        dataSourceService.delete(id);
        return Result.success(null);
    }
}
