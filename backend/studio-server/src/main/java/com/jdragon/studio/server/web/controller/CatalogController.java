package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DatasourceTypeCapabilityView;
import com.jdragon.studio.dto.model.PluginRuntimeOptionSchemaView;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.PluginRuntimeOptionSchemaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Catalog", description = "Plugin catalog and capability APIs")
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService;

    public CatalogController(DatasourceTypeCapabilityService datasourceTypeCapabilityService,
                             PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService) {
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.pluginRuntimeOptionSchemaService = pluginRuntimeOptionSchemaService;
    }

    @Operation(summary = "Get executable capability matrix")
    @GetMapping("/capabilities")
    public Result<Map<String, Object>> capabilityMatrix() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executableSourceTypes", datasourceTypeCapabilityService.executableSourceTypes());
        payload.put("executableTargetTypes", datasourceTypeCapabilityService.executableTargetTypes());
        payload.put("executableDatasourceTypes", datasourceTypeCapabilityService.executableDatasourceTypes());
        payload.put("sourceCapabilities", datasourceTypeCapabilityService.sourceCapabilities());
        return Result.success(payload);
    }

    @Operation(summary = "List enabled datasource type capabilities")
    @GetMapping("/datasource-types")
    public Result<List<DatasourceTypeCapabilityView>> datasourceTypes() {
        return Result.success(datasourceTypeCapabilityService.listEnabled());
    }

    @Operation(summary = "Get runtime reader/writer option schema")
    @GetMapping("/runtime-option-schemas")
    public Result<PluginRuntimeOptionSchemaView> runtimeOptionSchema(@RequestParam("role") String role,
                                                                     @RequestParam("datasourceType") String datasourceType) {
        return Result.success(pluginRuntimeOptionSchemaService.schema(role, datasourceType));
    }
}
