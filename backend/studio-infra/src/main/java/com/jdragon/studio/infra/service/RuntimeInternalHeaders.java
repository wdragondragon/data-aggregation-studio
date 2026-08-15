package com.jdragon.studio.infra.service;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

import java.util.List;
import java.util.Map;

/** Shared protocol constants for OMS-to-runtime internal HTTP calls. */
public final class RuntimeInternalHeaders {

    private static final int MAX_OPERATION_ID_LENGTH = 64;
    private static final Pattern SAFE_OPERATION_ID = Pattern.compile("[A-Za-z0-9._-]+");
    public static final String INTERNAL_ERROR_HEADER = "X-Studio-Internal-Error";
    public static final String INTERNAL_AUTHENTICATION = "INTERNAL_AUTHENTICATION";
    public static final String RUNTIME_RESPONSE_HEADER = "X-Studio-Runtime-Response";
    public static final String RUNTIME_RESPONSE_AUTHENTICATED = "AUTHENTICATED";
    public static final String RUNTIME_REQUEST_HEADER = "X-Studio-Runtime-Request";
    public static final String IDEMPOTENCY_KEY_HASH_HEADER = "X-Studio-Idempotency-Key-Hash";
    public static final String IDEMPOTENCY_FINGERPRINT_HEADER = "X-Studio-Idempotency-Fingerprint";
    public static final String OPERATION_ID_HEADER = "X-Studio-Operation-Id";

    public static String normalizeOperationId(String operationId) {
        if (!StringUtils.hasText(operationId)) {
            return null;
        }
        String value = operationId.trim();
        if (value.length() > MAX_OPERATION_ID_LENGTH || !SAFE_OPERATION_ID.matcher(value).matches()) {
            return null;
        }
        return value;
    }

    public static boolean isAuthenticatedRuntimeResponse(Map<String, ? extends List<String>> headers) {
        if (headers == null) {
            return false;
        }
        for (Map.Entry<String, ? extends List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !RUNTIME_RESPONSE_HEADER.equalsIgnoreCase(entry.getKey())
                    || entry.getValue() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value != null && RUNTIME_RESPONSE_AUTHENTICATED.equalsIgnoreCase(value.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private RuntimeInternalHeaders() {
    }
}
