package com.jdragon.studio.nacos.compat.support;

import com.jdragon.studio.nacos.compat.http.NacosHttpClient;
import com.jdragon.studio.nacos.compat.http.NacosHttpResponse;
import com.jdragon.studio.nacos.compat.model.NacosCompatMode;
import com.jdragon.studio.nacos.compat.model.NacosServerGeneration;
import com.jdragon.studio.nacos.compat.model.NacosServerInfo;
import com.jdragon.studio.nacos.compat.props.NacosCompatProperties;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class NacosServerProbeService {

    public static final String MODERN_SERVER_STATE_PATH = "/nacos/v3/admin/core/state";

    public static final String MODERN_CONSOLE_STATE_PATH = "/v3/console/server/state";

    public static final String LEGACY_STATE_PATH = "/nacos/v1/console/server/state";

    private final NacosHttpClient httpClient;

    private final NacosCompatProperties compatProperties;

    private final Map<String, NacosServerInfo> cache = new ConcurrentHashMap<>();

    public NacosServerProbeService(NacosHttpClient httpClient, NacosCompatProperties compatProperties) {
        this.httpClient = httpClient;
        this.compatProperties = compatProperties;
    }

    public NacosServerInfo probe(String serverAddr) {
        return this.cache.computeIfAbsent(serverAddr, this::doProbe);
    }

    private NacosServerInfo doProbe(String serverAddr) {
        Duration timeout = this.compatProperties.getProbeTimeout();
        if (this.compatProperties.getMode() == NacosCompatMode.LEGACY) {
            return probeLegacy(serverAddr, timeout);
        }
        if (this.compatProperties.getMode() == NacosCompatMode.MODERN) {
            return probeModern(serverAddr, timeout);
        }
        Exception modernFailure = null;
        try {
            NacosServerInfo modernInfo = probeModern(serverAddr, timeout);
            if (modernInfo != null) {
                return modernInfo;
            }
        }
        catch (Exception ex) {
            modernFailure = ex;
        }
        Exception legacyFailure = null;
        try {
            NacosServerInfo legacyInfo = probeLegacy(serverAddr, timeout);
            if (legacyInfo != null) {
                logAutoFallback(serverAddr, modernFailure, legacyInfo);
                return legacyInfo;
            }
        }
        catch (Exception ex) {
            legacyFailure = ex;
        }
        if (modernFailure != null) {
            logProbe("modern probe failed", serverAddr, modernFailure);
        }
        if (legacyFailure != null) {
            logProbe("legacy probe failed", serverAddr, legacyFailure);
        }
        throw new IllegalStateException("Unable to detect nacos server generation for " + serverAddr);
    }

    private NacosServerInfo probeModern(String serverAddr, Duration timeout) {
        IllegalStateException lastError = null;
        for (String statePath : new String[] { MODERN_SERVER_STATE_PATH, MODERN_CONSOLE_STATE_PATH }) {
            try {
                NacosHttpResponse response = this.httpClient.get(serverAddr, statePath, Map.of(), Map.of(), timeout);
                if (!response.is2xxSuccessful()) {
                    throw new IllegalStateException("Probe modern state endpoint failed, status=" + response.statusCode());
                }
                String version = NacosJsonSupport.findText(NacosJsonSupport.readTree(response.body()), "version");
                if (version == null || version.isBlank()) {
                    version = "3.0.0";
                }
                return new NacosServerInfo(NacosServerGeneration.MODERN, version, statePath,
                        NacosUrlSupport.normalizeServerAddress(serverAddr));
            }
            catch (IllegalStateException ex) {
                lastError = ex;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("Probe modern state endpoint failed");
    }

    private NacosServerInfo probeLegacy(String serverAddr, Duration timeout) {
        NacosHttpResponse response = this.httpClient.get(serverAddr, LEGACY_STATE_PATH, Map.of(), Map.of(), timeout);
        if (!response.is2xxSuccessful()) {
            throw new IllegalStateException("Probe legacy state endpoint failed, status=" + response.statusCode());
        }
        String version = NacosJsonSupport.findText(NacosJsonSupport.readTree(response.body()), "version");
        if (version == null || version.isBlank()) {
            version = "1.3.2";
        }
        NacosServerGeneration generation = NacosVersionSupport.isModern(version)
                ? NacosServerGeneration.MODERN : NacosServerGeneration.LEGACY;
        return new NacosServerInfo(generation, version, LEGACY_STATE_PATH,
                NacosUrlSupport.normalizeServerAddress(serverAddr));
    }

    private void logProbe(String message, String serverAddr, Exception ex) {
        if (!this.compatProperties.isLogProbeDetail()) {
            return;
        }
        log.warn("{} server={}, error={}", message, serverAddr, ex.getMessage());
    }

    private void logAutoFallback(String serverAddr, Exception modernFailure, NacosServerInfo serverInfo) {
        if (!this.compatProperties.isLogProbeDetail() || modernFailure == null) {
            return;
        }
        log.info("modern state endpoint unavailable, auto fallback resolved server={} as {} via {} version={}, reason={}",
                serverAddr, serverInfo.generation().name(), serverInfo.stateEndpoint(), serverInfo.version(),
                modernFailure.getMessage());
    }

    public Map<String, Object> describe(String serverAddr) {
        NacosServerInfo serverInfo = probe(serverAddr);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverAddr", serverInfo.baseServerAddress());
        result.put("generation", serverInfo.generation().name());
        result.put("version", serverInfo.version());
        result.put("stateEndpoint", serverInfo.stateEndpoint());
        return result;
    }

}
