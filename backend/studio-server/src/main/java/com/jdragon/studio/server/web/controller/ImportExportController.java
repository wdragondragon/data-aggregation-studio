package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.infra.service.PluginCatalogService;
import com.jdragon.studio.infra.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "ImportExport", description = "Import and export APIs")
@RestController
@RequestMapping("/api/v1")
public class ImportExportController {

    private final PluginCatalogService pluginCatalogService;
    private final MetadataSchemaService metadataSchemaService;
    private final WorkflowService workflowService;

    public ImportExportController(PluginCatalogService pluginCatalogService,
                                  MetadataSchemaService metadataSchemaService,
                                  WorkflowService workflowService) {
        this.pluginCatalogService = pluginCatalogService;
        this.metadataSchemaService = metadataSchemaService;
        this.workflowService = workflowService;
    }

    @Operation(summary = "Export project definition bundle")
    @GetMapping("/exports/project")
    public Result<Map<String, Object>> exportProject() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("catalog", pluginCatalogService.list());
        payload.put("metaSchemas", metadataSchemaService.listSchemas());
        payload.put("workflows", workflowService.list());
        return Result.success(payload);
    }

    @Operation(summary = "Get import capability description")
    @GetMapping("/imports/template")
    public Result<Map<String, Object>> importTemplate() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("supported", true);
        payload.put("message", "Import endpoint reserved for JSON bundle ingestion.");
        return Result.success(payload);
    }
}
