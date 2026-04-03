package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Metadata schema draft save request")
public class MetadataSchemaSaveRequest {
    @Schema(description = "Existing schema id")
    private Long schemaId;

    @NotBlank(message = "Schema code is required")
    @Schema(description = "Schema code", required = true, example = "datasource:mysql8")
    private String schemaCode;

    @NotBlank(message = "Schema name is required")
    @Schema(description = "Schema name", required = true)
    private String schemaName;

    @NotBlank(message = "Object type is required")
    @Schema(description = "Object type", required = true, example = "datasource")
    private String objectType;

    @NotBlank(message = "Type code is required")
    @Schema(description = "Plugin or object type code", required = true, example = "mysql8")
    private String typeCode;

    @Schema(description = "Schema description")
    private String description;

    @Schema(description = "Field definitions")
    private List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
}
