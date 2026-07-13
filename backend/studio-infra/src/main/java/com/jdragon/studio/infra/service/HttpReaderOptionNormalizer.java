package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class HttpReaderOptionNormalizer {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> RESERVED_KEYS = new LinkedHashSet<String>(Arrays.asList(
            "url", "mode", "protocolmode", "soapversion", "soapaction", "resulttype",
            "responsestatus", "totalcodepath", "columns"));

    private HttpReaderOptionNormalizer() {
    }

    public static void mergeInto(Map<String, Object> config, Object readerOptionsValue) {
        if (config == null || !(readerOptionsValue instanceof Map<?, ?>)) {
            return;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) readerOptionsValue).entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey()).trim();
            Object value = entry.getValue();
            if (key.isEmpty() || RESERVED_KEYS.contains(key.toLowerCase(Locale.ENGLISH)) || isBlank(value)) {
                continue;
            }
            config.put(key, normalizeValue(key, value));
        }
    }

    public static void enforceProtocolContract(Map<String, Object> config) {
        if (config == null) {
            return;
        }
        String protocolMode = text(config.get("protocolMode")).toUpperCase(Locale.ENGLISH);
        if (protocolMode.isEmpty()) {
            protocolMode = protocolModeFromResultType(config.get("resultType"));
            config.put("protocolMode", protocolMode);
        }
        String contentType = text(config.get("contentType"));
        String normalizedContentType = contentType.toLowerCase(Locale.ENGLISH);
        if ("SOAP".equals(protocolMode)) {
            config.put("mode", "POST");
            config.put("resultType", "soap");
            String soapVersion = text(config.get("soapVersion")).toUpperCase(Locale.ENGLISH);
            String expected = "SOAP_12".equals(soapVersion)
                    ? "application/soap+xml;charset=UTF-8"
                    : "text/xml;charset=UTF-8";
            if (contentType.isEmpty()
                    || normalizedContentType.contains("json")
                    || ("SOAP_12".equals(soapVersion) && normalizedContentType.startsWith("text/xml"))
                    || (!"SOAP_12".equals(soapVersion) && normalizedContentType.contains("application/soap+xml"))) {
                config.put("contentType", expected);
            }
            return;
        }
        if ("REST_XML".equals(protocolMode)) {
            config.put("resultType", "xml");
            if (contentType.isEmpty() || normalizedContentType.contains("json")
                    || normalizedContentType.contains("soap")) {
                config.put("contentType", "application/xml;charset=UTF-8");
            }
            return;
        }
        config.put("protocolMode", "REST_JSON");
        config.put("resultType", "json");
        if (contentType.isEmpty() || normalizedContentType.contains("xml")
                || normalizedContentType.contains("soap")) {
            config.put("contentType", "application/json;charset=utf-8");
        }
    }

    private static Object normalizeValue(String key, Object value) {
        if ("header".equalsIgnoreCase(key) || "params".equalsIgnoreCase(key)) {
            String text = jsonString(value);
            try {
                JsonNode node = OBJECT_MAPPER.readTree(text);
                if (node == null || !node.isObject()) {
                    throw new IllegalArgumentException("HTTP reader " + key + " must be a JSON object");
                }
                return text;
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalArgumentException("HTTP reader " + key + " must be a JSON object", ex);
            }
        }
        if ("requestBody".equalsIgnoreCase(key) || "contentType".equalsIgnoreCase(key)) {
            return value instanceof String ? value : jsonString(value);
        }
        return value;
    }

    private static String jsonString(Object value) {
        if (value instanceof String) {
            return ((String) value).trim();
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize HTTP reader option", ex);
        }
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String && ((String) value).trim().isEmpty());
    }

    private static String protocolModeFromResultType(Object resultType) {
        String normalized = text(resultType).toLowerCase(Locale.ENGLISH);
        if ("soap".equals(normalized)) {
            return "SOAP";
        }
        if ("xml".equals(normalized)) {
            return "REST_XML";
        }
        return "REST_JSON";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
