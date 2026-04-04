package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Dynamic model query condition")
public class DataModelQueryCondition {
    @Schema(description = "Field key", required = true)
    private String fieldKey;

    @Schema(description = "Operator, such as EQ, LIKE, IN, GT, GE, LT, LE, BETWEEN", required = true)
    private String operator;

    @Schema(description = "Single comparison value")
    private Object value;

    @Schema(description = "Multi-value comparison payload")
    private List<Object> values = new ArrayList<Object>();
}
