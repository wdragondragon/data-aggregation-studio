package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.ModelKind;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class DataModelDefinition extends BaseDefinition {
    private Long datasourceId;
    private String name;
    private ModelKind modelKind;
    private String physicalLocator;
    private Long schemaVersionId;
    private Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
    private Map<String, Object> businessMetadata = new LinkedHashMap<String, Object>();
}

