package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Collection task source binding")
public class CollectionTaskSourceBinding {
    @Schema(description = "Source alias")
    private String sourceAlias;

    @Schema(description = "Datasource id")
    private Long datasourceId;

    @Schema(description = "Datasource name")
    private String datasourceName;

    @Schema(description = "Datasource type")
    private String datasourceTypeCode;

    @Schema(description = "Model id")
    private Long modelId;

    @Schema(description = "Model name")
    private String modelName;

    @Schema(description = "Model locator")
    private String modelPhysicalLocator;

    @Schema(description = "Reader advanced options")
    private Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();

    @Schema(description = "Incremental cursor definition")
    private CollectionIncrementalDefinition incremental;
}
