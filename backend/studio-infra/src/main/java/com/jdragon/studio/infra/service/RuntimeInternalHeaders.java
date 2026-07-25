package com.jdragon.studio.infra.service;

import java.util.List;
import java.util.Map;

/** Shared protocol constants for OMS-to-runtime internal HTTP calls. */
public final class RuntimeInternalHeaders {
    public static final String INTERNAL_ERROR_HEADER = "X-Studio-Internal-Error";
    public static final String INTERNAL_AUTHENTICATION = "INTERNAL_AUTHENTICATION";
    public static final String RUNTIME_RESPONSE_HEADER = "X-Studio-Runtime-Response";
    public static final String RUNTIME_RESPONSE_AUTHENTICATED = "AUTHENTICATED";
    public static final String IDEMPOTENCY_KEY_HASH_HEADER = "X-Studio-Idempotency-Key-Hash";
    public static final String IDEMPOTENCY_FINGERPRINT_HEADER = "X-Studio-Idempotency-Fingerprint";

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
