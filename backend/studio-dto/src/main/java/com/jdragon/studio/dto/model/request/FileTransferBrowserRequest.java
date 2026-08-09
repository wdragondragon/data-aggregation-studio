package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FileTransferBrowserRequest {
    @NotNull(message = "Runtime cluster is required")
    private Long runtimeClusterId;
    @NotNull(message = "Datasource is required")
    private Long datasourceId;
    private String path = "/";
    private String cursor;
    private Integer pageSize = 200;
}
