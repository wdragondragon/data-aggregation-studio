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
import java.util.ArrayList;
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
    private static final String RECORDS_REPEAT_START = "{{#records}}";
    private static final String RECORDS_REPEAT_END = "{{/records}}";

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
        String protocolMode = soap ? "SOAP" : resolveProtocolMode(metadata);
        readerConfig.put("protocolMode", protocolMode);
        readerConfig.put("soapVersion", resolveSoapVersion(metadata));
        putIfPresent(readerConfig, "soapAction", metadata.get("soapAction"), null);
        readerConfig.put("soapFaultFail", Boolean.TRUE);
        readerConfig.put("contentType", resolveHttpContentType(protocolMode, resolveSoapVersion(metadata)));
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
        putIfPresent(writerConfig, "namespaceUri", metadata.get("namespaceUri"), null);
        putIfPresent(writerConfig, "operationName", metadata.get("operationName"), null);
        putIfPresent(writerConfig, "requestRootName", firstPresent(metadata, "requestRootName", "operationName"), null);
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
        boolean soap = isSoapReaderConfig(config);
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
        HttpReaderOptionNormalizer.enforceProtocolContract(config);
    }

    private boolean isSoapReaderConfig(Map<String, Object> config) {
        Object protocolMode = config == null ? null : config.get("protocolMode");
        if (!isBlankValue(protocolMode)) {
            return "SOAP".equalsIgnoreCase(String.valueOf(protocolMode).trim());
        }
        return isSoapConfig(config);
    }

    void normalizeWriterRuntimeConfig(Map<String, Object> config) {
        boolean soap = isSoapConfig(config);
        normalizeHttpStringOption(config, "contentType", soap ? resolveSoapContentType(resolveSoapVersion(config)) : "application/json;charset=utf-8");
        normalizeHttpJsonObjectString(config, "header", "HTTP writer");
        normalizeHttpJsonObjectString(config, "params", "HTTP writer");
        normalizeHttpStringOption(config, "requestBody", "");
        if (isBlankValue(config.get("payloadMode"))) {
            config.put("payloadMode", "object");
        } else {
            config.put("payloadMode", String.valueOf(config.get("payloadMode")).trim().toLowerCase(Locale.ENGLISH));
        }
        if (isBlankValue(config.get("batchSize"))) {
            config.put("batchSize", Integer.valueOf(500));
        }
        if (soap) {
            config.put("mode", "POST");
            config.put("protocolMode", "SOAP");
            config.put("payloadFormat", "soap");
            config.put("responseType", "soap");
            if (isBlankValue(config.get("soapFaultFail"))) {
                config.put("soapFaultFail", Boolean.TRUE);
            }
            if ("array".equalsIgnoreCase(String.valueOf(config.get("payloadMode")))) {
                String dataNodePath = resolveArrayDataNodePath(config);
                config.put("dataNodePath", dataNodePath);
                validateArrayColumnParents(config, dataNodePath);
            }
            config.put("requestBody", buildSoapWriterRequestBody(config));
            validateSoapEnvelope(String.valueOf(config.get("requestBody")), "HTTP SOAP writer requestBody");
            if ("array".equalsIgnoreCase(String.valueOf(config.get("payloadMode")))) {
                validateRecordsRepeatBlock(String.valueOf(config.get("requestBody")), "HTTP SOAP writer requestBody");
            }
            ensureSoapActionHeader(config);
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
        Object requestPathValue = firstPresent(metadata, "requestPath", "physicalName");
        String requestPath = model == null ? null : model.getPhysicalLocator();
        if (isBlank(requestPath) && !isBlankValue(requestPathValue)) {
            requestPath = String.valueOf(requestPathValue).trim();
        }
        if (!isBlank(requestPath) && isAbsoluteHttpUrl(requestPath)) {
            return requestPath.trim();
        }
        Object baseUrlValue = firstPresent(datasourceConnect, "url", "endpoint");
        String baseUrl = baseUrlValue == null ? null : String.valueOf(baseUrlValue).trim();
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

    private String resolveHttpContentType(String protocolMode, String soapVersion) {
        if ("SOAP".equalsIgnoreCase(protocolMode)) {
            return resolveSoapContentType(soapVersion);
        }
        return "REST_XML".equalsIgnoreCase(protocolMode)
                ? "application/xml;charset=UTF-8"
                : "application/json;charset=utf-8";
    }

    private String resolveHttpWriterMode(Map<String, Object> metadata) {
        Object mode = metadata == null ? null : metadata.get("mode");
        return isBlankValue(mode) ? "POST" : String.valueOf(mode).trim().toUpperCase(Locale.ENGLISH);
    }

    private String resolveHttpResultType(Map<String, Object> metadata) {
        Object protocolMode = metadata == null ? null : metadata.get("protocolMode");
        if (!isBlankValue(protocolMode)) {
            String normalizedProtocol = String.valueOf(protocolMode).trim().toUpperCase(Locale.ENGLISH);
            if ("SOAP".equals(normalizedProtocol)) {
                return "soap";
            }
            if ("REST_XML".equals(normalizedProtocol)) {
                return "xml";
            }
            if ("REST_JSON".equals(normalizedProtocol)) {
                return "json";
            }
        }
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

    private void validateRecordsRepeatBlock(String template, String label) {
        int start = template.indexOf(RECORDS_REPEAT_START);
        int end = template.indexOf(RECORDS_REPEAT_END);
        if (start < 0 || end < 0) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    label + " must contain repeat block {{#records}}...{{/records}} when payloadMode is array");
        }
        if (start != template.lastIndexOf(RECORDS_REPEAT_START) || end != template.lastIndexOf(RECORDS_REPEAT_END)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    label + " must contain exactly one repeat block {{#records}}...{{/records}} when payloadMode is array");
        }
        int innerStart = start + RECORDS_REPEAT_START.length();
        if (end <= innerStart || template.substring(innerStart, end).trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    label + " repeat block {{#records}}...{{/records}} must not be empty");
        }
    }

    private String buildSoapWriterRequestBody(Map<String, Object> config) {
        boolean soap12 = "SOAP_12".equalsIgnoreCase(resolveSoapVersion(config));
        String soapNs = soap12 ? "http://www.w3.org/2003/05/soap-envelope" : "http://schemas.xmlsoap.org/soap/envelope/";
        String namespaceUri = resolveSoapNamespaceUri(config);
        String requestRootName = safeXmlName(resolveSoapRequestRootName(config));
        String bodyInnerXml;
        if ("array".equalsIgnoreCase(String.valueOf(config.get("payloadMode")))) {
            bodyInnerXml = buildSoapRecordsRepeatXmlByPath(config.get("columns"), String.valueOf(config.get("dataNodePath")), 6);
        } else {
            bodyInnerXml = buildSoapFieldsXmlByParentPath(config.get("columns"), "", 6);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        builder.append("<soap:Envelope xmlns:soap=\"").append(escapeXml(soapNs))
                .append("\" xmlns:tns=\"").append(escapeXml(namespaceUri)).append("\">\n");
        builder.append("  <soap:Header>\n");
        builder.append("  </soap:Header>\n");
        builder.append("  <soap:Body>\n");
        builder.append("    <tns:").append(isBlank(requestRootName) ? "Operation" : requestRootName).append(">\n");
        if (!isBlank(bodyInnerXml)) {
            builder.append(bodyInnerXml).append('\n');
        }
        builder.append("    </tns:").append(isBlank(requestRootName) ? "Operation" : requestRootName).append(">\n");
        builder.append("  </soap:Body>\n");
        builder.append("</soap:Envelope>");
        return builder.toString();
    }

    private String buildSoapRecordsRepeatXmlByPath(Object columnsValue, String dataNodePath, int indentSize) {
        List<String> segments = splitXmlPath(dataNodePath);
        if (segments.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segments.size() - 1; i++) {
            appendIndent(builder, indentSize + i * 2).append('<').append(segments.get(i)).append(">\n");
        }
        int repeatIndent = indentSize + (segments.size() - 1) * 2;
        String recordName = segments.get(segments.size() - 1);
        appendIndent(builder, repeatIndent).append(RECORDS_REPEAT_START).append('\n');
        appendIndent(builder, repeatIndent).append('<').append(recordName).append(">\n");
        String fieldXml = buildSoapFieldsXmlByParentPath(columnsValue, dataNodePath, indentSize + segments.size() * 2);
        if (!isBlank(fieldXml)) {
            builder.append(fieldXml).append('\n');
        }
        appendIndent(builder, repeatIndent).append("</").append(recordName).append(">\n");
        appendIndent(builder, repeatIndent).append(RECORDS_REPEAT_END).append('\n');
        for (int i = segments.size() - 2; i >= 0; i--) {
            appendIndent(builder, indentSize + i * 2).append("</").append(segments.get(i)).append(">\n");
        }
        return trimTrailingNewline(builder.toString());
    }

    private String buildSoapFieldsXmlByParentPath(Object columnsValue, String basePath, int indentSize) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        List<String> baseSegments = splitPath(basePath);
        for (Object column : listValue(columnsValue)) {
            if (!(column instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> columnMap = (Map<?, ?>) column;
            Object rawName = columnMap.get("name");
            if (isBlankValue(rawName)) {
                continue;
            }
            String name = String.valueOf(rawName).trim();
            String elementName = safeXmlName(name);
            if (isBlank(elementName)) {
                continue;
            }
            List<String> parentSegments = splitPath(String.valueOf(columnMap.get("parentNode") == null ? "" : columnMap.get("parentNode")));
            List<String> relativeParentSegments = startsWithSegments(parentSegments, baseSegments)
                    ? parentSegments.subList(baseSegments.size(), parentSegments.size())
                    : parentSegments;
            List<String> path = new ArrayList<String>(relativeParentSegments);
            path.add(elementName);
            putXmlPath(payload, path, "{{" + name + "}}");
        }
        StringBuilder builder = new StringBuilder();
        appendXmlObjectChildren(builder, payload, indentSize);
        return trimTrailingNewline(builder.toString());
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private void putXmlPath(Map<String, Object> root, List<String> path, String value) {
        Map<String, Object> current = root;
        for (int i = 0; i < path.size(); i++) {
            String segment = safeXmlName(path.get(i));
            if (isBlank(segment)) {
                continue;
            }
            if (i == path.size() - 1) {
                current.put(segment, value);
                return;
            }
            Object child = current.get(segment);
            if (!(child instanceof Map<?, ?>)) {
                child = new LinkedHashMap<String, Object>();
                current.put(segment, child);
            }
            current = (Map<String, Object>) child;
        }
    }

    @SuppressWarnings("unchecked")
    private void appendXmlObjectChildren(StringBuilder builder, Map<String, Object> payload, int indentSize) {
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String name = safeXmlName(entry.getKey());
            if (isBlank(name)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                appendIndent(builder, indentSize).append('<').append(name).append(">\n");
                appendXmlObjectChildren(builder, (Map<String, Object>) value, indentSize + 2);
                appendIndent(builder, indentSize).append("</").append(name).append(">\n");
            } else {
                appendIndent(builder, indentSize).append('<').append(name).append('>')
                        .append(value == null ? "" : value)
                        .append("</").append(name).append(">\n");
            }
        }
    }

    private String resolveSoapNamespaceUri(Map<String, Object> config) {
        Object namespaceUri = config.get("namespaceUri");
        if (!isBlankValue(namespaceUri)) {
            return String.valueOf(namespaceUri).trim();
        }
        Object soapAction = config.get("soapAction");
        if (!isBlankValue(soapAction)) {
            String value = String.valueOf(soapAction).trim();
            int index = Math.max(value.lastIndexOf('/'), value.lastIndexOf('#'));
            if (index > 0) {
                return value.substring(0, index);
            }
        }
        return "urn:studio";
    }

    private String resolveSoapRequestRootName(Map<String, Object> config) {
        Object configured = firstPresent(config, "requestRootName", "operationName");
        if (!isBlankValue(configured)) {
            return String.valueOf(configured).trim();
        }
        Object soapAction = config.get("soapAction");
        if (!isBlankValue(soapAction)) {
            String value = String.valueOf(soapAction).trim();
            int index = Math.max(value.lastIndexOf('/'), value.lastIndexOf('#'));
            if (index >= 0 && index < value.length() - 1) {
                return value.substring(index + 1);
            }
        }
        return "Operation";
    }

    private String safeXmlName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        if (normalized.isEmpty()) {
            return "";
        }
        char first = normalized.charAt(0);
        if ((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z') || first == '_') {
            return normalized;
        }
        return "_" + normalized;
    }

    private List<String> splitXmlPath(String value) {
        List<String> result = new ArrayList<String>();
        for (String segment : splitPath(value)) {
            String name = safeXmlName(segment);
            if (!isBlank(name)) {
                result.add(name);
            }
        }
        return result;
    }

    private StringBuilder appendIndent(StringBuilder builder, int indentSize) {
        for (int i = 0; i < indentSize; i++) {
            builder.append(' ');
        }
        return builder;
    }

    private String trimTrailingNewline(String value) {
        String result = value;
        while (result.endsWith("\n") || result.endsWith("\r")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String resolveArrayDataNodePath(Map<String, Object> config) {
        Object configured = config.get("dataNodePath");
        if (!isBlankValue(configured)) {
            return String.valueOf(configured).trim();
        }
        String inferred = resolveCommonColumnParentNode(config.get("columns"));
        if (!isBlank(inferred)) {
            return inferred;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST,
                "HTTP SOAP writer dataNodePath is required when payloadMode is array and target fields have no single parentNode");
    }

    private String resolveCommonColumnParentNode(Object columnsValue) {
        if (!(columnsValue instanceof List<?>)) {
            return null;
        }
        Set<String> parents = new LinkedHashSet<String>();
        for (Object column : (List<?>) columnsValue) {
            if (!(column instanceof Map<?, ?>)) {
                continue;
            }
            Object parentNode = ((Map<?, ?>) column).get("parentNode");
            if (!isBlankValue(parentNode)) {
                parents.add(String.valueOf(parentNode).trim());
            }
        }
        return parents.size() == 1 ? parents.iterator().next() : null;
    }

    private void validateArrayColumnParents(Map<String, Object> config, String dataNodePath) {
        Object columnsValue = config.get("columns");
        if (!(columnsValue instanceof List<?>)) {
            return;
        }
        List<String> dataSegments = splitPath(dataNodePath);
        for (Object column : (List<?>) columnsValue) {
            if (!(column instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> columnMap = (Map<?, ?>) column;
            Object parentNode = columnMap.get("parentNode");
            if (isBlankValue(parentNode)) {
                continue;
            }
            String parentPath = String.valueOf(parentNode).trim();
            List<String> parentSegments = splitPath(parentPath);
            if (!startsWithSegments(parentSegments, dataSegments)) {
                Object name = columnMap.get("name");
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "HTTP SOAP writer field " + name + " parentNode " + parentPath
                                + " must be under dataNodePath " + dataNodePath
                                + " when payloadMode is array");
            }
        }
    }

    private List<String> splitPath(String value) {
        List<String> result = new ArrayList<String>();
        if (value == null) {
            return result;
        }
        String[] segments = value.split("\\.");
        for (String segment : segments) {
            if (!segment.trim().isEmpty()) {
                result.add(segment.trim());
            }
        }
        return result;
    }

    private boolean startsWithSegments(List<String> value, List<String> prefix) {
        if (prefix.isEmpty()) {
            return true;
        }
        if (value.size() < prefix.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(value.get(i))) {
                return false;
            }
        }
        return true;
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
