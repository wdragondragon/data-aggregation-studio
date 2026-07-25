package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.DataServiceSourceType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DataServiceResolveFieldsRequest {
    @NotNull(message = "Runtime cluster is required")
    private Long runtimeClusterId;
    private DataServiceSourceType sourceType;
    private Long datasourceId;
    private Long modelId;
    private String customSql;
}
