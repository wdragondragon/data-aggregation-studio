package com.jdragon.studio.dto.model.system;

import com.jdragon.studio.dto.model.BaseDefinition;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceShareView extends BaseDefinition {
    private Long sourceProjectId;
    private String sourceProjectName;
    private Long targetProjectId;
    private String targetProjectName;
    private String resourceType;
    private Long resourceId;
    private String resourceLabel;
    private String resourceName;
    private String resourceCode;
    private String resourceStatus;
    private Long sharedByUserId;
    private Boolean enabled;
}
