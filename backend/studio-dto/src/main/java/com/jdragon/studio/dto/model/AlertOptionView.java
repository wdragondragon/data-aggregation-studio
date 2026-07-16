package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AlertOptionView {
    private String code;
    private String label;
    private String description;
    private String defaultSeverity;
    private List<String> subjectTypes = new ArrayList<String>();
    private Map<String, Object> conditionSchema = new LinkedHashMap<String, Object>();
    private Map<String, Object> defaults = new LinkedHashMap<String, Object>();
}
