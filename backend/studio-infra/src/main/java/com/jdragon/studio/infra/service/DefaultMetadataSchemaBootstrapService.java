package com.jdragon.studio.infra.service;

import org.springframework.stereotype.Service;

@Service
public class DefaultMetadataSchemaBootstrapService {

    private final MetadataSchemaService metadataSchemaService;

    public DefaultMetadataSchemaBootstrapService(MetadataSchemaService metadataSchemaService) {
        this.metadataSchemaService = metadataSchemaService;
    }

    public void bootstrap() {
        metadataSchemaService.syncAllTechnicalMetaModels();
    }
}
