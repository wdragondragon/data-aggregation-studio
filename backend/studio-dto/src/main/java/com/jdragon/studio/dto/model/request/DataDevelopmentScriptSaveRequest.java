package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.ScriptType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Data development script save request")
public class DataDevelopmentScriptSaveRequest {
    @NotNull(message = "Runtime cluster is required")
    @Schema(description = "Runtime cluster id", required = true)
    private Long runtimeClusterId;
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

    @Schema(description = "Script environment id, required for Java script")
    private Long environmentId;

    @Schema(description = "Description")
    private String description;

    @NotBlank(message = "Script content is required")
    @Schema(description = "Script content", required = true)
    private String content;

    @Schema(description = "Execution config, for example modelIds for FLINK_QUESTION_SQL")
    private Map<String, Object> executionConfig = new LinkedHashMap<String, Object>();
}

