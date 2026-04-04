package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.FieldComponentType;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MetadataFieldDefinition {
    private String fieldKey;
    private String fieldName;
    private String description;
    private MetadataScope scope;
    private FieldValueType valueType;
    private FieldComponentType componentType;
    private Boolean required;
    private Boolean sensitive;
    private Integer sortOrder;
    private String validationRule;
    private String placeholder;
    private String defaultValue;
    private List<String> options = new ArrayList<String>();
    private Boolean searchable;
    private Boolean sortable;
    private List<String> queryOperators = new ArrayList<String>();
    private String queryDefaultOperator;
}

