package com.jdragon.studio.worker.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.pluginloader.JarLoaderCenter;
import com.jdragon.aggregation.pluginloader.constant.SystemConstants;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.CloudObjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectStoragePluginRuntimeResolverTest {
    private String originalHome;
    private String originalPluginHome;
    private String originalCoreConfig;
    private String originalHomeProperty;

    @BeforeEach
    void rememberAggregationRuntime() {
        originalHome = SystemConstants.HOME;
        originalPluginHome = SystemConstants.PLUGIN_HOME;
        originalCoreConfig = SystemConstants.CORE_CONFIG;
        originalHomeProperty = System.getProperty("aggregation.home");
    }

    @AfterEach
    void restoreAggregationRuntime() {
        SystemConstants.HOME = originalHome;
        SystemConstants.PLUGIN_HOME = originalPluginHome;
        SystemConstants.CORE_CONFIG = originalCoreConfig;
        if (originalHomeProperty == null) {
            System.clearProperty("aggregation.home");
        } else {
            System.setProperty("aggregation.home", originalHomeProperty);
        }
    }

    @Test
    void concurrentColdLoadsShareOneDownload(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", false), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        ExecutorService executor = Executors.newFixedThreadPool(32);
        try {
            resolver.initialize();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResolvedPlugin>> futures = new ArrayList<Future<ResolvedPlugin>>();
            for (int index = 0; index < 32; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return resolver.resolve(SourcePluginType.SOURCE, "demo");
                }));
            }
            start.countDown();
            String identity = null;
            for (Future<ResolvedPlugin> future : futures) {
                ResolvedPlugin plugin = future.get(10, TimeUnit.SECONDS);
                identity = identity == null ? plugin.getIdentity() : identity;
                assertEquals(identity, plugin.getIdentity());
                assertTrue(Files.isDirectory(plugin.getDirectory()));
            }

            assertEquals(1, storage.downloads.get());
            assertTrue(Files.isRegularFile(tempDirectory.resolve("conf").resolve("core.json")));
            assertEquals(1, resolver.statusSnapshot().get("cachedReleaseCount"));
        } finally {
            executor.shutdownNow();
            resolver.shutdown();
        }
    }

    @Test
    void warmResolveNeverTouchesObjectStorage(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", false), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        try {
            resolver.initialize();
            ResolvedPlugin first = resolver.resolve(SourcePluginType.SOURCE, "demo");
            int statsAfterColdLoad = storage.stats.get();
            int getsAfterColdLoad = storage.gets.get();
            int downloadsAfterColdLoad = storage.downloads.get();

            for (int index = 0; index < 100; index++) {
                assertEquals(first.getIdentity(),
                        resolver.resolve(SourcePluginType.SOURCE, "demo").getIdentity());
            }

            assertEquals(statsAfterColdLoad, storage.stats.get());
            assertEquals(getsAfterColdLoad, storage.gets.get());
            assertEquals(downloadsAfterColdLoad, storage.downloads.get());
            assertEquals(0, storage.availableCalls.get());
        } finally {
            resolver.shutdown();
        }
    }

    @Test
    void taskSessionStaysPinnedAndBadRefreshKeepsLastKnownGood(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", false), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        PluginRuntimeSession firstTask = PluginRuntimeSession.createDetached();
        PluginRuntimeSession nextTask = PluginRuntimeSession.createDetached();
        try {
            resolver.initialize();
            ResolvedPlugin v1 = firstTask.call(() ->
                    com.jdragon.aggregation.pluginloader.LoadUtil.resolvePlugin(SourcePluginType.SOURCE, "demo"));

            storage.publish("v2", pluginZip("demo", false, "test-jar-v2"), null);
            ResolvedPlugin activatedV2 = resolver.refreshNow(SourcePluginType.SOURCE, "demo")
                    .get(10, TimeUnit.SECONDS);
            ResolvedPlugin stillV1 = firstTask.call(() ->
                    com.jdragon.aggregation.pluginloader.LoadUtil.resolvePlugin(SourcePluginType.SOURCE, "demo"));
            ResolvedPlugin taskV2 = nextTask.call(() ->
                    com.jdragon.aggregation.pluginloader.LoadUtil.resolvePlugin(SourcePluginType.SOURCE, "demo"));

            assertEquals(v1.getIdentity(), stillV1.getIdentity());
            assertEquals(activatedV2.getIdentity(), taskV2.getIdentity());
            assertFalse(v1.getIdentity().equals(taskV2.getIdentity()));

            storage.publish("v3", pluginZip("demo", false), "0".repeat(64));
            ResolvedPlugin afterBadRefresh = resolver.refreshNow(SourcePluginType.SOURCE, "demo")
                    .get(10, TimeUnit.SECONDS);

            assertEquals(taskV2.getIdentity(), afterBadRefresh.getIdentity());
            assertEquals("DEGRADED", resolver.statusSnapshot().get("state"));
            assertEquals(1, resolver.statusSnapshot().get("failedRefreshCount"));
        } finally {
            firstTask.close();
            nextTask.close();
            resolver.shutdown();
        }
    }

    @Test
    void immutableReleaseCannotBeOverwritten(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", false), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        try {
            resolver.initialize();
            ResolvedPlugin original = resolver.resolve(SourcePluginType.SOURCE, "demo");

            storage.publish("v1", pluginZip("demo", false, "overwritten-release"), null);
            ResolvedPlugin afterOverwrite = resolver.refreshNow(SourcePluginType.SOURCE, "demo")
                    .get(10, TimeUnit.SECONDS);
            assertEquals(original.getIdentity(), afterOverwrite.getIdentity());
            assertEquals(1, storage.downloads.get());
            assertEquals("DEGRADED", resolver.statusSnapshot().get("state"));
        } finally {
            resolver.shutdown();
        }
    }

    @Test
    void cachedReleaseSurvivesPointer404(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", false), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        try {
            resolver.initialize();
            ResolvedPlugin original = resolver.resolve(SourcePluginType.SOURCE, "demo");
            storage.failPointerStat = true;

            ResolvedPlugin after404 = resolver.refreshNow(SourcePluginType.SOURCE, "demo")
                    .get(10, TimeUnit.SECONDS);

            assertEquals(original.getIdentity(), after404.getIdentity());
            assertEquals("DEGRADED", resolver.statusSnapshot().get("state"));
            assertEquals(1, resolver.statusSnapshot().get("failedRefreshCount"));
            assertFalse(resolver.statusSnapshot().containsKey("refreshErrors"));
            assertFalse(resolver.statusSnapshot().toString().contains("Object not found"));
        } finally {
            resolver.shutdown();
        }
    }

    @Test
    void uncachedPluginFailsWhenAliyunEndpointIsUnreachable(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        properties.getObjectStorage().setProvider("oss");
        properties.getObjectStorage().setEndpoint("http://127.0.0.1:1");
        properties.getObjectStorage().setAccessKey("offline-probe-access-key");
        properties.getObjectStorage().setSecretKey("offline-probe-secret-key");
        properties.getObjectStorage().setBucket("offline-probe-bucket");
        properties.getObjectStorage().setCreateBucket(false);
        properties.getPluginRuntime().setColdLoadTimeoutSeconds(2);

        ObjectStoragePluginRuntimeResolver resolver = new ObjectStoragePluginRuntimeResolver(
                properties, new CloudObjectStorageService(properties),
                new ObjectMapper().findAndRegisterModules());
        try {
            resolver.initialize();

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> resolver.resolve(SourcePluginType.SOURCE, "uncached-offline-probe"));

            assertTrue(failure.getMessage().contains("cold load"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ("UP".equals(resolver.statusSnapshot().get("state"))
                    && System.nanoTime() < deadline) {
                Thread.sleep(100L);
            }
            assertEquals("DEGRADED", resolver.statusSnapshot().get("state"));
            assertEquals(0, resolver.statusSnapshot().get("activePluginCount"));
            assertFalse(Files.exists(tempDirectory.resolve("cache/source/uncached-offline-probe")));
        } finally {
            resolver.shutdown();
        }
    }

    @Test
    void incompatibleManifestKeepsLastKnownGood(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", false), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        try {
            resolver.initialize();
            ResolvedPlugin original = resolver.resolve(SourcePluginType.SOURCE, "demo");
            storage.publish("v2", pluginZip("demo", false, "test-jar-v2"), null);
            storage.mutatePointer("runtimeVersion", "another-runtime");

            ResolvedPlugin afterRefresh = resolver.refreshNow(SourcePluginType.SOURCE, "demo")
                    .get(10, TimeUnit.SECONDS);

            assertEquals(original.getIdentity(), afterRefresh.getIdentity());
            assertEquals("DEGRADED", resolver.statusSnapshot().get("state"));
            assertEquals(1, resolver.statusSnapshot().get("failedRefreshCount"));
        } finally {
            resolver.shutdown();
        }
    }

    @Test
    void invalidArtifactLimitsAndMissingJarAreRejected(@TempDir Path tempDirectory) throws Exception {
        assertColdLoadRejected(tempDirectory.resolve("artifact-limit"), properties ->
                        properties.getPluginRuntime().setMaxArtifactBytes(16L),
                pluginZip("demo", false));
        assertColdLoadRejected(tempDirectory.resolve("extract-limit"), properties ->
                        properties.getPluginRuntime().setMaxExtractedBytes(8L),
                pluginZip("demo", false));
        assertColdLoadRejected(tempDirectory.resolve("entry-limit"), properties ->
                        properties.getPluginRuntime().setMaxEntryCount(1),
                pluginZip("demo", false));
        assertColdLoadRejected(tempDirectory.resolve("missing-jar"), properties -> {
        }, pluginZipWithoutJar("demo"));
    }

    @Test
    void artifactChangingAfterHeadCannotBypassStreamingLimit(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        byte[] validArtifact = pluginZip("demo", false);
        storage.publish("v1", validArtifact, null);
        storage.replaceNextArtifactDownload(new byte[validArtifact.length + 1]);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        try {
            resolver.initialize();
            assertThrows(IllegalStateException.class,
                    () -> resolver.resolve(SourcePluginType.SOURCE, "demo"));
            assertEquals(validArtifact.length, storage.lastDownloadLimit);
            assertEquals(0, resolver.statusSnapshot().get("activePluginCount"));
            assertEquals("DEGRADED", resolver.statusSnapshot().get("state"));
        } finally {
            resolver.shutdown();
        }
    }

    @Test
    void prefixCannotEscapeObjectStorageRepository(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        properties.getPluginRuntime().setPrefix("aggregation-plugins/../outside");
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", false), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        try {
            assertThrows(IllegalStateException.class, resolver::initialize);
        } finally {
            resolver.shutdown();
        }
    }

    @Test
    void zipSlipArchiveIsRejectedWithoutEscapingStagingRoot(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", true), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        try {
            resolver.initialize();

            assertThrows(IllegalStateException.class,
                    () -> resolver.resolve(SourcePluginType.SOURCE, "demo"));
            assertFalse(Files.exists(tempDirectory.resolve("escape.txt")));
            assertEquals("DEGRADED", resolver.statusSnapshot().get("state"));
        } finally {
            resolver.shutdown();
        }
    }

    @Test
    void restartRestoresActiveReleaseWithoutReadingObjectStorage(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        FakeObjectStorage firstStorage = new FakeObjectStorage(properties);
        firstStorage.publish("v1", pluginZip("demo", false), null);
        ObjectStoragePluginRuntimeResolver firstResolver = resolver(properties, firstStorage);
        ResolvedPlugin beforeRestart;
        try {
            firstResolver.initialize();
            beforeRestart = firstResolver.resolve(SourcePluginType.SOURCE, "demo");
        } finally {
            firstResolver.shutdown();
        }

        FakeObjectStorage offlineStorage = new FakeObjectStorage(properties);
        ObjectStoragePluginRuntimeResolver restartedResolver = resolver(properties, offlineStorage);
        try {
            restartedResolver.initialize();
            ResolvedPlugin restored = restartedResolver.resolve(SourcePluginType.SOURCE, "demo");

            assertEquals(beforeRestart.getIdentity(), restored.getIdentity());
            assertEquals(beforeRestart.getDirectory(), restored.getDirectory());
            assertEquals(0, offlineStorage.downloads.get());
        } finally {
            restartedResolver.shutdown();
        }
    }

    @Test
    void restartRejectsCachedReleaseFromAnotherRuntimeVersion(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        FakeObjectStorage firstStorage = new FakeObjectStorage(properties);
        firstStorage.publish("v1", pluginZip("demo", false), null);
        ObjectStoragePluginRuntimeResolver firstResolver = resolver(properties, firstStorage);
        try {
            firstResolver.initialize();
            firstResolver.resolve(SourcePluginType.SOURCE, "demo");
        } finally {
            firstResolver.shutdown();
        }

        properties.setRuntimeVersion("runtime-v2");
        FakeObjectStorage offlineStorage = new FakeObjectStorage(properties);
        offlineStorage.failPointerStat = true;
        ObjectStoragePluginRuntimeResolver restartedResolver = resolver(properties, offlineStorage);
        try {
            restartedResolver.initialize();
            assertEquals(0, restartedResolver.statusSnapshot().get("activePluginCount"));
            assertThrows(IllegalStateException.class,
                    () -> restartedResolver.resolve(SourcePluginType.SOURCE, "demo"));
        } finally {
            restartedResolver.shutdown();
        }
    }

    @Test
    void timedOutColdLoadContinuesWarmingCacheInBackground(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        properties.getPluginRuntime().setColdLoadTimeoutSeconds(1);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.downloadDelayMillis = 1500L;
        storage.publish("v1", pluginZip("demo", false), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        try {
            resolver.initialize();
            assertThrows(IllegalStateException.class,
                    () -> resolver.resolve(SourcePluginType.SOURCE, "demo"));

            Thread.sleep(800L);
            ResolvedPlugin warmed = resolver.resolve(SourcePluginType.SOURCE, "demo");
            assertTrue(Files.isDirectory(warmed.getDirectory()));
            assertEquals(1, storage.downloads.get());
        } finally {
            resolver.shutdown();
        }
    }

    @Test
    void cacheCleanupProtectsInUseReleaseAndRetainsTwoAfterRelease(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        properties.getPluginRuntime().setRetainedReleases(2);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", false, "test-jar-v1"), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        PluginRuntimeSession firstTask = PluginRuntimeSession.createDetached();
        try {
            resolver.initialize();
            ResolvedPlugin v1 = firstTask.call(() ->
                    com.jdragon.aggregation.pluginloader.LoadUtil.resolvePlugin(SourcePluginType.SOURCE, "demo"));

            Thread.sleep(20L);
            storage.publish("v2", pluginZip("demo", false, "test-jar-v2"), null);
            resolver.refreshNow(SourcePluginType.SOURCE, "demo").get(10, TimeUnit.SECONDS);
            Thread.sleep(20L);
            storage.publish("v3", pluginZip("demo", false, "test-jar-v3"), null);
            resolver.refreshNow(SourcePluginType.SOURCE, "demo").get(10, TimeUnit.SECONDS);

            assertTrue(Files.isDirectory(v1.getDirectory()), "an in-use release must survive cleanup");
            assertEquals(3, resolver.statusSnapshot().get("cachedReleaseCount"));

            firstTask.close();
            Thread.sleep(20L);
            storage.publish("v4", pluginZip("demo", false, "test-jar-v4"), null);
            ResolvedPlugin v4 = resolver.refreshNow(SourcePluginType.SOURCE, "demo")
                    .get(10, TimeUnit.SECONDS);

            assertFalse(Files.exists(v1.getDirectory()), "a released stale revision may be collected");
            assertTrue(Files.isDirectory(v4.getDirectory()));
            assertEquals(2, resolver.statusSnapshot().get("cachedReleaseCount"));
        } finally {
            if (!firstTask.isClosed()) {
                firstTask.close();
            }
            resolver.shutdown();
        }
    }

    @Test
    void cacheLimitRejectsIncomingReleaseWithoutDeletingActiveOrInUseCache(
            @TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        properties.getPluginRuntime().setRetainedReleases(1);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", false, "test-jar-v1"), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        PluginRuntimeSession task = PluginRuntimeSession.createDetached();
        try {
            resolver.initialize();
            ResolvedPlugin v1 = task.call(() ->
                    com.jdragon.aggregation.pluginloader.LoadUtil.resolvePlugin(SourcePluginType.SOURCE, "demo"));
            properties.getPluginRuntime().setCacheMaxBytes(directoryBytes(v1.getDirectory()));

            storage.publish("v2", pluginZip("demo", false, "test-jar-v2-is-larger"), null);
            ResolvedPlugin afterRejectedRefresh = resolver.refreshNow(SourcePluginType.SOURCE, "demo")
                    .get(10, TimeUnit.SECONDS);

            assertEquals(v1.getIdentity(), afterRejectedRefresh.getIdentity());
            assertTrue(Files.isDirectory(v1.getDirectory()), "the active release must survive capacity rejection");
            assertTrue(JarLoaderCenter.isDirectoryInUse(v1.getDirectory()),
                    "the task lease must remain usable after capacity rejection");
            assertEquals(1, resolver.statusSnapshot().get("cachedReleaseCount"));
            assertEquals("DEGRADED", resolver.statusSnapshot().get("state"));
            assertEquals(1, resolver.statusSnapshot().get("failedRefreshCount"));
            assertEquals("v1", new ObjectMapper().readTree(tempDirectory.resolve(".state")
                    .resolve("source").resolve("demo.json").toFile()).path("release").asText());
            assertEquals(2, storage.downloads.get());
        } finally {
            if (!task.isClosed()) {
                task.close();
            }
            JarLoaderCenter.clearJarLoader();
            resolver.shutdown();
        }
    }

    @Test
    void cacheLimitEvictsStaleReleaseBeforePublishingIncomingRelease(
            @TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        properties.getPluginRuntime().setRetainedReleases(3);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", false, "test-jar-v1"), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        try {
            resolver.initialize();
            ResolvedPlugin v1 = resolver.resolve(SourcePluginType.SOURCE, "demo");
            storage.publish("v2", pluginZip("demo", false, "test-jar-v2"), null);
            ResolvedPlugin v2 = resolver.refreshNow(SourcePluginType.SOURCE, "demo")
                    .get(10, TimeUnit.SECONDS);
            properties.getPluginRuntime().setCacheMaxBytes(Math.addExact(
                    directoryBytes(v1.getDirectory()), directoryBytes(v2.getDirectory())));

            storage.publish("v3", pluginZip("demo", false, "test-jar-v3"), null);
            ResolvedPlugin v3 = resolver.refreshNow(SourcePluginType.SOURCE, "demo")
                    .get(10, TimeUnit.SECONDS);

            assertFalse(Files.exists(v1.getDirectory()), "a stale release may be removed to make capacity");
            assertTrue(Files.isDirectory(v2.getDirectory()));
            assertTrue(Files.isDirectory(v3.getDirectory()));
            assertEquals(2, resolver.statusSnapshot().get("cachedReleaseCount"));
            assertEquals("UP", resolver.statusSnapshot().get("state"));
        } finally {
            resolver.shutdown();
        }
    }

    @Test
    void resolveAndLeaseAreAtomicWithReleaseCleanup(@TempDir Path tempDirectory) throws Exception {
        StudioPlatformProperties properties = lazyProperties(tempDirectory);
        properties.getPluginRuntime().setRetainedReleases(1);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", pluginZip("demo", false, "test-jar-v1"), null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch v1Resolved = new CountDownLatch(1);
        CountDownLatch acquireLease = new CountDownLatch(1);
        AtomicReference<JarLoaderCenter.LoaderLease> lease = new AtomicReference<JarLoaderCenter.LoaderLease>();
        try {
            resolver.initialize();
            ResolvedPlugin v1 = resolver.resolve(SourcePluginType.SOURCE, "demo");

            Future<ResolvedPlugin> resolving = executor.submit(() -> resolver.withResolvedPlugin(
                    SourcePluginType.SOURCE, "demo", resolved -> {
                        v1Resolved.countDown();
                        try {
                            assertTrue(acquireLease.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                        lease.set(JarLoaderCenter.acquire(resolved));
                        return resolved;
                    }));
            assertTrue(v1Resolved.await(5, TimeUnit.SECONDS));

            storage.publish("v2", pluginZip("demo", false, "test-jar-v2"), null);
            CompletableFuture<ResolvedPlugin> v2Refresh = resolver.refreshNow(SourcePluginType.SOURCE, "demo");
            acquireLease.countDown();

            assertEquals(v1.getIdentity(), resolving.get(10, TimeUnit.SECONDS).getIdentity());
            ResolvedPlugin v2 = v2Refresh.get(10, TimeUnit.SECONDS);
            assertFalse(v1.getIdentity().equals(v2.getIdentity()));
            assertTrue(Files.isDirectory(v1.getDirectory()));
            assertTrue(JarLoaderCenter.isDirectoryInUse(v1.getDirectory()));

            lease.get().close();
            storage.publish("v3", pluginZip("demo", false, "test-jar-v3"), null);
            resolver.refreshNow(SourcePluginType.SOURCE, "demo").get(10, TimeUnit.SECONDS);
            assertFalse(Files.exists(v1.getDirectory()));
        } finally {
            JarLoaderCenter.LoaderLease currentLease = lease.get();
            if (currentLease != null) {
                currentLease.close();
            }
            JarLoaderCenter.clearJarLoader();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            resolver.shutdown();
        }
    }

    private ObjectStoragePluginRuntimeResolver resolver(StudioPlatformProperties properties,
                                                         FakeObjectStorage storage) {
        return new ObjectStoragePluginRuntimeResolver(properties, storage,
                new ObjectMapper().findAndRegisterModules());
    }

    private void assertColdLoadRejected(Path runtimeHome,
                                        java.util.function.Consumer<StudioPlatformProperties> customizer,
                                        byte[] artifact) throws Exception {
        StudioPlatformProperties properties = lazyProperties(runtimeHome);
        customizer.accept(properties);
        FakeObjectStorage storage = new FakeObjectStorage(properties);
        storage.publish("v1", artifact, null);
        ObjectStoragePluginRuntimeResolver resolver = resolver(properties, storage);
        try {
            resolver.initialize();
            assertThrows(IllegalStateException.class,
                    () -> resolver.resolve(SourcePluginType.SOURCE, "demo"));
            assertEquals("DEGRADED", resolver.statusSnapshot().get("state"));
            assertEquals(0, resolver.statusSnapshot().get("activePluginCount"));
        } finally {
            resolver.shutdown();
        }
    }

    private StudioPlatformProperties lazyProperties(Path runtimeHome) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setAggregationHome(runtimeHome.toString());
        properties.setRuntimeVersion("runtime-v1");
        properties.getPluginRuntime().setMode("LAZY_OBJECT_STORAGE");
        properties.getPluginRuntime().setBucket("plugin-bucket");
        properties.getPluginRuntime().setPrefix("aggregation-plugins");
        properties.getPluginRuntime().setChannel("production");
        properties.getPluginRuntime().setRefreshIntervalSeconds(3600);
        properties.getPluginRuntime().setRefreshJitterSeconds(0);
        properties.getPluginRuntime().setColdLoadTimeoutSeconds(5);
        return properties;
    }

    private byte[] pluginZip(String pluginName, boolean zipSlip) throws IOException {
        return pluginZip(pluginName, zipSlip, "test-jar-v1");
    }

    private byte[] pluginZip(String pluginName, boolean zipSlip, String jarContent) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            if (zipSlip) {
                zip.putNextEntry(new ZipEntry("../escape.txt"));
                zip.write("escape".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("plugin.json"));
            zip.write(("{\"name\":\"" + pluginName + "\",\"class\":\"example.Plugin\"}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(pluginName + ".jar"));
            zip.write(jarContent.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private byte[] pluginZipWithoutJar(String pluginName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("plugin.json"));
            zip.write(("{\"name\":\"" + pluginName + "\",\"class\":\"example.Plugin\"}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private long directoryBytes(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            }).sum();
        } catch (java.io.UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    private static final class FakeObjectStorage extends CloudObjectStorageService {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final AtomicInteger downloads = new AtomicInteger();
        private final AtomicInteger stats = new AtomicInteger();
        private final AtomicInteger gets = new AtomicInteger();
        private final AtomicInteger availableCalls = new AtomicInteger();
        private volatile byte[] pointerBytes;
        private volatile byte[] artifactBytes;
        private volatile String pointerEtag;
        private volatile String artifactKey;
        private volatile long downloadDelayMillis;
        private volatile boolean failPointerStat;
        private volatile byte[] nextArtifactBytes;
        private volatile long lastDownloadLimit = -1L;

        private FakeObjectStorage(StudioPlatformProperties properties) {
            super(properties);
        }

        private void publish(String release, byte[] artifact, String shaOverride) throws Exception {
            String sha256 = shaOverride == null ? sha256(artifact) : shaOverride;
            this.artifactBytes = artifact;
            this.artifactKey = "aggregation-plugins/production/source/demo/releases/"
                    + release + "/plugin.zip";
            Map<String, Object> pointer = Map.of(
                    "schemaVersion", 1,
                    "type", "source",
                    "name", "demo",
                    "release", release,
                    "artifact", "releases/" + release + "/plugin.zip",
                    "sha256", sha256,
                    "size", artifact.length,
                    "runtimeVersion", "runtime-v1",
                    "updatedAt", Instant.now().toString());
            this.pointerBytes = objectMapper.writeValueAsBytes(pointer);
            this.pointerEtag = release + "-" + sha256;
        }

        private void mutatePointer(String field, Object value) throws Exception {
            Map<String, Object> pointer = objectMapper.readValue(pointerBytes, LinkedHashMap.class);
            pointer.put(field, value);
            pointerBytes = objectMapper.writeValueAsBytes(pointer);
            pointerEtag = pointerEtag + "-mutated-" + field;
        }

        private void replaceNextArtifactDownload(byte[] bytes) {
            this.nextArtifactBytes = bytes;
        }

        @Override
        public byte[] get(String bucket, String objectKey) {
            gets.incrementAndGet();
            return pointerBytes;
        }

        @Override
        public ObjectInfo stat(String bucket, String objectKey) {
            stats.incrementAndGet();
            if (objectKey.endsWith("/current.json")) {
                if (failPointerStat) {
                    throw new IllegalStateException("Object not found: " + objectKey);
                }
                return new ObjectInfo(pointerBytes.length, pointerEtag, null, Instant.now());
            }
            if (!objectKey.equals(artifactKey)) {
                throw new IllegalStateException("Unexpected artifact key " + objectKey);
            }
            return new ObjectInfo(artifactBytes.length, "artifact-etag", null, Instant.now());
        }

        @Override
        public void downloadTo(String bucket, String objectKey, Path target, long maxBytes) {
            lastDownloadLimit = maxBytes;
            byte[] bytes;
            boolean artifact;
            if (objectKey.endsWith("/current.json")) {
                bytes = pointerBytes;
                artifact = false;
            } else {
                if (!objectKey.equals(artifactKey)) {
                    throw new IllegalStateException("Unexpected artifact key " + objectKey);
                }
                downloads.incrementAndGet();
                bytes = nextArtifactBytes == null ? artifactBytes : nextArtifactBytes;
                nextArtifactBytes = null;
                artifact = true;
            }
            if (bytes.length > maxBytes) {
                throw new IllegalStateException("Object download exceeds configured byte limit");
            }
            try {
                if (artifact && downloadDelayMillis > 0) {
                    Thread.sleep(downloadDelayMillis);
                }
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public boolean available() {
            availableCalls.incrementAndGet();
            return true;
        }

        private String sha256(byte[] bytes) throws Exception {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
    }
}
