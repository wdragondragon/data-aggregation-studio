package com.jdragon.studio.nacos.compat.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class NacosJsonSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private NacosJsonSupport() {
    }

    public static JsonNode readTree(String content) {
        if (content == null || content.isBlank()) {
            return OBJECT_MAPPER.nullNode();
        }
        try {
            return OBJECT_MAPPER.readTree(content);
        }
        catch (Exception ex) {
            return OBJECT_MAPPER.nullNode();
        }
    }

    public static JsonNode readRequiredTree(String content, String errorMessage) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException(errorMessage + ": empty response body");
        }
        try {
            return OBJECT_MAPPER.readTree(content);
        }
        catch (Exception ex) {
            throw new IllegalStateException(errorMessage, ex);
        }
    }

    public static String findText(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode direct = node.get(fieldName);
        if (direct != null && direct.isValueNode()) {
            return direct.asText();
        }
        if (node.isObject()) {
            for (var entry : node.properties()) {
                String value = findText(entry.getValue(), fieldName);
                if (value != null) {
                    return value;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = findText(item, fieldName);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

}
