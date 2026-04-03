package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class FieldMappingDefinition {
    private String sourceField;
    private String targetField;
    private String expression;
    private List<TransformerBinding> transformers = new ArrayList<TransformerBinding>();
}

