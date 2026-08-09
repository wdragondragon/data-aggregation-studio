package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FileTransferManualItemRequest {
    /** Preferred single-cluster request field. */
    private Long runtimeClusterId;
    /** @deprecated New requests use runtimeClusterId. */
    private Long sourceRuntimeClusterId;
    @NotNull(message = "Source datasource is required")
    private Long sourceDatasourceId;
    @NotBlank(message = "Source path is required")
    private String sourcePath;
    /** @deprecated New requests use runtimeClusterId. */
    private Long targetRuntimeClusterId;
    @NotNull(message = "Target datasource is required")
    private Long targetDatasourceId;
    @NotBlank(message = "Target path is required")
    private String targetPath;
    private Boolean recursive = Boolean.TRUE;
}
