package com.jdragon.studio.infra.script.python;

import java.util.LinkedHashMap;
import java.util.Map;

public class PythonScriptContextPayload {
    private Long scriptId;
    private String scriptName;
    private String tenantId;
    private String username;
    private Map<String, Object> arguments = new LinkedHashMap<String, Object>();
    private Map<String, Object> runtimeContext = new LinkedHashMap<String, Object>();
    private PythonBridgeConnectionInfo bridge;

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

    public PythonBridgeConnectionInfo getBridge() {
        return bridge;
    }

    public void setBridge(PythonBridgeConnectionInfo bridge) {
        this.bridge = bridge;
    }
}
