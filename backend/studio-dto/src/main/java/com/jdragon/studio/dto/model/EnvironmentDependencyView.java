package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EnvironmentDependencyView extends BaseDefinition {
    private String name;
    private String version;
    private String artifactUrl;
    private String artifactType;
    private String checksum;
    private Boolean enabled;
    private String description;
}
