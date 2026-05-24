package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataModelDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class CollectionTaskHttpConfigSupport {

    private static final ObjectMapper RUNTIME_OPTION_OBJECT_MAPPER = new ObjectMapper();
    private static final Object NO_VALUE = null;
    private static final Map<String, Object> NO_RESPONSE_STATUS = null;

    private final CollectionTaskFieldMappingResolver fieldMappingResolver;

    CollectionTaskHttpConfigSupport(CollectionTaskFieldMappingResolver fieldMappingResolver) {
        this.fieldMappingResolver = fieldMappingResolver;
    }

    Map<String, Object> buildReaderConfig(Map<String, Object> datasourceConnect,
                                          DataModelDefinition model,
                                          List<String> sourceFields) {
        Map<String, Object> readerConfig = new LinkedHashMap<String, Object>();
        Map<String, Object> metadata = model == null || model.getTechnicalMetadata() == null
                ? Collections.<String, Object>emptyMap()
                : model.getTechnicalMetadata();
        readerConfig.put("url", resolveHttpUrl(datasourceConnect, model, metadata));
        readerConfig.put("mode", resolveHttpMode(metadata));
        readerConfig.put("contentType", "application/json;charset=utf-8");
        readerConfig.put("header", "{}");
        readerConfig.put("params", "{}");
        readerConfig.put("requestBody", "");
        readerConfig.put("resultType", resolveHttpResultType(metadata));
        putIfPresent(readerConfig, "totalCodePath", metadata.get("totalCodePath"), null);
        putIfPresent(readerConfig, "responseStatus", resolveHttpResponseStatus(metadata), null);
        readerConfig.put("pageRead", Boolean.FALSE);
        readerConfig.put("pageSize", Integer.valueOf(500));
        readerConfig.put("columns", fieldMappingResolver.resolveHttpColumnEntries(model, sourceFields));
        return readerConfig;
    }

    Map<String, Object> buildWriterConfig(Map<String, Object> datasourceConnect,
                                          DataModelDefinition model,
                                          List<String> targetFields) {
        Map<String, Object> writerConfig = new LinkedHashMap<String, Object>();
        Map<String, Object> metadata = model == null || model.getTechnicalMetadata() == null
                ? Collections.<String, Object>emptyMap()
                : model.getTechnicalMetadata();
        writerConfig.put("url", resolveHttpUrl(datasourceConnect, model, metadata));
        writerConfig.put("mode", resolveHttpWriterMode(metadata));
        writerConfig.put("contentType", "application/json;charset=utf-8");
        writerConfig.put("header", "{}");
        writerConfig.put("params", "{}");
        writerConfig.put("requestBody", "");
        writerConfig.put("payloadMode", "object");
        writerConfig.put("includeTotal", Boolean.FALSE);
        writerConfig.put("batchSize", Integer.valueOf(500));
        writerConfig.put("retryTimes", Integer.valueOf(3));
        writerConfig.put("retryIntervalMs", Long.valueOf(1000L));
        writerConfig.put("connectTimeoutMs", Integer.valueOf(3000));
        writerConfig.put("socketTimeoutMs", Integer.valueOf(3000));
        writerConfig.put("columns", fieldMappingResolver.resolveHttpWriterColumnEntries(model, targetFields));
        return writerConfig;
    }

    void normalizeReaderRuntimeConfig(Map<String, Object> config) {
        normalizeHttpStringOption(config, "contentType", "application/json;charset=utf-8");
        normalizeHttpJsonObjectString(config, "header", "HTTP reader");
        normalizeHttpJsonObjectString(config, "params", "HTTP reader");
        normalizeHttpStringOption(config, "requestBody", "");
    }

    void normalizeWriterRuntimeConfig(Map<String, Object> config) {
        normalizeHttpStringOption(config, "contentType", "application/json;charset=utf-8");
        normalizeHttpJsonObjectString(config, "header", "HTTP writer");
        normalizeHttpJsonObjectString(config, "params", "HTTP writer");
        normalizeHttpStringOption(config, "requestBody", "");
        if (isBlankValue(config.get("payloadMode"))) {
            config.put("payloadMode", "object");
        }
        if (isBlankValue(config.get("batchSize"))) {
            config.put("batchSize", Integer.valueOf(500));
        }
        boolean includeTotal = booleanValue(config.get("includeTotal"));
        config.put("includeTotal", Boolean.valueOf(includeTotal));
        if (includeTotal && isBlankValue(config.get("totalNodePath"))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP writer totalNodePath is required when includeTotal is true");
        }
    }

    Set<String> runtimeStringKeys() {
        Set<String> keys = new LinkedHashSet<String>();
        keys.add("header");
        keys.add("params");
        keys.add("requestbody");
        return keys;
    }

    private String resolveHttpUrl(Map<String, Object> datasourceConnect,
                                  DataModelDefinition model,
                                  Map<String, Object> metadata) {
        Object requestPathValue = firstPresent(metadata, "physicalName", "requestPath");
        String requestPath = model == null ? null : model.getPhysicalLocator();
        if (isBlank(requestPath) && !isBlankValue(requestPathValue)) {
            requestPath = String.valueOf(requestPathValue).trim();
        }
        if (!isBlank(requestPath) && isAbsoluteHttpUrl(requestPath)) {
            return requestPath.trim();
        }
        String baseUrl = datasourceConnect == null || datasourceConnect.get("url") == null
                ? null
                : String.valueOf(datasourceConnect.get("url")).trim();
        if (isBlank(baseUrl)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP datasource url is required");
        }
        if (isBlank(requestPath)) {
            return baseUrl;
        }
        return joinHttpUrl(baseUrl, requestPath.trim());
    }

    private String joinHttpUrl(String baseUrl, String requestPath) {
        boolean baseEndsWithSlash = baseUrl.endsWith("/");
        boolean pathStartsWithSlash = requestPath.startsWith("/");
        if (baseEndsWithSlash && pathStartsWithSlash) {
            return baseUrl + requestPath.substring(1);
        }
        if (!baseEndsWithSlash && !pathStartsWithSlash) {
            return baseUrl + "/" + requestPath;
        }
        return baseUrl + requestPath;
    }

    private boolean isAbsoluteHttpUrl(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private String resolveHttpMode(Map<String, Object> metadata) {
        Object mode = metadata == null ? null : metadata.get("mode");
        return isBlankValue(mode) ? "GET" : String.valueOf(mode).trim().toUpperCase(Locale.ENGLISH);
    }

    private String resolveHttpWriterMode(Map<String, Object> metadata) {
        Object mode = metadata == null ? null : metadata.get("mode");
        return isBlankValue(mode) ? "POST" : String.valueOf(mode).trim().toUpperCase(Locale.ENGLISH);
    }

    private String resolveHttpResultType(Map<String, Object> metadata) {
        Object resultType = metadata == null ? null : metadata.get("resultType");
        return isBlankValue(resultType) ? "json" : String.valueOf(resultType).trim().toLowerCase(Locale.ENGLISH);
    }

    private Map<String, Object> resolveHttpResponseStatus(Map<String, Object> metadata) {
        Object statusPath = metadata == null ? null : metadata.get("businessStatusPath");
        Object statusCode = metadata == null ? null : metadata.get("businessStatusCode");
        boolean hasPath = !isBlankValue(statusPath);
        boolean hasCode = !isBlankValue(statusCode);
        if (!hasPath && !hasCode) {
            return NO_RESPONSE_STATUS;
        }
        if (!hasPath || !hasCode) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP business status path and code must be configured together");
        }
        Map<String, Object> responseStatus = new LinkedHashMap<String, Object>();
        responseStatus.put("path", String.valueOf(statusPath).trim());
        responseStatus.put("code", String.valueOf(statusCode).trim());
        return responseStatus;
    }

    private void normalizeHttpStringOption(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        if (isBlankValue(value)) {
            config.put(key, defaultValue);
            return;
        }
        if (value instanceof String) {
            config.put(key, value);
            return;
        }
        try {
            config.put(key, RUNTIME_OPTION_OBJECT_MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            config.put(key, String.valueOf(value));
        }
    }

    private void normalizeHttpJsonObjectString(Map<String, Object> config, String key, String label) {
        Object value = config.get(key);
        String text;
        if (isBlankValue(value)) {
            text = "{}";
        } else if (value instanceof String) {
            text = ((String) value).trim();
        } else {
            try {
                text = RUNTIME_OPTION_OBJECT_MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, label + " " + key + " must be a JSON object string");
            }
        }
        try {
            JsonNode node = RUNTIME_OPTION_OBJECT_MAPPER.readTree(text);
            if (node == null || !node.isObject()) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, label + " " + key + " must be a JSON object string");
            }
        } catch (StudioException e) {
            throw e;
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, label + " " + key + " must be a JSON object string");
        }
        config.put(key, text);
    }

    private Object firstPresent(Map<String, Object> metadata, String... keys) {
        if (metadata == null || keys == null) {
            return NO_VALUE;
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (!isBlankValue(value)) {
                return value;
            }
        }
        return NO_VALUE;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value, Object defaultValue) {
        if (!isBlankValue(value)) {
            target.put(key, value);
        } else if (defaultValue != null) {
            target.put(key, defaultValue);
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return Boolean.TRUE.equals(value);
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isBlankValue(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }
}
