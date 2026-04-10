package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.MetadataScope;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Statistics schema option")
public class DataModelStatisticsSchemaOptionView {
    @Schema(description = "Schema code")
    private String schemaCode;

    @Schema(description = "Schema name")
    private String schemaName;

    @Schema(description = "Schema scope")
    private MetadataScope scope;

    @Schema(description = "Datasource type")
    private String datasourceType;

    @Schema(description = "Meta model code")
    private String metaModelCode;

    @Schema(description = "Display mode")
    private String displayMode;

    @Schema(description = "Available fields")
    private List<DataModelStatisticsFieldOptionView> fields = new ArrayList<DataModelStatisticsFieldOptionView>();
}
