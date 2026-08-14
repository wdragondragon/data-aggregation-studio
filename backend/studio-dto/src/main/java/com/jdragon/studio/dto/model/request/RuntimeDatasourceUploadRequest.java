package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RuntimeDatasourceUploadRequest extends RuntimeDatasourceProbeRequest {
    @NotBlank
    private String targetPath;
    @NotNull
    private Long contentLength;
    private Boolean overwrite;
}
