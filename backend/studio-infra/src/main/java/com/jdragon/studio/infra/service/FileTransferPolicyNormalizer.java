package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class FileTransferPolicyNormalizer {

    public static final int DEFAULT_FRAME_COUNT = 16;
    public static final long DEFAULT_FRAME_SIZE_BYTES = 1024L * 1024L;
    private static final long FRAME_ALIGNMENT = 64L * 1024L;
    private static final Set<String> VERIFICATION_MODES = Set.of("NONE", "PARTIAL", "STRONG");
    private static final Set<String> SOURCE_ACTIONS = Set.of("KEEP", "DELETE", "BACKUP");

    private FileTransferPolicyNormalizer() {
    }

    public static Map<String, Object> normalize(Map<String, Object> values) {
        Map<String, Object> result = values == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(values);
        result.put("conflictPolicy", enumValue(result.get("conflictPolicy"), "FAIL",
                Set.of("FAIL", "OVERWRITE", "BACKUP_THEN_OVERWRITE"), "policy.conflictPolicy"));
        String checksumAlgorithm = stringValue(result.get("checksumAlgorithm"), "SHA-256").toUpperCase(Locale.ROOT);
        if (!"SHA-256".equals(checksumAlgorithm)) {
            throw bad("policy.checksumAlgorithm only supports SHA-256");
        }
        result.put("checksumAlgorithm", checksumAlgorithm);
        String verificationMode = enumValue(result.get("verificationMode"), "STRONG",
                VERIFICATION_MODES, "policy.verificationMode");
        result.put("verificationMode", verificationMode);
        int frameCount = integerValue(result.get("verificationFrameCount"), DEFAULT_FRAME_COUNT,
                "policy.verificationFrameCount");
        if (frameCount < 1 || frameCount > 64) {
            throw bad("policy.verificationFrameCount must be between 1 and 64");
        }
        result.put("verificationFrameCount", frameCount);
        long frameSize = longValue(result.get("verificationFrameSizeBytes"), DEFAULT_FRAME_SIZE_BYTES,
                "policy.verificationFrameSizeBytes");
        if (frameSize < FRAME_ALIGNMENT || frameSize > 4L * 1024L * 1024L
                || frameSize % FRAME_ALIGNMENT != 0L) {
            throw bad("policy.verificationFrameSizeBytes must be a 64 KiB multiple between 64 KiB and 4 MiB");
        }
        result.put("verificationFrameSizeBytes", frameSize);
        String sourceAction = enumValue(result.get("sourceSuccessAction"), "KEEP",
                SOURCE_ACTIONS, "policy.sourceSuccessAction");
        if ("NONE".equals(verificationMode) && !"KEEP".equals(sourceAction)) {
            throw bad("verificationMode NONE only supports sourceSuccessAction KEEP");
        }
        if ("BACKUP".equals(sourceAction)
                && !hasText(result.get("sourceBackupRootPath"))) {
            throw bad("policy.sourceBackupRootPath is required for source BACKUP action");
        }
        result.put("sourceSuccessAction", sourceAction);
        if (!"BACKUP".equals(sourceAction)) {
            result.put("sourceBackupRootPath", null);
        }
        return result;
    }

    private static String enumValue(Object raw, String fallback, Set<String> allowed, String field) {
        String value = stringValue(raw, fallback).trim().replace('-', '_').toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw bad(field + " is invalid: " + value);
        }
        return value;
    }

    private static int integerValue(Object raw, int fallback, String field) {
        long value = longValue(raw, fallback, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw bad(field + " is outside the integer range");
        }
        return (int) value;
    }

    private static long longValue(Object raw, long fallback, String field) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(raw)).longValueExact();
        } catch (RuntimeException exception) {
            throw bad(field + " must be an integer");
        }
    }

    private static String stringValue(Object raw, String fallback) {
        return raw == null || String.valueOf(raw).isBlank() ? fallback : String.valueOf(raw);
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static StudioException bad(String message) {
        return new StudioException(StudioErrorCode.BAD_REQUEST, message);
    }
}
