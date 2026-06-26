package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class EnvironmentDependencyFileView extends BaseDefinition {
    private Long dependencyId;
    private String originalFileName;
    private String artifactType;
    private String checksum;
    private Long sizeBytes;
    private Boolean visible;
    private Boolean runtimeArtifact;
    private Long sourceFileId;
    private Boolean enabled;
    private LocalDateTime uploadedAt;
}
