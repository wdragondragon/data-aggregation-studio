package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.ModelKind;
import lombok.Data;

@Data
public class DataModelDatasourceOptionView {
    private Long id;
    private Long datasourceId;
    private String name;
    private ModelKind modelKind;
    private String physicalLocator;
}
