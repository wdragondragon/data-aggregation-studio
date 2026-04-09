package com.jdragon.studio.infra.script.python;

import java.util.LinkedHashMap;
import java.util.Map;

public class PythonDataScriptResult {
    private boolean success = true;
    private String status = "SUCCESS";
    private String message;
    private Map<String, Object> resultJson = new LinkedHashMap<String, Object>();

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getResultJson() {
        return resultJson;
    }

    public void setResultJson(Map<String, Object> resultJson) {
        this.resultJson = resultJson == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(resultJson);
    }
}
