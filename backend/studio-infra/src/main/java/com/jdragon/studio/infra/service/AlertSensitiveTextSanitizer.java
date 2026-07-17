package com.jdragon.studio.infra.service;

import java.util.regex.Pattern;

final class AlertSensitiveTextSanitizer {

    private static final String SENSITIVE_NAME_PATTERN =
            "[A-Za-z0-9_.-]*(?:password|access[_-]?key|secret|token|private[_-]?key|api[_-]?key|authorization|cookie|credential)[A-Za-z0-9_.-]*";
    private static final Pattern SENSITIVE_JSON_VALUE = Pattern.compile(
            "(?i)(\"(?:" + SENSITIVE_NAME_PATTERN + ")\"\\s*:\\s*)(\"(?:\\\\.|[^\"\\\\])*\"|[^,}\\]\\s]+)");
    private static final Pattern SENSITIVE_HEADER_VALUE = Pattern.compile(
            "(?i)(\\b(?:authorization|proxy-authorization|cookie|set-cookie)\\b\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern MOBILE_VALUE = Pattern.compile("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)");

    private AlertSensitiveTextSanitizer() {
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String sanitized = SENSITIVE_JSON_VALUE.matcher(value).replaceAll("$1\"******\"");
        sanitized = SENSITIVE_HEADER_VALUE.matcher(sanitized).replaceAll("$1******");
        sanitized = MOBILE_VALUE.matcher(sanitized).replaceAll("$1****$2");
        return OpenServiceInvocationLogSupport.sanitizeSensitiveLog(sanitized);
    }
}
