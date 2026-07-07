package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Flink query model reference")
public class FlinkModelRefView {
    private Long datasourceId;
    private String datasourceName;
    private String datasourceType;
    private Long modelId;
    private String modelName;
    private String physicalLocator;
    private String flinkTableName;
}
