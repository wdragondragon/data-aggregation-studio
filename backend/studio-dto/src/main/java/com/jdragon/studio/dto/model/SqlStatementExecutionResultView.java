package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class SqlStatementExecutionResultView {
    private Integer statementIndex;
    private String sql;
    private Boolean query;
    private Integer affectedRows;
    private Long executionMs;
    private String message;
    private List<String> columns = new ArrayList<String>();
    private List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    private Map<String, Object> summary = new LinkedHashMap<String, Object>();
}
