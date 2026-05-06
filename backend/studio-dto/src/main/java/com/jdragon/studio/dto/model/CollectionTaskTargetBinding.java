package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Collection task target binding")
public class CollectionTaskTargetBinding {
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

    @Schema(description = "Writer advanced options")
    private Map<String, Object> writerOptions = new LinkedHashMap<String, Object>();
}
