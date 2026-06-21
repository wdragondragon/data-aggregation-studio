package com.jdragon.studio.infra.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.jdragon.studio.commons.constant.StudioConstants;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class OpenServiceInvocationLogSupport {

    static final String MDC_KEY = StudioConstants.MDC_OPEN_SERVICE_INVOCATION_LOG_ID;
    static final int MAX_LOG_CHARS = 1024 * 1024;
    private static final Object LEVEL_LOCK = new Object();
    private static final Map<Logger, LevelReference> LEVEL_REFERENCES = new IdentityHashMap<Logger, LevelReference>();
    private static final List<String> INFO_LOGGER_NAMES = Arrays.asList(
            DataIngestionExecutionSupport.class.getName(),
            "com.jdragon.aggregation"
    );
    private static final String SENSITIVE_NAME_PATTERN =
            "[A-Za-z0-9_.-]*(?:password|access[_-]?key|secret|token|private[_-]?key|api[_-]?key|authorization|cookie|credential)[A-Za-z0-9_.-]*";
    private static final Pattern SENSITIVE_JSON_VALUE = Pattern.compile(
            "(?i)(\"(?:" + SENSITIVE_NAME_PATTERN + ")\"\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern SENSITIVE_ASSIGNMENT_VALUE = Pattern.compile(
            "(?i)(\\b(?:" + SENSITIVE_NAME_PATTERN + ")\\b\\s*[=:]\\s*)([^,&\"'\\s}\\]<]+)");
    private static final String XML_NAME_PREFIX = "(?:[A-Za-z_][A-Za-z0-9_.-]*:)?";
    private static final Pattern SENSITIVE_XML_ELEMENT_VALUE = Pattern.compile(
            "(?is)(<\\s*" + XML_NAME_PREFIX + "(?:" + SENSITIVE_NAME_PATTERN + ")\\b[^>]*>)([^<]*)(</\\s*"
                    + XML_NAME_PREFIX + "(?:" + SENSITIVE_NAME_PATTERN + ")\\s*>)");
    private static final Pattern SENSITIVE_XML_ATTRIBUTE_VALUE = Pattern.compile(
            "(?i)(\\b" + XML_NAME_PREFIX + "(?:" + SENSITIVE_NAME_PATTERN + ")\\s*=\\s*[\"'])([^\"']*)([\"'])");

    private final LoggerContext loggerContext;
    private final Logger rootLogger;

    OpenServiceInvocationLogSupport() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext) {
            this.loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            this.rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        } else {
            this.loggerContext = null;
            this.rootLogger = null;
        }
    }

    LogScope open(String requestId, String domain, Long jobId) {
        if (loggerContext == null || rootLogger == null || isBlank(requestId)) {
            return new LogScope(requestId);
        }
        List<Logger> levelOverrides = ensureInfoLogging();
        InMemoryAppender appender = new InMemoryAppender(loggerContext, requestId, domain, jobId);
        rootLogger.addAppender(appender);
        MDC.put(MDC_KEY, requestId);
        return new LogScope(requestId, appender, levelOverrides);
    }

    static void withMdc(String requestId, Runnable runnable) {
        if (runnable == null) {
            return;
        }
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (!isBlank(requestId)) {
                MDC.put(MDC_KEY, requestId);
            }
            runnable.run();
        } finally {
            restoreMdc(previous);
        }
    }

    static String sanitizeSensitiveLog(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = SENSITIVE_JSON_VALUE.matcher(value).replaceAll("$1******$3");
        sanitized = SENSITIVE_XML_ATTRIBUTE_VALUE.matcher(sanitized).replaceAll("$1******$3");
        sanitized = SENSITIVE_ASSIGNMENT_VALUE.matcher(sanitized).replaceAll("$1******");
        return SENSITIVE_XML_ELEMENT_VALUE.matcher(sanitized).replaceAll("$1******$3");
    }

    private List<Logger> ensureInfoLogging() {
        List<Logger> result = new ArrayList<Logger>();
        synchronized (LEVEL_LOCK) {
            for (String loggerName : INFO_LOGGER_NAMES) {
                Logger logger = loggerContext.getLogger(loggerName);
                LevelReference reference = LEVEL_REFERENCES.get(logger);
                if (reference != null) {
                    reference.count++;
                    result.add(logger);
                    continue;
                }
                Level effectiveLevel = logger.getEffectiveLevel();
                if (effectiveLevel == null || effectiveLevel.toInt() > Level.INFO_INT) {
                    LEVEL_REFERENCES.put(logger, new LevelReference(logger.getLevel()));
                    logger.setLevel(Level.INFO);
                    result.add(logger);
                }
            }
        }
        return result;
    }

    private static void restoreLogging(List<Logger> loggers) {
        if (loggers == null || loggers.isEmpty()) {
            return;
        }
        synchronized (LEVEL_LOCK) {
            for (Logger logger : loggers) {
                LevelReference reference = LEVEL_REFERENCES.get(logger);
                if (reference == null) {
                    continue;
                }
                reference.count--;
                if (reference.count <= 0) {
                    logger.setLevel(reference.previousLevel);
                    LEVEL_REFERENCES.remove(logger);
                }
            }
        }
    }

    private static void restoreMdc(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    final class LogScope implements AutoCloseable {
        private final String requestId;
        private final InMemoryAppender appender;
        private final List<Logger> levelOverrides;
        private final boolean disabled;

        private LogScope(String requestId, InMemoryAppender appender, List<Logger> levelOverrides) {
            this.requestId = requestId;
            this.appender = appender;
            this.levelOverrides = levelOverrides;
            this.disabled = false;
        }

        private LogScope(String requestId) {
            this.requestId = requestId;
            this.appender = null;
            this.levelOverrides = null;
            this.disabled = true;
        }

        String content() {
            return appender == null ? "" : appender.content();
        }

        @Override
        public void close() {
            if (!disabled && requestId != null && requestId.equals(MDC.get(MDC_KEY))) {
                MDC.remove(MDC_KEY);
            }
            if (appender != null) {
                rootLogger.detachAppender(appender);
                appender.stop();
            }
            restoreLogging(levelOverrides);
        }
    }

    private static final class LevelReference {
        private final Level previousLevel;
        private int count = 1;

        private LevelReference(Level previousLevel) {
            this.previousLevel = previousLevel;
        }
    }

    private static final class InMemoryAppender extends AppenderBase<ILoggingEvent> {
        private final String requestId;
        private final Set<String> threadNames = new LinkedHashSet<String>();
        private final PatternLayout layout;
        private final StringBuilder content = new StringBuilder(8192);
        private boolean truncated;

        private InMemoryAppender(LoggerContext context, String requestId, String domain, Long jobId) {
            this.requestId = requestId;
            setContext(context);
            setName(("OPEN_SERVICE_INVOCATION_" + domain + "_" + requestId).replaceAll("[^A-Za-z0-9_-]", "_"));
            this.layout = new PatternLayout();
            this.layout.setContext(context);
            this.layout.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n%ex");
            this.layout.start();
            if (jobId != null) {
                String suffix = String.valueOf(jobId);
                this.threadNames.addAll(Arrays.asList(
                        "DataIngestion-JobContainer-" + suffix,
                        "DataAggregation-Thread-reader-" + suffix,
                        "DataAggregation-Thread-writer-" + suffix,
                        "DataAggregation-Thread-reporter-" + suffix
                ));
            }
            start();
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (event == null || truncated || !matches(event)) {
                return;
            }
            event.prepareForDeferredProcessing();
            appendText(layout.doLayout(event));
        }

        private boolean matches(ILoggingEvent event) {
            String currentRequestId = event.getMDCPropertyMap() == null ? null : event.getMDCPropertyMap().get(MDC_KEY);
            if (requestId.equals(currentRequestId)) {
                return true;
            }
            String threadName = event.getThreadName();
            return threadName != null && threadNames.contains(threadName);
        }

        private synchronized void appendText(String value) {
            if (value == null || value.isEmpty() || truncated) {
                return;
            }
            value = sanitizeSensitiveLog(value);
            int remaining = MAX_LOG_CHARS - content.length();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            if (value.length() <= remaining) {
                content.append(value);
                return;
            }
            content.append(value, 0, remaining);
            truncated = true;
            content.append(System.lineSeparator()).append("[log truncated after ").append(MAX_LOG_CHARS).append(" chars]");
        }

        private synchronized String content() {
            return content.toString();
        }
    }
}
