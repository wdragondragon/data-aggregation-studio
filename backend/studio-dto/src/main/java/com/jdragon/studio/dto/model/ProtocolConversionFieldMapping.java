package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.FieldValueType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ProtocolConversionFieldMapping {
    private Integer sortOrder;
    private DataIngestionSourcePosition sourcePosition;
    private String sourceField;
    private String targetField;
    private String targetPath;
    private FieldValueType valueType;
    private Boolean required;
    private String defaultValue;
    private String description;
    private List<TransformerBinding> transformers = new ArrayList<TransformerBinding>();
}
