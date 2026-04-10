package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Statistics chart query request")
public class DataModelStatisticsChartRequest extends DataModelStatisticsRequest {
    @Schema(description = "Chart type: TREND, BAR, PIE, TOPN")
    private String chartType;

    @Schema(description = "Recent days for trend chart")
    private Integer days;

    @Schema(description = "Time mode for trend chart, only CREATED_AT is supported in V1")
    private String timeMode = "CREATED_AT";
}
