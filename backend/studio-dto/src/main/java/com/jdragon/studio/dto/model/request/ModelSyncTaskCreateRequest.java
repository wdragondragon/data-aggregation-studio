package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.ModelSyncTaskSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Model sync task create request")
public class ModelSyncTaskCreateRequest {
    @NotNull(message = "Datasource id is required")
    @Schema(description = "Datasource id", required = true)
    private Long datasourceId;

    @Schema(description = "Physical locators selected for sync, such as table names")
    private List<String> physicalLocators = new ArrayList<String>();

    @Schema(description = "Task source")
    private ModelSyncTaskSource source = ModelSyncTaskSource.MANUAL;
}
