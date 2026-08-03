package com.jdragon.studio.worker.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.core.plugin.PluginType;
import com.jdragon.aggregation.pluginloader.JarLoaderCenter;
import com.jdragon.aggregation.pluginloader.LoadUtil;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.jdragon.aggregation.pluginloader.spi.AbstractPlugin;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.CloudObjectStorageService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in acceptance test for the real Aliyun OSS repository. It never logs credentials.
 * The phased workflow lets the IDEA Worker restart between hot update and rollback checks.
 */
class AliyunOssPluginRuntimeAcceptanceTest {
    private static final String ENABLE_ENV = "STUDIO_OSS_PLUGIN_ACCEPTANCE";
    private static final String PHASE_ENV = "STUDIO_OSS_PLUGIN_ACCEPTANCE_PHASE";
    private static final String CONFIG_ENV = "STUDIO_OSS_PLUGIN_ACCEPTANCE_CONFIG";
    private static final String RUNTIME_HOME_ENV = "STUDIO_OSS_PLUGIN_ACCEPTANCE_HOME";
    private static final String REPOSITORY_V1_ENV = "STUDIO_OSS_PLUGIN_ACCEPTANCE_REPOSITORY_V1";
    private static final String REPOSITORY_V2_ENV = "STUDIO_OSS_PLUGIN_ACCEPTANCE_REPOSITORY_V2";
    private static final String RECOVERY_ENV = "STUDIO_OSS_PLUGIN_ACCEPTANCE_RECOVERY";

