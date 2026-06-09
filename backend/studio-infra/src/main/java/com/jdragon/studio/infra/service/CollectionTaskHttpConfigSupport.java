package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataModelDefinition;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
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
        boolean soap = isSoapProtocol(metadata);
        readerConfig.put("mode", soap ? "POST" : resolveHttpMode(metadata));
        readerConfig.put("protocolMode", soap ? "SOAP" : resolveProtocolMode(metadata));
        readerConfig.put("soapVersion", resolveSoapVersion(metadata));
        putIfPresent(readerConfig, "soapAction", metadata.get("soapAction"), null);
        readerConfig.put("soapFaultFail", Boolean.TRUE);
        readerConfig.put("contentType", soap ? resolveSoapContentType(resolveSoapVersion(metadata)) : "application/json;charset=utf-8");
        readerConfig.put("header", "{}");
        readerConfig.put("params", "{}");
        readerConfig.put("requestBody", "");
        readerConfig.put("resultType", soap ? "soap" : resolveHttpResultType(metadata));
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
        boolean soap = isSoapProtocol(metadata);
        writerConfig.put("mode", soap ? "POST" : resolveHttpWriterMode(metadata));
        writerConfig.put("protocolMode", soap ? "SOAP" : resolveProtocolMode(metadata));
        writerConfig.put("soapVersion", resolveSoapVersion(metadata));
        putIfPresent(writerConfig, "soapAction", metadata.get("soapAction"), null);
        writerConfig.put("soapFaultFail", Boolean.TRUE);
        writerConfig.put("contentType", soap ? resolveSoapContentType(resolveSoapVersion(metadata)) : "application/json;charset=utf-8");
        writerConfig.put("header", "{}");
        writerConfig.put("params", "{}");
        writerConfig.put("requestBody", "");
        writerConfig.put("payloadMode", "object");
        if (soap) {
            writerConfig.put("payloadFormat", "soap");
            writerConfig.put("responseType", "soap");
        }
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
        boolean soap = isSoapConfig(config);
        normalizeHttpStringOption(config, "contentType", soap ? resolveSoapContentType(resolveSoapVersion(config)) : "application/json;charset=utf-8");
        normalizeHttpJsonObjectString(config, "header", "HTTP reader");
        normalizeHttpJsonObjectString(config, "params", "HTTP reader");
        normalizeHttpStringOption(config, "requestBody", "");
        if (soap) {
            config.put("mode", "POST");
            config.put("resultType", "soap");
            config.put("protocolMode", "SOAP");
            if (isBlankValue(config.get("soapFaultFail"))) {
                config.put("soapFaultFail", Boolean.TRUE);
            }
            if (isBlankValue(config.get("requestBody"))) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP SOAP reader requestBody is required");
            }
            validateSoapEnvelope(String.valueOf(config.get("requestBody")), "HTTP SOAP reader requestBody");
            ensureSoapActionHeader(config);
        }
    }

    void normalizeWriterRuntimeConfig(Map<String, Object> config) {
        boolean soap = isSoapConfig(config);
        normalizeHttpStringOption(config, "contentType", soap ? resolveSoapContentType(resolveSoapVersion(config)) : "application/json;charset=utf-8");
        normalizeHttpJsonObjectString(config, "header", "HTTP writer");
        normalizeHttpJsonObjectString(config, "params", "HTTP writer");
        normalizeHttpStringOption(config, "requestBody", "");
        if (soap) {
            if ("array".equalsIgnoreCase(String.valueOf(config.get("payloadMode")))) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP SOAP writer does not support array payloadMode in this version");
            }
            config.put("mode", "POST");
            config.put("protocolMode", "SOAP");
            config.put("payloadFormat", "soap");
            config.put("responseType", "soap");
            config.put("payloadMode", "object");
            if (isBlankValue(config.get("soapFaultFail"))) {
                config.put("soapFaultFail", Boolean.TRUE);
            }
            if (isBlankValue(config.get("requestBody"))) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP SOAP writer requestBody is required");
            }
            validateSoapEnvelope(String.valueOf(config.get("requestBody")), "HTTP SOAP writer requestBody");
            ensureSoapActionHeader(config);
        }
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
        keys.add("contenttype");
        keys.add("soapaction");
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

    private String resolveProtocolMode(Map<String, Object> metadata) {
        Object protocolMode = metadata == null ? null : metadata.get("protocolMode");
        if (isBlankValue(protocolMode)) {
            String resultType = resolveHttpResultType(metadata);
            if ("xml".equalsIgnoreCase(resultType)) {
                return "REST_XML";
            }
            if ("soap".equalsIgnoreCase(resultType)) {
                return "SOAP";
            }
            return "REST_JSON";
        }
        return String.valueOf(protocolMode).trim().toUpperCase(Locale.ENGLISH);
    }

    private boolean isSoapProtocol(Map<String, Object> metadata) {
        return "SOAP".equalsIgnoreCase(resolveProtocolMode(metadata));
    }

    private boolean isSoapConfig(Map<String, Object> config) {
        Object protocolMode = config == null ? null : config.get("protocolMode");
        Object resultType = config == null ? null : config.get("resultType");
        Object payloadFormat = config == null ? null : config.get("payloadFormat");
        Object responseType = config == null ? null : config.get("responseType");
        return "SOAP".equalsIgnoreCase(String.valueOf(protocolMode))
                || "soap".equalsIgnoreCase(String.valueOf(resultType))
                || "soap".equalsIgnoreCase(String.valueOf(payloadFormat))
                || "soap".equalsIgnoreCase(String.valueOf(responseType));
    }

    private String resolveSoapVersion(Map<String, Object> metadata) {
        Object soapVersion = metadata == null ? null : metadata.get("soapVersion");
        return isBlankValue(soapVersion) ? "SOAP_11" : String.valueOf(soapVersion).trim().toUpperCase(Locale.ENGLISH);
    }

    private String resolveSoapContentType(String soapVersion) {
        return "SOAP_12".equalsIgnoreCase(soapVersion)
                ? "application/soap+xml;charset=UTF-8"
                : "text/xml;charset=UTF-8";
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

    private void ensureSoapActionHeader(Map<String, Object> config) {
        Object soapAction = config.get("soapAction");
        if (isBlankValue(soapAction)) {
            return;
        }
        String headerText = String.valueOf(config.get("header"));
        try {
            JsonNode node = RUNTIME_OPTION_OBJECT_MAPPER.readTree(headerText);
            if (node.has("SOAPAction") || node.has("soapAction")) {
                return;
            }
            Map<String, Object> header = new LinkedHashMap<String, Object>();
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode value = entry.getValue();
                header.put(entry.getKey(), value == null || value.isNull() ? null : value.asText());
            }
            header.put("SOAPAction", String.valueOf(soapAction).trim());
            config.put("header", RUNTIME_OPTION_OBJECT_MAPPER.writeValueAsString(header));
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP SOAP header must be a JSON object string");
        }
    }

    private void validateSoapEnvelope(String xml, String label) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, label + " must be valid XML: " + e.getMessage());
        }
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
