package com.jdragon.studio.infra.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Filters transport headers that must not cross a managed runtime endpoint. */
@Component
public class RuntimeEndpointHeaderPolicy {

    private static final Set<String> FIXED_BLOCKED_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length");

    public Map<String, String> sanitizeConfiguredHeaders(Map<String, String> headers,
                                                         Set<String> additionalExcludedHeaders) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> connectionValues = new ArrayList<String>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("connection".equals(normalize(entry.getKey())) && entry.getValue() != null) {
                connectionValues.add(entry.getValue());
            }
        }
        Set<String> connectionHeaderNames = connectionHeaderNames(connectionValues);
        Set<String> excludedHeaders = normalizeNames(additionalExcludedHeaders);
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String normalized = normalize(entry.getKey());
            if (entry.getValue() != null
                    && StringUtils.hasText(normalized)
                    && !isHopByHop(entry.getKey(), connectionHeaderNames)
                    && !isReservedStudioHeader(entry.getKey())
                    && !excludedHeaders.contains(normalized)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public Set<String> connectionHeaderNames(Collection<String> connectionHeaderValues) {
        Set<String> result = new LinkedHashSet<String>();
        if (connectionHeaderValues == null) {
            return result;
        }
        for (String value : connectionHeaderValues) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            for (String token : value.split(",")) {
                String normalized = normalize(token);
                if (!normalized.isEmpty()) {
                    result.add(normalized);
                }
            }
        }
        return result;
    }

    public boolean isHopByHop(String headerName, Set<String> connectionHeaderNames) {
        String normalized = normalize(headerName);
        return FIXED_BLOCKED_HEADERS.contains(normalized)
                || connectionHeaderNames != null && connectionHeaderNames.contains(normalized);
    }

    public boolean isReservedStudioHeader(String headerName) {
        String normalized = normalize(headerName);
        return normalized.equals("x-studio") || normalized.startsWith("x-studio-");
    }

    private Set<String> normalizeNames(Collection<String> values) {
        Set<String> result = new LinkedHashSet<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
