package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Statistics chart table row")
public class DataModelStatisticsChartTableRowView {
    @Schema(description = "Logical key")
    private String key;

    @Schema(description = "Row label")
    private String label;

    @Schema(description = "Category or bucket value")
    private String category;

    @Schema(description = "Trend date key")
    private String date;

    @Schema(description = "Rank number")
    private Integer rank;

    @Schema(description = "Count value")
    private Long count;

    @Schema(description = "Ratio between 0 and 1")
    private BigDecimal ratio;

    @Schema(description = "Lower bound for numeric bucket")
    private BigDecimal lowerBound;

    @Schema(description = "Upper bound for numeric bucket")
    private BigDecimal upperBound;

    @Schema(description = "Original display value")
    private String value;
}
