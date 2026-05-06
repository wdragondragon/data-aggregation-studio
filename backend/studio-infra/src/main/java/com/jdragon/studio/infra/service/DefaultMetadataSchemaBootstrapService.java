package com.jdragon.studio.infra.service;

import org.springframework.stereotype.Service;

@Service
public class DefaultMetadataSchemaBootstrapService {

    private final MetadataSchemaService metadataSchemaService;
    private final StandardRuntimeOptionSchemaBootstrapService runtimeOptionSchemaBootstrapService;

    public DefaultMetadataSchemaBootstrapService(MetadataSchemaService metadataSchemaService,
                                                 StandardRuntimeOptionSchemaBootstrapService runtimeOptionSchemaBootstrapService) {
        this.metadataSchemaService = metadataSchemaService;
        this.runtimeOptionSchemaBootstrapService = runtimeOptionSchemaBootstrapService;
    }

    public void bootstrap() {
        metadataSchemaService.syncAllTechnicalMetaModels();
        runtimeOptionSchemaBootstrapService.syncStandardRuntimeOptionSchemas();
    }
}
