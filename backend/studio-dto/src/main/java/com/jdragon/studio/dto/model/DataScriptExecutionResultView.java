package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.ScriptType;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class DataScriptExecutionResultView {
    private ScriptType scriptType;
    private Boolean success;
    private String status;
    private String message;
    private Long executionMs;
    private String datasourceName;
    private String logs;
    private Map<String, Object> resultJson = new LinkedHashMap<String, Object>();
    private SqlExecutionResultView sqlResult;
}
