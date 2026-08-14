package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class UnstructuredArchiveDownloadRequest {
    @NotNull
    private Long runtimeClusterId;
    @NotNull
    private Long datasourceId;
    @NotEmpty
    private List<String> paths = new ArrayList<String>();
}
