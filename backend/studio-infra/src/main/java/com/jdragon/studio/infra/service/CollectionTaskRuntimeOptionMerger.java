package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.commons.util.Configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class CollectionTaskRuntimeOptionMerger {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Object SKIP_VALUE = new Object();

    void merge(Map<String, Object> config,
               Map<String, Object> runtimeOptions,
               String role,
               Iterable<String> reservedKeys) {
        merge(config, runtimeOptions, role, Collections.<String>emptySet(), reservedKeys);
    }

    void merge(Map<String, Object> config,
               Map<String, Object> runtimeOptions,
               String role,
               Set<String> preserveStringKeys,
               Iterable<String> reservedKeys) {
        if (runtimeOptions == null || runtimeOptions.isEmpty()) {
            return;
        }
        Set<String> reserved = new LinkedHashSet<String>();
        for (String reservedKey : reservedKeys) {
            if (reservedKey != null) {
                reserved.add(reservedKey.trim().toLowerCase(Locale.ENGLISH));
            }
        }
        Configuration configuration = Configuration.from(config);
        for (Map.Entry<String, Object> entry : runtimeOptions.entrySet()) {
            String key = entry.getKey();
            if (isBlank(key) || isReservedRuntimeOptionKey(key, reserved)) {
                continue;
            }
            Object value = normalizeRuntimeOptionValue(key, entry.getValue(), preserveStringKeys);
            if (value == SKIP_VALUE) {
                continue;
            }
            configuration.set(key.trim(), value);
        }
        config.clear();
        config.putAll(configuration.getMap("", Collections.<String, Object>emptyMap()));
    }

    Map<String, Object> toMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map<?, ?>)) {
            return result;
        }
        Map<?, ?> source = (Map<?, ?>) value;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private boolean isReservedRuntimeOptionKey(String key, Set<String> reserved) {
        String normalized = key.trim().toLowerCase(Locale.ENGLISH);
        if (reserved.contains(normalized)) {
            return true;
        }
        int dotIndex = normalized.indexOf('.');
        return dotIndex > 0 && reserved.contains(normalized.substring(0, dotIndex));
    }

    private Object normalizeRuntimeOptionValue(String key, Object value, Set<String> preserveStringKeys) {
        if (preserveStringKeys != null && preserveStringKeys.contains(normalizeKey(key))) {
            return value;
        }
        if (!(value instanceof String)) {
            return value;
        }
        String text = ((String) value).trim();
        if (text.isEmpty()) {
            return SKIP_VALUE;
        }
        if (!(text.startsWith("{") || text.startsWith("["))) {
            return value;
        }
        try {
            return OBJECT_MAPPER.readValue(text, Object.class);
        } catch (Exception parseFailure) {
            return value;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ENGLISH);
    }
}
