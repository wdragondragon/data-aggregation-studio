package com.jdragon.studio.infra.script.java;

public interface JavaDataScript {
    JavaDataScriptResult execute(JavaDataScriptContext context) throws Exception;
}
