package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Runtime reader/writer option schema")
public class PluginRuntimeOptionSchemaView {
    @Schema(description = "reader or writer")
    private String role;

    @Schema(description = "Datasource type code")
    private String datasourceType;

    @Schema(description = "Datasource category")
    private String sourceCategory;

    @Schema(description = "Job plugin type used by JobContainer")
    private String pluginType;

    @Schema(description = "Whether a runtime job plugin exists for this role")
    private Boolean runtimeSupported;

    @Schema(description = "Whether the role supports incremental cursor injection")
    private Boolean incrementalSupported;

    @Schema(description = "Form fields for advanced options")
    private List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();

    @Schema(description = "Keys managed by Studio and blocked from advanced options")
    private List<String> reservedKeys = new ArrayList<String>();

    @Schema(description = "Human readable description")
    private String description;
}
