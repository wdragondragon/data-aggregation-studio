package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Statistics field option")
public class DataModelStatisticsFieldOptionView {
    @Schema(description = "Field key")
    private String fieldKey;

    @Schema(description = "Field name")
    private String fieldName;

    @Schema(description = "Metadata scope")
    private MetadataScope scope;

    @Schema(description = "Field value type")
    private FieldValueType valueType;

    @Schema(description = "Allowed query operators")
    private List<String> queryOperators = new ArrayList<String>();

    @Schema(description = "Default query operator")
    private String queryDefaultOperator;

    @Schema(description = "Supported chart types")
    private List<String> supportedChartTypes = new ArrayList<String>();
}
