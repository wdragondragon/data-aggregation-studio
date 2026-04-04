package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@Schema(description = "Data development directory save request")
public class DataDevelopmentDirectorySaveRequest {
    @Schema(description = "Directory id")
    private Long id;

    @Schema(description = "Parent directory id")
    private Long parentId;

    @NotBlank(message = "Directory name is required")
    @Schema(description = "Directory name", required = true)
    private String name;

    @Schema(description = "Permission code")
    private String permissionCode;

    @Schema(description = "Directory description")
    private String description;
}
