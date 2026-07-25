package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DataIngestionResolveFieldsRequest {
    @NotNull(message = "Runtime cluster is required")
    private Long runtimeClusterId;
    private Long datasourceId;
    private Long modelId;
}
