package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Statistics chart series")
public class DataModelStatisticsChartSeriesView {
    @Schema(description = "Series name")
    private String name;

    @Schema(description = "Series type")
    private String type;

    @Schema(description = "Series data")
    private List<Object> data = new ArrayList<Object>();
}
