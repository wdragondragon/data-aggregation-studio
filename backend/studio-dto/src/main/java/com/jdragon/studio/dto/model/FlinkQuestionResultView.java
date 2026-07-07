package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Flink SQL or natural-language query result")
public class FlinkQuestionResultView {
    private String question;
    private String sql;
    private List<String> columns = new ArrayList<String>();
    private List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    private List<FlinkModelRefView> modelRefs = new ArrayList<FlinkModelRefView>();
    private List<String> warnings = new ArrayList<String>();
    private Long executionMs;
    private Map<String, Object> summary = new LinkedHashMap<String, Object>();
}
