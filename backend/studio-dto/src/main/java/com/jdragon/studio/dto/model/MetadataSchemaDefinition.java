package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.SchemaStatus;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MetadataSchemaDefinition extends BaseDefinition {
    private String schemaCode;
    private String schemaName;
    private String objectType;
    private String typeCode;
    private Long currentVersionId;
    private Integer versionNumber;
    private SchemaStatus status;
    private String description;
    private List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
    private List<PluginAssetDescriptor> assets = new ArrayList<PluginAssetDescriptor>();
}

