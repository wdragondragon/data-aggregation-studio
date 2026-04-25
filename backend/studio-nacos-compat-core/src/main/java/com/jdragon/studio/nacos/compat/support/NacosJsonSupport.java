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

    public static String findText(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode direct = node.get(fieldName);
        if (direct != null && direct.isValueNode()) {
            return direct.asText();
        }
        if (node.isObject()) {
            var iterator = node.fields();
            while (iterator.hasNext()) {
                var entry = iterator.next();
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
