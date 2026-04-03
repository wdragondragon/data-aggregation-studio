package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class TransformerBinding {
    private String transformerCode;
    private Map<String, Object> parameters = new LinkedHashMap<String, Object>();
}

