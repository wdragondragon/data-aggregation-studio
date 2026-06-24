package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.ScriptType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Execute data development script request")
public class DataScriptExecutionRequest {
    @NotNull(message = "Script type is required")
    @Schema(description = "Script type", required = true)
    private ScriptType scriptType;

    @Schema(description = "Datasource id, required for SQL script")
    private Long datasourceId;

    @Schema(description = "Script environment id, required for Java script")
    private Long environmentId;

    @NotBlank(message = "Script content is required")
    @Schema(description = "Script content", required = true)
    private String content;

    @Schema(description = "Execution arguments for non-SQL scripts")
    private Map<String, Object> arguments = new LinkedHashMap<String, Object>();

    @Schema(description = "Maximum rows for SQL query")
    private Integer maxRows;
}

