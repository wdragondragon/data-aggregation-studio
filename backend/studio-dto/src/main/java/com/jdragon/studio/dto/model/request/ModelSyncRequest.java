package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Selective datasource model sync request")
public class ModelSyncRequest {
    @NotNull(message = "Runtime cluster is required")
    @Schema(description = "Runtime cluster used for discovery and metadata hydration")
    private Long runtimeClusterId;

    @Schema(description = "Physical locators selected for sync, such as table names")
    private List<String> physicalLocators = new ArrayList<String>();
}
