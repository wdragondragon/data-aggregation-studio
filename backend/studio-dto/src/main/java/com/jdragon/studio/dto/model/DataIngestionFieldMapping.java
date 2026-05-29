package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.FieldValueType;
import lombok.Data;

@Data
public class DataIngestionFieldMapping {
    private Integer sortOrder;
    private DataIngestionSourcePosition sourcePosition;
    private String sourceField;
    private String targetField;
    private FieldValueType valueType;
    private Boolean required;
    private String defaultValue;
    private String description;
}
