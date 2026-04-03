package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.entity.CatalogPluginEntity;
import com.jdragon.studio.infra.service.PluginCatalogService;
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

    private final PluginCatalogService pluginCatalogService;

    public CatalogController(PluginCatalogService pluginCatalogService) {
        this.pluginCatalogService = pluginCatalogService;
    }

    @Operation(summary = "List plugin catalog entries")
    @GetMapping("/plugins")
    public Result<List<CatalogPluginEntity>> listPlugins(@RequestParam(value = "category", required = false) String category) {
        return Result.success(category == null ? pluginCatalogService.list() : pluginCatalogService.listByCategory(category));
    }

    @Operation(summary = "Get executable capability matrix")
    @GetMapping("/capabilities")
    public Result<Map<String, Object>> capabilityMatrix() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executableSourceTypes", pluginCatalogService.executableSourceTypes());
        payload.put("plugins", pluginCatalogService.list());
        return Result.success(payload);
    }
}
