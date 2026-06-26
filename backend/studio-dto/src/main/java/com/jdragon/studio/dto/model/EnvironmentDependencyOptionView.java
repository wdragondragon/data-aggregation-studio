package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EnvironmentDependencyOptionView extends BaseDefinition {
    private String name;
    private String version;
    private String scriptType;
    private Boolean enabled;
}
