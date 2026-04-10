package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Statistics workspace options")
public class DataModelStatisticsOptionsView {
    @Schema(description = "Resolved datasource type")
    private String datasourceType;

    @Schema(description = "Query schema options")
    private List<DataModelStatisticsSchemaOptionView> querySchemas = new ArrayList<DataModelStatisticsSchemaOptionView>();

    @Schema(description = "Target schema options")
    private List<DataModelStatisticsSchemaOptionView> targetSchemas = new ArrayList<DataModelStatisticsSchemaOptionView>();
}
