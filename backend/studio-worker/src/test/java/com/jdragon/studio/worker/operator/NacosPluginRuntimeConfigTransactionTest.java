package com.jdragon.studio.worker.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.nacos.compat.support.NacosMd5Support;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NacosPluginRuntimeConfigTransactionTest {

    private static final NacosPluginRuntimeConfigTransaction.Target TARGET =
            new NacosPluginRuntimeConfigTransaction.Target("studio-worker-prod.yaml", "ZCYY_GROUP", "ZCYY");

    private static final String ORIGINAL = """
            server:
              port: 18081
            custom:
              labels:
                - alpha
                - beta
            studio:
              aggregation-home: C:\\old\\aggregation
              runtime-version: old-runtime
              object-storage:
                provider: ALIYUN_OSS
                endpoint: https://original.example.invalid
                bucket: plugin-bucket
                secret-key: keep-this-value
              plugin-runtime:
                mode: EAGER_LOCAL
                max-artifact-bytes: 536870912
                retained-releases: 2
            """;

    private static final NacosPluginRuntimeConfigTransaction.PluginRuntimeOverride OVERRIDE =
            new NacosPluginRuntimeConfigTransaction.PluginRuntimeOverride(
                    "C:\\runtime\\plugin-cache", "1.0_jdk17-SNAPSHOT", "plugin-bucket",
                    "aggregation-plugins", "production", 5, 0, 30, null);

    @TempDir
    Path tempDir;

    @Test
    void shouldMergeOnlyPluginRuntimeValuesAndRestoreExactOriginalContent() throws Exception {
        InMemoryClient client = new InMemoryClient(ORIGINAL);
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);
        Path stateFile = tempDir.resolve("nacos-transaction.json");

        NacosPluginRuntimeConfigTransaction.ApplyResult applied = transaction.apply(TARGET, OVERRIDE, stateFile);

        assertThat(applied.originalMd5()).isEqualTo(NacosMd5Support.md5(ORIGINAL));
        assertThat(Files.isRegularFile(stateFile)).isTrue();
        assertOwnerOnly(stateFile);
        Map<String, Object> root = yaml(client.current);
        assertThat(path(root, "server", "port")).isEqualTo(18081);
        assertThat(path(root, "custom", "labels")).isEqualTo(List.of("alpha", "beta"));
        assertThat(path(root, "studio", "object-storage", "secret-key")).isEqualTo("keep-this-value");
        assertThat(path(root, "studio", "object-storage", "endpoint"))
                .isEqualTo("https://original.example.invalid");
        assertThat(path(root, "studio", "aggregation-home")).isEqualTo("C:\\runtime\\plugin-cache");
        assertThat(path(root, "studio", "runtime-version")).isEqualTo("1.0_jdk17-SNAPSHOT");
        assertThat(path(root, "studio", "plugin-runtime", "mode")).isEqualTo("LAZY_OBJECT_STORAGE");
        assertThat(path(root, "studio", "plugin-runtime", "bucket")).isEqualTo("plugin-bucket");
        assertThat(path(root, "studio", "plugin-runtime", "prefix")).isEqualTo("aggregation-plugins");
        assertThat(path(root, "studio", "plugin-runtime", "channel")).isEqualTo("production");
        assertThat(path(root, "studio", "plugin-runtime", "refresh-interval-seconds")).isEqualTo(5);
        assertThat(path(root, "studio", "plugin-runtime", "refresh-jitter-seconds")).isEqualTo(0);
        assertThat(path(root, "studio", "plugin-runtime", "cold-load-timeout-seconds")).isEqualTo(30);
        assertThat(path(root, "studio", "plugin-runtime", "max-artifact-bytes")).isEqualTo(536870912);
        assertThat(path(root, "studio", "plugin-runtime", "retained-releases")).isEqualTo(2);

        JsonNode state = new ObjectMapper().readTree(stateFile.toFile());
        assertThat(state.path("originalContent").asText()).isEqualTo(ORIGINAL);
        assertThat(state.path("originalMd5").asText()).isEqualTo(applied.originalMd5());
        assertThat(state.path("testMd5").asText()).isEqualTo(applied.testMd5());
        assertThat(client.expectedMd5s).containsExactly(applied.originalMd5());

        NacosPluginRuntimeConfigTransaction.RestoreResult restored = transaction.restore(TARGET, stateFile);

        assertThat(restored.originalMd5()).isEqualTo(applied.originalMd5());
        assertThat(client.current).isEqualTo(ORIGINAL);
        assertThat(client.expectedMd5s).containsExactly(applied.originalMd5(), applied.testMd5());

        NacosPluginRuntimeConfigTransaction.VerifyResult restoredVerification =
                transaction.verifyRestored(TARGET, stateFile);
        assertThat(restoredVerification.matches()).isTrue();
    }

    @Test
    void shouldLeaveRemoteUntouchedWhenApplyCasConflicts() throws Exception {
        InMemoryClient client = new InMemoryClient(ORIGINAL);
        client.failNextCas = true;
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);
        Path stateFile = tempDir.resolve("apply-conflict.json");

        assertThatThrownBy(() -> transaction.apply(TARGET, OVERRIDE, stateFile))
                .isInstanceOf(NacosPluginRuntimeConfigTransaction.CasConflictException.class)
                .hasMessageContaining("changed before");

        assertThat(client.current).isEqualTo(ORIGINAL);
        assertThat(Files.isRegularFile(stateFile)).isTrue();
        JsonNode state = new ObjectMapper().readTree(stateFile.toFile());
        assertThat(state.path("originalContent").asText()).isEqualTo(ORIGINAL);
    }

    @Test
    void shouldRefuseToReplaceAnExistingTransactionState() throws Exception {
        InMemoryClient client = new InMemoryClient(ORIGINAL);
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);
        Path stateFile = tempDir.resolve("existing-state.json");
        Files.writeString(stateFile, "do-not-replace");

        assertThatThrownBy(() -> transaction.apply(TARGET, OVERRIDE, stateFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing Nacos transaction state");

        assertThat(Files.readString(stateFile)).isEqualTo("do-not-replace");
        assertThat(client.current).isEqualTo(ORIGINAL);
        assertThat(client.publishAttempts).isZero();
    }

    @Test
    void shouldRefuseStateCreatedConcurrentlyAfterPreflight() throws Exception {
        InMemoryClient client = new InMemoryClient(ORIGINAL);
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);
        Path stateFile = tempDir.resolve("concurrent-state.json");
        client.beforeNextGet = () -> {
            try {
                Files.writeString(stateFile, "concurrent-owner");
            }
            catch (java.io.IOException ex) {
                throw new UncheckedIOException(ex);
            }
        };

        assertThatThrownBy(() -> transaction.apply(TARGET, OVERRIDE, stateFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing Nacos transaction state");

        assertThat(Files.readString(stateFile)).isEqualTo("concurrent-owner");
        assertThat(client.publishAttempts).isZero();
    }

    @Test
    void shouldRefuseRestoreWhenAnotherPublisherChangedTheTestRevision() throws Exception {
        InMemoryClient client = new InMemoryClient(ORIGINAL);
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);
        Path stateFile = tempDir.resolve("restore-conflict.json");
        transaction.apply(TARGET, OVERRIDE, stateFile);
        String concurrentValue = client.current + "concurrent-change: true\n";
        client.current = concurrentValue;

        assertThatThrownBy(() -> transaction.restore(TARGET, stateFile))
                .isInstanceOf(NacosPluginRuntimeConfigTransaction.CasConflictException.class)
                .hasMessageContaining("Refusing restore");

        assertThat(client.current).isEqualTo(concurrentValue);
        assertThat(client.publishAttempts).isEqualTo(1);
    }

    @Test
    void shouldReportRestoreCasRaceWithoutOverwritingTestRevision() throws Exception {
        InMemoryClient client = new InMemoryClient(ORIGINAL);
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);
        Path stateFile = tempDir.resolve("restore-race.json");
        transaction.apply(TARGET, OVERRIDE, stateFile);
        String applied = client.current;
        client.failNextCas = true;

        assertThatThrownBy(() -> transaction.restore(TARGET, stateFile))
                .isInstanceOf(NacosPluginRuntimeConfigTransaction.CasConflictException.class)
                .hasMessageContaining("during restore");

        assertThat(client.current).isEqualTo(applied);
    }

    @Test
    void shouldRejectStateCreatedForAnotherNamespace() throws Exception {
        InMemoryClient client = new InMemoryClient(ORIGINAL);
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);
        Path stateFile = tempDir.resolve("target-mismatch.json");
        transaction.apply(TARGET, OVERRIDE, stateFile);

        NacosPluginRuntimeConfigTransaction.Target other =
                new NacosPluginRuntimeConfigTransaction.Target(TARGET.dataId(), TARGET.group(), "OTHER");
        assertThatThrownBy(() -> transaction.restore(other, stateFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void shouldRejectScalarStudioYamlRatherThanDiscardIt() {
        InMemoryClient client = new InMemoryClient("studio: scalar\n");
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);

        assertThatThrownBy(() -> transaction.apply(TARGET, OVERRIDE, tempDir.resolve("invalid.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("studio must be an object");
        assertThat(client.publishAttempts).isZero();
    }

    @Test
    void shouldRejectMinioObjectStorageForTheAliyunAcceptanceOperator() {
        InMemoryClient client = new InMemoryClient(ORIGINAL.replace("ALIYUN_OSS", "MINIO"));
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);

        assertThatThrownBy(() -> transaction.apply(TARGET, OVERRIDE, tempDir.resolve("minio.json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Nacos plugin-runtime object storage provenance is not approved");
        assertThat(client.publishAttempts).isZero();
    }

    @Test
    void shouldAcceptTheCanonicalOssProviderName() throws Exception {
        InMemoryClient client = new InMemoryClient(ORIGINAL.replace("ALIYUN_OSS", "oss"));
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);

        transaction.apply(TARGET, OVERRIDE, tempDir.resolve("oss-provider.json"));

        assertThat(client.publishAttempts).isOne();
    }

    @Test
    void shouldApplyOnlyTheFixedLoopbackEndpointAndRestoreTheOriginalEndpoint() throws Exception {
        InMemoryClient client = new InMemoryClient(ORIGINAL);
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);
        Path stateFile = tempDir.resolve("offline-endpoint.json");
        NacosPluginRuntimeConfigTransaction.PluginRuntimeOverride offline =
                new NacosPluginRuntimeConfigTransaction.PluginRuntimeOverride(
                        OVERRIDE.aggregationHome(), OVERRIDE.runtimeVersion(), OVERRIDE.bucket(), OVERRIDE.prefix(),
                        OVERRIDE.channel(), OVERRIDE.refreshIntervalSeconds(), OVERRIDE.refreshJitterSeconds(),
                        OVERRIDE.coldLoadTimeoutSeconds(),
                        NacosPluginRuntimeConfigTransaction.OFFLINE_TEST_ENDPOINT);

        transaction.apply(TARGET, offline, stateFile);

        assertThat(path(yaml(client.current), "studio", "object-storage", "endpoint"))
                .isEqualTo(NacosPluginRuntimeConfigTransaction.OFFLINE_TEST_ENDPOINT);
        transaction.restore(TARGET, stateFile);
        assertThat(client.current).isEqualTo(ORIGINAL);
    }

    @Test
    void shouldRejectAnArbitraryObjectStorageEndpoint() {
        InMemoryClient client = new InMemoryClient(ORIGINAL);
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);
        NacosPluginRuntimeConfigTransaction.PluginRuntimeOverride redirected =
                new NacosPluginRuntimeConfigTransaction.PluginRuntimeOverride(
                        OVERRIDE.aggregationHome(), OVERRIDE.runtimeVersion(), OVERRIDE.bucket(), OVERRIDE.prefix(),
                        OVERRIDE.channel(), OVERRIDE.refreshIntervalSeconds(), OVERRIDE.refreshJitterSeconds(),
                        OVERRIDE.coldLoadTimeoutSeconds(), "https://unapproved.example.invalid");

        assertThatThrownBy(() -> transaction.apply(TARGET, redirected, tempDir.resolve("redirected.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only the fixed loopback OSS outage endpoint is approved");
        assertThat(client.publishAttempts).isZero();
    }

    @Test
    void shouldRejectAPluginBucketThatDiffersFromConfiguredObjectStorageBucket() {
        InMemoryClient client = new InMemoryClient(ORIGINAL.replace("bucket: plugin-bucket", "bucket: another-bucket"));
        NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);

        assertThatThrownBy(() -> transaction.apply(TARGET, OVERRIDE, tempDir.resolve("bucket-mismatch.json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Nacos plugin-runtime object storage provenance is not approved");
        assertThat(client.publishAttempts).isZero();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String content) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return (Map<String, Object>) new Yaml(options).load(content);
    }

    @SuppressWarnings("unchecked")
    private static Object path(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            current = ((Map<String, Object>) current).get(key);
        }
        return current;
    }

    private static void assertOwnerOnly(Path stateFile) throws Exception {
        PosixFileAttributeView posix = Files.getFileAttributeView(stateFile,
                PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            assertThat(posix.readAttributes().permissions()).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(stateFile,
                AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        assertThat(acl).isNotNull();
        java.nio.file.attribute.UserPrincipal owner = acl.getOwner();
        assertThat(acl.getAcl()).isNotEmpty()
                .allMatch(entry -> owner.equals(entry.principal()));
    }

    private static final class InMemoryClient implements NacosPluginRuntimeConfigTransaction.ConfigClient {
        private String current;
        private boolean failNextCas;
        private Runnable beforeNextGet;
        private int publishAttempts;
        private final java.util.ArrayList<String> expectedMd5s = new java.util.ArrayList<>();

        private InMemoryClient(String current) {
            this.current = current;
        }

        @Override
        public String getConfig(String dataId, String group) {
            Runnable callback = beforeNextGet;
            beforeNextGet = null;
            if (callback != null) {
                callback.run();
            }
            return current;
        }

        @Override
        public boolean publishConfigCas(String dataId, String group, String content, String expectedMd5, String type) {
            publishAttempts++;
            expectedMd5s.add(expectedMd5);
            if (failNextCas) {
                failNextCas = false;
                return false;
            }
            if (!NacosMd5Support.md5(current).equalsIgnoreCase(expectedMd5)) {
                return false;
            }
            current = content;
            return true;
        }
    }
}
