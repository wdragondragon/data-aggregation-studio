package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataDevelopmentDirectoryView extends BaseDefinition {
    private Long parentId;
    private String name;
    private String permissionCode;
    private String description;
}
