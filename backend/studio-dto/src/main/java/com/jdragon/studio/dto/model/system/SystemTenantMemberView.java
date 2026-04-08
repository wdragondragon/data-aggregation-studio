package com.jdragon.studio.dto.model.system;

import com.jdragon.studio.dto.model.BaseDefinition;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SystemTenantMemberView extends BaseDefinition {
    private Long userId;
    private String username;
    private String displayName;
    private String roleCode;
    private String status;
}
