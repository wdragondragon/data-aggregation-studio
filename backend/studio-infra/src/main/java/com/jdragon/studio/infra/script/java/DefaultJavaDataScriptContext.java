package com.jdragon.studio.infra.script.java;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class DefaultJavaDataScriptContext implements JavaDataScriptContext {

    private final String tenantId;
    private final String username;
    private final Map<String, Object> arguments;
    private final Map<String, Object> runtimeContext;
    private final JavaDataScriptLogger logger;
    private final JavaDataScriptServices services;

    public DefaultJavaDataScriptContext(String tenantId,
                                        String username,
                                        Map<String, Object> arguments,
                                        Map<String, Object> runtimeContext,
                                        JavaDataScriptLogger logger,
                                        JavaDataScriptServices services) {
        this.tenantId = tenantId;
        this.username = username;
        this.arguments = Collections.unmodifiableMap(arguments == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(arguments));
        this.runtimeContext = Collections.unmodifiableMap(runtimeContext == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(runtimeContext));
        this.logger = logger;
        this.services = services;
    }

    @Override
    public String getTenantId() {
        return tenantId;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public Map<String, Object> getArguments() {
        return arguments;
    }

    @Override
    public Map<String, Object> getRuntimeContext() {
        return runtimeContext;
    }

    @Override
    public JavaDataScriptLogger getLogger() {
        return logger;
    }

    @Override
    public JavaDataScriptServices getServices() {
        return services;
    }
}
