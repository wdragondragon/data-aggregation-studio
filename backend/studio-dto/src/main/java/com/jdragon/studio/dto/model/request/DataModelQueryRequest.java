package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Dynamic model query request")
public class DataModelQueryRequest {
    @Schema(description = "Requested page number, starting from 1")
    private Integer pageNo;

    @Schema(description = "Requested page size")
    private Integer pageSize;

    @Schema(description = "Datasource id filter")
    private Long datasourceId;

    @Schema(description = "Datasource type filter")
    private String datasourceType;

    @Schema(description = "Model kind filter")
    private String modelKind;

    @Schema(description = "Meta model query groups")
    private List<DataModelQueryGroup> groups = new ArrayList<DataModelQueryGroup>();
}
