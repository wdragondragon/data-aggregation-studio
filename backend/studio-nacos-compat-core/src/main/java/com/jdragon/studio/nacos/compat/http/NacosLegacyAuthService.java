package com.jdragon.studio.nacos.compat.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.nacos.compat.support.NacosJsonSupport;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NacosLegacyAuthService {

    private final NacosHttpClient httpClient;

    private final Duration requestTimeout;

    private final Map<String, AccessTokenHolder> tokenCache = new ConcurrentHashMap<>();

    public NacosLegacyAuthService(NacosHttpClient httpClient, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    public String getAccessToken(String serverAddr, String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return null;
        }
        String cacheKey = serverAddr + "|" + username;
        AccessTokenHolder cached = this.tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(30))) {
            return cached.accessToken();
        }
        NacosHttpResponse response = this.httpClient.postForm(serverAddr, "/nacos/v1/auth/login", Map.of(),
                Map.of("username", username, "password", password), Map.of(), this.requestTimeout);
        if (!response.is2xxSuccessful()) {
            throw new IllegalStateException("Nacos auth login failed, status=" + response.statusCode() + ", server=" + serverAddr);
        }
        JsonNode jsonNode = NacosJsonSupport.readTree(response.body());
        String accessToken = NacosJsonSupport.findText(jsonNode, "accessToken");
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        String ttlText = NacosJsonSupport.findText(jsonNode, "tokenTtl");
        long ttlSeconds = 18000L;
        if (ttlText != null && !ttlText.isBlank()) {
            try {
                ttlSeconds = Long.parseLong(ttlText);
            }
            catch (NumberFormatException ignored) {
            }
        }
        this.tokenCache.put(cacheKey, new AccessTokenHolder(accessToken, Instant.now().plusSeconds(ttlSeconds)));
        return accessToken;
    }

    private record AccessTokenHolder(String accessToken, Instant expiresAt) {
    }

}
