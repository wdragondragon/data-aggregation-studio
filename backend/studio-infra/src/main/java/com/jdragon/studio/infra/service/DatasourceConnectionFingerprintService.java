package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public class DatasourceConnectionFingerprintService {

    private final EncryptionService encryptionService;
    private final StudioPlatformProperties properties;
    private final ObjectMapper objectMapper;

    public DatasourceConnectionFingerprintService(EncryptionService encryptionService,
                                                  StudioPlatformProperties properties) {
        this.encryptionService = encryptionService;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String fingerprint(String tenantId, String typeCode, Map<String, Object> technicalMetadata) {
        String normalizedTenantId = normalizeText(tenantId, "default");
        String normalizedTypeCode = normalizeText(typeCode, "");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("tenantId", normalizedTenantId);
        payload.put("typeCode", normalizedTypeCode.toLowerCase(Locale.ROOT));
        payload.put("technicalMetadata", normalizeValue(null, technicalMetadata));
        return hmacSha256(toJson(payload));
    }

    private Object normalizeValue(String key, Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> sorted = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String childKey = String.valueOf(entry.getKey());
                sorted.put(childKey, normalizeValue(childKey, entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?>) {
            List<Object> normalized = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                normalized.add(normalizeValue(null, item));
            }
            return normalized;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> normalized = new ArrayList<Object>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                normalized.add(normalizeValue(null, java.lang.reflect.Array.get(value, index)));
            }
            return normalized;
        }
        if (value instanceof String && isSensitive(key)) {
            return decryptIfEncrypted(String.valueOf(value));
        }
        if (value instanceof String) {
            return String.valueOf(value).trim();
        }
        return value;
    }

    private String decryptIfEncrypted(String value) {
        if (value == null || !value.startsWith("ENC(") || !value.endsWith(")")) {
            return value;
        }
        String cipher = value.substring(4, value.length() - 1);
        return encryptionService.decrypt(cipher);
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("accesskey");
    }

    private String normalizeText(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Failed to normalize datasource connection fingerprint", e);
        }
    }

    private String hmacSha256(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(resolveSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item & 0xff));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Failed to calculate datasource connection fingerprint", e);
        }
    }

    private String resolveSecret() {
        String secret = properties == null ? null : properties.getEncryptionSecret();
        return secret == null || secret.trim().isEmpty() ? "studio-secret-key" : secret.trim();
    }
}
