package com.jdragon.studio.infra.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Builds opaque hashes for the OMS-to-Worker idempotency protocol. */
public final class RuntimeInvocationFingerprintSupport {
    private static final String VERSION = "studio.runtime.idempotency.v1";

    private RuntimeInvocationFingerprintSupport() {
    }

    public static String hashKey(String key) {
        return sha256(key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8));
    }

    public static String fingerprint(String kind,
                                     String serviceCode,
                                     String serviceKey,
                                     String variant,
                                     String method,
                                     String rawQuery,
                                     String contentType,
                                     Map<String, List<String>> businessHeaders,
                                     byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, VERSION);
            update(digest, normalize(kind));
            update(digest, serviceCode);
            update(digest, serviceKey);
            update(digest, normalize(variant));
            update(digest, normalize(method));
            update(digest, rawQuery);
            update(digest, normalizeContentType(contentType));
            updateHeaders(digest, businessHeaders);
            update(digest, body == null ? new byte[0] : body);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public static boolean isSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (!((current >= '0' && current <= '9') || (current >= 'a' && current <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void update(MessageDigest digest, String value) {
        update(digest, value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    private static void update(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeContentType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void updateHeaders(MessageDigest digest, Map<String, List<String>> headers) {
        List<Map.Entry<String, List<String>>> entries = headers == null
                ? new ArrayList<Map.Entry<String, List<String>>>()
                : new ArrayList<Map.Entry<String, List<String>>>(headers.entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey() == null
                ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT)));
        update(digest, Integer.toString(entries.size()));
        for (Map.Entry<String, List<String>> entry : entries) {
            update(digest, entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT));
            List<String> values = entry.getValue() == null ? List.of() : entry.getValue();
            update(digest, Integer.toString(values.size()));
            for (String value : values) {
                update(digest, value);
            }
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte current : value) {
            result.append(Character.forDigit((current >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(current & 0x0f, 16));
        }
        return result.toString();
    }
}
