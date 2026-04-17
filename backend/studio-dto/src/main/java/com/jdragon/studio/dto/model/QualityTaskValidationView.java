package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class QualityTaskValidationView {
    private Boolean valid;
    private String message;
    private String resolvedSql;
    private List<String> columns = new ArrayList<String>();
    private List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    private List<QualityRuleOutputParamView> outputParams = new ArrayList<QualityRuleOutputParamView>();
    private List<String> warnings = new ArrayList<String>();
    private Map<String, Object> summary = new LinkedHashMap<String, Object>();
}
