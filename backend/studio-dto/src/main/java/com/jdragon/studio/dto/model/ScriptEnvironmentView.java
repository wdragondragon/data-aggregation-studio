package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class ScriptEnvironmentView extends BaseDefinition {
    private String environmentName;
    private String environmentCode;
    private Boolean enabled;
    private Boolean useApplicationParent;
    private String pythonInstallMode;
    private Long pythonRepositoryId;
    private Long environmentVersion;
    private String description;
    private List<Long> dependencyIds = new ArrayList<Long>();
    private List<EnvironmentDependencyView> dependencies = new ArrayList<EnvironmentDependencyView>();
}
