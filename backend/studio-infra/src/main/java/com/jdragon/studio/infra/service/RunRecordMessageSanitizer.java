package com.jdragon.studio.infra.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RunRecordMessageSanitizer {

    private static final int RUN_RECORD_MESSAGE_MAX_LENGTH = 2000;
    private static final String TRUNCATED_MESSAGE_SUFFIX = "...";
    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile(
            "\\s+at\\s+(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$][A-Za-z0-9_$]*(?:[.$][A-Za-z_$][A-Za-z0-9_$]*)*\\(");
    private static final Pattern EMBEDDED_EXCEPTION_PREFIX_PATTERN = Pattern.compile(
            "(?:^|[\\s\\-:])((?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error):\\s*)");
    private static final String LEADING_EXCEPTION_PREFIX_REGEX =
            "^(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error):\\s*";

    private RunRecordMessageSanitizer() {
    }

    static String sanitizeAndTruncateMessage(String message) {
        return truncateMessage(sanitizeMessage(message));
    }

    static Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Map<String, Object> sanitized = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            if (key != null) {
                sanitized.put(key, sanitizePayloadValue(key, entry.getValue()));
            }
        }
        return sanitized;
    }

    static Map<String, Object> sanitizePayloadOrEmpty(Map<String, Object> payload) {
        Map<String, Object> sanitized = sanitizePayload(payload);
        return sanitized == null ? new LinkedHashMap<String, Object>() : sanitized;
    }

    private static Object sanitizePayloadValue(String key, Object value) {
        if (value instanceof Map<?, ?>) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> nested = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() != null) {
                    String nestedKey = String.valueOf(entry.getKey());
                    nested.put(nestedKey, sanitizePayloadValue(nestedKey, entry.getValue()));
                }
            }
            return nested;
        }
        if (value instanceof List<?>) {
            List<Object> nested = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                nested.add(sanitizePayloadValue(key, item));
            }
            return nested;
        }
        if (value instanceof String && isErrorMessageKey(key)) {
            return sanitizeAndTruncateMessage((String) value);
        }
        if (value instanceof String && "exceptionType".equalsIgnoreCase(key)) {
            return simpleExceptionName((String) value);
        }
        return value;
    }

    private static boolean isErrorMessageKey(String key) {
        return "message".equalsIgnoreCase(key)
                || "error".equalsIgnoreCase(key)
                || "errorMessage".equalsIgnoreCase(key)
                || "logErrorSummary".equalsIgnoreCase(key);
    }

    private static String sanitizeMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return message;
        }
        String normalized = message.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        int causedByIndex = normalized.lastIndexOf("Caused by:");
        if (causedByIndex >= 0) {
            normalized = normalized.substring(causedByIndex + "Caused by:".length()).trim();
        } else {
            int exceptionIndex = lastEmbeddedExceptionPrefixIndex(normalized);
            if (exceptionIndex >= 0) {
                normalized = normalized.substring(exceptionIndex).trim();
            }
        }
        normalized = trimStackFrames(normalized);
        normalized = stripExceptionClassPrefix(normalized);
        return normalized.isEmpty() ? message.trim() : normalized;
    }

    private static int lastEmbeddedExceptionPrefixIndex(String message) {
        Matcher matcher = EMBEDDED_EXCEPTION_PREFIX_PATTERN.matcher(message);
        int result = -1;
        while (matcher.find()) {
            result = matcher.start(1);
        }
        return result;
    }

    private static String trimStackFrames(String message) {
        Matcher matcher = STACK_FRAME_PATTERN.matcher(message);
        if (matcher.find()) {
            return message.substring(0, matcher.start()).trim();
        }
        return message;
    }

    private static String stripExceptionClassPrefix(String message) {
        String result = message;
        while (result != null) {
            String stripped = result.replaceFirst(LEADING_EXCEPTION_PREFIX_REGEX, "").trim();
            if (stripped.equals(result)) {
                return result;
            }
            result = stripped;
        }
        return message;
    }

    private static String simpleExceptionName(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        int dotIndex = text.lastIndexOf('.');
        return dotIndex >= 0 && dotIndex + 1 < text.length() ? text.substring(dotIndex + 1) : text;
    }

    private static String truncateMessage(String message) {
        if (message == null || message.length() <= RUN_RECORD_MESSAGE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, RUN_RECORD_MESSAGE_MAX_LENGTH - TRUNCATED_MESSAGE_SUFFIX.length())
                + TRUNCATED_MESSAGE_SUFFIX;
    }
}
