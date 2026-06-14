package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.FieldValueType;
import lombok.Data;

@Data
public class ProtocolConversionFixedField {
    private String targetField;
    private String targetPath;
    private Object value;
    private FieldValueType valueType;
    private String description;
}
