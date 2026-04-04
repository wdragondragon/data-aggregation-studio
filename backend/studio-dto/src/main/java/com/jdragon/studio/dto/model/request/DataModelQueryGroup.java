package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Dynamic model query group for one meta model")
public class DataModelQueryGroup {
    @Schema(description = "Metadata scope, such as TECHNICAL or BUSINESS")
    private String scope;

    @Schema(description = "Meta schema code", required = true)
    private String metaSchemaCode;

    @Schema(description = "Row match mode for MULTIPLE meta models, such as SAME_ITEM or ANY_ITEM")
    private String rowMatchMode;

    @Schema(description = "Field conditions inside the group")
    private List<DataModelQueryCondition> conditions = new ArrayList<DataModelQueryCondition>();
}
