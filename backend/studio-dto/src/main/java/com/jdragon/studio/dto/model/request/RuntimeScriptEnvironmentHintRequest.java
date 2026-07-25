package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RuntimeScriptEnvironmentHintRequest {

    @NotNull
    private Long targetClusterId;

    @NotBlank
    private String targetClusterCode;

    @NotBlank
    private String tenantId;

    @NotNull
    private Long projectId;

    private Long userId;
    private String username;
    private Long environmentId;
    private String className;
    private String keyword;
    private Boolean staticOnly;

    @Min(1)
    @Max(500)
    private Integer limit;
}
