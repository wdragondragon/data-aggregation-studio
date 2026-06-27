package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.MetadataSchemaSaveRequest;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.infra.service.StandardRuntimeOptionSchemaBootstrapService;
import com.jdragon.studio.infra.service.StudioSecurityService;
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

@Tag(name = "MetadataSchemas", description = "Metadata schema APIs")
@RestController
@RequestMapping("/api/v1/meta-schemas")
public class MetaSchemaController {

    private final MetadataSchemaService metadataSchemaService;
    private final StandardRuntimeOptionSchemaBootstrapService runtimeOptionSchemaBootstrapService;
    private final StudioSecurityService securityService;

    public MetaSchemaController(MetadataSchemaService metadataSchemaService,
                                StandardRuntimeOptionSchemaBootstrapService runtimeOptionSchemaBootstrapService,
                                StudioSecurityService securityService) {
        this.metadataSchemaService = metadataSchemaService;
        this.runtimeOptionSchemaBootstrapService = runtimeOptionSchemaBootstrapService;
        this.securityService = securityService;
    }

    @Operation(summary = "List metadata schemas")
    @GetMapping
    public Result<List<MetadataSchemaDefinition>> list(@RequestParam(value = "includeFields", defaultValue = "true") boolean includeFields) {
        return Result.success(metadataSchemaService.listSchemas(includeFields));
    }

    @Operation(summary = "Get metadata schema detail")
    @GetMapping("/{schemaId}")
    public Result<MetadataSchemaDefinition> get(@PathVariable("schemaId") Long schemaId) {
        return Result.success(metadataSchemaService.getSchema(schemaId));
    }

    @Operation(summary = "Save metadata schema draft")
    @PostMapping("/draft")
    public Result<MetadataSchemaDefinition> saveDraft(@Valid @RequestBody MetadataSchemaSaveRequest request) {
        requireSchemaManagementPermission();
        return Result.success(metadataSchemaService.saveDraft(request));
    }

    @Operation(summary = "Publish metadata schema")
    @PostMapping("/{schemaId}/publish")
    public Result<MetadataSchemaDefinition> publish(@PathVariable("schemaId") Long schemaId) {
        requireSchemaManagementPermission();
        return Result.success(metadataSchemaService.publish(schemaId));
    }

    @Operation(summary = "Sync technical meta models for datasource type")
    @PostMapping("/technical/sync/{typeCode}")
    public Result<List<MetadataSchemaDefinition>> syncTechnical(@PathVariable("typeCode") String typeCode) {
        requireSchemaManagementPermission();
        return Result.success(metadataSchemaService.syncTechnicalMetaModels(typeCode));
    }

    @Operation(summary = "Sync technical meta models for all supported datasource types")
    @PostMapping("/technical/sync-all")
    public Result<List<MetadataSchemaDefinition>> syncAllTechnical() {
        requireSchemaManagementPermission();
        return Result.success(metadataSchemaService.syncAllTechnicalMetaModels());
    }

    @Operation(summary = "Sync standard runtime option meta models")
    @PostMapping("/runtime-options/sync-standard")
    public Result<List<MetadataSchemaDefinition>> syncStandardRuntimeOptions() {
        requireSchemaManagementPermission();
        return Result.success(runtimeOptionSchemaBootstrapService.syncStandardRuntimeOptionSchemas());
    }

    @Operation(summary = "Delete metadata schema")
    @DeleteMapping("/{schemaId}")
    public Result<Void> delete(@PathVariable("schemaId") Long schemaId) {
        requireSchemaManagementPermission();
        metadataSchemaService.delete(schemaId);
        return Result.success(null);
    }

    private void requireSchemaManagementPermission() {
        if (!securityService.hasAnyRole(
                StudioConstants.ROLE_SUPER_ADMIN,
                StudioConstants.ROLE_TENANT_ADMIN,
                StudioConstants.ROLE_ADMIN,
                StudioConstants.ROLE_PROJECT_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
    }
}

