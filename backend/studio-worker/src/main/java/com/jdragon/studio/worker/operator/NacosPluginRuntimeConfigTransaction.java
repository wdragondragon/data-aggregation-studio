package com.jdragon.studio.worker.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.nacos.compat.support.NacosMd5Support;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CAS transaction for the temporary Studio Worker plugin-runtime override.
 *
 * <p>The transaction deliberately has no knowledge of a Nacos client.  This
 * keeps the destructive part unit-testable with an in-memory client while the
 * command-line adapter supplies the real Nacos {@code ConfigService}.</p>
 */
public final class NacosPluginRuntimeConfigTransaction {

    static final String OFFLINE_TEST_ENDPOINT = "http://127.0.0.1:1";

    private static final int STATE_SCHEMA_VERSION = 1;

    private final ConfigClient client;

    private final ObjectMapper objectMapper;

    public NacosPluginRuntimeConfigTransaction(ConfigClient client) {
        this(client, new ObjectMapper());
    }

    NacosPluginRuntimeConfigTransaction(ConfigClient client, ObjectMapper objectMapper) {
        this.client = Objects.requireNonNull(client, "client");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Read the current document, persist the exact backup before publishing,
     * and publish the merged document using Nacos CAS.
     */
    public ApplyResult apply(Target target, PluginRuntimeOverride override, Path stateFile) throws Exception {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(override, "override");
        Path state = normalizeStateFile(stateFile);
        if (Files.exists(state)) {
            throw existingStateException();
        }
        String original = requireExistingConfig(client.getConfig(target.dataId(), target.group()), target);
        String originalMd5 = md5(original);
        String merged = mergePluginRuntime(original, override);
        requireApprovedObjectStorageProvenance(merged);
        String testMd5 = md5(merged);

        TransactionState transactionState = TransactionState.prepared(target, original, originalMd5, testMd5,
                override);
        writeState(state, transactionState);

        if (!client.publishConfigCas(target.dataId(), target.group(), merged, originalMd5, "yaml")) {
            throw new CasConflictException("Nacos config changed before plugin-runtime override could be published");
        }
        String published = awaitConfigMd5(target, testMd5);
        if (!testMd5.equals(md5(published))) {
            throw new CasConflictException("Nacos config changed while verifying plugin-runtime override");
        }
        return new ApplyResult(originalMd5, testMd5);
    }

    /**
     * Restore the exact original bytes only when the remote document still has
     * the test MD5 recorded in the state file.  A concurrent edit is never
     * overwritten.
     */
    public RestoreResult restore(Target expectedTarget, Path stateFile) throws Exception {
        Objects.requireNonNull(expectedTarget, "expectedTarget");
        Path statePath = normalizeStateFile(stateFile);
        TransactionState transactionState = readState(statePath);
        requireTargetMatches(transactionState, expectedTarget);
        String current = requireExistingConfig(client.getConfig(transactionState.dataId, transactionState.group),
                transactionState.target());
        String currentMd5 = md5(current);
        if (!transactionState.testMd5.equalsIgnoreCase(currentMd5)) {
            throw new CasConflictException("Refusing restore because Nacos config no longer matches the test revision");
        }
        if (!client.publishConfigCas(transactionState.dataId, transactionState.group,
                transactionState.originalContent, transactionState.testMd5, "yaml")) {
            throw new CasConflictException("Nacos config changed during restore");
        }
        String restored = awaitConfigMd5(transactionState.target(), transactionState.originalMd5);
        if (!transactionState.originalMd5.equalsIgnoreCase(md5(restored))) {
            throw new CasConflictException("Nacos config could not be verified after restore");
        }
        return new RestoreResult(transactionState.originalMd5, transactionState.testMd5);
    }

    /** Verify that the remote document has returned to the exact original revision. */
    public VerifyResult verifyRestored(Target expectedTarget, Path stateFile) throws Exception {
        Objects.requireNonNull(expectedTarget, "expectedTarget");
        TransactionState state = readState(normalizeStateFile(stateFile));
        requireTargetMatches(state, expectedTarget);
        String current = requireExistingConfig(client.getConfig(state.dataId, state.group), state.target());
        String currentMd5 = md5(current);
        return new VerifyResult(state.originalMd5, currentMd5, state.originalMd5.equalsIgnoreCase(currentMd5));
    }

    /** Verify that the remote document still points at the test revision. */
    public VerifyResult verifyApplied(Target expectedTarget, Path stateFile) throws Exception {
        Objects.requireNonNull(expectedTarget, "expectedTarget");
        TransactionState state = readState(normalizeStateFile(stateFile));
        requireTargetMatches(state, expectedTarget);
        String current = requireExistingConfig(client.getConfig(state.dataId, state.group), state.target());
        String currentMd5 = md5(current);
        return new VerifyResult(state.testMd5, currentMd5, state.testMd5.equalsIgnoreCase(currentMd5));
    }

    private String awaitConfigMd5(Target target, String expectedMd5) throws Exception {
        String latest = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            latest = requireExistingConfig(client.getConfig(target.dataId(), target.group()), target);
            if (expectedMd5.equalsIgnoreCase(md5(latest))) {
                return latest;
            }
            if (attempt < 29) {
                Thread.sleep(200L);
            }
        }
        throw new CasConflictException("Nacos config could not be verified after the CAS update");
    }

