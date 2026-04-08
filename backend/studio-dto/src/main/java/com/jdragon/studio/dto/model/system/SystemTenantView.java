package com.jdragon.studio.dto.model.system;

import com.jdragon.studio.dto.model.BaseDefinition;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SystemTenantView extends BaseDefinition {
    private String tenantCode;
    private String tenantName;
    private String description;
    private Boolean enabled;
}
