package com.jdragon.studio.worker.runtime;

import com.alibaba.fastjson.JSON;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.core.spi.NodeExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class HttpShellNodeExecutor implements NodeExecutor {

    private static final Pattern RUNTIME_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
    private static final Pattern SENSITIVE_NAME_PATTERN = Pattern.compile("(?i).*(authorization|password|passwd|pwd|secret|token|cookie|set-cookie|api[-_]?key|access[-_]?key|credential|signature).*");
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile("(?i)(\"(?:authorization|password|passwd|pwd|secret|token|cookie|apiKey|api_key|accessKey|access_key|credential|signature)\"\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern FORM_SECRET_PATTERN = Pattern.compile("(?i)((?:authorization|password|passwd|pwd|secret|token|cookie|api[-_]?key|access[-_]?key|credential|signature)=)([^&\\s]+)");
    private static final String MASKED_VALUE = "******";
    private static final int MAX_LOG_PREVIEW_CHARS = 2000;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public boolean supports(WorkflowNodeDefinition definition) {
        return definition.getNodeType() == NodeType.HTTP || definition.getNodeType() == NodeType.SHELL;
    }

    @Override
    public Map<String, Object> execute(WorkflowNodeDefinition definition, Map<String, Object> runtimeContext) {
        Map<String, Object> config = definition.getConfig() == null
                ? Collections.<String, Object>emptyMap()
                : definition.getConfig();
        if (definition.getNodeType() == NodeType.HTTP) {
            return executeHttp(definition, config, runtimeContext);
        }
        return executeShell(config);
    }

    private Map<String, Object> executeHttp(WorkflowNodeDefinition definition,
                                            Map<String, Object> config,
                                            Map<String, Object> runtimeContext) {
        String url = resolveRuntimePlaceholders(asString(config.get("url")), runtimeContext);
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("HTTP node url is required");
        }
        String method = resolveRuntimePlaceholders(asString(config.getOrDefault("method", "GET")), runtimeContext);
        HttpMethod httpMethod;
        try {
            httpMethod = HttpMethod.valueOf(method == null ? "GET" : method.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }
        String nodeCode = definition.getNodeCode() == null ? "HTTP" : definition.getNodeCode();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        HttpHeaders headers = new HttpHeaders();
        Map<String, Object> headerMap = normalizeParameterMap(config.get("headers"), runtimeContext);
        for (Map.Entry<String, Object> entry : headerMap.entrySet()) {
            String headerValue = asString(entry.getValue());
            headers.add(entry.getKey(), headerValue == null ? "" : headerValue);
        }
        Map<String, Object> queryParams = normalizeParameterMap(firstNonNull(config.get("queryParams"), config.get("params"), config.get("query")), runtimeContext);
        String requestUrl = appendQueryParams(url, queryParams);
        Object body = resolveBody(firstNonNull(config.get("body"), config.get("payload")), runtimeContext);
        if (body != null && !headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        long startedAt = System.currentTimeMillis();
        String safeRequestUrl = sanitizeUrl(requestUrl);
        int requestBodyBytes = valueBytes(body);
        log.info("[HTTP:{}] Start request method={}, url={}, queryParamCount={}, headerCount={}, hasBody={}",
                nodeCode, httpMethod.name(), safeRequestUrl, queryParams.size(), headers.size(), body != null);
        if (!queryParams.isEmpty()) {
            log.info("[HTTP:{}] Query parameters: {}", nodeCode, sanitizeParameterMap(queryParams));
        }
        if (!headers.isEmpty()) {
            log.info("[HTTP:{}] Request headers: {}", nodeCode, sanitizeHeaders(headers));
        }
        if (body != null) {
            String bodyPreview = previewValue(body);
            log.info("[HTTP:{}] Request body type={}, bytes={}, preview={}",
                    nodeCode, body.getClass().getSimpleName(), requestBodyBytes, bodyPreview);
        }
        try {
            ResponseEntity<String> responseEntity = restTemplate.exchange(requestUrl, httpMethod, new HttpEntity<Object>(body, headers), String.class);
            long durationMs = System.currentTimeMillis() - startedAt;
            String responseBody = responseEntity.getBody();
            int httpStatus = responseEntity.getStatusCode().value();
            log.info("[HTTP:{}] Completed request httpStatus={}, durationMs={}, responseBytes={}, contentType={}",
                    nodeCode,
                    httpStatus,
                    durationMs,
                    utf8Bytes(responseBody),
                    responseEntity.getHeaders().getContentType());
            if (!responseEntity.getHeaders().isEmpty()) {
                log.info("[HTTP:{}] Response headers: {}", nodeCode, sanitizeHeaders(responseEntity.getHeaders()));
            }
            if (responseBody != null && !responseBody.trim().isEmpty()) {
                log.info("[HTTP:{}] Response body preview: {}", nodeCode, previewText(responseBody));
            }
            result.put("status", "SUCCESS");
            result.put("nodeType", NodeType.HTTP.name());
            result.put("method", httpMethod.name());
            result.put("requestUrl", requestUrl);
            result.put("safeRequestUrl", safeRequestUrl);
            result.put("httpStatus", httpStatus);
            result.put("durationMs", durationMs);
            result.put("queryParamCount", queryParams.size());
            result.put("headerCount", headers.size());
            result.put("requestBodyBytes", requestBodyBytes);
            result.put("responseBytes", utf8Bytes(responseBody));
            result.put("response", responseBody);
            result.put("message", String.format("HTTP %s %s completed with %d in %d ms",
                    httpMethod.name(), safeRequestUrl, httpStatus, durationMs));
            return result;
        } catch (RestClientResponseException e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            String responseBody = e.getResponseBodyAsString();
            int httpStatus = e.getStatusCode().value();
            log.warn("[HTTP:{}] Request failed with httpStatus={}, statusText={}, durationMs={}, responseBytes={}, responsePreview={}",
                    nodeCode,
                    httpStatus,
                    e.getStatusText(),
                    durationMs,
                    utf8Bytes(responseBody),
                    previewText(responseBody));
            if (e.getResponseHeaders() != null && !e.getResponseHeaders().isEmpty()) {
                log.warn("[HTTP:{}] Error response headers: {}", nodeCode, sanitizeHeaders(e.getResponseHeaders()));
            }
            result.put("status", "FAILED");
            result.put("nodeType", NodeType.HTTP.name());
            result.put("method", httpMethod.name());
            result.put("requestUrl", requestUrl);
            result.put("safeRequestUrl", safeRequestUrl);
            result.put("httpStatus", httpStatus);
            result.put("durationMs", durationMs);
            result.put("queryParamCount", queryParams.size());
            result.put("headerCount", headers.size());
            result.put("requestBodyBytes", requestBodyBytes);
            result.put("responseBytes", utf8Bytes(responseBody));
            result.put("response", responseBody);
            result.put("error", e.getMessage());
            result.put("exceptionType", e.getClass().getName());
            result.put("message", String.format("HTTP %s %s failed with %d in %d ms: %s",
                    httpMethod.name(), safeRequestUrl, httpStatus, durationMs, e.getStatusText()));
            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            log.error("[HTTP:{}] Request failed before receiving a valid HTTP response, method={}, url={}, durationMs={}",
                    nodeCode, httpMethod.name(), safeRequestUrl, durationMs, e);
            result.put("status", "FAILED");
            result.put("nodeType", NodeType.HTTP.name());
            result.put("method", httpMethod.name());
            result.put("requestUrl", requestUrl);
            result.put("safeRequestUrl", safeRequestUrl);
            result.put("durationMs", durationMs);
            result.put("queryParamCount", queryParams.size());
            result.put("headerCount", headers.size());
            result.put("requestBodyBytes", requestBodyBytes);
            result.put("error", e.getMessage());
            result.put("exceptionType", e.getClass().getName());
            result.put("message", String.format("HTTP %s %s failed in %d ms: %s",
                    httpMethod.name(), safeRequestUrl, durationMs, e.getMessage()));
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeParameterMap(Object value, Map<String, Object> runtimeContext) {
        if (value == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = asString(entry.getKey());
                if (name != null && !name.trim().isEmpty()) {
                    result.put(name.trim(), resolveBody(entry.getValue(), runtimeContext));
                }
            }
            return result;
        }
        if (value instanceof List) {
            List<?> rows = (List<?>) value;
            for (Object row : rows) {
                if (!(row instanceof Map)) {
                    continue;
                }
                Map<String, Object> record = (Map<String, Object>) row;
                Object enabled = record.get("enabled");
                if (enabled != null && !Boolean.parseBoolean(String.valueOf(enabled))) {
                    continue;
                }
                String name = asString(firstNonNull(record.get("name"), record.get("key")));
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                result.put(name.trim(), resolveBody(record.get("value"), runtimeContext));
            }
        }
        return result;
    }

    private String appendQueryParams(String url, Map<String, Object> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return url;
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                builder.queryParam(entry.getKey(), "");
                continue;
            }
            if (value instanceof Iterable) {
                for (Object item : (Iterable<?>) value) {
                    builder.queryParam(entry.getKey(), item == null ? "" : String.valueOf(item));
                }
                continue;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int index = 0; index < length; index++) {
                    Object item = Array.get(value, index);
                    builder.queryParam(entry.getKey(), item == null ? "" : String.valueOf(item));
                }
                continue;
            }
            builder.queryParam(entry.getKey(), String.valueOf(value));
        }
        return builder.build().encode().toUriString();
    }

    @SuppressWarnings("unchecked")
    private Object resolveBody(Object value, Map<String, Object> runtimeContext) {
        if (value instanceof String) {
            return resolveRuntimePlaceholders((String) value, runtimeContext);
        }
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), resolveBody(entry.getValue(), runtimeContext));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(resolveBody(item, runtimeContext));
            }
            return result;
        }
        return value;
    }

    private String resolveRuntimePlaceholders(String value, Map<String, Object> runtimeContext) {
        if (value == null || runtimeContext == null || runtimeContext.isEmpty()) {
            return value;
        }
        Matcher matcher = RUNTIME_PLACEHOLDER.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            Object replacement = runtimeContext.get(matcher.group(1));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement == null ? matcher.group(0) : String.valueOf(replacement)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> sanitizeParameterMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), sanitizeValue(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private Map<String, Object> sanitizeHeaders(HttpHeaders headers) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (headers == null || headers.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (isSensitiveName(entry.getKey())) {
                result.put(entry.getKey(), MASKED_VALUE);
            } else {
                List<Object> values = new ArrayList<Object>();
                for (String value : entry.getValue()) {
                    values.add(previewText(value));
                }
                result.put(entry.getKey(), values);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(String name, Object value) {
        if (isSensitiveName(name)) {
            return MASKED_VALUE;
        }
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childName = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                result.put(childName, sanitizeValue(childName, entry.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(sanitizeValue(name, item));
            }
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<Object>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                result.add(sanitizeValue(name, Array.get(value, index)));
            }
            return result;
        }
        if (value instanceof String) {
            return previewText((String) value);
        }
        return value;
    }

    private boolean isSensitiveName(String name) {
        return name != null && SENSITIVE_NAME_PATTERN.matcher(name).matches();
    }

    private String sanitizeUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return url;
        }
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return previewText(url);
        }
        int fragmentStart = url.indexOf('#', queryStart);
        String prefix = url.substring(0, queryStart + 1);
        String query = fragmentStart < 0 ? url.substring(queryStart + 1) : url.substring(queryStart + 1, fragmentStart);
        String suffix = fragmentStart < 0 ? "" : url.substring(fragmentStart);
        return previewText(prefix + sanitizeRawQuery(query) + suffix);
    }

    private String sanitizeRawQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        String[] parts = query.split("&");
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) {
                builder.append('&');
            }
            String part = parts[index];
            int equalsIndex = part.indexOf('=');
            String name = equalsIndex < 0 ? part : part.substring(0, equalsIndex);
            if (isSensitiveName(name)) {
                builder.append(name).append('=').append(MASKED_VALUE);
            } else {
                builder.append(previewText(part));
            }
        }
        return builder.toString();
    }

    private String previewValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return previewText((String) value);
        }
        try {
            return previewText(JSON.toJSONString(sanitizeValue(null, value)));
        } catch (Exception e) {
            return previewText(String.valueOf(value));
        }
    }

    private String previewText(String text) {
        if (text == null) {
            return "";
        }
        String masked = maskSensitiveText(text);
        if (masked.length() <= MAX_LOG_PREVIEW_CHARS) {
            return masked;
        }
        return masked.substring(0, MAX_LOG_PREVIEW_CHARS) + "...(truncated, totalChars=" + masked.length() + ")";
    }

    private String maskSensitiveText(String text) {
        String masked = JSON_SECRET_PATTERN.matcher(text).replaceAll("$1" + MASKED_VALUE + "$3");
        return FORM_SECRET_PATTERN.matcher(masked).replaceAll("$1" + MASKED_VALUE);
    }

    private int utf8Bytes(String text) {
        if (text == null) {
            return 0;
        }
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private int valueBytes(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof String) {
            return utf8Bytes((String) value);
        }
        try {
            return utf8Bytes(JSON.toJSONString(value));
        } catch (Exception e) {
            return utf8Bytes(String.valueOf(value));
        }
    }

    private Map<String, Object> executeShell(Map<String, Object> config) {
        String command = String.valueOf(config.get("command"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
            process.waitFor();
            result.put("status", "SUCCESS");
            result.put("exitCode", process.exitValue());
            result.put("output", output.toString());
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to execute shell command", e);
        }
    }
}

