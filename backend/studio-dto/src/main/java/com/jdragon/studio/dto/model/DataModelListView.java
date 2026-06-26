package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.ModelKind;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataModelListView extends BaseDefinition {
    private Long datasourceId;
    private String name;
    private ModelKind modelKind;
    private String physicalLocator;
    private Long schemaVersionId;
}
