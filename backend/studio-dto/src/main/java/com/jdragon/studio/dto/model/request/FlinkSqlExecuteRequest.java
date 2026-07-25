package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Flink SQL execution request")
public class FlinkSqlExecuteRequest {
    @NotNull(message = "Runtime cluster is required")
    @Schema(description = "Target runtime cluster id", required = true)
    private Long runtimeClusterId;

    @Schema(description = "Data model ids to register as Flink temporary tables")
    private List<Long> modelIds = new ArrayList<Long>();

    @NotBlank
    @Schema(description = "Flink SQL, only SELECT or WITH SELECT is allowed")
    private String sql;

    @Schema(description = "Maximum rows returned to frontend")
    private Integer maxRows;

    @Schema(description = "Optional maximum rows scanned from each source; maxRows only limits returned rows")
    private Integer scanMaxRows;
}
