package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class HttpReaderOptionSecurityService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ENCRYPTED_PREFIX = "ENC(";
    private static final String ENCRYPTED_SUFFIX = ")";
    private static final String INHERITED_VALUE_MARKER = "__STUDIO_HTTP_READER_INHERITED_VALUE__";
    public static final String REMOVED_VALUE_MARKER = "__STUDIO_HTTP_READER_REMOVED_VALUE__";
    private static final String INHERITED_LIST_INDEX_KEY = "__studioHttpInheritedIndex";
    private static final String INHERITED_LIST_VALUE_KEY = "__studioHttpInheritedValue";
    private static final String INHERITED_XML_INDEX_ATTRIBUTE = "__studio_http_inherited_index";
    private static final String OPAQUE_REQUEST_BODY_KEY = "requestBodySecret";
    private static final String OPAQUE_STRUCTURED_OPTION_KEY = "httpStructuredOptionSecret";
    private static final Object MISSING_VALUE = new Object();
    private static final Set<String> HTTP_URL_METADATA_KEYS = new LinkedHashSet<String>(java.util.Arrays.asList(
            "url", "endpoint", "requestPath", "requestUrl", "physicalName", "wsdlUrl"));

    private final EncryptionService encryptionService;

    public HttpReaderOptionSecurityService(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    public Map<String, Object> encryptTechnicalMetadata(Map<String, Object> metadata,
                                                        Map<String, Object> existingMetadata) {
        return transformTechnicalMetadata(metadata, existingMetadata, Mode.ENCRYPT);
    }

    public Map<String, Object> decryptTechnicalMetadata(Map<String, Object> metadata) {
        return transformTechnicalMetadata(metadata, null, Mode.DECRYPT);
    }

    public Map<String, Object> maskTechnicalMetadata(Map<String, Object> metadata) {
        return transformTechnicalMetadata(metadata, null, Mode.MASK);
    }

    public String maskSensitiveUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return value;
        }
        int fragmentIndex = value.indexOf('#');
        String withoutFragment = fragmentIndex < 0 ? value : value.substring(0, fragmentIndex);
        String fragment = fragmentIndex < 0 ? "" : "#****";
        int queryIndex = withoutFragment.indexOf('?');
        String base = queryIndex < 0 ? withoutFragment : withoutFragment.substring(0, queryIndex);
        String query = queryIndex < 0 ? null : withoutFragment.substring(queryIndex + 1);
        StringBuilder result = new StringBuilder(maskUserInfo(base));
        if (query != null) {
            result.append('?').append(maskSensitiveQuery(query));
        }
        return result.append(fragment).toString();
    }

    public void validatePhysicalLocator(String value) {
        if (hasSensitiveUrlParts(value)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "HTTP model physical locator must not contain URL credentials, sensitive query parameters, "
                            + "or fragments; configure request credentials in Reader default parameters instead");
        }
    }

    public Map<String, Object> maskTechnicalMetadataUrls(Map<String, Object> metadata) {
        Map<String, Object> result = copyMap(metadata);
        for (String key : HTTP_URL_METADATA_KEYS) {
            Object value = result.get(key);
            if (value instanceof String) {
                result.put(key, maskSensitiveUrl((String) value));
            }
        }
        return result;
    }

    public void validateTechnicalMetadataUrls(Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        for (String key : HTTP_URL_METADATA_KEYS) {
            Object value = metadata.get(key);
            if (value instanceof String && hasSensitiveUrlParts((String) value)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "HTTP model technical metadata '" + key
                                + "' must not contain URL credentials, sensitive query parameters, or fragments");
            }
        }
    }

    public Map<String, Object> prepareReaderOptionOverrides(Map<String, Object> readerOptions,
                                                             Map<String, Object> inheritedTechnicalMetadata) {
        return prepareReaderOptionOverrides(readerOptions, inheritedTechnicalMetadata, null);
    }

    public Map<String, Object> prepareReaderOptionOverrides(Map<String, Object> readerOptions,
                                                             Map<String, Object> inheritedTechnicalMetadata,
                                                             Map<String, Object> existingReaderOptions) {
        Map<String, Object> result = copyMap(readerOptions);
        Map<String, Object> existingOverrides = copyMap(existingReaderOptions);
        if (result.isEmpty() && existingOverrides.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, Object> entry : existingOverrides.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, Object> inheritedMetadata = inheritedTechnicalMetadata == null
                ? new LinkedHashMap<String, Object>()
                : inheritedTechnicalMetadata;
        Map<String, Object> inheritedOptions = copyMap(asMap(inheritedMetadata.get("readerOptions")));
        Map<String, Object> maskedMetadata = maskTechnicalMetadata(inheritedMetadata);
        Map<String, Object> maskedOptions = copyMap(asMap(maskedMetadata.get("readerOptions")));
        for (String key : new String[]{"header", "params", "requestBody"}) {
            if (!result.containsKey(key)) {
                continue;
            }
            Object existingOverride = existingOverrides.get(key);
            Object existingEffective = existingOverrides.containsKey(key)
                    ? resolveStructuredOverride(existingOverride, inheritedOptions.get(key), key)
                    : null;
            Object maskedExistingEffective = existingOverrides.containsKey(key)
                    ? transformStructuredOption(existingEffective, null, key, Mode.MASK)
                    : null;
            if (existingOverrides.containsKey(key)
                    && Objects.deepEquals(result.get(key), maskedExistingEffective)) {
                result.put(key, prepareStructuredOverride(
                        existingOverride, inheritedOptions.get(key), key));
                continue;
            }
            if (inheritedOptions.containsKey(key)
                    && maskedOptions.containsKey(key)
                    && !Objects.deepEquals(inheritedOptions.get(key), maskedOptions.get(key))
                    && Objects.deepEquals(result.get(key), maskedOptions.get(key))
                    && !existingOverrides.containsKey(key)) {
                result.remove(key);
                continue;
            }
            Object restored = existingOverrides.containsKey(key)
                    ? restoreMaskedExistingSensitiveLeaves(
                    result.get(key), existingEffective, existingOverride, key)
                    : result.get(key);
            result.put(key, prepareStructuredOverride(restored, inheritedOptions.get(key), key));
        }
        return result;
    }

    public Map<String, Object> resolveReaderOptionOverrides(Map<String, Object> readerOptions,
                                                             Map<String, Object> inheritedTechnicalMetadata) {
        Map<String, Object> result = copyMap(readerOptions);
        Map<String, Object> inheritedOptions = inheritedTechnicalMetadata == null
                ? new LinkedHashMap<String, Object>()
                : copyMap(asMap(inheritedTechnicalMetadata.get("readerOptions")));
        for (String key : new String[]{"header", "params", "requestBody"}) {
            if (result.containsKey(key)) {
                Object resolved = resolveStructuredOverride(result.get(key), inheritedOptions.get(key), key);
                if (resolved == MISSING_VALUE) {
                    result.remove(key);
                } else {
                    result.put(key, resolved);
                }
            }
        }
        return result;
    }

    public Map<String, Object> maskReaderOptionOverridesForView(Map<String, Object> readerOptions,
                                                                 Map<String, Object> inheritedTechnicalMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("readerOptions", resolveReaderOptionOverrides(readerOptions, inheritedTechnicalMetadata));
        return copyMap(asMap(maskTechnicalMetadata(metadata).get("readerOptions")));
    }

    private Map<String, Object> transformTechnicalMetadata(Map<String, Object> metadata,
                                                           Map<String, Object> existingMetadata,
                                                           Mode mode) {
        Map<String, Object> result = copyMap(metadata);
        Object readerOptionsValue = result.get("readerOptions");
        if (!(readerOptionsValue instanceof Map<?, ?>)) {
            return result;
        }
        Map<String, Object> existingOptions = existingMetadata == null
                ? new LinkedHashMap<String, Object>()
                : copyMap(asMap(existingMetadata.get("readerOptions")));
        Map<String, Object> readerOptions = copyMap((Map<?, ?>) readerOptionsValue);
        for (String key : new String[]{"header", "params", "requestBody"}) {
            if (!readerOptions.containsKey(key)) {
                continue;
            }
            readerOptions.put(key, transformStructuredOption(
                    readerOptions.get(key), existingOptions.get(key), key, mode));
        }
        result.put("readerOptions", readerOptions);
        return result;
    }

    private Object transformStructuredOption(Object value, Object existingValue, String key, Mode mode) {
        if (requiresJsonObject(key)) {
            return transformJsonObjectOption(value, existingValue, key, mode);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return transformValue(value, existingValue, key, mode);
        }
        if (!(value instanceof String)) {
            return value;
        }
        String text = ((String) value).trim();
        if (text.isEmpty()) {
            return value;
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                Object parsed = OBJECT_MAPPER.readValue(text, Object.class);
                Object existingParsed = parseStructuredText(existingValue);
                return OBJECT_MAPPER.writeValueAsString(transformValue(parsed, existingParsed, key, mode));
            } catch (Exception parseFailure) {
                if (mode == Mode.ENCRYPT) {
                    throw invalidStructuredOption(key, "JSON", parseFailure);
                }
                return value;
            }
        }
        if (text.startsWith("<")) {
            String transformed = transformXml(text, existingValue, mode);
            if (transformed == null && mode == Mode.ENCRYPT) {
                throw invalidStructuredOption(key, "XML", null);
            }
            return transformed == null ? value : transformed;
        }
        if ("requestBody".equalsIgnoreCase(key)) {
            return transformScalar(value, existingValue, OPAQUE_REQUEST_BODY_KEY, mode);
        }
        return transformScalar(value, existingValue, key, mode);
    }

    private Object prepareStructuredOverride(Object value, Object inheritedValue, String key) {
        if (requiresJsonObject(key)) {
            return prepareJsonObjectOverride(value, inheritedValue, key);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            Object pruned = pruneUnmodifiedMaskedLeaves(value, inheritedValue, key);
            return transformValue(pruned == MISSING_VALUE ? value : pruned, null, key, Mode.ENCRYPT);
        }
        if (!(value instanceof String)) {
            return value;
        }
        String text = ((String) value).trim();
        if (text.isEmpty()) {
            return value;
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                Object parsed = OBJECT_MAPPER.readValue(text, Object.class);
                Object inheritedParsed = parseStructuredText(inheritedValue);
                Object pruned = pruneUnmodifiedMaskedLeaves(parsed, inheritedParsed, key);
                Object prepared = transformValue(pruned == MISSING_VALUE ? parsed : pruned, null, key, Mode.ENCRYPT);
                return OBJECT_MAPPER.writeValueAsString(prepared);
            } catch (StudioException exception) {
                throw exception;
            } catch (Exception parseFailure) {
                throw invalidStructuredOption(key, "JSON", parseFailure);
            }
        }
        if (text.startsWith("<")) {
            return prepareXmlOverride(text, inheritedValue, key);
        }
        if ("requestBody".equalsIgnoreCase(key)) {
            return transformScalar(value, inheritedValue, OPAQUE_REQUEST_BODY_KEY, Mode.ENCRYPT);
        }
        return value;
    }

    private Object transformJsonObjectOption(Object value, Object existingValue, String key, Mode mode) {
        if (value instanceof Map<?, ?>) {
            return transformValue(value, existingValue, key, mode);
        }
        if (!(value instanceof String)) {
            return invalidJsonObjectValue(value, existingValue, key, mode);
        }
        String text = ((String) value).trim();
        if (text.isEmpty()) {
            return value;
        }
        if (!text.startsWith("{")) {
            return invalidJsonObjectValue(value, existingValue, key, mode);
        }
        try {
            Object parsed = OBJECT_MAPPER.readValue(text, Object.class);
            if (!(parsed instanceof Map<?, ?>)) {
                return invalidJsonObjectValue(value, existingValue, key, mode);
            }
            Object existingParsed = parseStructuredText(existingValue);
            return OBJECT_MAPPER.writeValueAsString(transformValue(parsed, existingParsed, key, mode));
        } catch (StudioException exception) {
            throw exception;
        } catch (Exception parseFailure) {
            if (mode == Mode.ENCRYPT) {
                throw invalidStructuredOption(key, "JSON object", parseFailure);
            }
            return mode == Mode.MASK
                    ? transformScalar(value, existingValue, OPAQUE_STRUCTURED_OPTION_KEY, Mode.MASK)
                    : value;
        }
    }

    private Object invalidJsonObjectValue(Object value,
                                          Object existingValue,
                                          String key,
                                          Mode mode) {
        if (mode == Mode.ENCRYPT) {
            throw invalidStructuredOption(key, "JSON object", null);
        }
        return mode == Mode.MASK
                ? transformScalar(value, existingValue, OPAQUE_STRUCTURED_OPTION_KEY, Mode.MASK)
                : value;
    }

    private Object prepareJsonObjectOverride(Object value, Object inheritedValue, String key) {
        if (value instanceof Map<?, ?>) {
            Object pruned = pruneUnmodifiedMaskedLeaves(value, inheritedValue, key);
            return transformValue(pruned == MISSING_VALUE ? value : pruned, null, key, Mode.ENCRYPT);
        }
        if (!(value instanceof String)) {
            throw invalidStructuredOption(key, "JSON object", null);
        }
        String text = ((String) value).trim();
        if (text.isEmpty()) {
            return value;
        }
        if (!text.startsWith("{")) {
            throw invalidStructuredOption(key, "JSON object", null);
        }
        try {
            Object parsed = OBJECT_MAPPER.readValue(text, Object.class);
            if (!(parsed instanceof Map<?, ?>)) {
                throw invalidStructuredOption(key, "JSON object", null);
            }
            Object inheritedParsed = parseStructuredText(inheritedValue);
            Object pruned = pruneUnmodifiedMaskedLeaves(parsed, inheritedParsed, key);
            Object prepared = transformValue(pruned == MISSING_VALUE ? parsed : pruned, null, key, Mode.ENCRYPT);
            return OBJECT_MAPPER.writeValueAsString(prepared);
        } catch (StudioException exception) {
            throw exception;
        } catch (Exception parseFailure) {
            throw invalidStructuredOption(key, "JSON object", parseFailure);
        }
    }

    private boolean requiresJsonObject(String key) {
        return "header".equalsIgnoreCase(key) || "params".equalsIgnoreCase(key);
    }

    private Object resolveStructuredOverride(Object value, Object inheritedValue, String key) {
        if (REMOVED_VALUE_MARKER.equals(value)) {
            return MISSING_VALUE;
        }
        if (INHERITED_VALUE_MARKER.equals(value)) {
            return inheritedValue == null ? MISSING_VALUE : inheritedValue;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            Object decrypted = transformValue(value, null, key, Mode.DECRYPT);
            return mergeInheritedSensitiveLeaves(decrypted, inheritedValue, key);
        }
        if (!(value instanceof String)) {
            return value;
        }
        String text = ((String) value).trim();
        if (text.isEmpty()) {
            return value;
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                Object parsed = OBJECT_MAPPER.readValue(text, Object.class);
                Object decrypted = transformValue(parsed, null, key, Mode.DECRYPT);
                Object inheritedParsed = parseStructuredText(inheritedValue);
                return OBJECT_MAPPER.writeValueAsString(mergeInheritedSensitiveLeaves(decrypted, inheritedParsed, key));
            } catch (StudioException exception) {
                throw exception;
            } catch (Exception parseFailure) {
                throw invalidStructuredOption(key, "JSON", parseFailure);
            }
        }
        if (text.startsWith("<")) {
            return resolveXmlOverride(text, inheritedValue, key);
        }
        if ("requestBody".equalsIgnoreCase(key)) {
            return transformScalar(value, null, OPAQUE_REQUEST_BODY_KEY, Mode.DECRYPT);
        }
        return value;
    }

    private Object restoreMaskedExistingSensitiveLeaves(Object value,
                                                        Object existingEffective,
                                                        Object existingOverride,
                                                        String key) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return restoreMaskedExistingValue(value, existingEffective, existingOverride, key);
        }
        if (!(value instanceof String)) {
            return value;
        }
        String text = ((String) value).trim();
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                Object parsed = OBJECT_MAPPER.readValue(text, Object.class);
                Object effectiveParsed = parseStructuredText(existingEffective);
                Object overrideParsed = parseStructuredText(existingOverride);
                return OBJECT_MAPPER.writeValueAsString(
                        restoreMaskedExistingValue(parsed, effectiveParsed, overrideParsed, key));
            } catch (Exception parseFailure) {
                throw invalidStructuredOption(key, "JSON", parseFailure);
            }
        }
        if (text.startsWith("<")) {
            return restoreMaskedExistingXml(text, existingEffective, existingOverride, key);
        }
        if ("requestBody".equalsIgnoreCase(key)
                && hasExplicitOverrideValue(existingOverride)
                && Objects.equals(value, transformScalar(existingEffective, null, OPAQUE_REQUEST_BODY_KEY, Mode.MASK))) {
            return existingEffective;
        }
        return value;
    }

    private Object restoreMaskedExistingValue(Object value,
                                              Object existingEffective,
                                              Object existingOverride,
                                              String key) {
        if (isSensitiveKey(key)
                && !(value instanceof Map<?, ?>)
                && !(value instanceof List<?>)) {
            if (hasExplicitOverrideValue(existingOverride)
                    && Objects.deepEquals(value, transformValue(existingEffective, null, key, Mode.MASK))) {
                return existingEffective;
            }
            return value;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            Map<String, Object> effective = copyMap(asMap(existingEffective));
            Map<String, Object> override = copyMap(asMap(existingOverride));
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String childKey = String.valueOf(entry.getKey());
                result.put(childKey, restoreMaskedExistingValue(
                        entry.getValue(), effective.get(childKey), override.get(childKey), childKey));
            }
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            List<?> effective = existingEffective instanceof List<?>
                    ? (List<?>) existingEffective
                    : Collections.emptyList();
            List<?> override = existingOverride instanceof List<?>
                    ? (List<?>) existingOverride
                    : Collections.emptyList();
            Set<Integer> claimedIndexes = new LinkedHashSet<Integer>();
            List<?> source = (List<?>) value;
            for (int index = 0; index < source.size(); index++) {
                int existingIndex = findInheritedListIndex(
                        source.get(index), effective, claimedIndexes, key, index);
                Object previousEffective = existingIndex >= 0 ? effective.get(existingIndex) : null;
                Object previousOverride = existingIndex >= 0 && existingIndex < override.size()
                        ? unwrapInheritedListValue(override.get(existingIndex))
                        : null;
                result.add(restoreMaskedExistingValue(
                        source.get(index), previousEffective, previousOverride, key));
            }
            return result;
        }
        return value;
    }

    private Object unwrapInheritedListValue(Object value) {
        if (!isInheritedListWrapper(value)) {
            return value;
        }
        return ((Map<?, ?>) value).get(INHERITED_LIST_VALUE_KEY);
    }

    private boolean hasExplicitOverrideValue(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value);
        return !INHERITED_VALUE_MARKER.equals(text) && !REMOVED_VALUE_MARKER.equals(text);
    }

    private String restoreMaskedExistingXml(String xml,
                                            Object existingEffective,
                                            Object existingOverride,
                                            String key) {
        try {
            Document document = parseXml(xml);
            Document effectiveDocument = existingEffective instanceof String
                    && String.valueOf(existingEffective).trim().startsWith("<")
                    ? parseXml(String.valueOf(existingEffective))
                    : null;
            Document overrideDocument = existingOverride instanceof String
                    && String.valueOf(existingOverride).trim().startsWith("<")
                    ? parseXml(String.valueOf(existingOverride))
                    : null;
            if (effectiveDocument != null && overrideDocument != null) {
                restoreMaskedExistingXmlElement(document.getDocumentElement(),
                        effectiveDocument.getDocumentElement(), overrideDocument.getDocumentElement());
            }
            return serializeXml(document, xml);
        } catch (Exception parseFailure) {
            throw invalidStructuredOption(key, "XML", parseFailure);
        }
    }

    private void restoreMaskedExistingXmlElement(Element element,
                                                 Element existingEffective,
                                                 Element existingOverride) {
        if (existingEffective == null || existingOverride == null) {
            return;
        }
        NamedNodeMap attributes = element.getAttributes();
        NamedNodeMap effectiveAttributes = existingEffective.getAttributes();
        NamedNodeMap overrideAttributes = existingOverride.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            String attributeKey = attribute.getLocalName() == null
                    ? attribute.getNodeName()
                    : attribute.getLocalName();
            if (!isSensitiveKey(attributeKey)) {
                continue;
            }
            Node effectiveAttribute = findAttribute(effectiveAttributes, attribute);
            Node overrideAttribute = findAttribute(overrideAttributes, attribute);
            if (effectiveAttribute != null
                    && overrideAttribute != null
                    && hasExplicitOverrideValue(overrideAttribute.getNodeValue())
                    && Objects.equals(attribute.getNodeValue(), maskSensitiveValue(effectiveAttribute.getNodeValue()))) {
                attribute.setNodeValue(effectiveAttribute.getNodeValue());
            }
        }
        List<Element> children = childElements(element);
        if (children.isEmpty() && isSensitiveKey(localName(element))) {
            if (hasExplicitOverrideValue(existingOverride.getTextContent())
                    && Objects.equals(element.getTextContent(), maskSensitiveValue(existingEffective.getTextContent()))) {
                element.setTextContent(existingEffective.getTextContent());
            }
            return;
        }
        Map<String, Set<Integer>> claimedOccurrences = new LinkedHashMap<String, Set<Integer>>();
        for (Element child : children) {
            String identity = expandedName(child);
            int preferredOccurrence = claimedOccurrences.containsKey(identity)
                    ? claimedOccurrences.get(identity).size()
                    : 0;
            int occurrence = findInheritedXmlOccurrence(
                    child, existingEffective, claimedOccurrences, preferredOccurrence);
            if (occurrence < 0) {
                continue;
            }
            restoreMaskedExistingXmlElement(child,
                    findChildElement(existingEffective, child, occurrence),
                    findChildElement(existingOverride, child, occurrence));
        }
    }

    private Object pruneUnmodifiedMaskedLeaves(Object value, Object inheritedValue, String key) {
        if (inheritedValue != null && isSensitiveKey(key)) {
            Object maskedInherited = transformValue(inheritedValue, null, key, Mode.MASK);
            if (Objects.deepEquals(value, maskedInherited)) {
                return MISSING_VALUE;
            }
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            Map<String, Object> inherited = copyMap(asMap(inheritedValue));
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String childKey = String.valueOf(entry.getKey());
                Object child = pruneUnmodifiedMaskedLeaves(entry.getValue(), inherited.get(childKey), childKey);
                if (child != MISSING_VALUE) {
                    result.put(childKey, child);
                }
            }
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            List<?> inherited = inheritedValue instanceof List<?> ? (List<?>) inheritedValue : Collections.emptyList();
            List<?> source = (List<?>) value;
            Set<Integer> claimedInheritedIndexes = new LinkedHashSet<Integer>();
            for (int index = 0; index < source.size(); index++) {
                int inheritedIndex = findInheritedListIndex(
                        source.get(index), inherited, claimedInheritedIndexes, key, index);
                Object previous = inheritedIndex >= 0 ? inherited.get(inheritedIndex) : null;
                Object child = pruneUnmodifiedMaskedLeaves(
                        source.get(index), previous, isSensitiveKey(key) ? key : "");
                Object prepared = child == MISSING_VALUE ? INHERITED_VALUE_MARKER : child;
                if (inheritedIndex >= 0) {
                    Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
                    wrapper.put(INHERITED_LIST_INDEX_KEY, Integer.valueOf(inheritedIndex));
                    wrapper.put(INHERITED_LIST_VALUE_KEY, prepared);
                    result.add(wrapper);
                } else {
                    result.add(prepared);
                }
            }
            return result;
        }
        return value;
    }

    private Object mergeInheritedSensitiveLeaves(Object value, Object inheritedValue, String key) {
        if (REMOVED_VALUE_MARKER.equals(value)) {
            return MISSING_VALUE;
        }
        if (INHERITED_VALUE_MARKER.equals(value)) {
            return inheritedValue == null ? MISSING_VALUE : inheritedValue;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = copyMap((Map<?, ?>) value);
            Map<String, Object> inherited = copyMap(asMap(inheritedValue));
            List<String> explicitlyRemoved = new ArrayList<String>();
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                if (REMOVED_VALUE_MARKER.equals(entry.getValue())) {
                    explicitlyRemoved.add(entry.getKey());
                }
            }
            for (String removedKey : explicitlyRemoved) {
                result.remove(removedKey);
            }
            for (String resultKey : new ArrayList<String>(result.keySet())) {
                Object merged = mergeInheritedSensitiveLeaves(
                        result.get(resultKey), inherited.get(resultKey), resultKey);
                if (merged == MISSING_VALUE) {
                    result.remove(resultKey);
                } else {
                    result.put(resultKey, merged);
                }
            }
            for (Map.Entry<String, Object> entry : inherited.entrySet()) {
                if (explicitlyRemoved.contains(entry.getKey()) || result.containsKey(entry.getKey())) {
                    continue;
                }
                result.put(entry.getKey(), entry.getValue());
            }
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            List<?> inherited = inheritedValue instanceof List<?> ? (List<?>) inheritedValue : Collections.emptyList();
            List<?> source = (List<?>) value;
            for (int index = 0; index < source.size(); index++) {
                Object item = source.get(index);
                Object previous = index < inherited.size() ? inherited.get(index) : null;
                Object prepared = item;
                if (isInheritedListWrapper(item)) {
                    Map<?, ?> wrapper = (Map<?, ?>) item;
                    int inheritedIndex = ((Number) wrapper.get(INHERITED_LIST_INDEX_KEY)).intValue();
                    previous = inheritedIndex >= 0 && inheritedIndex < inherited.size()
                            ? inherited.get(inheritedIndex)
                            : null;
                    prepared = wrapper.get(INHERITED_LIST_VALUE_KEY);
                }
                Object merged = mergeInheritedSensitiveLeaves(prepared, previous, "");
                if (merged != MISSING_VALUE) {
                    result.add(merged);
                }
            }
            return result;
        }
        return value;
    }

    private int findInheritedListIndex(Object value,
                                       List<?> inherited,
                                       Set<Integer> claimedIndexes,
                                       String key,
                                       int preferredIndex) {
        String childKey = isSensitiveKey(key) ? key : "";
        for (int index = 0; index < inherited.size(); index++) {
            if (claimedIndexes.contains(Integer.valueOf(index))) {
                continue;
            }
            Object masked = transformValue(inherited.get(index), null, childKey, Mode.MASK);
            if (Objects.deepEquals(value, masked)) {
                claimedIndexes.add(Integer.valueOf(index));
                return index;
            }
        }
        Object valueIdentity = nonSensitiveIdentity(value, childKey);
        if (valueIdentity != MISSING_VALUE) {
            int match = -1;
            for (int index = 0; index < inherited.size(); index++) {
                if (claimedIndexes.contains(Integer.valueOf(index))) {
                    continue;
                }
                Object inheritedIdentity = nonSensitiveIdentity(inherited.get(index), childKey);
                if (!Objects.deepEquals(valueIdentity, inheritedIdentity)) {
                    continue;
                }
                if (match >= 0) {
                    match = -1;
                    break;
                }
                match = index;
            }
            if (match >= 0) {
                claimedIndexes.add(Integer.valueOf(match));
                return match;
            }
        }
        if (preferredIndex >= 0 && preferredIndex < inherited.size()
                && !claimedIndexes.contains(Integer.valueOf(preferredIndex))) {
            claimedIndexes.add(Integer.valueOf(preferredIndex));
            return preferredIndex;
        }
        return -1;
    }

    private Object nonSensitiveIdentity(Object value, String key) {
        if (isSensitiveKey(key)) {
            return MISSING_VALUE;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String childKey = String.valueOf(entry.getKey());
                Object child = nonSensitiveIdentity(entry.getValue(), childKey);
                if (child != MISSING_VALUE) {
                    result.put(childKey, child);
                }
            }
            return result.isEmpty() ? MISSING_VALUE : result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                Object child = nonSensitiveIdentity(item, key);
                if (child != MISSING_VALUE) {
                    result.add(child);
                }
            }
            return result.isEmpty() ? MISSING_VALUE : result;
        }
        return value;
    }

    private boolean isInheritedListWrapper(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        return map.size() == 2
                && map.get(INHERITED_LIST_INDEX_KEY) instanceof Number
                && map.containsKey(INHERITED_LIST_VALUE_KEY);
    }

    private Object transformValue(Object value, Object existingValue, String key, Mode mode) {
        if (isInheritedListWrapper(value)) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put(INHERITED_LIST_INDEX_KEY, source.get(INHERITED_LIST_INDEX_KEY));
            result.put(INHERITED_LIST_VALUE_KEY,
                    transformValue(source.get(INHERITED_LIST_VALUE_KEY), null, key, mode));
            return result;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            Map<String, Object> existing = copyMap(asMap(existingValue));
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String childKey = String.valueOf(entry.getKey());
                result.put(childKey, transformValue(entry.getValue(), existing.get(childKey), childKey, mode));
            }
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            List<?> existing = existingValue instanceof List<?> ? (List<?>) existingValue : new ArrayList<Object>();
            List<?> source = (List<?>) value;
            Set<Integer> claimedExistingIndexes = mode == Mode.ENCRYPT
                    ? new LinkedHashSet<Integer>()
                    : Collections.emptySet();
            for (int index = 0; index < source.size(); index++) {
                int existingIndex = mode == Mode.ENCRYPT
                        ? findInheritedListIndex(source.get(index), existing, claimedExistingIndexes, key, index)
                        : index;
                Object previous = existingIndex >= 0 && existingIndex < existing.size()
                        ? existing.get(existingIndex)
                        : null;
                result.add(transformValue(source.get(index), previous, key, mode));
            }
            return result;
        }
        return transformScalar(value, existingValue, key, mode);
    }

    private Object transformScalar(Object value, Object existingValue, String key, Mode mode) {
        if (!isSensitiveKey(key) || value == null) {
            return value;
        }
        if (!(value instanceof String)) {
            return value;
        }
        String text = (String) value;
        if (INHERITED_VALUE_MARKER.equals(text) || REMOVED_VALUE_MARKER.equals(text)) {
            return value;
        }
        if (mode == Mode.DECRYPT) {
            return decryptSensitiveValue(text);
        }
        if (mode == Mode.MASK) {
            return maskSensitiveValue(text);
        }
        if (isEncryptedSequence(text)) {
            return text;
        }
        String existingText = existingValue == null ? null : String.valueOf(existingValue);
        if (existingText != null && text.equals(maskSensitiveValue(existingText))) {
            return isEncryptedSequence(existingText) ? existingText : encryptSensitiveValue(existingText);
        }
        return encryptSensitiveValue(text);
    }

    private boolean hasSensitiveUrlParts(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        if (value.indexOf('#') >= 0) {
            return true;
        }
        int queryIndex = value.indexOf('?');
        String base = queryIndex < 0 ? value : value.substring(0, queryIndex);
        if (hasUserInfo(base)) {
            return true;
        }
        if (queryIndex < 0) {
            return false;
        }
        String query = value.substring(queryIndex + 1);
        for (String pair : query.split("&", -1)) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (isSensitiveKey(decodeQueryComponent(key))) {
                return true;
            }
        }
        return false;
    }

    private String maskUserInfo(String base) {
        if (!hasUserInfo(base)) {
            return base;
        }
        int authorityStart = base.indexOf("://") + 3;
        int authorityEnd = base.indexOf('/', authorityStart);
        if (authorityEnd < 0) {
            authorityEnd = base.length();
        }
        int at = base.lastIndexOf('@', authorityEnd - 1);
        return base.substring(0, authorityStart) + "****@" + base.substring(at + 1);
    }

    private boolean hasUserInfo(String base) {
        int schemeSeparator = base == null ? -1 : base.indexOf("://");
        if (schemeSeparator < 0) {
            return false;
        }
        int authorityStart = schemeSeparator + 3;
        int authorityEnd = base.indexOf('/', authorityStart);
        if (authorityEnd < 0) {
            authorityEnd = base.length();
        }
        return base.lastIndexOf('@', authorityEnd - 1) >= authorityStart;
    }

    private String maskSensitiveQuery(String query) {
        String[] pairs = query.split("&", -1);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < pairs.length; index++) {
            if (index > 0) {
                result.append('&');
            }
            String pair = pairs[index];
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (separator >= 0 && isSensitiveKey(decodeQueryComponent(key))) {
                result.append(key).append("=****");
            } else {
                result.append(pair);
            }
        }
        return result.toString();
    }

    private String decodeQueryComponent(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return value;
        }
    }

    private String transformXml(String xml, Object existingValue, Mode mode) {
        try {
            Document document = parseXml(xml);
            Document existingDocument = existingValue instanceof String && String.valueOf(existingValue).trim().startsWith("<")
                    ? parseXml(String.valueOf(existingValue))
                    : null;
            transformXmlElement(document.getDocumentElement(),
                    existingDocument == null ? null : existingDocument.getDocumentElement(), mode);
            return serializeXml(document, xml);
        } catch (Exception parseFailure) {
            return null;
        }
    }

    private String prepareXmlOverride(String xml, Object inheritedValue, String key) {
        try {
            Document document = parseXml(xml);
            Document inheritedDocument = inheritedValue instanceof String
                    && String.valueOf(inheritedValue).trim().startsWith("<")
                    ? parseXml(String.valueOf(inheritedValue))
                    : null;
            if (inheritedDocument != null) {
                pruneUnmodifiedMaskedXmlLeaves(document.getDocumentElement(), inheritedDocument.getDocumentElement());
                markRemovedInheritedSensitiveXmlLeaves(
                        document.getDocumentElement(), inheritedDocument.getDocumentElement());
            }
            transformXmlElement(document.getDocumentElement(), null, Mode.ENCRYPT);
            return serializeXml(document, xml);
        } catch (StudioException exception) {
            throw exception;
        } catch (Exception parseFailure) {
            throw invalidStructuredOption(key, "XML", parseFailure);
        }
    }

    private String resolveXmlOverride(String xml, Object inheritedValue, String key) {
        try {
            String decryptedXml = transformXml(xml, null, Mode.DECRYPT);
            if (decryptedXml == null) {
                throw invalidStructuredOption(key, "XML", null);
            }
            Document document = parseXml(decryptedXml);
            if (inheritedValue instanceof String && String.valueOf(inheritedValue).trim().startsWith("<")) {
                Document inheritedDocument = parseXml(String.valueOf(inheritedValue));
                mergeInheritedSensitiveXmlLeaves(document.getDocumentElement(), inheritedDocument.getDocumentElement());
            } else {
                pruneOrphanedXmlMarkers(document.getDocumentElement(), true);
            }
            return serializeXml(document, xml);
        } catch (StudioException exception) {
            throw exception;
        } catch (Exception parseFailure) {
            throw invalidStructuredOption(key, "XML", parseFailure);
        }
    }

    private void pruneOrphanedXmlMarkers(Element element, boolean root) {
        element.removeAttribute(INHERITED_XML_INDEX_ATTRIBUTE);
        NamedNodeMap attributes = element.getAttributes();
        for (int index = attributes.getLength() - 1; index >= 0; index--) {
            Node attribute = attributes.item(index);
            if (INHERITED_VALUE_MARKER.equals(attribute.getNodeValue())
                    || REMOVED_VALUE_MARKER.equals(attribute.getNodeValue())) {
                element.removeAttributeNode((org.w3c.dom.Attr) attribute);
            }
        }
        for (Element child : new ArrayList<Element>(childElements(element))) {
            if (childElements(child).isEmpty()
                    && isSensitiveKey(localName(child))
                    && (INHERITED_VALUE_MARKER.equals(child.getTextContent())
                    || REMOVED_VALUE_MARKER.equals(child.getTextContent()))) {
                element.removeChild(child);
                continue;
            }
            pruneOrphanedXmlMarkers(child, false);
        }
        if (root && childElements(element).isEmpty()
                && isSensitiveKey(localName(element))
                && (INHERITED_VALUE_MARKER.equals(element.getTextContent())
                || REMOVED_VALUE_MARKER.equals(element.getTextContent()))) {
            element.setTextContent("");
        }
    }

    private void pruneUnmodifiedMaskedXmlLeaves(Element element, Element inheritedElement) {
        NamedNodeMap attributes = element.getAttributes();
        NamedNodeMap inheritedAttributes = inheritedElement == null ? null : inheritedElement.getAttributes();
        for (int index = attributes.getLength() - 1; index >= 0; index--) {
            Node attribute = attributes.item(index);
            String attributeKey = attribute.getLocalName() == null ? attribute.getNodeName() : attribute.getLocalName();
            if (!isSensitiveKey(attributeKey)) {
                continue;
            }
            Node inheritedAttribute = findAttribute(inheritedAttributes, attribute);
            if (inheritedAttribute != null
                    && attribute.getNodeValue().equals(maskSensitiveValue(inheritedAttribute.getNodeValue()))) {
                attribute.setNodeValue(INHERITED_VALUE_MARKER);
            }
        }
        List<Element> children = childElements(element);
        Map<String, Set<Integer>> claimedOccurrences = new LinkedHashMap<String, Set<Integer>>();
        for (Element child : children) {
            String childName = localName(child);
            String childIdentity = expandedName(child);
            int preferredOccurrence = claimedOccurrences.containsKey(childIdentity)
                    ? claimedOccurrences.get(childIdentity).size()
                    : 0;
            int occurrence = findInheritedXmlOccurrence(
                    child, inheritedElement, claimedOccurrences, preferredOccurrence);
            if (occurrence >= 0 && hasDuplicateNamedChildren(inheritedElement, child)) {
                child.setAttribute(INHERITED_XML_INDEX_ATTRIBUTE, String.valueOf(occurrence));
            }
            Element inheritedChild = findChildElement(inheritedElement, child, occurrence);
            if (inheritedChild == null) {
                continue;
            }
            if (childElements(child).isEmpty()
                    && isSensitiveKey(childName)
                    && child.getTextContent().equals(maskSensitiveValue(inheritedChild.getTextContent()))) {
                child.setTextContent(INHERITED_VALUE_MARKER);
                continue;
            }
            pruneUnmodifiedMaskedXmlLeaves(child, inheritedChild);
        }
    }

    private void mergeInheritedSensitiveXmlLeaves(Element element, Element inheritedElement) {
        if (inheritedElement == null) {
            return;
        }
        if (childElements(inheritedElement).isEmpty()
                && isSensitiveKey(localName(inheritedElement))
                && REMOVED_VALUE_MARKER.equals(element.getTextContent())) {
            Node parent = element.getParentNode();
            if (parent != null) {
                parent.removeChild(element);
            }
            return;
        }
        NamedNodeMap inheritedAttributes = inheritedElement.getAttributes();
        for (int index = 0; index < inheritedAttributes.getLength(); index++) {
            Node inheritedAttribute = inheritedAttributes.item(index);
            String attributeKey = inheritedAttribute.getLocalName() == null
                    ? inheritedAttribute.getNodeName()
                    : inheritedAttribute.getLocalName();
            Node currentAttribute = findAttribute(element.getAttributes(), inheritedAttribute);
            if (isSensitiveKey(attributeKey) && currentAttribute != null
                    && REMOVED_VALUE_MARKER.equals(currentAttribute.getNodeValue())) {
                element.removeAttributeNode((org.w3c.dom.Attr) currentAttribute);
            } else if (isSensitiveKey(attributeKey) && currentAttribute == null) {
                setAttributeValue(element, inheritedAttribute, inheritedAttribute.getNodeValue());
            } else if (isSensitiveKey(attributeKey) && currentAttribute != null
                    && INHERITED_VALUE_MARKER.equals(currentAttribute.getNodeValue())) {
                currentAttribute.setNodeValue(inheritedAttribute.getNodeValue());
            }
        }
        if (childElements(inheritedElement).isEmpty()
                && isSensitiveKey(localName(inheritedElement))
                && INHERITED_VALUE_MARKER.equals(element.getTextContent())) {
            element.setTextContent(inheritedElement.getTextContent());
        }
        Map<String, Integer> occurrences = new LinkedHashMap<String, Integer>();
        for (Element inheritedChild : childElements(inheritedElement)) {
            String childIdentity = expandedName(inheritedChild);
            int occurrence = occurrences.containsKey(childIdentity) ? occurrences.get(childIdentity).intValue() : 0;
            occurrences.put(childIdentity, Integer.valueOf(occurrence + 1));
            Element child = findChildElementByInheritedIndex(element, inheritedChild, occurrence);
            if (child == null && !hasIndexedChild(element, inheritedChild)) {
                child = findChildElement(element, inheritedChild, occurrence);
            }
            if (child != null) {
                child.removeAttribute(INHERITED_XML_INDEX_ATTRIBUTE);
                mergeInheritedSensitiveXmlLeaves(child, inheritedChild);
                continue;
            }
            Element projection = sensitiveXmlProjection(element.getOwnerDocument(), inheritedChild);
            if (projection != null) {
                element.appendChild(projection);
            }
        }
    }

    private void markRemovedInheritedSensitiveXmlLeaves(Element element, Element inheritedElement) {
        if (element == null || inheritedElement == null) {
            return;
        }
        NamedNodeMap inheritedAttributes = inheritedElement.getAttributes();
        for (int index = 0; index < inheritedAttributes.getLength(); index++) {
            Node inheritedAttribute = inheritedAttributes.item(index);
            String attributeKey = inheritedAttribute.getLocalName() == null
                    ? inheritedAttribute.getNodeName()
                    : inheritedAttribute.getLocalName();
            if (isSensitiveKey(attributeKey)
                    && findAttribute(element.getAttributes(), inheritedAttribute) == null) {
                setAttributeValue(element, inheritedAttribute, REMOVED_VALUE_MARKER);
            }
        }
        Map<String, Integer> occurrences = new LinkedHashMap<String, Integer>();
        for (Element inheritedChild : childElements(inheritedElement)) {
            String childIdentity = expandedName(inheritedChild);
            int occurrence = occurrences.containsKey(childIdentity) ? occurrences.get(childIdentity).intValue() : 0;
            occurrences.put(childIdentity, Integer.valueOf(occurrence + 1));
            Element child = findChildElementByInheritedIndex(element, inheritedChild, occurrence);
            if (child == null && !hasIndexedChild(element, inheritedChild)) {
                child = findChildElement(element, inheritedChild, occurrence);
            }
            if (child != null) {
                markRemovedInheritedSensitiveXmlLeaves(child, inheritedChild);
                continue;
            }
            Element removalProjection = removedSensitiveXmlProjection(element.getOwnerDocument(), inheritedChild);
            if (removalProjection != null) {
                if (hasDuplicateNamedChildren(inheritedElement, inheritedChild)) {
                    removalProjection.setAttribute(INHERITED_XML_INDEX_ATTRIBUTE, String.valueOf(occurrence));
                }
                element.appendChild(removalProjection);
            }
        }
    }

    private boolean hasDuplicateNamedChildren(Element parent, Element reference) {
        return sameNameChildren(parent, reference).size() > 1;
    }

    private int findInheritedXmlOccurrence(Element element,
                                           Element inheritedParent,
                                           Map<String, Set<Integer>> claimedOccurrences,
                                           int preferredOccurrence) {
        if (inheritedParent == null) {
            return -1;
        }
        String identity = expandedName(element);
        Set<Integer> claimed = claimedOccurrences.computeIfAbsent(
                identity, ignored -> new LinkedHashSet<Integer>());
        List<Element> candidates = sameNameChildren(inheritedParent, element);
        for (int index = 0; index < candidates.size(); index++) {
            if (!claimed.contains(Integer.valueOf(index))
                    && xmlMatchesMaskedInherited(element, candidates.get(index))) {
                claimed.add(Integer.valueOf(index));
                return index;
            }
        }
        if (preferredOccurrence >= 0 && preferredOccurrence < candidates.size()
                && !claimed.contains(Integer.valueOf(preferredOccurrence))) {
            claimed.add(Integer.valueOf(preferredOccurrence));
            return preferredOccurrence;
        }
        return -1;
    }

    private List<Element> sameNameChildren(Element parent, Element reference) {
        List<Element> result = new ArrayList<Element>();
        for (Element child : childElements(parent)) {
            if (sameExpandedName(reference, child)) {
                result.add(child);
            }
        }
        return result;
    }

    private boolean xmlMatchesMaskedInherited(Element current, Element inherited) {
        if (!sameExpandedName(current, inherited)) {
            return false;
        }
        NamedNodeMap inheritedAttributes = inherited.getAttributes();
        for (int index = 0; index < inheritedAttributes.getLength(); index++) {
            Node inheritedAttribute = inheritedAttributes.item(index);
            if (isNamespaceAttribute(inheritedAttribute)) {
                continue;
            }
            Node currentAttribute = findAttribute(current.getAttributes(), inheritedAttribute);
            if (currentAttribute == null) {
                return false;
            }
            String key = inheritedAttribute.getLocalName() == null
                    ? inheritedAttribute.getNodeName()
                    : inheritedAttribute.getLocalName();
            String expected = isSensitiveKey(key)
                    ? maskSensitiveValue(inheritedAttribute.getNodeValue())
                    : inheritedAttribute.getNodeValue();
            if (!Objects.equals(expected, currentAttribute.getNodeValue())) {
                return false;
            }
        }
        List<Element> currentChildren = childElements(current);
        List<Element> inheritedChildren = childElements(inherited);
        if (currentChildren.size() != inheritedChildren.size()) {
            return false;
        }
        if (inheritedChildren.isEmpty()) {
            String expected = isSensitiveKey(localName(inherited))
                    ? maskSensitiveValue(inherited.getTextContent())
                    : inherited.getTextContent();
            return Objects.equals(expected, current.getTextContent());
        }
        for (int index = 0; index < inheritedChildren.size(); index++) {
            if (!xmlMatchesMaskedInherited(currentChildren.get(index), inheritedChildren.get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean isNamespaceAttribute(Node attribute) {
        return attribute != null && (XMLConstants.XMLNS_ATTRIBUTE.equals(attribute.getNodeName())
                || XMLConstants.XMLNS_ATTRIBUTE.equals(attribute.getPrefix()));
    }

    private Element findChildElementByInheritedIndex(Element parent, Element reference, int occurrence) {
        if (parent == null) {
            return null;
        }
        String expectedIndex = String.valueOf(occurrence);
        for (Element child : childElements(parent)) {
            if (sameExpandedName(reference, child)
                    && expectedIndex.equals(child.getAttribute(INHERITED_XML_INDEX_ATTRIBUTE))) {
                return child;
            }
        }
        return null;
    }

    private boolean hasIndexedChild(Element parent, Element reference) {
        if (parent == null) {
            return false;
        }
        for (Element child : childElements(parent)) {
            if (sameExpandedName(reference, child) && child.hasAttribute(INHERITED_XML_INDEX_ATTRIBUTE)) {
                return true;
            }
        }
        return false;
    }

    private Element removedSensitiveXmlProjection(Document targetDocument, Element inheritedElement) {
        Element projection = (Element) targetDocument.importNode(inheritedElement, false);
        boolean sensitive = false;
        for (int index = projection.getAttributes().getLength() - 1; index >= 0; index--) {
            Node attribute = projection.getAttributes().item(index);
            String attributeKey = attribute.getLocalName() == null ? attribute.getNodeName() : attribute.getLocalName();
            boolean namespaceAttribute = XMLConstants.XMLNS_ATTRIBUTE.equals(attribute.getNodeName())
                    || XMLConstants.XMLNS_ATTRIBUTE.equals(attribute.getPrefix());
            if (namespaceAttribute) {
                continue;
            }
            if (isSensitiveKey(attributeKey)) {
                attribute.setNodeValue(REMOVED_VALUE_MARKER);
                sensitive = true;
            } else {
                projection.removeAttributeNode((org.w3c.dom.Attr) attribute);
            }
        }
        List<Element> inheritedChildren = childElements(inheritedElement);
        if (inheritedChildren.isEmpty() && isSensitiveKey(localName(inheritedElement))) {
            projection.setTextContent(REMOVED_VALUE_MARKER);
            sensitive = true;
        }
        for (Element inheritedChild : inheritedChildren) {
            Element childProjection = removedSensitiveXmlProjection(targetDocument, inheritedChild);
            if (childProjection != null) {
                projection.appendChild(childProjection);
                sensitive = true;
            }
        }
        return sensitive ? projection : null;
    }

    private Element sensitiveXmlProjection(Document targetDocument, Element inheritedElement) {
        String elementName = localName(inheritedElement);
        if (childElements(inheritedElement).isEmpty() && isSensitiveKey(elementName)) {
            return (Element) targetDocument.importNode(inheritedElement, true);
        }
        Element projection = (Element) targetDocument.importNode(inheritedElement, false);
        boolean sensitive = false;
        NamedNodeMap attributes = inheritedElement.getAttributes();
        for (int index = projection.getAttributes().getLength() - 1; index >= 0; index--) {
            Node attribute = projection.getAttributes().item(index);
            String attributeKey = attribute.getLocalName() == null ? attribute.getNodeName() : attribute.getLocalName();
            boolean namespaceAttribute = XMLConstants.XMLNS_ATTRIBUTE.equals(attribute.getNodeName())
                    || XMLConstants.XMLNS_ATTRIBUTE.equals(attribute.getPrefix());
            if (!namespaceAttribute && !isSensitiveKey(attributeKey)) {
                projection.removeAttributeNode((org.w3c.dom.Attr) attribute);
            } else if (isSensitiveKey(attributeKey)) {
                sensitive = true;
            }
        }
        for (Element inheritedChild : childElements(inheritedElement)) {
            Element childProjection = sensitiveXmlProjection(targetDocument, inheritedChild);
            if (childProjection != null) {
                projection.appendChild(childProjection);
                sensitive = true;
            }
        }
        return sensitive ? projection : null;
    }

    private Element findChildElement(Element parent, Element reference, int occurrence) {
        if (parent == null) {
            return null;
        }
        int current = 0;
        for (Element child : childElements(parent)) {
            if (!sameExpandedName(reference, child)) {
                continue;
            }
            if (current == occurrence) {
                return child;
            }
            current++;
        }
        return null;
    }

    private Node findAttribute(NamedNodeMap attributes, Node reference) {
        if (attributes == null || reference == null) {
            return null;
        }
        if (reference.getLocalName() != null) {
            return attributes.getNamedItemNS(reference.getNamespaceURI(), reference.getLocalName());
        }
        return attributes.getNamedItem(reference.getNodeName());
    }

    private void setAttributeValue(Element element, Node reference, String value) {
        String namespaceUri = reference.getNamespaceURI();
        if (namespaceUri == null || namespaceUri.isEmpty()) {
            element.setAttribute(reference.getNodeName(), value);
            return;
        }
        String prefix = reference.getPrefix();
        if (prefix != null && !prefix.isEmpty()
                && !namespaceUri.equals(element.lookupNamespaceURI(prefix))) {
            element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                    XMLConstants.XMLNS_ATTRIBUTE + ":" + prefix, namespaceUri);
        }
        element.setAttributeNS(namespaceUri, reference.getNodeName(), value);
    }

    private boolean sameExpandedName(Element left, Element right) {
        return left != null && right != null && expandedName(left).equals(expandedName(right));
    }

    private String expandedName(Element element) {
        return String.valueOf(element == null ? null : element.getNamespaceURI())
                + "|" + (element == null ? "" : localName(element));
    }

    private String serializeXml(Document document, String originalXml) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, originalXml.startsWith("<?xml") ? "no" : "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private void transformXmlElement(Element element, Element existingElement, Mode mode) {
        NamedNodeMap attributes = element.getAttributes();
        NamedNodeMap existingAttributes = existingElement == null ? null : existingElement.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            if (!isSensitiveKey(attribute.getLocalName() == null ? attribute.getNodeName() : attribute.getLocalName())) {
                continue;
            }
            Node existingAttribute = findAttribute(existingAttributes, attribute);
            Object transformed = transformScalar(attribute.getNodeValue(),
                    existingAttribute == null ? null : existingAttribute.getNodeValue(), attribute.getNodeName(), mode);
            attribute.setNodeValue(String.valueOf(transformed));
        }
        List<Element> children = childElements(element);
        if (children.isEmpty() && isSensitiveKey(localName(element))) {
            Object existingText = existingElement == null ? null : existingElement.getTextContent();
            element.setTextContent(String.valueOf(transformScalar(element.getTextContent(), existingText, localName(element), mode)));
            return;
        }
        Map<String, Integer> occurrences = new LinkedHashMap<String, Integer>();
        Map<String, Set<Integer>> claimedOccurrences = mode == Mode.ENCRYPT
                ? new LinkedHashMap<String, Set<Integer>>()
                : Collections.emptyMap();
        for (int index = 0; index < children.size(); index++) {
            Element child = children.get(index);
            String childIdentity = expandedName(child);
            int occurrence = occurrences.containsKey(childIdentity) ? occurrences.get(childIdentity).intValue() : 0;
            occurrences.put(childIdentity, Integer.valueOf(occurrence + 1));
            int existingOccurrence = mode == Mode.ENCRYPT
                    ? findInheritedXmlOccurrence(child, existingElement, claimedOccurrences, occurrence)
                    : occurrence;
            transformXmlElement(child, findChildElement(existingElement, child, existingOccurrence), mode);
        }
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private List<Element> childElements(Element element) {
        List<Element> result = new ArrayList<Element>();
        NodeList nodes = element.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private String localName(Element element) {
        String localName = element.getLocalName();
        return localName == null || localName.trim().isEmpty() ? element.getTagName() : localName;
    }

    private Object parseStructuredText(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return value;
        }
        if (!(value instanceof String)) {
            return null;
        }
        String text = ((String) value).trim();
        if (!(text.startsWith("{") || text.startsWith("["))) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(text, Object.class);
        } catch (Exception parseFailure) {
            return null;
        }
    }

    private String encryptSensitiveValue(String value) {
        if (value == null || value.isEmpty() || isEncryptedSequence(value)) {
            return value;
        }
        String plainText = isProtectedSensitiveValue(value) ? decryptSensitiveValue(value) : value;
        return ENCRYPTED_PREFIX + encryptionService.encrypt(plainText) + ENCRYPTED_SUFFIX;
    }

    private String decryptSensitiveValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int offset = 0;
        while (offset < value.length()) {
            int encryptedStart = value.indexOf(ENCRYPTED_PREFIX, offset);
            if (encryptedStart < 0) {
                result.append(value.substring(offset));
                break;
            }
            result.append(value, offset, encryptedStart);
            int encryptedEnd = value.indexOf(ENCRYPTED_SUFFIX, encryptedStart + ENCRYPTED_PREFIX.length());
            if (encryptedEnd < 0) {
                result.append(value.substring(encryptedStart));
                break;
            }
            String encryptedText = value.substring(encryptedStart + ENCRYPTED_PREFIX.length(), encryptedEnd);
            result.append(encryptionService.decrypt(encryptedText));
            offset = encryptedEnd + ENCRYPTED_SUFFIX.length();
        }
        return result.toString();
    }

    private String maskSensitiveValue(String value) {
        String decrypted = decryptSensitiveValue(value);
        StringBuilder result = new StringBuilder();
        int offset = 0;
        DynamicRange range;
        while ((range = nextDynamicRange(decrypted, offset)) != null) {
            appendMaskedStaticSegment(result, decrypted.substring(offset, range.start));
            result.append(maskDynamicRange(decrypted.substring(range.start, range.end)));
            offset = range.end;
        }
        appendMaskedStaticSegment(result, decrypted.substring(offset));
        return result.toString();
    }

    private String maskDynamicRange(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.startsWith("{{") && value.endsWith("}}")) {
            return "{{****}}";
        }
        int openParenthesis = value.indexOf('(');
        int closeParenthesis = value.lastIndexOf(')');
        if (openParenthesis > 0 && closeParenthesis > openParenthesis) {
            return value.substring(0, openParenthesis + 1) + "****"
                    + value.substring(closeParenthesis);
        }
        return value;
    }

    private void appendMaskedStaticSegment(StringBuilder result, String segment) {
        if (!segment.isEmpty()) {
            result.append(encryptionService.mask(segment));
        }
    }

    private boolean isProtectedSensitiveValue(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int offset = 0;
        boolean encrypted = false;
        DynamicRange range;
        while ((range = nextDynamicRange(value, offset)) != null) {
            String segment = value.substring(offset, range.start);
            if (!segment.isEmpty()) {
                if (!isEncryptedSequence(segment)) {
                    return false;
                }
                encrypted = true;
            }
            offset = range.end;
        }
        String tail = value.substring(offset);
        if (!tail.isEmpty()) {
            if (!isEncryptedSequence(tail)) {
                return false;
            }
            encrypted = true;
        }
        return encrypted;
    }

    private boolean isEncryptedSequence(String value) {
        int offset = 0;
        boolean found = false;
        while (offset < value.length()) {
            if (!value.startsWith(ENCRYPTED_PREFIX, offset)) {
                return false;
            }
            int end = value.indexOf(ENCRYPTED_SUFFIX, offset + ENCRYPTED_PREFIX.length());
            if (end < 0) {
                return false;
            }
            found = true;
            offset = end + ENCRYPTED_SUFFIX.length();
        }
        return found;
    }

    private DynamicRange nextDynamicRange(String value, int offset) {
        int dynStart = value.indexOf("{dyn_", offset);
        int templateStart = value.indexOf("{{", offset);
        int start;
        int end;
        if (dynStart < 0 || (templateStart >= 0 && templateStart < dynStart)) {
            start = templateStart;
            end = start < 0 ? -1 : value.indexOf("}}", start + 2);
            end = end < 0 ? -1 : end + 2;
        } else {
            start = dynStart;
            end = start < 0 ? -1 : dynamicExpressionEnd(value, start);
        }
        return start < 0 || end < 0 ? null : new DynamicRange(start, end);
    }

    private int dynamicExpressionEnd(String value, int start) {
        int braceDepth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                continue;
            }
            if (current == '{') {
                braceDepth++;
                continue;
            }
            if (current == '}' && --braceDepth == 0) {
                return index + 1;
            }
        }
        return -1;
    }

    private StudioException invalidStructuredOption(String key, String format, Throwable cause) {
        String message = "Invalid " + format + " in HTTP reader option '" + key + "'";
        return cause == null
                ? new StudioException(StudioErrorCode.BAD_REQUEST, message)
                : new StudioException(StudioErrorCode.BAD_REQUEST, message, cause);
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
        return normalized.contains("authorization")
                || normalized.contains("authentication")
                || normalized.equals("auth")
                || normalized.endsWith("auth")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.equals("pwd")
                || normalized.endsWith("pwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("accesskey")
                || normalized.contains("apikey")
                || normalized.contains("privatekey")
                || normalized.contains("credential")
                || normalized.contains("cookie")
                || normalized.contains("signature")
                || normalized.equals("sig")
                || normalized.equals("sas")
                || normalized.contains("subscriptionkey")
                || normalized.contains("functionskey")
                || normalized.equals("session")
                || normalized.endsWith("session")
                || normalized.contains("sessionid");
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<?, ?>) value : new LinkedHashMap<Object, Object>();
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private enum Mode {
        ENCRYPT,
        DECRYPT,
        MASK
    }

    private static final class DynamicRange {
        private final int start;
        private final int end;

        private DynamicRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
