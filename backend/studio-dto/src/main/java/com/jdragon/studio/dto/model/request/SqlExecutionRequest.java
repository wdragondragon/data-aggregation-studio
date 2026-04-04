package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.ScriptType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@Schema(description = "Ad-hoc SQL execution request")
public class SqlExecutionRequest {
    @NotNull(message = "Datasource is required")
    @Schema(description = "Datasource id", required = true)
    private Long datasourceId;

    @NotNull(message = "Script type is required")
    @Schema(description = "Script type", required = true)
    private ScriptType scriptType;

    @NotBlank(message = "Script content is required")
    @Schema(description = "Script content", required = true)
    private String content;

    @Schema(description = "Maximum rows to return for query statements")
    private Integer maxRows;
}
