package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Flink SQL execution request")
public class FlinkSqlExecuteRequest {
    @Schema(description = "Data model ids to register as Flink temporary tables")
    private List<Long> modelIds = new ArrayList<Long>();

    @NotBlank
    @Schema(description = "Flink SQL, only SELECT or WITH SELECT is allowed")
    private String sql;

    @Schema(description = "Maximum rows returned to frontend")
    private Integer maxRows;
}
