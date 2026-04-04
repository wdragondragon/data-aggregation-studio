package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Dynamic model query request")
public class DataModelQueryRequest {
    @Schema(description = "Datasource id filter")
    private Long datasourceId;

    @Schema(description = "Model kind filter")
    private String modelKind;

    @Schema(description = "Meta model query groups")
    private List<DataModelQueryGroup> groups = new ArrayList<DataModelQueryGroup>();
}
