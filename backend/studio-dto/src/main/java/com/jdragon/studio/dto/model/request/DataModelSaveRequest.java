package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.ModelKind;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Data model save request")
public class DataModelSaveRequest {
    @Schema(description = "Model id")
    private Long id;

    @NotNull(message = "Datasource id is required")
    @Schema(description = "Datasource id", required = true)
    private Long datasourceId;

    @NotBlank(message = "Model name is required")
    @Schema(description = "Model display name", required = true)
    private String name;

    @NotBlank(message = "Physical locator is required")
    @Schema(description = "Physical locator, such as table name, file path or topic name", required = true)
    private String physicalLocator;

    @Schema(description = "Model kind")
    private ModelKind modelKind;

    @Schema(description = "Published schema version id")
    private Long schemaVersionId;

    @Schema(description = "Technical metadata")
    private Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();

    @Schema(description = "Business metadata")
    private Map<String, Object> businessMetadata = new LinkedHashMap<String, Object>();
}
