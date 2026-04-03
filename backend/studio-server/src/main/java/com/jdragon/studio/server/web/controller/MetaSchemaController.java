package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.MetadataSchemaSaveRequest;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@Tag(name = "MetadataSchemas", description = "Metadata schema APIs")
@RestController
@RequestMapping("/api/v1/meta-schemas")
public class MetaSchemaController {

    private final MetadataSchemaService metadataSchemaService;

    public MetaSchemaController(MetadataSchemaService metadataSchemaService) {
        this.metadataSchemaService = metadataSchemaService;
    }

    @Operation(summary = "List metadata schemas")
    @GetMapping
    public Result<List<MetadataSchemaDefinition>> list() {
        return Result.success(metadataSchemaService.listSchemas());
    }

    @Operation(summary = "Save metadata schema draft")
    @PostMapping("/draft")
    public Result<MetadataSchemaDefinition> saveDraft(@Valid @RequestBody MetadataSchemaSaveRequest request) {
        return Result.success(metadataSchemaService.saveDraft(request));
    }

    @Operation(summary = "Publish metadata schema")
    @PostMapping("/{schemaId}/publish")
    public Result<MetadataSchemaDefinition> publish(@PathVariable("schemaId") Long schemaId) {
        return Result.success(metadataSchemaService.publish(schemaId));
    }

    @Operation(summary = "Sync technical meta models for datasource type")
    @PostMapping("/technical/sync/{typeCode}")
    public Result<List<MetadataSchemaDefinition>> syncTechnical(@PathVariable("typeCode") String typeCode) {
        return Result.success(metadataSchemaService.syncTechnicalMetaModels(typeCode));
    }

    @Operation(summary = "Sync technical meta models for all supported datasource types")
    @PostMapping("/technical/sync-all")
    public Result<List<MetadataSchemaDefinition>> syncAllTechnical() {
        return Result.success(metadataSchemaService.syncAllTechnicalMetaModels());
    }

    @Operation(summary = "Delete metadata schema")
    @DeleteMapping("/{schemaId}")
    public Result<Void> delete(@PathVariable("schemaId") Long schemaId) {
        metadataSchemaService.delete(schemaId);
        return Result.success(null);
    }
}
