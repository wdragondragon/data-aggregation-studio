package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Statistics workspace options request")
public class DataModelStatisticsOptionsRequest {
    @Schema(description = "Datasource id")
    private Long datasourceId;

    @Schema(description = "Datasource type")
    private String datasourceType;

    @Schema(description = "Target scope, such as BUSINESS or TECHNICAL")
    private String targetScope = "BUSINESS";
}
