package com.jdragon.studio.nacos.compat.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.nacos.compat.http.NacosHttpClient;
import com.jdragon.studio.nacos.compat.http.NacosHttpResponse;
import com.jdragon.studio.nacos.compat.http.NacosLegacyAuthService;
import com.jdragon.studio.nacos.compat.model.NacosCompatMode;
import com.jdragon.studio.nacos.compat.model.NacosServerGeneration;
import com.jdragon.studio.nacos.compat.model.NacosServerInfo;
import com.jdragon.studio.nacos.compat.props.NacosCompatProperties;
import com.jdragon.studio.nacos.compat.props.NacosConfigProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import com.jdragon.studio.nacos.compat.support.NacosClientManager;
import com.jdragon.studio.nacos.compat.support.NacosJsonSupport;
import com.jdragon.studio.nacos.compat.support.NacosMd5Support;
import com.jdragon.studio.nacos.compat.support.NacosServerProbeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class NacosConfigAccessor {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigAccessor.class);

    private final NacosHttpClient httpClient;

    private final NacosLegacyAuthService legacyAuthService;

    private final NacosServerProbeService probeService;

    private final NacosClientManager clientManager;

    public NacosConfigAccessor(NacosHttpClient httpClient, NacosLegacyAuthService legacyAuthService,
            NacosServerProbeService probeService, NacosClientManager clientManager) {
        this.httpClient = httpClient;
        this.legacyAuthService = legacyAuthService;
        this.probeService = probeService;
        this.clientManager = clientManager;
    }

    public String loadConfig(NacosConfigDataResource resource) {
        NacosServerInfo serverInfo = resolveServerInfo(resource);
        if (serverInfo.generation() == NacosServerGeneration.MODERN) {
            return loadModernConfig(resource);
        }
        return loadLegacyConfig(resource);
    }

    public Closeable watchConfig(NacosConfigDataResource resource, Consumer<String> changeConsumer) {
        NacosServerInfo serverInfo = resolveServerInfo(resource);
        if (!resource.isRefreshEnabled()) {
            return () -> { };
        }
        if (serverInfo.generation() == NacosServerGeneration.MODERN) {
            return watchModern(resource, changeConsumer);
        }
        return watchLegacy(resource, changeConsumer);
    }

    private NacosServerInfo resolveServerInfo(NacosConfigDataResource resource) {
        if (resource.getCompatMode() == NacosCompatMode.LEGACY) {
            return new NacosServerInfo(NacosServerGeneration.LEGACY, "1.3.2", NacosServerProbeService.LEGACY_STATE_PATH,
                    resource.getServerAddr());
        }
        if (resource.getCompatMode() == NacosCompatMode.MODERN) {
            return new NacosServerInfo(NacosServerGeneration.MODERN, "3.0.3",
                    NacosServerProbeService.MODERN_SERVER_STATE_PATH,
                    resource.getServerAddr());
        }
        return this.probeService.probe(resource.getServerAddr());
    }

    private String loadModernConfig(NacosConfigDataResource resource) {
        NacosRootProperties rootProperties = new NacosRootProperties();
        rootProperties.setUsername(resource.getUsername());
        rootProperties.setPassword(resource.getPassword());
        NacosConfigProperties configProperties = new NacosConfigProperties();
        configProperties.setServerAddr(resource.getServerAddr());
        configProperties.setNamespace(resource.getNamespace());
        configProperties.setAccessKey(resource.getAccessKey());
        configProperties.setSecretKey(resource.getSecretKey());
        try {
            ConfigService configService = this.clientManager.getConfigService(rootProperties, configProperties);
            return Objects.requireNonNullElse(configService.getConfig(resource.getDataId(), resource.getGroup(),
                    resource.getConfigReadTimeout().toMillis()), "");
        }
        catch (NacosException ex) {
            throw new IllegalStateException("Load nacos config failed for " + resource, ex);
        }
    }

    private String loadLegacyConfig(NacosConfigDataResource resource) {
        Map<String, String> query = createLegacyConfigQuery(resource);
        Map<String, String> headers = Map.of();
        NacosHttpResponse response = this.httpClient.get(resource.getServerAddr(), "/nacos/v1/cs/configs", query, headers,
                resource.getConfigReadTimeout());
        if (!response.is2xxSuccessful()) {
            if (resource.isOptionalResource() && response.statusCode() == 404) {
                return "";
            }
            throw new IllegalStateException("Load legacy nacos config failed, status=" + response.statusCode() + ", resource=" + resource);
        }
        return Objects.requireNonNullElse(response.body(), "");
    }

    private Closeable watchModern(NacosConfigDataResource resource, Consumer<String> changeConsumer) {
        NacosRootProperties rootProperties = new NacosRootProperties();
        rootProperties.setUsername(resource.getUsername());
        rootProperties.setPassword(resource.getPassword());
        NacosConfigProperties configProperties = new NacosConfigProperties();
        configProperties.setServerAddr(resource.getServerAddr());
        configProperties.setNamespace(resource.getNamespace());
        configProperties.setAccessKey(resource.getAccessKey());
        configProperties.setSecretKey(resource.getSecretKey());
        try {
            ConfigService configService = this.clientManager.getConfigService(rootProperties, configProperties);
            Listener listener = new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    changeConsumer.accept(configInfo);
                }
            };
            configService.addListener(resource.getDataId(), resource.getGroup(), listener);
            return () -> configService.removeListener(resource.getDataId(), resource.getGroup(), listener);
        }
        catch (NacosException ex) {
            throw new IllegalStateException("Watch modern nacos config failed for " + resource, ex);
        }
    }

    private Closeable watchLegacy(NacosConfigDataResource resource, Consumer<String> changeConsumer) {
        ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "nacos-legacy-config-watch-" + resource.getDataId());
            thread.setDaemon(true);
            return thread;
        });
        LegacyWatcher watcher = new LegacyWatcher(resource, changeConsumer, executorService);
        executorService.submit(watcher);
        return watcher;
    }

    private Map<String, String> createLegacyConfigQuery(NacosConfigDataResource resource) {
        Map<String, String> query = new HashMap<>();
        query.put("dataId", resource.getDataId());
        query.put("group", resource.getGroup());
        if (StringUtils.hasText(resource.getNamespace())) {
            query.put("tenant", resource.getNamespace());
            query.put("namespaceId", resource.getNamespace());
        }
        String accessToken = this.legacyAuthService.getAccessToken(resource.getServerAddr(), resource.getUsername(),
                resource.getPassword());
        if (StringUtils.hasText(accessToken)) {
            query.put("accessToken", accessToken);
        }
        return query;
    }

    private final class LegacyWatcher implements Runnable, Closeable {

        private final NacosConfigDataResource resource;

        private final Consumer<String> changeConsumer;

        private final ExecutorService executorService;

        private volatile boolean running = true;

        private volatile String currentMd5;

        private LegacyWatcher(NacosConfigDataResource resource, Consumer<String> changeConsumer,
                ExecutorService executorService) {
            this.resource = resource;
            this.changeConsumer = changeConsumer;
            this.executorService = executorService;
            this.currentMd5 = NacosMd5Support.md5(loadLegacyConfig(resource));
        }

        @Override
        public void run() {
            while (running) {
                try {
                    NacosHttpResponse response = httpClient.postForm(resource.getServerAddr(), "/nacos/v1/cs/configs/listener",
                            Map.of(), createLegacyListenForm(resource, currentMd5), createLegacyListenHeaders(resource),
                            resource.getLegacyLongPollingTimeout().plusSeconds(5));
                    if (!response.is2xxSuccessful()) {
                        log.warn("Legacy nacos listener failed, status={}, resource={}", response.statusCode(), resource);
                        sleepBackoff();
                        continue;
                    }
                    String changed = Objects.requireNonNullElse(response.body(), "");
                    if (!changed.isBlank()) {
                        String latest = loadLegacyConfig(resource);
                        String latestMd5 = NacosMd5Support.md5(latest);
                        if (!latestMd5.equals(this.currentMd5)) {
                            this.currentMd5 = latestMd5;
                            changeConsumer.accept(latest);
                        }
                    }
                }
                catch (Exception ex) {
                    log.warn("Legacy nacos listener error for {}: {}", resource, ex.getMessage());
                    sleepBackoff();
                }
            }
        }

        @Override
        public void close() throws IOException {
            this.running = false;
            this.executorService.shutdownNow();
            try {
                this.executorService.awaitTermination(3, TimeUnit.SECONDS);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private void sleepBackoff() {
            try {
                Thread.sleep(1500L);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Map<String, String> createLegacyListenHeaders(NacosConfigDataResource resource) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Long-Pulling-Timeout", String.valueOf(resource.getLegacyLongPollingTimeout().toMillis()));
        return headers;
    }

    private Map<String, String> createLegacyListenForm(NacosConfigDataResource resource, String md5) {
        Map<String, String> form = new HashMap<>();
        String accessToken = this.legacyAuthService.getAccessToken(resource.getServerAddr(), resource.getUsername(),
                resource.getPassword());
        if (StringUtils.hasText(accessToken)) {
            form.put("accessToken", accessToken);
        }
        form.put("Listening-Configs", buildListeningConfigs(resource, md5));
        return form;
    }

    private String buildListeningConfigs(NacosConfigDataResource resource, String md5) {
        StringBuilder builder = new StringBuilder();
        builder.append(resource.getDataId()).append((char) 2)
                .append(resource.getGroup()).append((char) 2)
                .append(md5 == null ? "" : md5).append((char) 2);
        if (StringUtils.hasText(resource.getNamespace())) {
            builder.append(resource.getNamespace());
        }
        builder.append((char) 1);
        return builder.toString();
    }

}
