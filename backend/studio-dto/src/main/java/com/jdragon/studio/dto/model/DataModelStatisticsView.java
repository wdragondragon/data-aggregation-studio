package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Dynamic metadata statistics result")
public class DataModelStatisticsView {
    @Schema(description = "Matched model count after filtering and target field selection")
    private Long matchedModelCount;

    @Schema(description = "Matched item count after filtering and target field selection")
    private Long matchedItemCount;

    @Schema(description = "Bucket results")
    private List<DataModelStatisticsBucketView> buckets = new ArrayList<DataModelStatisticsBucketView>();

    @Schema(description = "Summary metrics")
    private Map<String, Object> summaryMetrics = new LinkedHashMap<String, Object>();
}
