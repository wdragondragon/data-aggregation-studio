package com.jdragon.studio.core.spi;

import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.MetadataSchemaSaveRequest;

import java.util.List;

public interface MetadataSchemaRegistry {
    List<MetadataSchemaDefinition> listSchemas();

    MetadataSchemaDefinition saveDraft(MetadataSchemaSaveRequest request);

    MetadataSchemaDefinition publish(Long schemaId);
}


