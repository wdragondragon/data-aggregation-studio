package com.jdragon.studio.commons.logging;

import java.util.regex.Pattern;

/**
 * Redacts credential-like values before they reach persisted or externally visible logs.
 */
public final class StudioSensitiveLogSanitizer {

    private static final String SENSITIVE_NAME_PATTERN =
            "[A-Za-z0-9_.-]*(?:password|access[_-]?key|secret|token|private[_-]?key|api[_-]?key|authorization|cookie|credential)[A-Za-z0-9_.-]*";
    private static final Pattern SENSITIVE_JSON_VALUE = Pattern.compile(
            "(?i)(\"(?:" + SENSITIVE_NAME_PATTERN + ")\"\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern SENSITIVE_HEADER_VALUE = Pattern.compile(
            "(?i)(\\b(?:authorization|cookie)\\b\\s*[:=]\\s*)([^\\r\\n]*)");
    private static final Pattern SENSITIVE_ASSIGNMENT_VALUE = Pattern.compile(
            "(?i)(\\b(?:" + SENSITIVE_NAME_PATTERN + ")\\b\\s*[=:]\\s*)([^,&\"'\\s}\\]<]+)");
    private static final Pattern JDBC_CONNECTION_URL = Pattern.compile(
            "(?i)(\\bjdbc:[a-z0-9]+:)(?://)?[^\\s,\\]\\[)]+");
    private static final String XML_NAME_PREFIX = "(?:[A-Za-z_][A-Za-z0-9_.-]*:)?";
    private static final Pattern SENSITIVE_XML_ELEMENT_VALUE = Pattern.compile(
            "(?is)(<\\s*" + XML_NAME_PREFIX + "(?:" + SENSITIVE_NAME_PATTERN + ")\\b[^>]*>)([^<]*)(</\\s*"
                    + XML_NAME_PREFIX + "(?:" + SENSITIVE_NAME_PATTERN + ")\\s*>)");
    private static final Pattern SENSITIVE_XML_ATTRIBUTE_VALUE = Pattern.compile(
            "(?i)(\\b" + XML_NAME_PREFIX + "(?:" + SENSITIVE_NAME_PATTERN + ")\\s*=\\s*[\"'])([^\"']*)([\"'])");

    private StudioSensitiveLogSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = SENSITIVE_JSON_VALUE.matcher(value).replaceAll("$1******$3");
        sanitized = SENSITIVE_XML_ATTRIBUTE_VALUE.matcher(sanitized).replaceAll("$1******$3");
        sanitized = SENSITIVE_XML_ELEMENT_VALUE.matcher(sanitized).replaceAll("$1******$3");
        sanitized = SENSITIVE_HEADER_VALUE.matcher(sanitized).replaceAll("$1******");
        sanitized = SENSITIVE_ASSIGNMENT_VALUE.matcher(sanitized).replaceAll("$1******");
        return JDBC_CONNECTION_URL.matcher(sanitized).replaceAll("$1******");
    }
}
