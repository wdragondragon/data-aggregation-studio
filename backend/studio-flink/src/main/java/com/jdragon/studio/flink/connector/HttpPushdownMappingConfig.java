package com.jdragon.studio.flink.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

public final class HttpPushdownMappingConfig implements Serializable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter();
    private static final DateTimeFormatter TIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter();
    private static final Pattern BRACED_PATH_VARIABLE = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern COLON_PATH_VARIABLE = Pattern.compile("(?<![A-Za-z0-9_]):([A-Za-z_][A-Za-z0-9_]*)");
    private static final List<String> HTTP_LOCATIONS = Arrays.asList("param", "query", "body", "header", "path");
    private static final Set<String> RESERVED_HTTP_HEADERS = new LinkedHashSet<String>(Arrays.asList(
            "contenttype", "contentlength", "host", "connection", "transferencoding",
            "soapaction", "proxyconnection", "te", "trailer", "upgrade"));
    static final String INTERNAL_CONTEXT_FIELD_PREFIX = "__studio_http_context_";

    private final List<Mapping> mappings;
    private final Set<String> blockedBodyFields;

    private HttpPushdownMappingConfig(List<Mapping> mappings, Set<String> blockedBodyFields) {
        this.mappings = mappings == null ? new ArrayList<Mapping>() : new ArrayList<Mapping>(mappings);
        this.blockedBodyFields = blockedBodyFields == null
                ? new LinkedHashSet<String>()
                : new LinkedHashSet<String>(blockedBodyFields);
    }

    public static HttpPushdownMappingConfig from(Map<String, Object> modelMetadata) {
        return from(modelMetadata, null);
    }

    public static HttpPushdownMappingConfig from(Map<String, Object> modelMetadata, String requestPath) {
        List<Mapping> mappings = new ArrayList<Mapping>();
        Set<String> blockedBodyFields = new LinkedHashSet<String>();
        addReaderOptionMappings(mappings, blockedBodyFields, modelMetadata, requestPath);
        return new HttpPushdownMappingConfig(mappings, blockedBodyFields);
    }

    public boolean isEnabled() {
        return !mappings.isEmpty();
    }

    public List<Mapping> getMappings() {
        return new ArrayList<Mapping>(mappings);
    }

    public List<Mapping> findByField(String field) {
        List<Mapping> result = new ArrayList<Mapping>();
        String normalized = normalizeText(field);
        if (normalized.isEmpty()) {
            return result;
        }
        for (Mapping mapping : mappings) {
            String mappedField = normalizeText(mapping.getField());
            if (normalized.equals(mappedField) || normalized.equals(leafField(mappedField))) {
                result.add(mapping);
            }
        }
        return result;
    }

    public Mapping findByLocationAndField(String location, String field) {
        String normalizedLocation = normalizeLocation(location);
        String normalizedField = normalizeText(field);
        if (normalizedLocation.isEmpty() || normalizedField.isEmpty()) {
            return null;
        }
        if (isSensitiveRequestField(normalizedField)) {
            return null;
        }
        if ("header".equals(normalizedLocation) && isReservedHttpHeader(normalizedField)) {
            return null;
        }
        List<Mapping> matches = new ArrayList<Mapping>();
        for (Mapping mapping : mappings) {
            if (normalizedLocation.equals(normalizeLocation(mapping.getLocation()))
                    && normalizedField.equals(normalizeText(mapping.getField()))) {
                matches.add(mapping);
            }
        }
        if (matches.isEmpty()) {
            for (Mapping mapping : mappings) {
                if (normalizedLocation.equals(normalizeLocation(mapping.getLocation()))
                        && normalizedField.equals(leafField(normalizeText(mapping.getField())))) {
                    matches.add(mapping);
                }
            }
        }
        if (matches.size() > 1) {
            List<String> targets = new ArrayList<String>();
            for (Mapping mapping : matches) {
                targets.add(mapping.targetDescription());
            }
            throw new IllegalArgumentException("HTTP 下推字段 " + normalizedLocation + "." + normalizedField
                    + " 同时映射到多个请求目标: " + String.join(", ", targets));
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if ("body".equals(normalizedLocation) && isBlockedBodyField(normalizedField)) {
            return null;
        }
        if ("path".equals(normalizedLocation)) {
            return null;
        }
        return isHttpLocation(normalizedLocation) ? Mapping.convention(normalizedField, normalizedLocation, null) : null;
    }

    public Set<String> mappedFields() {
        Set<String> fields = new LinkedHashSet<String>();
        for (Mapping mapping : mappings) {
            if (hasText(mapping.getField())) {
                fields.add(leafField(mapping.getField()));
            }
        }
        return fields;
    }

    public Map<String, Set<String>> fieldsByLocation() {
        Map<String, Set<String>> result = new LinkedHashMap<String, Set<String>>();
        for (Mapping mapping : mappings) {
            String location = normalizeLocation(mapping.getLocation());
            if (location.isEmpty() || !hasText(mapping.getField())) {
                continue;
            }
            if (!result.containsKey(location)) {
                result.put(location, new LinkedHashSet<String>());
            }
            result.get(location).add(mapping.getField());
        }
        return result;
    }

    public static List<String> httpLocations() {
        return new ArrayList<String>(HTTP_LOCATIONS);
    }

    public static boolean isHttpLocation(String location) {
        String normalized = normalizeLocation(location);
        return HTTP_LOCATIONS.contains(normalized);
    }

    private static void addReaderOptionMappings(List<Mapping> mappings,
                                                Set<String> blockedBodyFields,
                                                Map<String, Object> modelMetadata,
                                                String requestPath) {
        Map<String, Object> readerOptions = readerOptions(modelMetadata);
        addObjectKeyMappings(mappings, "param", readerOptions.get("params"), "requestParamName");
        addObjectKeyMappings(mappings, "header", readerOptions.get("header"), "headerName");
        addBodyMappings(mappings, blockedBodyFields, readerOptions.get("requestBody"));
        addPathMappings(mappings, firstText(requestPath,
                modelMetadata == null ? null : modelMetadata.get("requestPath"),
                readerOptions.get("url"),
                modelMetadata == null ? null : modelMetadata.get("url"),
                modelMetadata == null ? null : modelMetadata.get("physicalName")));
    }

    private static Map<String, Object> readerOptions(Map<String, Object> modelMetadata) {
        if (modelMetadata == null) {
            return Collections.emptyMap();
        }
        Object configured = modelMetadata.get("readerOptions");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (configured instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        for (String key : Arrays.asList("url", "params", "header", "requestBody")) {
            if (!result.containsKey(key) && modelMetadata.get(key) != null) {
                result.put(key, modelMetadata.get(key));
            }
        }
        return result;
    }

    private static void addObjectKeyMappings(List<Mapping> mappings, String location, Object value, String targetKey) {
        Map<String, Object> object = toObjectMap(value);
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            String key = entry.getKey();
            if (!hasText(key) || isSensitiveRequestField(key)
                    || ("header".equals(location) && isReservedHttpHeader(key))) {
                continue;
            }
            if ("requestParamName".equals(targetKey)) {
                addIfMissing(mappings, Mapping.convention(key, location, key, entry.getValue()));
            } else if ("headerName".equals(targetKey)) {
                addIfMissing(mappings, Mapping.convention(key, location, key, entry.getValue()));
            }
        }
    }

    private static void addBodyMappings(List<Mapping> mappings,
                                        Set<String> blockedBodyFields,
                                        Object value) {
        Map<String, Object> object = toObjectMap(value);
        if (!object.isEmpty()) {
            collectBodyPaths(mappings, blockedBodyFields, "", object);
            return;
        }
        collectXmlBodyPaths(mappings, blockedBodyFields, value);
    }

    private static void collectBodyPaths(List<Mapping> mappings,
                                         Set<String> blockedBodyFields,
                                         String prefix,
                                         Map<String, Object> object) {
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            String key = entry.getKey();
            if (!hasText(key) || isSensitiveRequestField(key)) {
                continue;
            }
            String path = hasText(prefix) ? prefix + "." + key : key;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                blockBodyPath(blockedBodyFields, path);
                Map<String, Object> child = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> childEntry : ((Map<?, ?>) value).entrySet()) {
                    if (childEntry.getKey() != null) {
                        child.put(String.valueOf(childEntry.getKey()), childEntry.getValue());
                    }
                }
                collectBodyPaths(mappings, blockedBodyFields, path, child);
            } else if (value instanceof Iterable<?> || (value != null && value.getClass().isArray())) {
                blockBodyPath(blockedBodyFields, path);
            } else {
                addIfMissing(mappings, Mapping.bodyConvention(path, path, value));
            }
        }
    }

    private static void blockBodyPath(Set<String> blockedBodyFields, String path) {
        blockedBodyFields.add(normalizeText(path));
        blockedBodyFields.add(leafField(path));
    }

    private boolean isBlockedBodyField(String field) {
        String normalized = normalizeText(field);
        return blockedBodyFields.contains(normalized) || blockedBodyFields.contains(leafField(normalized));
    }

    private static void collectXmlBodyPaths(List<Mapping> mappings,
                                            Set<String> blockedBodyFields,
                                            Object value) {
        String xml = value == null ? "" : String.valueOf(value).trim();
        if (!xml.startsWith("<")) {
            return;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            collectXmlLeafPaths(mappings, blockedBodyFields, document.getDocumentElement(), "");
        } catch (Exception ignored) {
            // Templated XML remains available through explicit body.<field> convention.
        }
    }

    private static void collectXmlLeafPaths(List<Mapping> mappings,
                                            Set<String> blockedBodyFields,
                                            Element element,
                                            String prefix) {
        String name = localName(element);
        if (isSensitiveRequestField(name)) {
            return;
        }
        String qualifiedName = qualifiedXmlName(element);
        String path = hasText(prefix) ? prefix + "." + qualifiedName : qualifiedName;
        List<Element> children = childElements(element);
        if (children.isEmpty()) {
            addIfMissing(mappings, Mapping.bodyConvention(path, path, element.getTextContent()));
            return;
        }
        blockBodyPath(blockedBodyFields, path);
        for (Element child : children) {
            collectXmlLeafPaths(mappings, blockedBodyFields, child, path);
        }
    }

    private static List<Element> childElements(Element element) {
        List<Element> children = new ArrayList<Element>();
        NodeList nodes = element.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element) {
                children.add((Element) node);
            }
        }
        return children;
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        return hasText(local) ? local : element.getTagName().replaceFirst("^.*:", "");
    }

    private static String qualifiedXmlName(Element element) {
        String local = localName(element);
        String prefix = element.getPrefix();
        if (hasText(prefix)) {
            return prefix + ":" + local;
        }
        String namespaceUri = element.getNamespaceURI();
        return hasText(namespaceUri) ? "{" + namespaceUri + "}" + local : local;
    }

    private static void addPathMappings(List<Mapping> mappings, String url) {
        if (!hasText(url)) {
            return;
        }
        String path = uriPath(url);
        addPathMappings(mappings, path, BRACED_PATH_VARIABLE);
        addPathMappings(mappings, path, COLON_PATH_VARIABLE);
    }

    private static String uriPath(String url) {
        String text = normalizeText(url);
        if (text.isEmpty()) {
            return "";
        }
        try {
            URI uri = new URI(text.replace("{", "%7B").replace("}", "%7D"));
            String path = uri.getRawPath();
            return path == null ? "" : path.replace("%7B", "{").replace("%7D", "}")
                    .replace("%7b", "{").replace("%7d", "}");
        } catch (Exception ignored) {
            int fragment = text.indexOf('#');
            if (fragment >= 0) {
                text = text.substring(0, fragment);
            }
            int query = text.indexOf('?');
            if (query >= 0) {
                text = text.substring(0, query);
            }
            int scheme = text.indexOf("://");
            if (scheme >= 0) {
                int pathStart = text.indexOf('/', scheme + 3);
                return pathStart < 0 ? "" : text.substring(pathStart);
            }
            return text;
        }
    }

    private static void addPathMappings(List<Mapping> mappings, String url, Pattern pattern) {
        Matcher matcher = pattern.matcher(url);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!isSensitiveRequestField(name)) {
                addIfMissing(mappings, Mapping.pathConvention(name, name));
            }
        }
    }

    private static Map<String, Object> toObjectMap(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        if (value == null || !hasText(String.valueOf(value))) {
            return Collections.emptyMap();
        }
        String text = String.valueOf(value).trim();
        if (!text.startsWith("{")) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(text, new TypeReference<Map<String, Object>>() {
            });
            return parsed == null ? Collections.<String, Object>emptyMap() : parsed;
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private static void addIfMissing(List<Mapping> mappings, Mapping mapping) {
        if (mapping == null) {
            return;
        }
        for (Mapping existing : mappings) {
            if (normalizeLocation(existing.getLocation()).equals(normalizeLocation(mapping.getLocation()))
                    && normalizeText(existing.getField()).equals(normalizeText(mapping.getField()))
                    && existing.targetIdentity().equals(mapping.targetIdentity())) {
                return;
            }
        }
        mappings.add(mapping);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    static String leafField(String value) {
        List<String> segments = splitBodyPath(value);
        String leaf = segments.isEmpty() ? normalizeText(value) : segments.get(segments.size() - 1);
        leaf = leaf.replaceFirst("^\\{[^}]+}", "");
        int prefix = leaf.indexOf(':');
        return normalizeText(prefix < 0 ? leaf : leaf.substring(prefix + 1));
    }

    public static List<String> splitBodyPath(String value) {
        List<String> result = new ArrayList<String>();
        String text = normalizeText(value);
        StringBuilder segment = new StringBuilder();
        boolean inClarkNamespace = false;
        for (int index = 0; index <= text.length(); index++) {
            char current = index == text.length() ? '.' : text.charAt(index);
            if (current == '{' && segment.length() == 0) {
                inClarkNamespace = true;
            } else if (current == '}' && inClarkNamespace) {
                inClarkNamespace = false;
            }
            if (current == '.' && !inClarkNamespace) {
                String item = segment.toString().trim();
                if (!item.isEmpty()) {
                    result.add(item);
                }
                segment.setLength(0);
            } else {
                segment.append(current);
            }
        }
        return result;
    }

    public static String normalizeLocation(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
    }

    public static boolean isSensitiveRequestField(String value) {
        for (String segment : splitBodyPath(value)) {
            String normalized = segment.replaceFirst("^\\{[^}]+}", "")
                    .replaceFirst("^.*:", "")
                    .toLowerCase(Locale.ENGLISH)
                    .replaceAll("[^a-z0-9]", "");
            if (normalized.contains("authorization")
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
                    || normalized.equals("sig")
                    || normalized.equals("sas")
                    || normalized.contains("subscriptionkey")
                    || normalized.contains("xfunctionskey")
                    || normalized.contains("cookie")
                    || normalized.contains("signature")
                    || normalized.equals("session")
                    || normalized.endsWith("session")
                    || normalized.contains("sessionid")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isReservedHttpHeader(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9]", "");
        return RESERVED_HTTP_HEADERS.contains(normalized);
    }

    public static final class Mapping implements Serializable {
        private final String field;
        private final String location;
        private final String requestParamName;
        private final String bodyPath;
        private final String headerName;
        private final String pathVariable;
        private final Set<String> supportedOperators;
        private final Set<String> paginationTokens;

        private Mapping(String field,
                        String location,
                        String requestParamName,
                        String bodyPath,
                        String headerName,
                        String pathVariable,
                        Object configuredValue) {
            this.field = field;
            this.location = normalizeLocation(location);
            this.requestParamName = requestParamName;
            this.bodyPath = bodyPath;
            this.headerName = headerName;
            this.pathVariable = pathVariable;
            this.supportedOperators = new LinkedHashSet<String>(Arrays.asList("="));
            this.paginationTokens = paginationTokens(configuredValue);
        }

        static Mapping convention(String field, String location, String targetName) {
            return convention(field, location, targetName, null);
        }

        static Mapping convention(String field, String location, String targetName, Object configuredValue) {
            String normalizedLocation = normalizeLocation(location);
            String target = hasText(targetName) ? targetName : field;
            if ("body".equals(normalizedLocation)) {
                return bodyConvention(field, target, configuredValue);
            }
            if ("header".equals(normalizedLocation)) {
                return new Mapping(field, normalizedLocation, null, null, target, null, configuredValue);
            }
            if ("path".equals(normalizedLocation)) {
                return pathConvention(field, target);
            }
            return new Mapping(field, normalizedLocation, target, null, null, null, configuredValue);
        }

        static Mapping bodyConvention(String field, String bodyPath) {
            return bodyConvention(field, bodyPath, null);
        }

        static Mapping bodyConvention(String field, String bodyPath, Object configuredValue) {
            return new Mapping(field, "body", null, bodyPath, null, null, configuredValue);
        }

        static Mapping pathConvention(String field, String pathVariable) {
            return new Mapping(field, "path", null, null, null, pathVariable, null);
        }

        public boolean supportsOperator(String operator) {
            return supportedOperators.contains(normalizeOperator(operator));
        }

        public Map<String, Object> toPushdownPredicate(String operator, List<Object> values, String expression) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("field", internalContextField());
            map.put("resultField", field);
            map.put("location", location);
            putIfPresent(map, "requestParamName", requestParamName);
            putIfPresent(map, "bodyPath", bodyPath);
            putIfPresent(map, "headerName", headerName);
            putIfPresent(map, "pathVariable", pathVariable);
            map.put("operator", normalizeOperator(operator));
            map.put("values", normalizePredicateValues(values));
            if (!paginationTokens.isEmpty()) {
                map.put("paginationTokens", new ArrayList<String>(paginationTokens));
            }
            putIfPresent(map, "expression", expression);
            return map;
        }

        private String internalContextField() {
            return INTERNAL_CONTEXT_FIELD_PREFIX + targetIdentity().replaceAll("[^A-Za-z0-9_]", "_");
        }

        public String getField() {
            return field;
        }

        public String getLocation() {
            return location;
        }

        public String getRequestParamName() {
            return requestParamName;
        }

        public String getBodyPath() {
            return bodyPath;
        }

        public String getHeaderName() {
            return headerName;
        }

        public String getPathVariable() {
            return pathVariable;
        }

        public Set<String> getSupportedOperators() {
            return new LinkedHashSet<String>(supportedOperators);
        }

        public Set<String> getPaginationTokens() {
            return new LinkedHashSet<String>(paginationTokens);
        }

        String targetDescription() {
            return targetIdentity();
        }

        private String targetIdentity() {
            if ("body".equals(location)) {
                return "body." + normalizeText(bodyPath);
            }
            if ("header".equals(location)) {
                return "header." + normalizeText(headerName).toLowerCase(Locale.ENGLISH);
            }
            if ("path".equals(location)) {
                return "path." + normalizeText(pathVariable);
            }
            return "query." + normalizeText(requestParamName);
        }

        private static void putIfPresent(Map<String, Object> map, String key, String value) {
            if (hasText(value)) {
                map.put(key, value);
            }
        }

        private static List<Object> normalizePredicateValues(List<Object> values) {
            List<Object> normalized = new ArrayList<Object>();
            if (values == null) {
                return normalized;
            }
            for (Object value : values) {
                normalized.add(normalizePredicateValue(value));
            }
            return normalized;
        }

        private static Object normalizePredicateValue(Object value) {
            if (value instanceof LocalDate) {
                return value.toString();
            }
            if (value instanceof java.sql.Date) {
                return ((java.sql.Date) value).toLocalDate().toString();
            }
            if (value instanceof LocalDateTime) {
                return DATE_TIME_FORMATTER.format((LocalDateTime) value);
            }
            if (value instanceof Timestamp) {
                return DATE_TIME_FORMATTER.format(((Timestamp) value).toLocalDateTime());
            }
            if (value instanceof LocalTime) {
                return TIME_FORMATTER.format((LocalTime) value);
            }
            if (value instanceof Time) {
                return TIME_FORMATTER.format(((Time) value).toLocalTime());
            }
            return value;
        }

        private static Set<String> paginationTokens(Object configuredValue) {
            Set<String> tokens = new LinkedHashSet<String>();
            String value = configuredValue == null ? "" : String.valueOf(configuredValue);
            if (value.contains("{dyn_page}")) {
                tokens.add("PAGE");
            }
            if (value.contains("{dyn_pageSize}")) {
                tokens.add("PAGE_SIZE");
            }
            if (value.contains("{dyn_offset}")) {
                tokens.add("OFFSET");
            }
            return tokens;
        }

    }

    public static String normalizeOperator(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ENGLISH);
    }

    private static String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }
}
