package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EnvironmentDependencyFileListView extends BaseDefinition {
    private Long dependencyId;
    private String originalFileName;
    private String artifactType;
    private Long sizeBytes;
    private Boolean visible;
    private Boolean enabled;
}
