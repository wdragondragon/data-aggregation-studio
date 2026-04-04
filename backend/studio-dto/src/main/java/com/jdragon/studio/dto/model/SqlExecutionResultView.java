package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class SqlExecutionResultView {
    private Boolean query;
    private Integer statementCount;
    private Integer affectedRows;
    private Long executionMs;
    private String message;
    private String datasourceName;
    private List<String> columns = new ArrayList<String>();
    private List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    private Map<String, Object> summary = new LinkedHashMap<String, Object>();
    private List<SqlStatementExecutionResultView> results = new ArrayList<SqlStatementExecutionResultView>();
}
