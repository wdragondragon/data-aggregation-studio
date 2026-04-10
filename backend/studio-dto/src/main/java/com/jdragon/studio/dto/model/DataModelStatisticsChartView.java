package com.jdragon.studio.dto.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Statistics chart result")
public class DataModelStatisticsChartView {
    @Schema(description = "Chart type")
    private String chartType;

    @Schema(description = "Summary metrics")
    private Map<String, Object> summaryMetrics = new LinkedHashMap<String, Object>();

    @Schema(description = "X axis labels")
    @JsonProperty("xAxis")
    private List<String> xAxis = new ArrayList<String>();

    @Schema(description = "Chart series")
    private List<DataModelStatisticsChartSeriesView> series = new ArrayList<DataModelStatisticsChartSeriesView>();

    @Schema(description = "Tabular rows for current chart")
    private List<DataModelStatisticsChartTableRowView> tableRows = new ArrayList<DataModelStatisticsChartTableRowView>();

    @Schema(description = "Disabled reason when chart is unsupported")
    private String disabledReason;
}