    /**
     * Merge only the allow-listed plugin runtime keys.  All unrelated YAML
     * values remain in the parsed tree, including existing plugin-runtime
     * limits that are not part of the test override.
     */
    static String mergePluginRuntime(String original, PluginRuntimeOverride override) {
        Object rootValue = safeYaml().load(original == null ? "" : original);
        Map<Object, Object> root = asMap(rootValue, "root");
        Map<Object, Object> studio = childMap(root, "studio");
        if (hasText(override.aggregationHome())) {
            studio.put("aggregation-home", override.aggregationHome());
        }
        if (hasText(override.runtimeVersion())) {
            studio.put("runtime-version", override.runtimeVersion());
        }
        if (hasText(override.objectStorageEndpoint())) {
            if (!OFFLINE_TEST_ENDPOINT.equals(override.objectStorageEndpoint().trim())) {
                throw new IllegalArgumentException("Only the fixed loopback OSS outage endpoint is approved");
            }
            childMap(studio, "object-storage").put("endpoint", OFFLINE_TEST_ENDPOINT);
        }
        Map<Object, Object> runtime = childMap(studio, "plugin-runtime");
        // The operator is intentionally incapable of publishing an eager override.
        runtime.put("mode", "LAZY_OBJECT_STORAGE");
        putText(runtime, "bucket", override.bucket());
        putText(runtime, "prefix", override.prefix());
        putText(runtime, "channel", override.channel());
        putNumber(runtime, "refresh-interval-seconds", override.refreshIntervalSeconds());
        putNumber(runtime, "refresh-jitter-seconds", override.refreshJitterSeconds());
        putNumber(runtime, "cold-load-timeout-seconds", override.coldLoadTimeoutSeconds());

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(false);
        options.setIndent(2);
        options.setWidth(160);
        return new Yaml(options).dump(root);
    }

