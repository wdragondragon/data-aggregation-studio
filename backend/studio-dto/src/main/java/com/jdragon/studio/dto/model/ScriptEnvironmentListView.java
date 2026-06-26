package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class ScriptEnvironmentListView extends BaseDefinition {
    private String environmentName;
    private String environmentCode;
    private Boolean enabled;
    private Boolean useApplicationParent;
    private Long environmentVersion;
    private List<Long> dependencyIds = new ArrayList<Long>();
    private List<EnvironmentDependencyOptionView> dependencies = new ArrayList<EnvironmentDependencyOptionView>();
}
