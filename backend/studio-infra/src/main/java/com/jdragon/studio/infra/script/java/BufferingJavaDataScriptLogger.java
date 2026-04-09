package com.jdragon.studio.infra.script.java;

import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class BufferingJavaDataScriptLogger implements JavaDataScriptLogger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Logger delegate;
    private final StringBuilder buffer = new StringBuilder();

    public BufferingJavaDataScriptLogger(Logger delegate) {
        this.delegate = delegate;
    }

    @Override
    public void info(String message) {
        append("INFO", message);
        if (delegate != null) {
            delegate.info(message);
        }
    }

    @Override
    public void warn(String message) {
        append("WARN", message);
        if (delegate != null) {
            delegate.warn(message);
        }
    }

    @Override
    public void error(String message) {
        append("ERROR", message);
        if (delegate != null) {
            delegate.error(message);
        }
    }

    @Override
    public void debug(String message) {
        append("DEBUG", message);
        if (delegate != null) {
            delegate.debug(message);
        }
    }

    public String snapshot() {
        return buffer.toString();
    }

    private void append(String level, String message) {
        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(FORMATTER.format(LocalDateTime.now()))
                .append(" [")
                .append(level)
                .append("] ")
                .append(message == null ? "" : message);
    }
}
