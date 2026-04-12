package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.LineageLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Manual lineage save request")
public class DataModelManualLineageSaveRequest {

    @Schema(description = "Lineage level, only TABLE and FIELD are supported")
    private LineageLevel level;

    @Schema(description = "Source model id")
    private Long sourceModelId;

    @Schema(description = "Target model id")
    private Long targetModelId;

    @Schema(description = "Source field key, required for FIELD level")
    private String sourceFieldKey;

    @Schema(description = "Target field key, required for FIELD level")
    private String targetFieldKey;
}
