package com.jdragon.studio.infra.script.java;

import java.util.Map;

public interface
JavaDataScriptContext {
    String getTenantId();

    String getUsername();

    Map<String, Object> getArguments();

    Map<String, Object> getRuntimeContext();

    JavaDataScriptLogger getLogger();

    JavaDataScriptServices getServices();
}
