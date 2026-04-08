package com.jdragon.studio.dto.model.system;

import com.jdragon.studio.dto.model.BaseDefinition;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SystemProjectView extends BaseDefinition {
    private String projectCode;
    private String projectName;
    private String description;
    private Boolean enabled;
    private Boolean defaultProject;
}
