package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataSourceDefinition extends BaseDefinition {
    private String name;
    private String typeCode;
    private Long schemaVersionId;
    private Boolean enabled;
    private Boolean executable;
    private Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
    private Map<String, Object> businessMetadata = new LinkedHashMap<String, Object>();
}

