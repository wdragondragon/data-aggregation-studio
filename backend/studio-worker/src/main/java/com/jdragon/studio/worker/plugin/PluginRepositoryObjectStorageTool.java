package com.jdragon.studio.worker.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.CloudObjectStorageService;
import com.jdragon.studio.nacos.compat.config.NacosCompatConfigPropertySource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Non-web Java operator for the immutable OSS plugin repository.
 *
 * <p>The command intentionally reuses {@link CloudObjectStorageService}; its
 * configuration is loaded by the same Nacos-backed Spring config import as the
 * Worker. It never prints credentials or pointer contents.</p>
 */
public final class PluginRepositoryObjectStorageTool {
    private static final long MAX_POINTER_BYTES = 64L * 1024L;
    private static final long MAX_ARTIFACT_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_STATE_BYTES = 1024L * 1024L;
    private static final Pattern SHA256 = Pattern.compile("(?i)[0-9a-f]{64}");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private PluginRepositoryObjectStorageTool() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (!options.requireNacosProvenance) {
                throw new IllegalStateException("Explicit Nacos provenance confirmation is required");
            }
            SpringApplicationBuilder builder = new SpringApplicationBuilder(ToolConfiguration.class)
                    .web(WebApplicationType.NONE)
                    .registerShutdownHook(false)
                    .properties("spring.main.banner-mode=off", "spring.main.log-startup-info=false",
                            "studio.operator.plugin-repository=true",
                            "logging.level.root=OFF", "logging.level.com.alibaba.nacos=OFF",
                            "logging.level.com.aliyun.oss=OFF", "logging.level.com.jdragon.studio.nacos=OFF");
            try (ConfigurableApplicationContext context = builder.run(args)) {
                StudioPlatformProperties properties = context.getBean(StudioPlatformProperties.class);
                requireNacosProvenance(context, properties);
                CloudObjectStorageService storage = context.getBean(CloudObjectStorageService.class);
                run(properties, storage, options);
            }
        } catch (Throwable error) {
            System.err.println("OSS_PLUGIN_OP action=failed category=" + failureCategory(error));
            System.exit(2);
        }
    }

    private static void requireNacosProvenance(ConfigurableApplicationContext context,
                                               StudioPlatformProperties properties) {
        for (PropertySource<?> source : context.getEnvironment().getPropertySources()) {
            if (source instanceof NacosCompatConfigPropertySource nacos
                    && hasText(nacos.getRawContent()) && matchesEffectiveRepository(nacos, properties)) {
                return;
            }
        }
        throw new IllegalStateException("Effective plugin repository configuration is not Nacos-backed");
    }

    static boolean matchesEffectiveRepository(PropertySource<?> source,
                                               StudioPlatformProperties properties) {
        StudioPlatformProperties.PluginRuntimeProperties runtime = properties.getPluginRuntime();
        StudioPlatformProperties.ObjectStorageProperties storage = properties.getObjectStorage();
        return runtime != null && runtime.isLazyObjectStorage()
                && sourceMatches(source, "studio.runtime-version", properties.getRuntimeVersion())
                && sourceMatches(source, "studio.plugin-runtime.mode", runtime.getMode())
                && sourceMatches(source, "studio.plugin-runtime.bucket", runtime.getBucket())
                && sourceMatches(source, "studio.plugin-runtime.prefix", runtime.getPrefix())
                && sourceMatches(source, "studio.plugin-runtime.channel", runtime.getChannel())
                && storage != null
                && isAliyunProvider(storage.getProvider())
                && hasText(runtime.getBucket()) && hasText(storage.getBucket())
                && runtime.getBucket().trim().equals(storage.getBucket().trim())
                && sourceMatches(source, "studio.object-storage.provider", storage.getProvider())
                && sourceMatches(source, "studio.object-storage.endpoint", storage.getEndpoint())
                && sourceMatches(source, "studio.object-storage.access-key", storage.getAccessKey())
                && sourceMatches(source, "studio.object-storage.secret-key", storage.getSecretKey())
                && sourceMatches(source, "studio.object-storage.bucket", storage.getBucket());
    }

    private static boolean isAliyunProvider(String provider) {
        if (!hasText(provider)) {
            return false;
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        return "oss".equals(normalized) || "aliyun".equals(normalized)
                || "aliyun_oss".equals(normalized) || "aliyun-oss".equals(normalized);
    }

    private static boolean sourceMatches(PropertySource<?> source, String key, Object expected) {
        Object actual = source.getProperty(key);
        return actual != null && expected != null
                && String.valueOf(actual).trim().equals(String.valueOf(expected).trim());
    }

    private static String failureCategory(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ProbeConditionException probeFailure) {
                return "PROBE_" + probeFailure.phase + probeStorageSuffix(probeFailure.getCause());
            }
            if (current instanceof IllegalArgumentException) {
                return "VALIDATION";
            }
            if (current instanceof java.nio.file.NoSuchFileException) {
                return "LOCAL_STATE";
            }
            current = current.getCause();
        }
        return error instanceof IllegalStateException ? "CONFLICT_OR_STORAGE" : "INTERNAL";
    }

    private static String probeStorageSuffix(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof com.aliyun.oss.OSSException oss) {
                String code = oss.getErrorCode();
                if (hasText(code)) {
                    String safe = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
                    return "_" + safe.substring(0, Math.min(safe.length(), 40));
                }
                return "_ALIYUN_ERROR";
            }
            if (current instanceof IllegalStateException
                    && current.getMessage() != null
                    && current.getMessage().startsWith("Object remained after atomic delete")) {
                return "_NOT_ABSENT";
            }
            current = current.getCause();
        }
        return "";
    }

    static void run(StudioPlatformProperties properties,
                    CloudObjectStorageService storage,
                    String... args) throws Exception {
        run(properties, storage, Options.parse(args));
    }

    private static void run(StudioPlatformProperties properties,
                            CloudObjectStorageService storage,
                            Options options) throws Exception {
        new Operator(properties, storage, options).run();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "studio.operator.plugin-repository", havingValue = "true")
    @EnableConfigurationProperties(StudioPlatformProperties.class)
    @Import(CloudObjectStorageService.class)
    static class ToolConfiguration {
    }

    private static final class Operator {
        private final StudioPlatformProperties properties;
        private final CloudObjectStorageService storage;
        private final Options options;
        private final String bucket;
        private final String baseKey;

        private Operator(StudioPlatformProperties properties,
                         CloudObjectStorageService storage,
                         Options options) {
            this.properties = Objects.requireNonNull(properties, "properties");
            this.storage = Objects.requireNonNull(storage, "storage");
            this.options = options;
            StudioPlatformProperties.PluginRuntimeProperties runtime = properties.getPluginRuntime();
            String configuredBucket = runtime == null ? null : runtime.getBucket();
            this.bucket = hasText(configuredBucket) ? configuredBucket.trim() : storage.resolveBucket();
            String prefix = runtime == null ? null : runtime.getPrefix();
            String channel = runtime == null ? null : runtime.getChannel();
            this.baseKey = join(safePrefix(prefix), safeSegment(channel, "plugin channel"));
        }

        private void run() throws Exception {
            switch (options.action) {
                case "backup":
                    backup();
                    return;
                case "publish":
                    publish();
                    return;
                case "restore":
                    restore();
                    return;
                case "verify":
                    verify();
                    return;
                case "probe-conditions":
                    probeConditions();
                    return;
                default:
                    throw new IllegalArgumentException("Unsupported action: " + options.action);
            }
        }

        private void backup() throws Exception {
            List<LocalPlugin> plugins = readLocalPlugins(options.repository);
            if (Files.exists(options.stateFile)) {
                throw new IllegalStateException("Backup state already exists: " + options.stateFile);
            }
            Files.createDirectories(options.backupDirectory);
            List<BackupEntry> entries = new ArrayList<BackupEntry>();
            for (LocalPlugin plugin : plugins) {
                String key = remoteCurrentKey(plugin.type, plugin.name);
                Path backupFile = options.backupDirectory.resolve(plugin.type + "__" + plugin.name + ".current.json");
                if (Files.exists(backupFile)) {
                    throw new IllegalStateException("Pointer backup already exists: " + backupFile);
                }
                boolean exists = storage.exists(bucket, key);
                String sha = null;
                long size = 0L;
                if (exists) {
                    CloudObjectStorageService.ObjectInfo info = storage.stat(bucket, key);
                    requireSize(info.getSize(), MAX_POINTER_BYTES, "remote current.json " + key);
                    Path staging = Files.createTempFile(options.backupDirectory, ".pointer-", ".tmp");
                    try {
                        storage.downloadTo(bucket, key, staging, MAX_POINTER_BYTES);
                        size = Files.size(staging);
                        if (size != info.getSize()) {
                            throw new IllegalStateException("Remote current.json changed while being backed up: " + key);
                        }
                        parseAndValidatePointer(staging, plugin.type, plugin.name);
                        sha = sha256(staging);
                        moveWithAtomicFallback(staging, backupFile);
                    } finally {
                        Files.deleteIfExists(staging);
                    }
                } else {
                    Files.deleteIfExists(backupFile);
                }
                entries.add(new BackupEntry(plugin.type, plugin.name, key, exists, backupFile.toString(), size, sha));
                emit("backup", plugin.coordinate(), null, size, sha);
            }
            BackupState state = new BackupState();
            state.schemaVersion = Integer.valueOf(1);
            state.createdAt = Instant.now().toString();
            state.bucket = bucket;
            state.baseKey = baseKey;
            state.backupDirectory = options.backupDirectory.toString();
            state.entries = entries;
            writeState(state);
            System.out.println("OSS_PLUGIN_OP action=backup count=" + entries.size());
        }

        private void publish() throws Exception {
            BackupState state = readState();
            List<LocalPlugin> plugins = readLocalPlugins(options.repository);
            Map<String, LocalPlugin> expected = validateState(state, plugins);
            // Do not add a new local revision to the allow-list until every
            // remote pointer is still original or was recorded by this state.
            requireAllPointersAllowed(state);
            bindTestPointers(state, expected);
            for (LocalPlugin plugin : plugins) {
                uploadArtifactIfNeeded(plugin);
            }
            for (BackupEntry entry : state.entries) {
                LocalPlugin plugin = expected.get(entry.coordinate());
                AllowedPointer current = requireAllowedRemotePointer(entry);
                PointerRevision target = pendingRevision(entry);
                if (!target.matches(current.digest)) {
                    putPointerAtomically(entry, current.digest, plugin.currentFile);
                }
                verifyRemoteFile(entry.remoteKey, plugin.currentFile, MAX_POINTER_BYTES);
                entry.phase = "PUBLISHED";
                writeState(state);
                emit("publish-pointer", plugin.coordinate(), plugin.pointer.release,
                        plugin.artifactSize, plugin.artifactSha256);
            }
            System.out.println("OSS_PLUGIN_OP action=publish count=" + plugins.size());
        }

        private void uploadArtifactIfNeeded(LocalPlugin plugin) throws Exception {
            String key = remoteArtifactKey(plugin);
            storage.putFileIfAbsent(bucket, key, plugin.artifactFile, "application/zip");
            verifyRemoteFile(key, plugin.artifactFile, MAX_ARTIFACT_BYTES);
            emit("publish-artifact", plugin.coordinate(), plugin.pointer.release,
                    plugin.artifactSize, plugin.artifactSha256);
        }

        private void restore() throws Exception {
            BackupState state = readState();
            List<LocalPlugin> plugins = readLocalPlugins(options.repository);
            validateState(state, plugins);

            // Original and revisions recorded by this backup state are the only
            // accepted values. This permits retries and v1/v2/rollback testing
            // without authorizing a third-party pointer.
            requireAllPointersAllowed(state);
            for (BackupEntry entry : state.entries) {
                AllowedPointer current = requireAllowedRemotePointer(entry);
                if (current.version != PointerVersion.ORIGINAL) {
                    if (entry.exists) {
                        Path backup = resolveBackupFile(state, entry);
                        putPointerAtomically(entry, current.digest, backup);
                    } else {
                        if (!current.digest.present) {
                            throw pointerConflict(entry);
                        }
                        storage.delete(bucket, entry.remoteKey);
                    }
                }
                verifyOriginalPointer(entry);
                entry.phase = "RESTORED";
                writeState(state);
                emit("restore", entry.type + "/" + entry.name, null, entry.size, entry.sha256);
            }
            System.out.println("OSS_PLUGIN_OP action=restore count=" + state.entries.size());
        }

        private void verify() throws Exception {
            List<LocalPlugin> plugins = readLocalPlugins(options.repository);
            for (LocalPlugin plugin : plugins) {
                verifyRemoteFile(remoteCurrentKey(plugin.type, plugin.name),
                        plugin.currentFile, MAX_POINTER_BYTES);
                verifyRemoteFile(remoteArtifactKey(plugin), plugin.artifactFile, MAX_ARTIFACT_BYTES);
                emit("verify", plugin.coordinate(), plugin.pointer.release,
                        plugin.artifactSize, plugin.artifactSha256);
            }
            System.out.println("OSS_PLUGIN_OP action=verify count=" + plugins.size());
        }

        private void probeConditions() throws Exception {
            String key = join(baseKey, ".operator-probes", "atomic-" + UUID.randomUUID() + ".txt");
            Path first = Files.createTempFile("oss-atomic-probe-first-", ".txt");
            Path second = Files.createTempFile("oss-atomic-probe-second-", ".txt");
            String phase = "LOCAL_PREPARE";
            try {
                Files.writeString(first, "first", StandardCharsets.UTF_8);
                Files.writeString(second, "second", StandardCharsets.UTF_8);
                phase = "CREATE";
                if (!storage.putFileIfAbsent(bucket, key, first, "text/plain")) {
                    throw new IllegalStateException("Immutable probe create did not create the object");
                }
                phase = "ATOMIC_REPLACE";
                storage.putFileExistingBucket(bucket, key, second, "text/plain");
                phase = "VERIFY_REPLACE";
                verifyRemoteFile(key, second, 1024L);
                System.out.println("OSS_PLUGIN_OP action=probe-atomic-writes status=passed");
            } catch (Exception ex) {
                throw new ProbeConditionException(phase, ex);
            } finally {
                Files.deleteIfExists(first);
                Files.deleteIfExists(second);
                try {
                    if (storage.exists(bucket, key)) {
                        storage.delete(bucket, key);
                    }
                } catch (Exception ignored) {
                    // The unique probe key is reported only through the local failure category.
                }
            }
        }

        private void verifyRemoteFile(String key, Path expected, long maxBytes) throws Exception {
            Path probe = Files.createTempFile("plugin-remote-verify-", ".json");
            try {
                CloudObjectStorageService.ObjectInfo info = storage.stat(bucket, key);
                requireSize(info.getSize(), maxBytes, key);
                long expectedSize = Files.size(expected);
                if (info.getSize() != expectedSize) {
                    throw new IllegalStateException("Remote object differs from expected local file: " + key);
                }
                storage.downloadTo(bucket, key, probe, expectedSize);
                if (Files.size(probe) != expectedSize
                        || !sha256(probe).equalsIgnoreCase(sha256(expected))) {
                    throw new IllegalStateException("Remote object differs from expected local file: " + key);
                }
            } finally {
                Files.deleteIfExists(probe);
            }
        }

        private void bindTestPointers(BackupState state,
                                      Map<String, LocalPlugin> plugins) throws Exception {
            boolean changed = false;
            for (BackupEntry entry : state.entries) {
                LocalPlugin plugin = plugins.get(entry.coordinate());
                long size = Files.size(plugin.currentFile);
                String digest = sha256(plugin.currentFile);
                PointerRevision revision = new PointerRevision(size, digest);
                List<PointerRevision> known = knownTestRevisions(entry);
                if (known.stream().noneMatch(revision::sameAs)) {
                    if (known.size() >= 1000) {
                        throw new IllegalStateException("Too many test pointer revisions: " + entry.coordinate());
                    }
                    if (entry.testRevisions == null) {
                        entry.testRevisions = new ArrayList<PointerRevision>();
                    }
                    entry.testRevisions.add(revision);
                    changed = true;
                }
                boolean targetChanged = entry.pendingTestSize == null
                        || entry.pendingTestSize.longValue() != size
                        || !digest.equalsIgnoreCase(entry.pendingTestSha256);
                if (targetChanged) {
                    entry.pendingTestSize = Long.valueOf(size);
                    entry.pendingTestSha256 = digest;
                    entry.phase = "PREPARED";
                    changed = true;
                } else if (!hasText(entry.phase)) {
                    entry.phase = "PREPARED";
                    changed = true;
                }
            }
            if (changed) {
                writeState(state);
            }
        }

        private void requireAllPointersAllowed(BackupState state) throws Exception {
            for (BackupEntry entry : state.entries) {
                requireAllowedRemotePointer(entry);
            }
        }

        private AllowedPointer requireAllowedRemotePointer(BackupEntry entry) throws Exception {
            RemoteDigest current = readRemoteDigest(entry.remoteKey);
            boolean original = entry.exists
                    ? current.present && current.size == entry.size
                    && entry.sha256.equalsIgnoreCase(current.sha256)
                    : !current.present;
            if (original) {
                return new AllowedPointer(PointerVersion.ORIGINAL, current);
            }
            for (PointerRevision revision : knownTestRevisions(entry)) {
                if (revision.matches(current)) {
                    return new AllowedPointer(PointerVersion.TEST, current);
                }
            }
            throw pointerConflict(entry);
        }

        private RemoteDigest readRemoteDigest(String key) throws Exception {
            if (!storage.exists(bucket, key)) {
                return RemoteDigest.absent();
            }
            CloudObjectStorageService.ObjectInfo info = storage.stat(bucket, key);
            requireSize(info.getSize(), MAX_POINTER_BYTES, "remote pointer");
            Path probe = Files.createTempFile("plugin-pointer-digest-", ".json");
            try {
                storage.downloadTo(bucket, key, probe, MAX_POINTER_BYTES);
                long size = Files.size(probe);
                if (size != info.getSize()) {
                    throw new IllegalStateException("Remote pointer changed while being validated");
                }
                return RemoteDigest.present(size, sha256(probe));
            } finally {
                Files.deleteIfExists(probe);
            }
        }

        private void putPointerAtomically(BackupEntry entry, RemoteDigest current, Path source) {
            if (!current.present && entry.exists) {
                throw pointerConflict(entry);
            }
            // OSS PutObject replaces the object atomically. Keep the
            // preflight/allow-list checks above and verify the complete JSON
            // after the write; release artifacts remain no-overwrite.
            storage.putFileExistingBucket(bucket, entry.remoteKey, source, "application/json");
        }

        private void verifyOriginalPointer(BackupEntry entry) throws Exception {
            if (!entry.exists) {
                awaitRemoteAbsent(entry.remoteKey);
                return;
            }
            RemoteDigest current = readRemoteDigest(entry.remoteKey);
            boolean original = current.present && current.size == entry.size
                    && entry.sha256.equalsIgnoreCase(current.sha256);
            if (!original) {
                throw new IllegalStateException("Pointer restore verification failed: " + entry.coordinate());
            }
        }

        private void awaitRemoteAbsent(String key) throws Exception {
            for (int attempt = 0; attempt < 30; attempt++) {
                if (!storage.exists(bucket, key)) {
                    return;
                }
                Thread.sleep(200L);
            }
            throw new IllegalStateException("Object remained after atomic delete");
        }

        private IllegalStateException pointerConflict(BackupEntry entry) {
            return new IllegalStateException("Refusing to overwrite a pointer changed by another publisher: "
                    + entry.coordinate());
        }

        private PointerRevision pendingRevision(BackupEntry entry) {
            if (entry.pendingTestSize == null || !hasText(entry.pendingTestSha256)) {
                throw new IllegalStateException("Backup state has no pending test revision: " + entry.coordinate());
            }
            return new PointerRevision(entry.pendingTestSize.longValue(), entry.pendingTestSha256);
        }

        private List<PointerRevision> knownTestRevisions(BackupEntry entry) {
            List<PointerRevision> revisions = new ArrayList<PointerRevision>();
            if (entry.testRevisions != null) {
                revisions.addAll(entry.testRevisions);
            }
            // States written by the first operator implementation carried one
            // revision in these scalar fields. Keep it authorized for restore.
            if (hasText(entry.testSha256) && entry.testSize > 0L) {
                PointerRevision legacy = new PointerRevision(entry.testSize, entry.testSha256);
                if (revisions.stream().noneMatch(legacy::sameAs)) {
                    revisions.add(legacy);
                }
            }
            return revisions;
        }

        private void writeState(BackupState state) throws IOException {
            Path target = options.stateFile.toAbsolutePath().normalize();
            Path parent = target.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("Backup state must have a parent directory");
            }
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
            try {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
                requireSize(Files.size(temporary), MAX_STATE_BYTES, "backup state");
                moveWithAtomicFallback(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private List<LocalPlugin> readLocalPlugins(Path repository) throws Exception {
            if (repository == null || !Files.isDirectory(repository)) {
                throw new IllegalArgumentException("repository must be an existing directory");
            }
            Path normalizedRepository = repository.toAbsolutePath().normalize();
            Path repositoryReal = normalizedRepository.toRealPath();
            List<LocalPlugin> plugins = new ArrayList<LocalPlugin>();
            Set<String> coordinates = new LinkedHashSet<String>();
            try (java.util.stream.Stream<Path> paths = Files.walk(normalizedRepository, 4)) {
                paths.filter(path -> Files.isRegularFile(path) && "current.json".equals(path.getFileName().toString()))
                        .forEach(path -> {
                            try {
                                Path currentReal = path.toRealPath();
                                if (!currentReal.startsWith(repositoryReal)) {
                                    throw new IllegalArgumentException("current.json escapes repository: " + path);
                                }
                                requireSize(Files.size(path), MAX_POINTER_BYTES, "current.json " + path);
                                Path relative = normalizedRepository.relativize(path);
                                if (relative.getNameCount() != 3) {
                                    throw new IllegalArgumentException("current.json must be under type/name: " + path);
                                }
                                String type = safeSegment(relative.getName(0).toString(), "type");
                                String name = safeSegment(relative.getName(1).toString(), "name");
                                if (!coordinates.add(type + "/" + name)) {
                                    throw new IllegalArgumentException("Duplicate plugin coordinate: " + type + "/" + name);
                                }
                                PluginPointer pointer = parseAndValidatePointer(path, type, name);
                                String artifact = pointer.artifact.replace('\\', '/').trim();
                                Path pluginDirectory = path.getParent().toAbsolutePath().normalize();
                                Path artifactFile = pluginDirectory.resolve(artifact).normalize();
                                Path pluginDirectoryReal = pluginDirectory.toRealPath();
                                Path artifactReal = artifactFile.toRealPath();
                                if (!artifactReal.startsWith(pluginDirectoryReal)
                                        || !Files.isRegularFile(artifactReal)) {
                                    throw new IllegalArgumentException("Artifact is outside plugin directory: " + path);
                                }
                                long artifactSize = Files.size(artifactReal);
                                requireSize(artifactSize, MAX_ARTIFACT_BYTES, "local artifact " + artifactFile);
                                if (pointer.size.longValue() != artifactSize) {
                                    throw new IllegalArgumentException("current.json size does not match local artifact for "
                                            + type + "/" + name);
                                }
                                String artifactSha256 = sha256(artifactReal);
                                if (!artifactSha256.equalsIgnoreCase(pointer.sha256)) {
                                    throw new IllegalArgumentException("Local artifact SHA-256 does not match current.json for "
                                            + type + "/" + name);
                                }
                                plugins.add(new LocalPlugin(type, name, path, artifactReal, pointer,
                                        artifactSize, artifactSha256));
                            } catch (Exception ex) {
                                throw new LocalPluginReadException(ex);
                            }
                        });
            } catch (LocalPluginReadException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw new IllegalStateException("Failed to read local plugin repository", cause);
            }
            plugins.sort(Comparator.comparing(LocalPlugin::coordinate));
            if (plugins.isEmpty()) {
                throw new IllegalArgumentException("No plugin current.json files found in " + repository);
            }
            return plugins;
        }

        private PluginPointer parseAndValidatePointer(Path file, String type, String name) throws IOException {
            PluginPointer pointer = MAPPER.readValue(file.toFile(), PluginPointer.class);
            validatePointer(pointer, type, name);
            return pointer;
        }

        private BackupState readState() throws IOException {
            if (options.stateFile == null || !Files.isRegularFile(options.stateFile)) {
                throw new IllegalArgumentException("Backup state file is required: " + options.stateFile);
            }
            requireSize(Files.size(options.stateFile), MAX_STATE_BYTES, "backup state");
            return MAPPER.readValue(options.stateFile.toFile(), BackupState.class);
        }

        private Map<String, LocalPlugin> validateState(BackupState state,
                                                        List<LocalPlugin> plugins) throws Exception {
            if (state == null || !bucket.equals(state.bucket) || !baseKey.equals(state.baseKey)) {
                throw new IllegalStateException("Backup state does not match configured OSS repository");
            }
            if (state.schemaVersion != null && state.schemaVersion.intValue() != 1) {
                throw new IllegalStateException("Unsupported backup state schemaVersion");
            }
            if (!hasText(state.createdAt)) {
                throw new IllegalStateException("Backup state createdAt is required");
            }
            try {
                Instant.parse(state.createdAt.trim());
            } catch (Exception ex) {
                throw new IllegalStateException("Backup state createdAt is invalid", ex);
            }
            if (state.entries == null || state.entries.isEmpty()) {
                throw new IllegalStateException("Backup state has no entries");
            }
            Map<String, LocalPlugin> expected = new LinkedHashMap<String, LocalPlugin>();
            for (LocalPlugin plugin : plugins) {
                if (expected.put(plugin.coordinate(), plugin) != null) {
                    throw new IllegalStateException("Duplicate local plugin coordinate: " + plugin.coordinate());
                }
            }
            Set<String> seen = new LinkedHashSet<String>();
            for (BackupEntry entry : state.entries) {
                if (entry == null) {
                    throw new IllegalStateException("Backup state contains a null entry");
                }
                String type = safeSegment(entry.type, "backup type");
                String name = safeSegment(entry.name, "backup name");
                String coordinate = type + "/" + name;
                if (!seen.add(coordinate)) {
                    throw new IllegalStateException("Duplicate backup coordinate: " + coordinate);
                }
                LocalPlugin plugin = expected.get(coordinate);
                if (plugin == null) {
                    throw new IllegalStateException("Backup state coordinate is not in repository: " + coordinate);
                }
                if (!remoteCurrentKey(type, name).equals(entry.remoteKey)) {
                    throw new IllegalStateException("Backup state remote key does not match coordinate: " + coordinate);
                }
                Path backup = resolveBackupFile(state, entry);
                if (entry.exists) {
                    if (!Files.isRegularFile(backup)) {
                        throw new IllegalStateException("Missing pointer backup for " + coordinate);
                    }
                    requireSize(Files.size(backup), MAX_POINTER_BYTES, "pointer backup " + coordinate);
                    if (entry.size != Files.size(backup)
                            || !hasText(entry.sha256)
                            || !entry.sha256.equalsIgnoreCase(sha256(backup))) {
                        throw new IllegalStateException("Pointer backup checksum does not match state: " + coordinate);
                    }
                    parseAndValidatePointer(backup, type, name);
                } else {
                    if (entry.size != 0L || hasText(entry.sha256) || Files.exists(backup)) {
                        throw new IllegalStateException("Absent pointer backup is inconsistent: " + coordinate);
                    }
                }
                // The repository must be the exact test revision used as the
                // expected-current guard during restore.
                if (!plugin.type.equals(type) || !plugin.name.equals(name)) {
                    throw new IllegalStateException("Backup state coordinate mismatch: " + coordinate);
                }
                if (hasText(entry.phase) && !Set.of("PREPARED", "PUBLISHED", "RESTORED").contains(entry.phase)) {
                    throw new IllegalStateException("Unsupported backup entry phase: " + coordinate);
                }
                if (hasText(entry.testSha256)
                        && (!SHA256.matcher(entry.testSha256).matches() || entry.testSize <= 0L)) {
                    throw new IllegalStateException("Backup test pointer digest is invalid: " + coordinate);
                }
                List<PointerRevision> known = knownTestRevisions(entry);
                if (known.size() > 1000) {
                    throw new IllegalStateException("Too many test pointer revisions: " + coordinate);
                }
                Set<String> revisionKeys = new LinkedHashSet<String>();
                for (PointerRevision revision : known) {
                    if (revision == null || revision.size <= 0L || !hasText(revision.sha256)
                            || !SHA256.matcher(revision.sha256).matches()) {
                        throw new IllegalStateException("Backup test pointer revision is invalid: " + coordinate);
                    }
                    if (!revisionKeys.add(revision.size + ":" + revision.sha256.toLowerCase(Locale.ROOT))) {
                        throw new IllegalStateException("Duplicate backup test pointer revision: " + coordinate);
                    }
                }
                boolean pendingSize = entry.pendingTestSize != null;
                boolean pendingSha = hasText(entry.pendingTestSha256);
                if (pendingSize != pendingSha || pendingSize
                        && (entry.pendingTestSize.longValue() <= 0L
                        || !SHA256.matcher(entry.pendingTestSha256).matches()
                        || known.stream().noneMatch(pendingRevision(entry)::sameAs))) {
                    throw new IllegalStateException("Backup pending test pointer revision is invalid: " + coordinate);
                }
            }
            if (seen.size() != expected.size()) {
                throw new IllegalStateException("Backup state does not cover the local repository");
            }
            return expected;
        }

        private Path resolveBackupFile(BackupState state, BackupEntry entry) {
            Path stateParent = options.stateFile.toAbsolutePath().normalize().getParent();
            Path root = hasText(state.backupDirectory)
                    ? Path.of(state.backupDirectory).toAbsolutePath().normalize()
                    : stateParent;
            String expectedName = safeSegment(entry.type, "backup type") + "__"
                    + safeSegment(entry.name, "backup name") + ".current.json";
            Path expected = root.resolve(expectedName).normalize();
            if (!hasText(entry.backupFile)) {
                throw new IllegalStateException("Backup file is required for " + entry.type + "/" + entry.name);
            }
            Path declared = Path.of(entry.backupFile);
            if (!declared.isAbsolute()) {
                declared = root.resolve(declared);
            }
            declared = declared.toAbsolutePath().normalize();
            if (!declared.equals(expected) || !declared.startsWith(root)) {
                throw new IllegalStateException("Backup file is outside the backup directory: " + entry.type + "/"
                        + entry.name);
            }
            return expected;
        }

        private String remoteCurrentKey(String type, String name) {
            return join(baseKey, type, name, "current.json");
        }

        private String remoteArtifactKey(LocalPlugin plugin) {
            return join(baseKey, plugin.type, plugin.name,
                    plugin.pointer.artifact.replace('\\', '/').trim());
        }

        private void validatePointer(PluginPointer pointer, String type, String name) {
            if (pointer == null || pointer.schemaVersion == null || pointer.schemaVersion.intValue() != 1) {
                throw new IllegalArgumentException("Unsupported or missing current.json schemaVersion for "
                        + type + "/" + name);
            }
            if (!type.equals(pointer.type) || !name.equals(pointer.name)) {
                throw new IllegalArgumentException("current.json coordinate does not match " + type + "/" + name);
            }
            String release = safeSegment(pointer.release, "release");
            if (!hasText(pointer.artifact)) {
                throw new IllegalArgumentException("current.json artifact is required for " + type + "/" + name);
            }
            String expectedArtifact = "releases/" + release + "/plugin.zip";
            if (!expectedArtifact.equals(pointer.artifact.replace('\\', '/').trim())) {
                throw new IllegalArgumentException("current.json artifact must equal " + expectedArtifact);
            }
            if (!hasText(pointer.sha256) || !SHA256.matcher(pointer.sha256).matches()) {
                throw new IllegalArgumentException("current.json sha256 is invalid for " + type + "/" + name);
            }
            if (pointer.size == null || pointer.size.longValue() <= 0L
                    || pointer.size.longValue() > MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("current.json size is invalid for " + type + "/" + name);
            }
            if (!hasText(pointer.runtimeVersion)) {
                throw new IllegalArgumentException("current.json runtimeVersion is required for " + type + "/" + name);
            }
            String configuredRuntimeVersion = requireSegment(properties.getRuntimeVersion(), "studio.runtime-version");
            if (!configuredRuntimeVersion.equals(pointer.runtimeVersion.trim())) {
                throw new IllegalArgumentException("Plugin runtimeVersion is incompatible with Worker runtime for "
                        + type + "/" + name);
            }
            if (!hasText(pointer.updatedAt)) {
                throw new IllegalArgumentException("current.json updatedAt is required for " + type + "/" + name);
            }
            try {
                Instant.parse(pointer.updatedAt.trim());
            } catch (Exception ex) {
                throw new IllegalArgumentException("current.json updatedAt must be an ISO-8601 instant for "
                        + type + "/" + name, ex);
            }
        }

        private void emit(String action, String coordinate, String release, long size, String sha) {
            String safeSha = hasText(sha) && sha.length() > 12 ? sha.substring(0, 12) : sha;
            System.out.println("OSS_PLUGIN_OP action=" + action + " coordinate=" + coordinate
                    + (release == null ? "" : " release=" + release)
                    + " size=" + size + " sha256=" + (safeSha == null ? "" : safeSha));
        }
    }

    private static void requireSize(long size, long max, String label) {
        if (size < 0L || size > max) {
            throw new IllegalArgumentException(label + " exceeds allowed size");
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Replaces a file with an atomic move where the filesystem supports it,
     * and falls back to a regular replace for filesystems (or mounts) that do
     * not implement ATOMIC_MOVE, including cross-filesystem staging setups.
     */
    static void moveWithAtomicFallback(Path source, Path target) throws IOException {
        moveWithAtomicFallback(source, target, Files::move);
    }

    static void moveWithAtomicFallback(Path source, Path target, FileMover mover) throws IOException {
        try {
            mover.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            mover.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    interface FileMover {
        Path move(Path source, Path target, CopyOption... options) throws IOException;
    }

    private static String join(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (!hasText(value)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(value.replace('\\', '/').replaceAll("^/+|/+$", ""));
        }
        return builder.toString();
    }

    private static String trimSlashes(String value) {
        String result = value == null ? "" : value.replace('\\', '/');
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String safePrefix(String value) {
        String normalized = trimSlashes(value);
        if (!hasText(normalized)) {
            throw new IllegalArgumentException("plugin prefix must not be blank");
        }
        String[] segments = normalized.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(safeSegment(segment, "plugin prefix segment"));
        }
        return result.toString();
    }

    private static String safeSegment(String value, String label) {
        String result = requireSegment(value, label);
        if (!result.matches("[A-Za-z0-9._-]+") || ".".equals(result) || "..".equals(result)) {
            throw new IllegalArgumentException(label + " contains unsupported path characters");
        }
        return result;
    }

    private static String requireSegment(String value, String label) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class LocalPluginReadException extends RuntimeException {
        private LocalPluginReadException(Throwable cause) {
            super(cause);
        }
    }

    private static final class LocalPlugin {
        private final String type;
        private final String name;
        private final Path currentFile;
        private final Path artifactFile;
        private final PluginPointer pointer;
        private final long artifactSize;
        private final String artifactSha256;

        private LocalPlugin(String type, String name, Path currentFile, Path artifactFile, PluginPointer pointer,
                            long artifactSize, String artifactSha256) {
            this.type = type;
            this.name = name;
            this.currentFile = currentFile;
            this.artifactFile = artifactFile;
            this.pointer = pointer;
            this.artifactSize = artifactSize;
            this.artifactSha256 = artifactSha256;
        }

        private String coordinate() {
            return type + "/" + name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class PluginPointer {
        public Integer schemaVersion;
        public String type;
        public String name;
        public String release;
        public String artifact;
        public String sha256;
        public Long size;
        public String runtimeVersion;
        public String updatedAt;
    }

    private static final class BackupState {
        public Integer schemaVersion;
        public String createdAt;
        public String bucket;
        public String baseKey;
        public String backupDirectory;
        public List<BackupEntry> entries;
    }

    private static final class BackupEntry {
        public String type;
        public String name;
        public String remoteKey;
        public boolean exists;
        public String backupFile;
        public long size;
        public String sha256;
        /** Digest of the local pointer revision being published in this transaction. */
        public long testSize;
        public String testSha256;
        /** All test revisions authorized by this original-pointer backup. */
        public List<PointerRevision> testRevisions;
        /** Revision requested by the current publish invocation. */
        public Long pendingTestSize;
        public String pendingTestSha256;
        /** PREPARED, PUBLISHED or RESTORED; progress is persisted after each pointer. */
        public String phase;

        public BackupEntry() {
        }

        private BackupEntry(String type, String name, String remoteKey, boolean exists,
                            String backupFile, long size, String sha256) {
            this.type = type;
            this.name = name;
            this.remoteKey = remoteKey;
            this.exists = exists;
            this.backupFile = backupFile;
            this.size = size;
            this.sha256 = sha256;
        }

        private String coordinate() {
            return type + "/" + name;
        }
    }

    private enum PointerVersion {
        ORIGINAL,
        TEST
    }

    private static final class PointerRevision {
        public long size;
        public String sha256;

        public PointerRevision() {
        }

        private PointerRevision(long size, String sha256) {
            this.size = size;
            this.sha256 = sha256;
        }

        private boolean matches(RemoteDigest digest) {
            return digest != null && digest.present && digest.size == size
                    && hasText(sha256) && sha256.equalsIgnoreCase(digest.sha256);
        }

        private boolean sameAs(PointerRevision other) {
            return other != null && size == other.size && hasText(sha256)
                    && sha256.equalsIgnoreCase(other.sha256);
        }
    }

    private static final class AllowedPointer {
        private final PointerVersion version;
        private final RemoteDigest digest;

        private AllowedPointer(PointerVersion version, RemoteDigest digest) {
            this.version = version;
            this.digest = digest;
        }
    }

    private static final class RemoteDigest {
        private final boolean present;
        private final long size;
        private final String sha256;

        private RemoteDigest(boolean present, long size, String sha256) {
            this.present = present;
            this.size = size;
            this.sha256 = sha256;
        }

        private static RemoteDigest absent() {
            return new RemoteDigest(false, 0L, null);
        }

        private static RemoteDigest present(long size, String sha256) {
            return new RemoteDigest(true, size, sha256);
        }
    }

    private static final class ProbeConditionException extends IllegalStateException {
        private final String phase;

        private ProbeConditionException(String phase, Throwable cause) {
            super("Conditional object-storage probe failed", cause);
            this.phase = phase;
        }
    }

    private static final class Options {
        private final String action;
        private final Path repository;
        private final Path backupDirectory;
        private final Path stateFile;
        private final boolean requireNacosProvenance;

        private Options(String action, Path repository, Path backupDirectory, Path stateFile) {
            this(action, repository, backupDirectory, stateFile, false);
        }

        private Options(String action, Path repository, Path backupDirectory, Path stateFile,
                        boolean requireNacosProvenance) {
            this.action = action;
            this.repository = repository;
            this.backupDirectory = backupDirectory;
            this.stateFile = stateFile;
            this.requireNacosProvenance = requireNacosProvenance;
        }

        private static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<String, String>();
            for (String arg : args == null ? new String[0] : args) {
                if (arg != null && arg.startsWith("--") && arg.contains("=")) {
                    int split = arg.indexOf('=');
                    values.put(arg.substring(2, split), arg.substring(split + 1));
                }
            }
            String action = values.get("tool.action");
            if (!hasText(action)) {
                action = values.get("action");
            }
            if (!hasText(action)) {
                throw new IllegalArgumentException(
                        "--tool.action=backup|publish|restore|verify|probe-conditions is required");
            }
            Path repository = path(values.get("tool.repository"), values.get("repository"));
            Path backup = path(values.get("tool.backup-directory"), values.get("backup-directory"));
            Path state = path(values.get("tool.state-file"), values.get("state-file"));
            if (backup == null && state != null) {
                backup = state.getParent();
            }
            if (state == null && backup != null) {
                state = backup.resolve("backup-state.json");
            }
            String normalizedAction = action.trim().toLowerCase(Locale.ROOT);
            boolean requireNacosProvenance = "true".equalsIgnoreCase(values.get("tool.require-nacos-provenance"));
            if (("backup".equals(normalizedAction) || "publish".equals(normalizedAction)
                    || "restore".equals(normalizedAction) || "verify".equals(normalizedAction))
                    && repository == null) {
                throw new IllegalArgumentException("--tool.repository is required for " + normalizedAction);
            }
            if ("backup".equals(normalizedAction) && (backup == null || state == null)) {
                throw new IllegalArgumentException("backup requires --tool.backup-directory or --tool.state-file");
            }
            if (("publish".equals(normalizedAction) || "restore".equals(normalizedAction)) && state == null) {
                throw new IllegalArgumentException("--tool.state-file is required for " + normalizedAction);
            }
            return new Options(normalizedAction, repository, backup, state, requireNacosProvenance);
        }

        private static Path path(String primary, String fallback) {
            String value = hasText(primary) ? primary : fallback;
            return hasText(value) ? Path.of(value).toAbsolutePath().normalize() : null;
        }
    }
}
