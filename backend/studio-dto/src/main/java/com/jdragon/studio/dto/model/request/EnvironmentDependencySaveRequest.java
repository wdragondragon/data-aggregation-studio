package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
@Schema(description = "Environment dependency save request")
public class EnvironmentDependencySaveRequest {
    private Long id;

    @NotBlank(message = "Dependency name is required")
    private String name;

    private String version;

    @NotBlank(message = "Artifact URL is required")
    private String artifactUrl;

    @NotBlank(message = "Artifact type is required")
    private String artifactType;

    private String checksum;
    private Boolean enabled;
    private String description;
}