    private static final String TYPE = "reader";
    private static final String NAME = "codexruntimeprobe";
    private static final String RELEASE_V1 = "codex-20260731-ossruntime2-v1";
    private static final String RELEASE_V2 = "codex-20260731-ossruntime2-v2";
    private static final String RUNTIME_VERSION = "1.0_jdk17-SNAPSHOT";
    private static final String PREFIX = "aggregation-plugins";
    private static final String CHANNEL = "production";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void exerciseRealAliyunOssPluginRuntimeByPhase() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv(ENABLE_ENV)),
                "real Aliyun OSS acceptance is opt-in");

        AcceptanceContext context = acceptanceContext();
        String phase = requiredEnv(PHASE_ENV).trim().toUpperCase(Locale.ROOT);
        switch (phase) {
            case "HOT_UPDATE" -> hotUpdate(context);
            case "OFFLINE_CACHE" -> offlineCache(context);
            case "ROLLBACK" -> rollback(context);
            case "CLEANUP" -> cleanup(context);
            default -> throw new IllegalArgumentException("Unsupported acceptance phase: " + phase);
        }
    }

    private void hotUpdate(AcceptanceContext context) throws Exception {
        assertEmptyCache(context.runtimeHome);
        CloudObjectStorageService storage = storage(context, context.endpoint);
        backupOriginalPointer(context, storage);
        uploadImmutableRelease(context, storage, RELEASE_V1, context.repositoryV1);
        uploadImmutableRelease(context, storage, RELEASE_V2, context.repositoryV2);
        putPointer(context, storage, context.repositoryV1);

        ObjectStoragePluginRuntimeResolver resolver = resolver(context, context.endpoint);
        resolver.initialize();
        PluginRuntimeSession sessionA = PluginRuntimeSession.open();
        try {
            AbstractPlugin pluginA = LoadUtil.loadJobPlugin(PluginType.READER, NAME);
            ResolvedPlugin resolvedA = sessionA.resolve(PluginType.READER, NAME);
            assertEquals("codex-20260731-v1", revision(pluginA));
            assertEquals(identity(context.repositoryV1), resolvedA.getIdentity());

            putPointer(context, storage, context.repositoryV2);
            ResolvedPlugin resolvedV2 = awaitIdentity(resolver, identity(context.repositoryV2), Duration.ofSeconds(20));

            try (PluginRuntimeSession sessionB = PluginRuntimeSession.open()) {
                AbstractPlugin pluginB = LoadUtil.loadJobPlugin(PluginType.READER, NAME);
                assertEquals("codex-20260731-v2", revision(pluginB));
                assertNotSame(pluginA.getClass().getClassLoader(), pluginB.getClass().getClassLoader());
                assertEquals(resolvedV2.getIdentity(), sessionB.resolve(PluginType.READER, NAME).getIdentity());
                assertEquals("codex-20260731-v1", revision(pluginA));
                assertTrue(JarLoaderCenter.isDirectoryInUse(resolvedA.getDirectory()));
            }
            assertTrue(JarLoaderCenter.isDirectoryInUse(resolvedA.getDirectory()));
        } finally {
            sessionA.close();
            resolver.shutdown();
        }
        assertStateIdentity(context, identity(context.repositoryV2));
        writeResult(context, "HOT_UPDATE", RELEASE_V2, "PASS");
    }

    private void offlineCache(AcceptanceContext context) throws Exception {
        String unreachableEndpoint = "http://127.0.0.1:1";
        ObjectStoragePluginRuntimeResolver resolver = resolver(context, unreachableEndpoint);
        resolver.initialize();
        try (PluginRuntimeSession session = PluginRuntimeSession.open()) {
            AbstractPlugin cached = LoadUtil.loadJobPlugin(PluginType.READER, NAME);
            assertEquals("codex-20260731-v2", revision(cached));
            assertEquals(identity(context.repositoryV2), session.resolve(PluginType.READER, NAME).getIdentity());
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(PluginType.READER, NAME + "-uncached"));
        assertTrue(failure.getMessage().contains("cold load"));
        awaitStatus(resolver, "DEGRADED", Duration.ofSeconds(5));
        resolver.shutdown();
        assertStateIdentity(context, identity(context.repositoryV2));
        writeResult(context, "OFFLINE_CACHE", RELEASE_V2, "PASS");
    }

    private void rollback(AcceptanceContext context) throws Exception {
        CloudObjectStorageService storage = storage(context, context.endpoint);
        ObjectStoragePluginRuntimeResolver resolver = resolver(context, context.endpoint);
        resolver.initialize();
        PluginRuntimeSession sessionA = PluginRuntimeSession.open();
        try {
            AbstractPlugin pluginA = LoadUtil.loadJobPlugin(PluginType.READER, NAME);
            assertEquals("codex-20260731-v2", revision(pluginA));

            putPointer(context, storage, context.repositoryV1);
            awaitIdentity(resolver, identity(context.repositoryV1), Duration.ofSeconds(20));
            try (PluginRuntimeSession sessionB = PluginRuntimeSession.open()) {
                AbstractPlugin pluginB = LoadUtil.loadJobPlugin(PluginType.READER, NAME);
                assertEquals("codex-20260731-v1", revision(pluginB));
                assertNotSame(pluginA.getClass().getClassLoader(), pluginB.getClass().getClassLoader());
                assertEquals("codex-20260731-v2", revision(pluginA));
            }
        } finally {
            sessionA.close();
            resolver.shutdown();
        }
        assertStateIdentity(context, identity(context.repositoryV1));
        writeResult(context, "ROLLBACK", RELEASE_V1, "PASS");
    }

    private void cleanup(AcceptanceContext context) throws Exception {
        CloudObjectStorageService storage = storage(context, context.endpoint);
        Path marker = context.recoveryDirectory.resolve("original-pointer-present.txt");
        if (!Files.isRegularFile(marker)) {
            throw new IllegalStateException("Original pointer backup marker is missing");
        }
        boolean originallyPresent = Boolean.parseBoolean(Files.readString(marker, StandardCharsets.UTF_8).trim());
        if (originallyPresent) {
            Path backup = context.recoveryDirectory.resolve("current-original.json");
            storage.put(context.bucket, pointerKey(), Files.readAllBytes(backup), "application/json");
        } else {
            storage.delete(context.bucket, pointerKey());
        }
        writeResult(context, "CLEANUP", originallyPresent ? "ORIGINAL" : "ABSENT", "PASS");
    }

    private ObjectStoragePluginRuntimeResolver resolver(AcceptanceContext context, String endpoint) {
        StudioPlatformProperties properties = properties(context, endpoint);
        return new ObjectStoragePluginRuntimeResolver(properties,
                new CloudObjectStorageService(properties), objectMapper);
    }

    private CloudObjectStorageService storage(AcceptanceContext context, String endpoint) {
        return new CloudObjectStorageService(properties(context, endpoint));
    }

    private StudioPlatformProperties properties(AcceptanceContext context, String endpoint) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setAggregationHome(context.runtimeHome.toString());
        properties.setRuntimeVersion(RUNTIME_VERSION);
        properties.getObjectStorage().setProvider("oss");
        properties.getObjectStorage().setEndpoint(endpoint);
        properties.getObjectStorage().setAccessKey(context.accessKey);
        properties.getObjectStorage().setSecretKey(context.secretKey);
        properties.getObjectStorage().setBucket(context.bucket);
        properties.getObjectStorage().setCreateBucket(false);
        properties.getPluginRuntime().setMode("LAZY_OBJECT_STORAGE");
        properties.getPluginRuntime().setBucket(context.bucket);
        properties.getPluginRuntime().setPrefix(PREFIX);
        properties.getPluginRuntime().setChannel(CHANNEL);
        properties.getPluginRuntime().setRefreshIntervalSeconds(1);
        properties.getPluginRuntime().setRefreshJitterSeconds(0);
        properties.getPluginRuntime().setColdLoadTimeoutSeconds(1);
        return properties;
    }

    private AcceptanceContext acceptanceContext() throws Exception {
        Path config = Path.of(requiredEnv(CONFIG_ENV)).toAbsolutePath().normalize();
        Map<String, String> objectStorage = readObjectStorage(config);
        return new AcceptanceContext(
                required(objectStorage, "endpoint"),
                required(objectStorage, "access-key"),
                required(objectStorage, "secret-key"),
                required(objectStorage, "bucket"),
                Path.of(requiredEnv(RUNTIME_HOME_ENV)).toAbsolutePath().normalize(),
                Path.of(requiredEnv(REPOSITORY_V1_ENV)).toAbsolutePath().normalize(),
                Path.of(requiredEnv(REPOSITORY_V2_ENV)).toAbsolutePath().normalize(),
                Path.of(requiredEnv(RECOVERY_ENV)).toAbsolutePath().normalize());
    }

    private Map<String, String> readObjectStorage(Path yaml) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        boolean inStudio = false;
        boolean inObjectStorage = false;
        for (String line : Files.readAllLines(yaml, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.trim();
            if (indent == 0) {
                inStudio = "studio:".equals(trimmed);
                inObjectStorage = false;
                continue;
            }
            if (inStudio && indent == 2) {
                inObjectStorage = "object-storage:".equals(trimmed);
                continue;
            }
            if (inObjectStorage && indent == 4) {
                int separator = trimmed.indexOf(':');
                if (separator > 0) {
                    result.put(trimmed.substring(0, separator).trim(),
                            unquote(trimmed.substring(separator + 1).trim()));
                }
            }
        }
        return result;
    }

    private void backupOriginalPointer(AcceptanceContext context,
                                       CloudObjectStorageService storage) throws Exception {
        Files.createDirectories(context.recoveryDirectory);
        Path marker = context.recoveryDirectory.resolve("original-pointer-present.txt");
        if (Files.exists(marker)) {
            throw new IllegalStateException("Original pointer was already inspected; use a fresh recovery directory");
        }
        try {
            storage.stat(context.bucket, pointerKey());
            byte[] original = storage.get(context.bucket, pointerKey());
            Files.write(context.recoveryDirectory.resolve("current-original.json"), original);
            Files.writeString(marker, "true\n", StandardCharsets.UTF_8);
        } catch (IllegalStateException ex) {
            if (!isMissingObject(ex)) {
                throw ex;
            }
            Files.writeString(marker, "false\n", StandardCharsets.UTF_8);
        }
    }

    private void uploadImmutableRelease(AcceptanceContext context,
                                        CloudObjectStorageService storage,
                                        String release,
                                        Path repository) throws Exception {
        String key = pluginBaseKey() + "/releases/" + release + "/plugin.zip";
        try {
            storage.stat(context.bucket, key);
            throw new IllegalStateException("Immutable acceptance release already exists: " + release);
        } catch (IllegalStateException ex) {
            if (!isMissingObject(ex)) {
                throw ex;
            }
        }
        Path zip = pluginDirectory(repository).resolve("releases").resolve(release).resolve("plugin.zip");
        JsonNode pointer = pointer(repository);
        byte[] bytes = Files.readAllBytes(zip);
        assertEquals(pointer.path("size").asLong(), bytes.length);
        storage.put(context.bucket, key, bytes, "application/zip");
        CloudObjectStorageService.ObjectInfo uploaded = storage.stat(context.bucket, key);
        assertEquals(bytes.length, uploaded.getSize());
    }

    private void putPointer(AcceptanceContext context,
                            CloudObjectStorageService storage,
                            Path repository) throws Exception {
        byte[] pointer = Files.readAllBytes(pluginDirectory(repository).resolve("current.json"));
        storage.put(context.bucket, pointerKey(), pointer, "application/json");
        assertEquals(pointer.length, storage.stat(context.bucket, pointerKey()).getSize());
    }

    private ResolvedPlugin awaitIdentity(ObjectStoragePluginRuntimeResolver resolver,
                                         String expectedIdentity,
                                         Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        ResolvedPlugin last = null;
        while (Instant.now().isBefore(deadline)) {
            try (PluginRuntimeSession probe = PluginRuntimeSession.open()) {
                last = probe.resolve(PluginType.READER, NAME);
                if (expectedIdentity.equals(last.getIdentity())) {
                    return last;
                }
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Timed out waiting for expected plugin identity; last="
                + (last == null ? "none" : last.getIdentity()));
    }

    private void awaitStatus(ObjectStoragePluginRuntimeResolver resolver,
                             String expectedState,
                             Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        Object state = null;
        while (Instant.now().isBefore(deadline)) {
            state = resolver.statusSnapshot().get("state");
            if (expectedState.equals(state)) {
                return;
            }
            Thread.sleep(100L);
        }
        assertEquals(expectedState, state);
    }

    private String revision(AbstractPlugin plugin) throws Exception {
        Method method = plugin.getClass().getMethod("revision");
        return String.valueOf(method.invoke(plugin));
    }

    private void assertEmptyCache(Path runtimeHome) throws Exception {
        Path cache = runtimeHome.resolve("cache");
        if (!Files.isDirectory(cache)) {
            return;
        }
        try (var files = Files.walk(cache)) {
            assertFalse(files.anyMatch(Files::isRegularFile),
                    "HOT_UPDATE must begin with an empty dedicated cache");
        }
    }

    private void assertStateIdentity(AcceptanceContext context, String expectedIdentity) throws Exception {
        Path state = context.runtimeHome.resolve(".state").resolve(TYPE).resolve(NAME + ".json");
        assertTrue(Files.isRegularFile(state));
        JsonNode json = objectMapper.readTree(state.toFile());
        assertEquals(expectedIdentity, "sha256:" + json.path("sha256").asText());
    }

    private void writeResult(AcceptanceContext context,
                             String phase,
                             String activeRelease,
                             String status) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", phase);
        result.put("status", status);
        result.put("type", TYPE);
        result.put("name", NAME);
        result.put("activeRelease", activeRelease);
        result.put("completedAt", Instant.now().toString());
        Files.createDirectories(context.recoveryDirectory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                context.recoveryDirectory.resolve("result-" + phase.toLowerCase(Locale.ROOT) + ".json").toFile(),
                result);
    }

    private JsonNode pointer(Path repository) throws Exception {
        return objectMapper.readTree(pluginDirectory(repository).resolve("current.json").toFile());
    }

    private String identity(Path repository) throws Exception {
        return "sha256:" + pointer(repository).path("sha256").asText();
    }

    private Path pluginDirectory(Path repository) {
        return repository.resolve(CHANNEL).resolve(TYPE).resolve(NAME);
    }

    private String pointerKey() {
        return pluginBaseKey() + "/current.json";
    }

    private String pluginBaseKey() {
        return PREFIX + "/" + CHANNEL + "/" + TYPE + "/" + NAME;
    }

    private boolean isMissingObject(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("NoSuchKey")
                    || message.contains("404")
                    || message.toLowerCase(Locale.ROOT).contains("does not exist"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The object-storage " + key + " setting is required");
        }
        return value;
    }

    private String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record AcceptanceContext(String endpoint,
                                     String accessKey,
                                     String secretKey,
                                     String bucket,
                                     Path runtimeHome,
                                     Path repositoryV1,
                                     Path repositoryV2,
                                     Path recoveryDirectory) {
    }
}
