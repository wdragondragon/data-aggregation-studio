package com.jdragon.studio.infra.script.java;

public interface JavaDataScriptLogger {
    void info(String message);

    void warn(String message);

    void error(String message);

    void debug(String message);
}
