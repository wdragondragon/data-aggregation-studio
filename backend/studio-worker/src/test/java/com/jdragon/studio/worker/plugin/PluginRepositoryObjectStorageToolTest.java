package com.jdragon.studio.worker.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.CloudObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRepositoryObjectStorageToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUCKET = "plugin-bucket";
    private static final String BASE = "aggregation-plugins/production";

    @Test
    void repositoryProvenanceRequiresAliyunAndTheSameNacosBackedBucket() {
        StudioPlatformProperties properties = properties();
        properties.getPluginRuntime().setMode("LAZY_OBJECT_STORAGE");
        properties.getObjectStorage().setProvider("oss");
        properties.getObjectStorage().setEndpoint("https://example.invalid");
        properties.getObjectStorage().setAccessKey("test-access");
        properties.getObjectStorage().setSecretKey("test-secret");
        properties.getObjectStorage().setBucket(BUCKET);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("studio.runtime-version", properties.getRuntimeVersion());
        values.put("studio.plugin-runtime.mode", "LAZY_OBJECT_STORAGE");
        values.put("studio.plugin-runtime.bucket", BUCKET);
        values.put("studio.plugin-runtime.prefix", "aggregation-plugins");
        values.put("studio.plugin-runtime.channel", "production");
        values.put("studio.object-storage.provider", "oss");
        values.put("studio.object-storage.endpoint", "https://example.invalid");
        values.put("studio.object-storage.access-key", "test-access");
        values.put("studio.object-storage.secret-key", "test-secret");
        values.put("studio.object-storage.bucket", BUCKET);

        assertTrue(PluginRepositoryObjectStorageTool.matchesEffectiveRepository(
                new MapPropertySource("nacos", values), properties));

        properties.getObjectStorage().setProvider("minio");
        values.put("studio.object-storage.provider", "minio");
        assertFalse(PluginRepositoryObjectStorageTool.matchesEffectiveRepository(
                new MapPropertySource("nacos", values), properties));

        properties.getObjectStorage().setProvider("oss");
        values.put("studio.object-storage.provider", "oss");
        properties.getObjectStorage().setBucket("another-bucket");
        values.put("studio.object-storage.bucket", "another-bucket");
        assertFalse(PluginRepositoryObjectStorageTool.matchesEffectiveRepository(
                new MapPropertySource("nacos", values), properties));
    }

    @Test
    void atomicWriteProbeNeedsNoRepositoryAndLeavesNoObject() throws Exception {
        StudioPlatformProperties properties = properties();
        FakeStorage storage = new FakeStorage(properties);

        PluginRepositoryObjectStorageTool.run(properties, storage,
                "--tool.action=probe-conditions");

        assertEquals(1, storage.operations.stream()
                .filter(operation -> operation.startsWith("PUT_IF_ABSENT ")).count());
        assertEquals(1, storage.operations.stream()
                .filter(operation -> operation.startsWith("PUT ")).count());
        assertEquals(1, storage.operations.stream()
                .filter(operation -> operation.startsWith("DELETE ")).count());
        assertTrue(storage.objects.isEmpty());
    }

    @Test
    void publishUploadsAndVerifiesEveryArtifactBeforePublishingAnyPointer(@TempDir Path temp) throws Exception {
        StudioPlatformProperties properties = properties();
        FakeStorage storage = new FakeStorage(properties);
        Path repository = repository(temp.resolve("repository"), "test-v1",
                List.of("reader/demo_reader", "source/demo_source"));
        Path state = temp.resolve("backup-state.json");

        run(properties, storage, "backup", repository, state);
        storage.operations.clear();
        run(properties, storage, "publish", repository, state);

        List<String> writes = storage.operations.stream()
                .filter(operation -> operation.startsWith("PUT"))
                .toList();
        assertEquals(4, writes.size());
        assertTrue(writes.get(0).contains("/releases/test-v1/plugin.zip"));
        assertTrue(writes.get(1).contains("/releases/test-v1/plugin.zip"));
        assertTrue(writes.get(2).endsWith("/current.json"));
        assertTrue(writes.get(3).endsWith("/current.json"));

        assertRepositoryEqualsRemote(repository, storage);
    }

    @Test
    void immutableArtifactMismatchStopsBeforeAnyPointerWrite(@TempDir Path temp) throws Exception {
        StudioPlatformProperties properties = properties();
        FakeStorage storage = new FakeStorage(properties);
        Path repository = repository(temp.resolve("repository"), "test-v1", List.of("source/demo"));
        Path state = temp.resolve("backup-state.json");
        run(properties, storage, "backup", repository, state);

        byte[] expected = Files.readAllBytes(repository.resolve("source/demo/releases/test-v1/plugin.zip"));
        byte[] different = expected.clone();
        different[0] ^= 1;
        storage.seed(BASE + "/source/demo/releases/test-v1/plugin.zip", different);
        storage.operations.clear();

        assertThrows(IllegalStateException.class,
                () -> run(properties, storage, "publish", repository, state));
        assertFalse(storage.operations.stream().anyMatch(operation -> operation.endsWith("/current.json")
                && operation.startsWith("PUT")));
    }

    @Test
    void restoreRejectsThirdPartyPointerWithoutMutatingAnyPointer(@TempDir Path temp) throws Exception {
        StudioPlatformProperties properties = properties();
        FakeStorage storage = new FakeStorage(properties);
        List<String> coordinates = List.of("reader/demo_reader", "source/demo_source");
        Path original = repository(temp.resolve("original"), "original-v1", coordinates);
        Path test = repository(temp.resolve("test"), "test-v1", coordinates);
        seedPointers(original, storage);
        Path state = temp.resolve("backup-state.json");
        run(properties, storage, "backup", test, state);
        run(properties, storage, "publish", test, state);

        Path thirdParty = repository(temp.resolve("third-party"), "third-party-v1",
                List.of("source/demo_source"));
        storage.seed(currentKey("source/demo_source"),
                Files.readAllBytes(thirdParty.resolve("source/demo_source/current.json")));
        byte[] readerBefore = storage.bytes(currentKey("reader/demo_reader"));
        byte[] sourceBefore = storage.bytes(currentKey("source/demo_source"));
        storage.operations.clear();

        assertThrows(IllegalStateException.class,
                () -> run(properties, storage, "restore", test, state));
        assertFalse(storage.operations.stream().anyMatch(operation -> operation.startsWith("PUT")
                || operation.startsWith("DELETE ")));
        assertArrayEquals(readerBefore, storage.bytes(currentKey("reader/demo_reader")));
        assertArrayEquals(sourceBefore, storage.bytes(currentKey("source/demo_source")));
    }

    @Test
    void restoreReinstatesExistingPointerAndDeletesOriginallyAbsentPointer(@TempDir Path temp) throws Exception {
        StudioPlatformProperties properties = properties();
        FakeStorage storage = new FakeStorage(properties);
        List<String> coordinates = List.of("reader/demo_reader", "source/demo_source");
        Path original = repository(temp.resolve("original"), "original-v1",
                List.of("reader/demo_reader"));
        Path test = repository(temp.resolve("test"), "test-v1", coordinates);
        seedPointers(original, storage);
        byte[] originalReader = storage.bytes(currentKey("reader/demo_reader"));
        Path state = temp.resolve("backup-state.json");

        run(properties, storage, "backup", test, state);
        run(properties, storage, "publish", test, state);
        run(properties, storage, "restore", test, state);

        assertArrayEquals(originalReader, storage.bytes(currentKey("reader/demo_reader")));
        assertFalse(storage.exists(BUCKET, currentKey("source/demo_source")));
    }

    @Test
    void legacyBackupStateWithoutNewMetadataStillRestores(@TempDir Path temp) throws Exception {
        StudioPlatformProperties properties = properties();
        FakeStorage storage = new FakeStorage(properties);
        Path original = repository(temp.resolve("original"), "original-v1", List.of("source/demo"));
        Path test = repository(temp.resolve("test"), "test-v1", List.of("source/demo"));
        seedPointers(original, storage);
        byte[] originalPointer = storage.bytes(currentKey("source/demo"));
        Path state = temp.resolve("backup-state.json");
        run(properties, storage, "backup", test, state);
        run(properties, storage, "publish", test, state);

        ObjectNode legacy = (ObjectNode) MAPPER.readTree(state.toFile());
        legacy.remove("schemaVersion");
        legacy.remove("backupDirectory");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(state.toFile(), legacy);
        run(properties, storage, "restore", test, state);

        assertArrayEquals(originalPointer, storage.bytes(currentKey("source/demo")));
    }

    @Test
    void tamperedBackupStateIsRejectedBeforeRemoteMutation(@TempDir Path temp) throws Exception {
        StudioPlatformProperties properties = properties();
        FakeStorage storage = new FakeStorage(properties);
        Path repository = repository(temp.resolve("repository"), "test-v1", List.of("source/demo"));
        Path state = temp.resolve("backup-state.json");
        run(properties, storage, "backup", repository, state);
        run(properties, storage, "publish", repository, state);

        ObjectNode root = (ObjectNode) MAPPER.readTree(state.toFile());
        ((ObjectNode) root.withArray("entries").get(0)).put("remoteKey", BASE + "/source/other/current.json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(state.toFile(), root);
        storage.operations.clear();

        assertThrows(IllegalStateException.class,
                () -> run(properties, storage, "restore", repository, state));
        assertFalse(storage.operations.stream().anyMatch(operation -> operation.startsWith("PUT")
                || operation.startsWith("DELETE ")));
    }

    @Test
    void publishRejectsPointerChangedAfterBackupBeforeUploadingArtifacts(@TempDir Path temp) throws Exception {
        StudioPlatformProperties properties = properties();
        FakeStorage storage = new FakeStorage(properties);
        Path repository = repository(temp.resolve("repository"), "test-v1",
                List.of("reader/demo_reader", "source/demo_source"));
        Path state = temp.resolve("backup-state.json");
        run(properties, storage, "backup", repository, state);

        Path thirdParty = repository(temp.resolve("third-party"), "third-party-v1",
                List.of("source/demo_source"));
        storage.seed(currentKey("source/demo_source"),
                Files.readAllBytes(thirdParty.resolve("source/demo_source/current.json")));
        storage.operations.clear();

        assertThrows(IllegalStateException.class,
                () -> run(properties, storage, "publish", repository, state));
        assertFalse(storage.operations.stream().anyMatch(operation -> operation.startsWith("PUT")));
    }

    @Test
    void partialPublishPersistsProgressAndCanBeRetried(@TempDir Path temp) throws Exception {
        StudioPlatformProperties properties = properties();
        FakeStorage storage = new FakeStorage(properties);
        List<String> coordinates = List.of("reader/demo_reader", "source/demo_source");
        Path repository = repository(temp.resolve("repository"), "test-v1", coordinates);
        Path state = temp.resolve("backup-state.json");
        run(properties, storage, "backup", repository, state);
        storage.failOnceOnPut(currentKey("source/demo_source"));

        assertThrows(IllegalStateException.class,
                () -> run(properties, storage, "publish", repository, state));
        JsonNode partial = MAPPER.readTree(state.toFile());
        assertEquals("PUBLISHED", partial.withArray("entries").get(0).get("phase").asText());

        run(properties, storage, "publish", repository, state);
        assertRepositoryEqualsRemote(repository, storage);
    }

    @Test
    void partialRestorePersistsProgressAndCanBeRetried(@TempDir Path temp) throws Exception {
        StudioPlatformProperties properties = properties();
        FakeStorage storage = new FakeStorage(properties);
        List<String> coordinates = List.of("reader/demo_reader", "source/demo_source");
        Path original = repository(temp.resolve("original"), "original-v1", coordinates);
        Path test = repository(temp.resolve("test"), "test-v1", coordinates);
        seedPointers(original, storage);
        Path state = temp.resolve("backup-state.json");
        run(properties, storage, "backup", test, state);
        run(properties, storage, "publish", test, state);
        storage.failOnceOnPut(currentKey("source/demo_source"));

        assertThrows(IllegalStateException.class,
                () -> run(properties, storage, "restore", test, state));
        JsonNode partial = MAPPER.readTree(state.toFile());
        assertEquals("RESTORED", partial.withArray("entries").get(0).get("phase").asText());

        run(properties, storage, "restore", test, state);
        assertArrayEquals(Files.readAllBytes(original.resolve("reader/demo_reader/current.json")),
                storage.bytes(currentKey("reader/demo_reader")));
        assertArrayEquals(Files.readAllBytes(original.resolve("source/demo_source/current.json")),
                storage.bytes(currentKey("source/demo_source")));
    }

    @Test
    void oneBackupSupportsV1V2RollbackAndFinalOriginalRestore(@TempDir Path temp) throws Exception {
        StudioPlatformProperties properties = properties();
        FakeStorage storage = new FakeStorage(properties);
        Path original = repository(temp.resolve("original"), "original-v1", List.of("source/demo"));
        Path first = repository(temp.resolve("first"), "test-v1", List.of("source/demo"));
        Path second = repository(temp.resolve("second"), "test-v2", List.of("source/demo"));
        seedPointers(original, storage);
        byte[] originalPointer = storage.bytes(currentKey("source/demo"));
        Path state = temp.resolve("backup-state.json");
        run(properties, storage, "backup", first, state);
        run(properties, storage, "publish", first, state);
        assertArrayEquals(Files.readAllBytes(first.resolve("source/demo/current.json")),
                storage.bytes(currentKey("source/demo")));

        run(properties, storage, "publish", second, state);
        assertArrayEquals(Files.readAllBytes(second.resolve("source/demo/current.json")),
                storage.bytes(currentKey("source/demo")));

        run(properties, storage, "publish", first, state);
        assertArrayEquals(Files.readAllBytes(first.resolve("source/demo/current.json")),
                storage.bytes(currentKey("source/demo")));

        run(properties, storage, "restore", second, state);
        assertArrayEquals(originalPointer, storage.bytes(currentKey("source/demo")));
        JsonNode entry = MAPPER.readTree(state.toFile()).withArray("entries").get(0);
        assertEquals(2, entry.withArray("testRevisions").size());
        assertEquals("RESTORED", entry.get("phase").asText());
    }

    @Test
    void invalidPointerInvariantsFailBeforeObjectStorageIsRead(@TempDir Path temp) throws Exception {
        StudioPlatformProperties properties = properties();
        List<java.util.function.Consumer<ObjectNode>> corruptions = List.of(
                pointer -> pointer.put("artifact", "releases/other/plugin.zip"),
                pointer -> pointer.put("size", 1),
                pointer -> pointer.put("runtimeVersion", "another-runtime"),
                pointer -> pointer.put("updatedAt", "not-an-instant"));

        for (int index = 0; index < corruptions.size(); index++) {
            int caseIndex = index;
            FakeStorage storage = new FakeStorage(properties);
            Path repository = repository(temp.resolve("repository-" + caseIndex),
                    "test-v1", List.of("source/demo"));
            Path pointerFile = repository.resolve("source/demo/current.json");
            ObjectNode pointer = (ObjectNode) MAPPER.readTree(pointerFile.toFile());
            corruptions.get(index).accept(pointer);
            MAPPER.writeValue(pointerFile.toFile(), pointer);

            assertThrows(IllegalArgumentException.class,
                    () -> run(properties, storage, "backup", repository,
                            temp.resolve("state-" + caseIndex + ".json")));
            assertTrue(storage.operations.isEmpty());
        }
    }

    @Test
    void atomicMoveFallsBackWhenAtomicMovesAreUnsupported(@TempDir Path temp) throws Exception {
        Path source = temp.resolve("source.tmp");
        Path target = temp.resolve("state.json");
        Files.writeString(source, "new", StandardCharsets.UTF_8);
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        AtomicInteger attempts = new AtomicInteger();

        PluginRepositoryObjectStorageTool.moveWithAtomicFallback(source, target,
                (from, to, options) -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "test fallback");
                    }
                    return Files.move(from, to, options);
                });

        assertEquals(2, attempts.get());
        assertEquals("new", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(source));
    }

    private static StudioPlatformProperties properties() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeVersion("runtime-v1");
        properties.getPluginRuntime().setBucket(BUCKET);
        properties.getPluginRuntime().setPrefix("aggregation-plugins");
        properties.getPluginRuntime().setChannel("production");
        return properties;
    }

    private static Path repository(Path root, String release, List<String> coordinates) throws Exception {
        for (String coordinate : coordinates) {
            String[] parts = coordinate.split("/", -1);
            Path plugin = root.resolve(parts[0]).resolve(parts[1]);
            Path artifact = plugin.resolve("releases").resolve(release).resolve("plugin.zip");
            Files.createDirectories(artifact.getParent());
            byte[] bytes = ("artifact:" + coordinate + ":" + release).getBytes(StandardCharsets.UTF_8);
            Files.write(artifact, bytes);
            Map<String, Object> pointer = new LinkedHashMap<String, Object>();
            pointer.put("schemaVersion", 1);
            pointer.put("type", parts[0]);
            pointer.put("name", parts[1]);
            pointer.put("release", release);
            pointer.put("artifact", "releases/" + release + "/plugin.zip");
            pointer.put("sha256", sha256(bytes));
            pointer.put("size", bytes.length);
            pointer.put("runtimeVersion", "runtime-v1");
            pointer.put("updatedAt", Instant.parse("2026-07-31T00:00:00Z").toString());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(plugin.resolve("current.json").toFile(), pointer);
        }
        return root;
    }

    private static void seedPointers(Path repository, FakeStorage storage) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(repository)) {
            paths.filter(path -> path.getFileName().toString().equals("current.json"))
                    .forEach(path -> {
                        try {
                            Path relative = repository.relativize(path);
                            storage.seed(BASE + "/" + relative.getName(0) + "/" + relative.getName(1)
                                    + "/current.json", Files.readAllBytes(path));
                        } catch (IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    });
        } catch (java.io.UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    private static void assertRepositoryEqualsRemote(Path repository, FakeStorage storage) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(repository)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path relative = repository.relativize(path);
                if (relative.getNameCount() == 3 && relative.getFileName().toString().equals("current.json")) {
                    assertArrayEquals(Files.readAllBytes(path), storage.bytes(BASE + "/" + relative.toString()
                            .replace('\\', '/')));
                } else if (relative.getFileName().toString().equals("plugin.zip")) {
                    String key = BASE + "/" + relative.toString().replace('\\', '/');
                    assertArrayEquals(Files.readAllBytes(path), storage.bytes(key));
                }
            }
        }
    }

    private static String currentKey(String coordinate) {
        return BASE + "/" + coordinate + "/current.json";
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void run(StudioPlatformProperties properties, FakeStorage storage, String action,
                            Path repository, Path state) throws Exception {
        PluginRepositoryObjectStorageTool.run(properties, storage,
                "--tool.action=" + action,
                "--tool.repository=" + repository,
                "--tool.backup-directory=" + state.getParent(),
                "--tool.state-file=" + state);
    }

    private static final class FakeStorage extends CloudObjectStorageService {
        private final Map<String, byte[]> objects = new LinkedHashMap<String, byte[]>();
        private final List<String> operations = new ArrayList<String>();
        private String failOnceOnPutKey;

        private FakeStorage(StudioPlatformProperties properties) {
            super(properties);
        }

        private void seed(String key, byte[] bytes) {
            objects.put(key, bytes.clone());
        }

        private byte[] bytes(String key) {
            byte[] value = objects.get(key);
            return value == null ? null : value.clone();
        }

        private void failOnceOnPut(String key) {
            this.failOnceOnPutKey = key;
        }

        @Override
        public boolean exists(String bucket, String objectKey) {
            operations.add("EXISTS " + objectKey);
            return objects.containsKey(objectKey);
        }

        @Override
        public ObjectInfo stat(String bucket, String objectKey) {
            operations.add("STAT " + objectKey);
            byte[] bytes = require(objectKey);
            return new ObjectInfo(bytes.length, shaUnchecked(bytes), null, Instant.now());
        }

        @Override
        public void downloadTo(String bucket, String objectKey, Path target, long maxBytes) {
            operations.add("DOWNLOAD " + objectKey);
            byte[] bytes = require(objectKey);
            if (bytes.length > maxBytes) {
                throw new IllegalStateException("download limit exceeded");
            }
            try {
                Files.write(target, bytes);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void putFile(String bucket, String objectKey, Path source, String contentType) {
            if (objectKey.equals(failOnceOnPutKey)) {
                failOnceOnPutKey = null;
                throw new IllegalStateException("injected pointer write failure");
            }
            operations.add("PUT " + objectKey);
            try {
                objects.put(objectKey, Files.readAllBytes(source));
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void putFileExistingBucket(String bucket, String objectKey, Path source, String contentType) {
            putFile(bucket, objectKey, source, contentType);
        }

        @Override
        public boolean putFileIfAbsent(String bucket, String objectKey, Path source, String contentType) {
            failPutIfRequested(objectKey);
            operations.add("PUT_IF_ABSENT " + objectKey);
            if (objects.containsKey(objectKey)) {
                return false;
            }
            try {
                objects.put(objectKey, Files.readAllBytes(source));
                return true;
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void delete(String bucket, String objectKey) {
            operations.add("DELETE " + objectKey);
            objects.remove(objectKey);
        }

        private void failPutIfRequested(String objectKey) {
            if (objectKey.equals(failOnceOnPutKey)) {
                failOnceOnPutKey = null;
                throw new IllegalStateException("injected pointer write failure");
            }
        }

        private byte[] require(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                throw new IllegalStateException("Object not found: " + key);
            }
            return bytes;
        }

        private String shaUnchecked(byte[] bytes) {
            try {
                return sha256(bytes);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
