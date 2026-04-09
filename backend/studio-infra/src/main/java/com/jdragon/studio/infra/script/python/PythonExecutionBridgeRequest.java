package com.jdragon.studio.infra.script.python;

import java.util.LinkedHashMap;
import java.util.Map;

public class PythonExecutionBridgeRequest {
    private String action;
    private Map<String, Object> payload = new LinkedHashMap<String, Object>();

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(payload);
    }
}
