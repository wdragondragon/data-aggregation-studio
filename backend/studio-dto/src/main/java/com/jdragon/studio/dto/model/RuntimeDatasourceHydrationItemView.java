package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class RuntimeDatasourceHydrationItemView {
    private String physicalLocator;
    private Boolean success;
    private DataModelDefinition definition;
    private String message;
}
