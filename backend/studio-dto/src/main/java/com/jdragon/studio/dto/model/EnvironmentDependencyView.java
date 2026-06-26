package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class EnvironmentDependencyView extends BaseDefinition {
    private String name;
    private String version;
    private String scriptType;
    private String artifactUrl;
    private String artifactType;
    private String checksum;
    private Boolean enabled;
    private String description;
    private List<EnvironmentDependencyFileView> files = new ArrayList<EnvironmentDependencyFileView>();
}
