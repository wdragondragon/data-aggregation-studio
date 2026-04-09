package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "Numeric bucket configuration for model statistics")
public class DataModelStatisticsBucketConfig {
    @Schema(description = "Inclusive lower bound")
    private BigDecimal lowerBound;

    @Schema(description = "Inclusive upper bound")
    private BigDecimal upperBound;

    @Schema(description = "Bucket step, must be greater than 0")
    private BigDecimal step;
}
