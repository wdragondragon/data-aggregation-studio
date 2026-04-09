package com.jdragon.studio.infra.service.script;

import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataSourceDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

public class DataDevelopmentExecutionContext {
    private Long scriptId;
    private String scriptName;
    private ScriptType scriptType;
    private Long datasourceId;
    private DataSourceDefinition datasource;
    private String content;
    private Integer maxRows;
    private String tenantId;
    private String username;
    private Map<String, Object> arguments = new LinkedHashMap<String, Object>();
    private Map<String, Object> runtimeContext = new LinkedHashMap<String, Object>();

    public Long getScriptId() {
        return scriptId;
    }

    public void setScriptId(Long scriptId) {
        this.scriptId = scriptId;
    }

    public String getScriptName() {
        return scriptName;
    }

    public void setScriptName(String scriptName) {
        this.scriptName = scriptName;
    }

    public ScriptType getScriptType() {
        return scriptType;
    }

    public void setScriptType(ScriptType scriptType) {
        this.scriptType = scriptType;
    }

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }

    public DataSourceDefinition getDatasource() {
        return datasource;
    }

    public void setDatasource(DataSourceDefinition datasource) {
        this.datasource = datasource;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(Integer maxRows) {
        this.maxRows = maxRows;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(arguments);
    }

    public Map<String, Object> getRuntimeContext() {
        return runtimeContext;
    }

    public void setRuntimeContext(Map<String, Object> runtimeContext) {
        this.runtimeContext = runtimeContext == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(runtimeContext);
    }
}
