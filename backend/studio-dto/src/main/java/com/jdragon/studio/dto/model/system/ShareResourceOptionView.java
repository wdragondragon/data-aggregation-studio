package com.jdragon.studio.dto.model.system;

import com.jdragon.studio.dto.model.BaseDefinition;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ShareResourceOptionView extends BaseDefinition {
    private String resourceType;
    private String label;
    private String name;
    private String code;
    private String status;
}
