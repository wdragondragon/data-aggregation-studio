package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Internal Worker request for cluster-directed Flink SQL execution")
public class RuntimeFlinkSqlExecuteRequest {

    @NotNull
    @Schema(description = "Target runtime cluster id")
    private Long targetClusterId;

    @NotBlank
    @Schema(description = "Target runtime cluster code")
    private String targetClusterCode;

    @NotBlank
    @Schema(description = "Tenant context propagated by the control plane")
    private String tenantId;

    @NotNull
    @Schema(description = "Project context propagated by the control plane")
    private Long projectId;

    @Schema(description = "Triggering user id")
    private Long userId;

    @Schema(description = "Triggering username")
    private String username;

    @Valid
    @NotNull
    @Schema(description = "Flink SQL execution payload")
    private FlinkSqlExecuteRequest execution;
}
