package com.jdragon.studio.nacos.compat.support;

import java.net.URI;
import java.util.Map;
import java.util.StringJoiner;

public final class NacosUrlSupport {

    private NacosUrlSupport() {
    }

    public static String normalizeServerAddress(String serverAddr) {
        if (serverAddr == null || serverAddr.isBlank()) {
            throw new IllegalArgumentException("spring.cloud.nacos.*.server-addr is blank");
        }
        String normalized = serverAddr.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static URI build(String serverAddr, String path, Map<String, String> queryParams) {
        String base = normalizeServerAddress(serverAddr);
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

    public static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

}
