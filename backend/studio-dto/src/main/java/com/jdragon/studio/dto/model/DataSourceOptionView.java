package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataSourceOptionView extends BaseDefinition {
    private String name;
    private String typeCode;
    private Long schemaVersionId;
    private Boolean enabled;
    private Boolean executable;
}
