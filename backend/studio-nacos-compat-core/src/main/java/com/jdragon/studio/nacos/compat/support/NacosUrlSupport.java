package com.jdragon.studio.nacos.compat.support;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class NacosUrlSupport {

    private NacosUrlSupport() {
    }

    public static String normalizeServerAddress(String serverAddr) {
        return normalizeServerAddresses(serverAddr).get(0);
    }

    public static List<String> normalizeServerAddresses(String serverAddr) {
        if (serverAddr == null || serverAddr.isBlank()) {
            throw new IllegalArgumentException("spring.cloud.nacos.*.server-addr is blank");
        }
        String[] parts = serverAddr.split(",");
        List<String> result = new ArrayList<>(parts.length);
        String inheritedScheme = defaultScheme(parts);
        for (String part : parts) {
            String normalized = normalizeSingleServerAddress(part, inheritedScheme);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("spring.cloud.nacos.*.server-addr is blank");
        }
        return List.copyOf(result);
    }

    public static URI build(String serverAddr, String path, Map<String, String> queryParams) {
        return buildAll(serverAddr, path, queryParams).get(0);
    }

    public static List<URI> buildAll(String serverAddr, String path, Map<String, String> queryParams) {
        List<String> bases = normalizeServerAddresses(serverAddr);
        List<URI> uris = new ArrayList<>(bases.size());
        for (String base : bases) {
            uris.add(buildSingle(base, path, queryParams));
        }
        return List.copyOf(uris);
    }

    private static URI buildSingle(String base, String path, Map<String, String> queryParams) {
        StringBuilder builder = new StringBuilder(base);
        if (!path.startsWith("/")) {
            builder.append('/');
        }
        builder.append(path);
        if (queryParams != null && !queryParams.isEmpty()) {
            StringJoiner joiner = new StringJoiner("&");
            queryParams.forEach((key, value) -> {
                if (value != null) {
                    joiner.add(urlEncode(key) + "=" + urlEncode(value));
                }
            });
            String query = joiner.toString();
            if (!query.isEmpty()) {
                builder.append('?').append(query);
            }
        }
        return URI.create(builder.toString());
    }

    private static String defaultScheme(String[] parts) {
        for (String part : parts) {
            String trimmed = part == null ? "" : part.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.regionMatches(true, 0, "https://", 0, "https://".length())) {
                return "https://";
            }
            if (trimmed.regionMatches(true, 0, "http://", 0, "http://".length())) {
                return "http://";
            }
            return "http://";
        }
        return "http://";
    }

    private static String normalizeSingleServerAddress(String serverAddr, String defaultScheme) {
        String normalized = serverAddr == null ? "" : serverAddr.trim();
        if (normalized.isBlank()) {
            return "";
        }
        if (!normalized.regionMatches(true, 0, "http://", 0, "http://".length())
                && !normalized.regionMatches(true, 0, "https://", 0, "https://".length())) {
            normalized = defaultScheme + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

}
