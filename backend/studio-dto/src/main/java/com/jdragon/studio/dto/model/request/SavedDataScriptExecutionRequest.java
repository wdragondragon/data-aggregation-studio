package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "Execute saved data development script request")
public class SavedDataScriptExecutionRequest {

    @Schema(description = "Optional authorized runtime cluster override for this manual execution")
    private Long runtimeClusterId;

    @Schema(description = "Execution arguments for non-SQL scripts")
    private Map<String, Object> arguments = new LinkedHashMap<String, Object>();

    @Schema(description = "Optional execution config override")
    private Map<String, Object> executionConfig = new LinkedHashMap<String, Object>();

    @Schema(description = "Maximum rows for script execution")
    private Integer maxRows;

    @Min(value = 1, message = "Wait timeout must be at least 1 second")
    @Max(value = 300, message = "Wait timeout must be at most 300 seconds")
    @Schema(description = "Server wait timeout in seconds")
    private Integer waitTimeoutSeconds;
}
