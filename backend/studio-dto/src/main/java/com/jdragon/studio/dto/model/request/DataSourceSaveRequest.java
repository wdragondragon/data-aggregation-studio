package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Datasource save request")
public class DataSourceSaveRequest {
    @Schema(description = "Datasource id")
    private Long id;

    @NotBlank(message = "Datasource name is required")
    @Schema(description = "Datasource display name", required = true)
    private String name;

    @NotBlank(message = "Datasource typeCode is required")
    @Schema(description = "Datasource plugin type code", required = true, example = "mysql8")
    private String typeCode;

    @Schema(description = "Published schema version id")
    private Long schemaVersionId;

    @Schema(description = "Whether datasource is enabled")
    private Boolean enabled;

    @Schema(description = "Whether datasource can be used in job execution")
    private Boolean executable;

    @Schema(description = "Technical metadata")
    private Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();

    @Schema(description = "Business metadata")
    private Map<String, Object> businessMetadata = new LinkedHashMap<String, Object>();
}