    /**
     * The acceptance operator is intentionally Aliyun-only. The merged
     * plugin-runtime bucket must be the same bucket already configured for
     * object storage so the temporary override cannot redirect credentials to
     * a different provider or bucket.
     */
    static void requireApprovedObjectStorageProvenance(String merged) {
        Object rootValue = safeYaml().load(merged == null ? "" : merged);
        Map<Object, Object> root = provenanceMap(rootValue);
        Map<Object, Object> studio = provenanceMap(root.get("studio"));
        Map<Object, Object> objectStorage = provenanceMap(studio.get("object-storage"));
        Map<Object, Object> pluginRuntime = provenanceMap(studio.get("plugin-runtime"));

        String provider = provenanceText(objectStorage.get("provider")).toLowerCase(Locale.ROOT);
        boolean aliyun = provider.equals("oss") || provider.equals("aliyun") || provider.equals("aliyun_oss")
                || provider.equals("aliyun-oss");
        String storageBucket = provenanceText(objectStorage.get("bucket"));
        String runtimeBucket = provenanceText(pluginRuntime.get("bucket"));
        if (!aliyun || storageBucket.isEmpty() || runtimeBucket.isEmpty()
                || !storageBucket.equals(runtimeBucket)) {
            throw new IllegalStateException("Nacos plugin-runtime object storage provenance is not approved");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> provenanceMap(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Nacos plugin-runtime object storage provenance is not approved");
        }
        return (Map<Object, Object>) value;
    }

    private static String provenanceText(Object value) {
        return value instanceof String text ? text.trim() : "";
    }

    private static void putText(Map<Object, Object> map, String key, String value) {
        if (hasText(value)) {
            map.put(key, value);
        }
    }

    private static void putNumber(Map<Object, Object> map, String key, Integer value) {
        if (value != null) {
            if (value < 0) {
                throw new IllegalArgumentException(key + " must not be negative");
            }
            map.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> asMap(Object value, String path) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Nacos YAML " + path + " must be an object");
        }
        return (Map<Object, Object>) value;
    }

    private static Map<Object, Object> childMap(Map<Object, Object> parent, String key) {
        Object current = parent.get(key);
        if (current == null) {
            Map<Object, Object> child = new LinkedHashMap<>();
            parent.put(key, child);
            return child;
        }
        return asMap(current, key);
    }

    private TransactionState readState(Path stateFile) throws IOException {
        requireSecureStateFile(stateFile);
        TransactionState state = objectMapper.readValue(stateFile.toFile(), TransactionState.class);
        if (state == null || state.schemaVersion != STATE_SCHEMA_VERSION || !hasText(state.dataId)
                || !hasText(state.group) || !hasText(state.originalMd5) || !hasText(state.testMd5)
                || state.originalContent == null || state.override == null) {
            throw new IllegalArgumentException("Invalid Nacos plugin-runtime transaction state");
        }
        String calculatedOriginalMd5 = md5(state.originalContent);
        String calculatedTestMd5 = md5(mergePluginRuntime(state.originalContent, state.override));
        if (!state.originalMd5.equalsIgnoreCase(calculatedOriginalMd5)
                || !state.testMd5.equalsIgnoreCase(calculatedTestMd5)) {
            throw new IllegalArgumentException("Nacos plugin-runtime transaction state failed integrity validation");
        }
        return state;
    }

    private static void requireTargetMatches(TransactionState state, Target expectedTarget) {
        if (!expectedTarget.dataId().equals(state.dataId) || !expectedTarget.group().equals(state.group)
                || !Objects.equals(normalizeNamespace(expectedTarget.namespace()), normalizeNamespace(state.namespace))) {
            throw new IllegalStateException("Nacos transaction state does not match the configured target");
        }
    }

    private static String normalizeNamespace(String namespace) {
        return namespace == null ? "" : namespace.trim();
    }

    private void writeState(Path stateFile, TransactionState state) throws IOException {
        Path parent = stateFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = createSecureTemporaryFile(parent == null ? Path.of(".") : parent,
                stateFile.getFileName().toString());
        boolean moved = false;
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
            try {
                // No REPLACE_EXISTING is intentional: a concurrent operator must never
                // overwrite the recovery state created by the first invocation.
                Files.move(temporary, stateFile);
            }
            catch (java.nio.file.FileAlreadyExistsException ex) {
                throw existingStateException();
            }
            moved = true;
            requireSecureStateFile(stateFile);
        }
        finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Path createSecureTemporaryFile(Path parent, String prefix) throws IOException {
        PosixFileAttributeView posixView = Files.getFileAttributeView(parent, PosixFileAttributeView.class);
        Path temporary;
        if (posixView != null) {
            temporary = Files.createTempFile(parent, prefix, ".tmp",
                    PosixFilePermissions.asFileAttribute(ownerReadWritePermissions()));
        }
        else {
            temporary = Files.createTempFile(parent, prefix, ".tmp");
            applyOwnerOnlyAcl(temporary);
        }
        requireSecureStateFile(temporary);
        return temporary;
    }

    private static void requireSecureStateFile(Path stateFile) throws IOException {
        if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Nacos transaction state must be a regular file");
        }
        PosixFileAttributeView posixView = Files.getFileAttributeView(stateFile,
                PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posixView != null) {
            Set<PosixFilePermission> permissions = posixView.readAttributes().permissions();
            if (!ownerReadWritePermissions().equals(permissions)) {
                throw new IOException("Nacos transaction state permissions are not owner-only");
            }
            return;
        }
        AclFileAttributeView aclView = Files.getFileAttributeView(stateFile,
                AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (aclView == null || !hasOwnerOnlyAcl(aclView)) {
            throw new IOException("Nacos transaction state ACL is not owner-only");
        }
    }

    private static void applyOwnerOnlyAcl(Path stateFile) throws IOException {
        AclFileAttributeView aclView = Files.getFileAttributeView(stateFile,
                AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (aclView == null) {
            throw new IOException("Nacos transaction state filesystem has no supported owner-only permissions");
        }
        UserPrincipal owner = aclView.getOwner();
        AclEntry ownerEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        aclView.setAcl(List.of(ownerEntry));
    }

    private static boolean hasOwnerOnlyAcl(AclFileAttributeView aclView) throws IOException {
        UserPrincipal owner = aclView.getOwner();
        List<AclEntry> entries = aclView.getAcl();
        return !entries.isEmpty() && entries.stream().allMatch(entry -> owner.equals(entry.principal()));
    }

    private static Set<PosixFilePermission> ownerReadWritePermissions() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private static IllegalStateException existingStateException() {
        return new IllegalStateException("Refusing to replace an existing Nacos transaction state");
    }

    private static Path normalizeStateFile(Path stateFile) {
        if (stateFile == null) {
            throw new IllegalArgumentException("state file is required");
        }
        return stateFile.toAbsolutePath().normalize();
    }

    private static String requireExistingConfig(String value, Target target) {
        if (value == null) {
            throw new IllegalStateException("Nacos transaction target config is absent");
        }
        return value;
    }

    private static String md5(String value) {
        return NacosMd5Support.md5(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static Yaml safeYaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(loaderOptions, dumperOptions);
    }

    public interface ConfigClient {
        String getConfig(String dataId, String group) throws Exception;

        boolean publishConfigCas(String dataId, String group, String content, String expectedMd5, String type)
                throws Exception;
    }

    public record Target(String dataId, String group, String namespace) {
        public Target {
            if (!hasText(dataId) || !hasText(group)) {
                throw new IllegalArgumentException("Nacos dataId and group are required");
            }
        }
    }

    public record PluginRuntimeOverride(String aggregationHome, String runtimeVersion, String bucket, String prefix,
                                         String channel, Integer refreshIntervalSeconds, Integer refreshJitterSeconds,
                                         Integer coldLoadTimeoutSeconds, String objectStorageEndpoint) {
        public PluginRuntimeOverride {
            if (!hasText(aggregationHome) || !hasText(runtimeVersion) || !hasText(bucket)
                    || !hasText(prefix) || !hasText(channel)) {
                throw new IllegalArgumentException("aggregation home, runtime version and OSS plugin coordinates are required");
            }
            validateNonNegative(refreshIntervalSeconds, "refreshIntervalSeconds");
            validateNonNegative(refreshJitterSeconds, "refreshJitterSeconds");
            validateNonNegative(coldLoadTimeoutSeconds, "coldLoadTimeoutSeconds");
        }

        private static void validateNonNegative(Integer value, String name) {
            if (value != null && value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        }
    }

    public record ApplyResult(String originalMd5, String testMd5) {
    }

    public record RestoreResult(String originalMd5, String testMd5) {
    }

    public record VerifyResult(String expectedMd5, String actualMd5, boolean matches) {
    }

    public static final class CasConflictException extends IllegalStateException {
        public CasConflictException(String message) {
            super(message);
        }
    }

    /** JSON state; originalContent is intentionally retained for exact restore. */
    public static final class TransactionState {
        public int schemaVersion;
        public String createdAt;
        public String dataId;
        public String group;
        public String namespace;
        public String originalMd5;
        public String testMd5;
        public String originalContent;
        public PluginRuntimeOverride override;

        public TransactionState() {
        }

        private static TransactionState prepared(Target target, String original, String originalMd5,
                                                 String testMd5, PluginRuntimeOverride override) {
            TransactionState state = new TransactionState();
            state.schemaVersion = STATE_SCHEMA_VERSION;
            state.createdAt = Instant.now().toString();
            state.dataId = target.dataId();
            state.group = target.group();
            state.namespace = target.namespace();
            state.originalMd5 = originalMd5;
            state.testMd5 = testMd5;
            state.originalContent = original;
            state.override = override;
            return state;
        }

        private Target target() {
            return new Target(dataId, group, namespace);
        }
    }
}
