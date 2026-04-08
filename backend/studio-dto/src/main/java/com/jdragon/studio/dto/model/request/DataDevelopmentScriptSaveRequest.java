package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.ScriptType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@Schema(description = "Data development script save request")
public class DataDevelopmentScriptSaveRequest {
    @Schema(description = "Script id")
    private Long id;

    @Schema(description = "Directory id")
    private Long directoryId;

    @NotBlank(message = "File name is required")
    @Schema(description = "File name", required = true)
    private String fileName;

    @NotNull(message = "Script type is required")
    @Schema(description = "Script type", required = true)
    private ScriptType scriptType;

    @Schema(description = "Datasource id, required for SQL script")
    private Long datasourceId;

    @Schema(description = "Description")
    private String description;

    @NotBlank(message = "Script content is required")
    @Schema(description = "Script content", required = true)
    private String content;
}
