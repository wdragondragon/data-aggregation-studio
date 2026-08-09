package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UnstructuredOperationRequest {
    @NotNull
    private Long runtimeClusterId;
    @NotNull
    private Long datasourceId;
    @NotNull
    private String operation;
    @NotBlank
    private String sourcePath;
    private String targetPath;
    private Boolean recursiveConfirmed;
}
