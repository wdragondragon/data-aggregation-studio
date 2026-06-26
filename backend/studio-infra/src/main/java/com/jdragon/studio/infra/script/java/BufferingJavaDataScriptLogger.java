package com.jdragon.studio.infra.script.java;

import org.slf4j.Logger;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class BufferingJavaDataScriptLogger extends MarkerIgnoringBase implements JavaDataScriptLogger {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Logger delegate;
    private final StringBuilder buffer = new StringBuilder();

    public BufferingJavaDataScriptLogger(Logger delegate) {
        this.delegate = delegate;
        this.name = delegate == null ? BufferingJavaDataScriptLogger.class.getName() : delegate.getName();
    }

    @Override
    public boolean isTraceEnabled() {
        return true;
    }

    @Override
    public void trace(String message) {
        append("TRACE", message, null);
        if (delegate != null) {
            delegate.trace(message);
        }
    }

    @Override
    public void trace(String format, Object arg) {
        append("TRACE", formatted(format, arg), throwable(format, arg));
        if (delegate != null) {
            delegate.trace(format, arg);
        }
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
        append("TRACE", formatted(format, arg1, arg2), throwable(format, arg1, arg2));
        if (delegate != null) {
            delegate.trace(format, arg1, arg2);
        }
    }

    @Override
    public void trace(String format, Object... arguments) {
        append("TRACE", formatted(format, arguments), throwable(format, arguments));
        if (delegate != null) {
            delegate.trace(format, arguments);
        }
    }

    @Override
    public void trace(String message, Throwable throwable) {
        append("TRACE", message, throwable);
        if (delegate != null) {
            delegate.trace(message, throwable);
        }
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    public void debug(String message) {
        append("DEBUG", message, null);
        if (delegate != null) {
            delegate.debug(message);
        }
    }

    @Override
    public void debug(String format, Object arg) {
        append("DEBUG", formatted(format, arg), throwable(format, arg));
        if (delegate != null) {
            delegate.debug(format, arg);
        }
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        append("DEBUG", formatted(format, arg1, arg2), throwable(format, arg1, arg2));
        if (delegate != null) {
            delegate.debug(format, arg1, arg2);
        }
    }

    @Override
    public void debug(String format, Object... arguments) {
        append("DEBUG", formatted(format, arguments), throwable(format, arguments));
        if (delegate != null) {
            delegate.debug(format, arguments);
        }
    }

    @Override
    public void debug(String message, Throwable throwable) {
        append("DEBUG", message, throwable);
        if (delegate != null) {
            delegate.debug(message, throwable);
        }
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public void info(String message) {
        append("INFO", message, null);
        if (delegate != null) {
            delegate.info(message);
        }
    }

    @Override
    public void info(String format, Object arg) {
        append("INFO", formatted(format, arg), throwable(format, arg));
        if (delegate != null) {
            delegate.info(format, arg);
        }
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        append("INFO", formatted(format, arg1, arg2), throwable(format, arg1, arg2));
        if (delegate != null) {
            delegate.info(format, arg1, arg2);
        }
    }

    @Override
    public void info(String format, Object... arguments) {
        append("INFO", formatted(format, arguments), throwable(format, arguments));
        if (delegate != null) {
            delegate.info(format, arguments);
        }
    }

    @Override
    public void info(String message, Throwable throwable) {
        append("INFO", message, throwable);
        if (delegate != null) {
            delegate.info(message, throwable);
        }
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public void warn(String message) {
        append("WARN", message, null);
        if (delegate != null) {
            delegate.warn(message);
        }
    }

    @Override
    public void warn(String format, Object arg) {
        append("WARN", formatted(format, arg), throwable(format, arg));
        if (delegate != null) {
            delegate.warn(format, arg);
        }
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        append("WARN", formatted(format, arg1, arg2), throwable(format, arg1, arg2));
        if (delegate != null) {
            delegate.warn(format, arg1, arg2);
        }
    }

    @Override
    public void warn(String format, Object... arguments) {
        append("WARN", formatted(format, arguments), throwable(format, arguments));
        if (delegate != null) {
            delegate.warn(format, arguments);
        }
    }

    @Override
    public void warn(String message, Throwable throwable) {
        append("WARN", message, throwable);
        if (delegate != null) {
            delegate.warn(message, throwable);
        }
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public void error(String message) {
        append("ERROR", message, null);
        if (delegate != null) {
            delegate.error(message);
        }
    }

    @Override
    public void error(String format, Object arg) {
        append("ERROR", formatted(format, arg), throwable(format, arg));
        if (delegate != null) {
            delegate.error(format, arg);
        }
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        append("ERROR", formatted(format, arg1, arg2), throwable(format, arg1, arg2));
        if (delegate != null) {
            delegate.error(format, arg1, arg2);
        }
    }

    @Override
    public void error(String format, Object... arguments) {
        append("ERROR", formatted(format, arguments), throwable(format, arguments));
        if (delegate != null) {
            delegate.error(format, arguments);
        }
    }

    @Override
    public void error(String message, Throwable throwable) {
        append("ERROR", message, throwable);
        if (delegate != null) {
            delegate.error(message, throwable);
        }
    }

    public String snapshot() {
        return buffer.toString();
    }

    private void append(String level, String message, Throwable throwable) {
        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(FORMATTER.format(LocalDateTime.now()))
                .append(" [")
                .append(level)
                .append("] ")
                .append(message == null ? "" : message);
        if (throwable != null) {
            buffer.append('\n').append(stackTraceOf(throwable));
        }
    }

    private String formatted(String format, Object arg) {
        return MessageFormatter.format(format, arg).getMessage();
    }

    private String formatted(String format, Object arg1, Object arg2) {
        return MessageFormatter.format(format, arg1, arg2).getMessage();
    }

    private String formatted(String format, Object... arguments) {
        return MessageFormatter.arrayFormat(format, arguments).getMessage();
    }

    private Throwable throwable(String format, Object arg) {
        return MessageFormatter.format(format, arg).getThrowable();
    }

    private Throwable throwable(String format, Object arg1, Object arg2) {
        return MessageFormatter.format(format, arg1, arg2).getThrowable();
    }

    private Throwable throwable(String format, Object... arguments) {
        FormattingTuple tuple = MessageFormatter.arrayFormat(format, arguments);
        return tuple.getThrowable();
    }

    private String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return writer.toString();
    }
}
