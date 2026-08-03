package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class RuntimeClusterSecurityValidator implements ApplicationRunner, Ordered {
    private static final Set<String> RUNTIME_APPLICATIONS = Set.of(
            "studio-server", "studio-worker", "studio-desktop-runtime", "studio-flink");
    private static final Set<String> WORKER_APPLICATIONS = Set.of(
            "studio-worker", "studio-desktop-runtime");
    private static final Set<String> NON_GATEWAY_APPLICATIONS = Set.of(
            "studio-worker", "studio-flink");
    private static final Set<String> CONTROL_PLANE_APPLICATIONS = Set.of(
            "studio-server", "studio-flink");
    private static final Set<String> DEFAULT_INTERNAL_TOKENS = Set.of(
            "studio-internal-token", "api_token");
    private static final Set<String> DEFAULT_ENCRYPTION_SECRETS = Set.of(
            "studio-secret-key", "secret-key");
    private static final Set<String> DEFAULT_GATEWAY_SECRETS = Set.of(
            "change-me", "studio-gateway-secret");
    private static final List<String> REQUIRED_PLUGIN_DIRECTORIES = List.of(
            "source", "reader", "writer", "transformer");

    private final StudioPlatformProperties properties;

    @Value("${spring.application.name:}")
    private String applicationName;

    public RuntimeClusterSecurityValidator(StudioPlatformProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        validateRuntimeSecurity();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    public void validateRuntimeSecurity() {
        String normalizedApplicationName = StringUtils.hasText(applicationName)
                ? applicationName.trim() : "";
        if (StringUtils.hasText(normalizedApplicationName)
                && !RUNTIME_APPLICATIONS.contains(normalizedApplicationName)) {
            return;
        }
        String token = properties.getInternalApiToken();
        if (!StringUtils.hasText(token) || DEFAULT_INTERNAL_TOKENS.contains(token.trim())) {
            throw new IllegalStateException(
                    "STUDIO_INTERNAL_API_TOKEN must be configured with a non-default secret");
        }
        String encryptionSecret = properties.getEncryptionSecret();
        if (!StringUtils.hasText(encryptionSecret)
                || DEFAULT_ENCRYPTION_SECRETS.contains(encryptionSecret.trim())) {
            throw new IllegalStateException(
                    "STUDIO_ENCRYPTION_SECRET must be configured with a non-default secret");
        }
        StudioPlatformProperties.GatewayProperties gateway = properties.getGateway();
        if (gateway != null && gateway.isTrustEnabled()
                && NON_GATEWAY_APPLICATIONS.contains(normalizedApplicationName)) {
            throw new IllegalStateException(
                    "STUDIO_GATEWAY_TRUST_ENABLED must be false for " + normalizedApplicationName);
        }
        if (gateway != null && gateway.isTrustEnabled()) {
            String sharedSecret = gateway.getSharedSecret();
            if (!StringUtils.hasText(sharedSecret)
                    || DEFAULT_GATEWAY_SECRETS.contains(sharedSecret.trim())) {
                throw new IllegalStateException(
                        "STUDIO_GATEWAY_SHARED_SECRET must be configured with a non-default secret when gateway trust is enabled");
            }
        }
        if (CONTROL_PLANE_APPLICATIONS.contains(normalizedApplicationName)) {
            validateControlPlaneBoundary(normalizedApplicationName);
        }
        if (WORKER_APPLICATIONS.contains(normalizedApplicationName)) {
            validateWorkerExecutionPlane(normalizedApplicationName);
        }
    }

    private void validateControlPlaneBoundary(String applicationName) {
        if (StringUtils.hasText(properties.getRuntimeClusterCode())) {
            throw new IllegalStateException(
                    "STUDIO_CLUSTER_CODE is Worker-only and must not be configured for " + applicationName);
        }
        if (StringUtils.hasText(properties.getAggregationHome())) {
            throw new IllegalStateException(
                    "STUDIO_AGGREGATION_HOME is Worker-only and must not be configured for " + applicationName);
        }
        if (StringUtils.hasText(System.getProperty("aggregation.home"))) {
            throw new IllegalStateException(
                    "JVM property aggregation.home is Worker-only and must not be configured for " + applicationName);
        }
    }

    private void validateWorkerExecutionPlane(String applicationName) {
        if (!StringUtils.hasText(properties.getRuntimeClusterCode())) {
            throw new IllegalStateException(
                    "STUDIO_CLUSTER_CODE must be configured explicitly for every Worker runtime");
        }
        String configuredHome = properties.getAggregationHome();
        if (!StringUtils.hasText(configuredHome)) {
            throw new IllegalStateException(
                    "STUDIO_AGGREGATION_HOME must be configured explicitly for every Worker runtime");
        }
        final Path aggregationHome;
        try {
            aggregationHome = Path.of(configuredHome.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalStateException("STUDIO_AGGREGATION_HOME is not a valid path", ex);
        }
        StudioPlatformProperties.PluginRuntimeProperties pluginRuntime = properties.getPluginRuntime();
        String mode = pluginRuntime == null || !StringUtils.hasText(pluginRuntime.getMode())
                ? "EAGER_LOCAL" : pluginRuntime.getMode().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("EAGER_LOCAL", "LAZY_OBJECT_STORAGE").contains(mode)) {
            throw new IllegalStateException(
                    "STUDIO_PLUGIN_RUNTIME_MODE must be EAGER_LOCAL or LAZY_OBJECT_STORAGE");
        }
        if ("LAZY_OBJECT_STORAGE".equals(mode)) {
            if (!"studio-worker".equals(applicationName)) {
                throw new IllegalStateException("LAZY_OBJECT_STORAGE is supported only by studio-worker");
            }
            validateLazyPluginRuntime(aggregationHome, pluginRuntime);
            return;
        }
        validateEagerPluginRuntime(aggregationHome);
    }

    private void validateEagerPluginRuntime(Path aggregationHome) {
        if (!Files.isDirectory(aggregationHome)) {
            throw new IllegalStateException(
                    "STUDIO_AGGREGATION_HOME must point to an existing directory: " + aggregationHome);
        }
        Path coreConfig = aggregationHome.resolve("conf").resolve("core.json");
        if (!Files.isRegularFile(coreConfig)) {
            throw new IllegalStateException(
                    "Worker execution home is incomplete; missing conf/core.json under " + aggregationHome);
        }
        Path pluginHome = aggregationHome.resolve("plugin");
        for (String pluginType : REQUIRED_PLUGIN_DIRECTORIES) {
            if (!Files.isDirectory(pluginHome.resolve(pluginType))) {
                throw new IllegalStateException(
                        "Worker execution home is incomplete; missing plugin/" + pluginType
                                + " under " + aggregationHome);
            }
        }
    }

    private void validateLazyPluginRuntime(Path aggregationHome,
                                           StudioPlatformProperties.PluginRuntimeProperties pluginRuntime) {
        Path cacheRoot = aggregationHome.resolve("cache").toAbsolutePath().normalize();
        Path probe = null;
        try {
            Files.createDirectories(cacheRoot);
            if (!Files.isDirectory(cacheRoot) || !Files.isWritable(cacheRoot)) {
                throw new IllegalStateException("Worker plugin cache root is not writable: " + cacheRoot);
            }
            probe = Files.createTempFile(cacheRoot, ".studio-plugin-write-probe-", ".tmp");
        } catch (IOException | SecurityException ex) {
            throw new IllegalStateException("Worker plugin cache root cannot be created or written: " + cacheRoot, ex);
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                    // Best-effort cleanup; inability to delete does not invalidate the successful write probe.
                }
            }
        }
        if (pluginRuntime == null || !StringUtils.hasText(pluginRuntime.getPrefix())
                || !StringUtils.hasText(pluginRuntime.getChannel())) {
            throw new IllegalStateException(
                    "STUDIO_PLUGIN_PREFIX and STUDIO_PLUGIN_CHANNEL are required for LAZY_OBJECT_STORAGE");
        }
        if (!StringUtils.hasText(properties.getRuntimeVersion())) {
            throw new IllegalStateException(
                    "STUDIO_RUNTIME_VERSION is required for LAZY_OBJECT_STORAGE compatibility checks");
        }
        StudioPlatformProperties.ObjectStorageProperties storage = effectiveObjectStorage();
        if (storage == null || !StringUtils.hasText(storage.getProvider())
                || !StringUtils.hasText(storage.getEndpoint())
                || !StringUtils.hasText(storage.getAccessKey())
                || !StringUtils.hasText(storage.getSecretKey())) {
            throw new IllegalStateException(
                    "Object storage provider, endpoint, access key and secret key are required for LAZY_OBJECT_STORAGE");
        }
        String provider = storage.getProvider().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("ALIYUN".equals(provider) || "ALIYUN_OSS".equals(provider)) {
            provider = "OSS";
        }
        if (!Set.of("MINIO", "OSS").contains(provider)) {
            throw new IllegalStateException("Object storage provider must be MINIO or OSS");
        }
        if (!StringUtils.hasText(pluginRuntime.getBucket()) && !StringUtils.hasText(storage.getBucket())) {
            throw new IllegalStateException(
                    "STUDIO_PLUGIN_BUCKET or STUDIO_OBJECT_BUCKET is required for LAZY_OBJECT_STORAGE");
        }
    }

    private StudioPlatformProperties.ObjectStorageProperties effectiveObjectStorage() {
        StudioPlatformProperties.ObjectStorageProperties storage = properties.getObjectStorage();
        if (storage != null && (StringUtils.hasText(storage.getEndpoint())
                || StringUtils.hasText(storage.getAccessKey())
                || StringUtils.hasText(storage.getSecretKey())
                || StringUtils.hasText(storage.getBucket()))) {
            return storage;
        }
        StudioPlatformProperties.RunLogProperties runLog = properties.getRunLog();
        return runLog == null ? storage : runLog.getObjectStorage();
    }
}
