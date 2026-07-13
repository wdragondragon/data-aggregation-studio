package com.jdragon.studio.flink.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class HttpBodyPushdownValidator {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String HTTP_READER_CONFIG_KEY = "__studio_http_reader_config";
    private static final Set<String> BODY_METHODS = new LinkedHashSet<String>(
            Arrays.asList("POST", "PUT", "PATCH"));

    private HttpBodyPushdownValidator() {
    }

    static void validate(AggregationFlinkTableRuntime runtime, List<Map<String, Object>> filters) {
        if (!containsBodyFilter(filters)) {
            return;
        }
        String method = resolveMethod(runtime);
        if (!BODY_METHODS.contains(method)) {
            throw new IllegalArgumentException("HTTP body 下推仅支持可携带请求体的方法 POST/PUT/PATCH，当前 method="
                    + method);
        }
    }

    private static boolean containsBodyFilter(List<Map<String, Object>> filters) {
        if (filters == null) {
            return false;
        }
        for (Map<String, Object> filter : filters) {
            if (filter != null && "body".equals(HttpPushdownMappingConfig.normalizeLocation(
                    stringValue(filter.get("location"))))) {
                return true;
            }
        }
        return false;
    }

    private static String resolveMethod(AggregationFlinkTableRuntime runtime) {
        Map<String, Object> readerConfig = readerConfig(runtime);
        String configuredMethod = stringValue(readerConfig.get("mode"));
        if (hasText(configuredMethod)) {
            return configuredMethod.toUpperCase(Locale.ENGLISH);
        }
        Map<String, Object> metadata = runtime == null || runtime.getModelMetadata() == null
                ? Collections.<String, Object>emptyMap()
                : runtime.getModelMetadata();
        if (isSoap(metadata)) {
            return "POST";
        }
        String method = stringValue(metadata.get("mode"));
        return hasText(method) ? method.toUpperCase(Locale.ENGLISH) : "GET";
    }

    private static Map<String, Object> readerConfig(AggregationFlinkTableRuntime runtime) {
        BaseDataSourceDTO dto = runtime == null ? null : runtime.getDataSourceDTO();
        String configJson = dto == null || dto.getExtraParams() == null
                ? null
                : dto.getExtraParams().get(HTTP_READER_CONFIG_KEY);
        if (!hasText(configJson)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> config = OBJECT_MAPPER.readValue(configJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            return config == null ? Collections.<String, Object>emptyMap() : config;
        } catch (Exception ex) {
            throw new IllegalArgumentException("HTTP reader config 无法解析 method，不能校验 body 下推", ex);
        }
    }

    private static boolean isSoap(Map<String, Object> metadata) {
        return "SOAP".equalsIgnoreCase(stringValue(metadata.get("protocolMode")))
                || "soap".equalsIgnoreCase(stringValue(metadata.get("resultType")));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
