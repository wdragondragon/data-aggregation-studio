package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Dynamic model statistics request")
public class DataModelStatisticsRequest extends DataModelQueryRequest {
    @Schema(description = "Target meta schema code", required = true)
    private String targetMetaSchemaCode;

    @Schema(description = "Target field key", required = true)
    private String targetFieldKey;

    @Schema(description = "Target metadata scope, only BUSINESS is supported in V1")
    private String targetScope = "BUSINESS";

    @Schema(description = "Statistic type, such as COUNT_BY_VALUE, SUMMARY or COUNT_BY_BUCKET")
    private String statType;

    @Schema(description = "Top N limit for COUNT_BY_VALUE result")
    private Integer topN;

    @Schema(description = "Numeric bucket config for COUNT_BY_BUCKET")
    private DataModelStatisticsBucketConfig bucketConfig;
}
