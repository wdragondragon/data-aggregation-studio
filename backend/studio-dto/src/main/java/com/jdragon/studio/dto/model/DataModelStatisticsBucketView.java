package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "One statistics bucket")
public class DataModelStatisticsBucketView {
    @Schema(description = "Bucket key")
    private String key;

    @Schema(description = "Bucket label")
    private String label;

    @Schema(description = "Bucket value for COUNT_BY_VALUE")
    private String value;

    @Schema(description = "Inclusive lower bound for numeric bucket")
    private BigDecimal lowerBound;

    @Schema(description = "Exclusive upper bound for numeric bucket except the last bucket")
    private BigDecimal upperBound;

    @Schema(description = "Matched row count in the bucket")
    private Long count;
}
