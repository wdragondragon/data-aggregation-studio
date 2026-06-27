package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ScriptEnvironmentOptionView extends BaseDefinition {
    private String environmentName;
    private String environmentCode;
    private Boolean enabled;
}
