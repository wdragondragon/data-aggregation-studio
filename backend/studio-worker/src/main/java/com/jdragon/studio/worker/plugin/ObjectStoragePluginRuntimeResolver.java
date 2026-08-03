package com.jdragon.studio.worker.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.pluginloader.JarLoaderCenter;
import com.jdragon.aggregation.pluginloader.LoadUtil;
import com.jdragon.aggregation.pluginloader.constant.SystemConstants;
import com.jdragon.aggregation.pluginloader.runtime.LocalPluginRuntimeResolver;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeResolver;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeResolvers;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.jdragon.aggregation.pluginloader.type.IPluginType;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.CloudObjectStorageService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Worker-only resolver that materializes immutable plugin releases from object storage. */
@Component
@Slf4j
public class ObjectStoragePluginRuntimeResolver implements PluginRuntimeResolver {
    private static final int STATE_SCHEMA_VERSION = 1;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_POINTER_BYTES = 64 * 1024;
    private static final String CORE_CONFIG = "{\"core\":{\"plugin\":{\"reporter\":[]}}}";
    private static final List<String> PLUGIN_TYPES = List.of("source", "reader", "writer", "transformer", "report");

    private final StudioPlatformProperties properties;
    private final CloudObjectStorageService objectStorage;
    private final ObjectMapper objectMapper;
    private final LocalPluginRuntimeResolver localResolver = new LocalPluginRuntimeResolver();
    private final Set<String> trackedCoordinates = ConcurrentHashMap.newKeySet();
    private final Map<String, ActiveRelease> activeReleases = new ConcurrentHashMap<String, ActiveRelease>();
    private final Map<String, CompletableFuture<ActiveRelease>> refreshes =
            new ConcurrentHashMap<String, CompletableFuture<ActiveRelease>>();
    private final Map<String, String> refreshErrors = new ConcurrentHashMap<String, String>();
    private final Map<String, Integer> refreshFailureCounts = new ConcurrentHashMap<String, Integer>();
    private final Map<String, Instant> nextRefreshAttempts = new ConcurrentHashMap<String, Instant>();
    /**
     * Serializes active-release selection with cache retirement.  A task session leases a
     * resolved directory while holding this monitor, so cleanup cannot delete the old
     * revision between resolve and JarLoaderCenter.acquire.
     */
    private final Object activeReleaseMonitor = new Object();
    /** Directories moved into cache but not yet committed as the active release. */
    private final Set<Path> pendingReleases = new java.util.HashSet<Path>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2, daemonFactory("studio-plugin-download-"));
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            daemonFactory("studio-plugin-refresh-"));

    private volatile Path runtimeHome;
    private volatile Path cacheRoot;
    private volatile Path stagingRoot;
    private volatile Path stateRoot;
    private volatile Instant lastSuccessfulRefreshAt;

    public ObjectStoragePluginRuntimeResolver(StudioPlatformProperties properties,
                                              CloudObjectStorageService objectStorage,
                                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectStorage = objectStorage;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        if (!lazyEnabled()) {
            return;
        }
        try {
            repositoryIdentity();
            runtimeHome = Path.of(requireText(properties.getAggregationHome(), "studio.aggregation-home"))
                    .toAbsolutePath().normalize();
            cacheRoot = runtimeHome.resolve("cache").normalize();
            stagingRoot = runtimeHome.resolve(".staging").normalize();
            stateRoot = runtimeHome.resolve(".state").normalize();
            initializeRuntimeHome();
            configureAggregationRuntimeHome();
            restoreCachedState();
            cleanupCache();
            PluginRuntimeResolvers.install(this);
            running.set(true);
            scheduleNextRefresh();
            log.info("Enabled lazy object-storage plugin runtime at {} with {} cached releases",
                    runtimeHome, activeReleases.size());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize lazy plugin runtime", ex);
        }
    }

    @Override
    public ResolvedPlugin resolve(IPluginType pluginType, String pluginName) {
        return withResolvedPlugin(pluginType, pluginName, Function.identity());
    }

    @Override
    public <T> T withResolvedPlugin(IPluginType pluginType, String pluginName,
                                    Function<ResolvedPlugin, T> action) {
        Objects.requireNonNull(action, "action");
        if (!lazyEnabled()) {
            return action.apply(localResolver.resolve(pluginType, pluginName));
        }
        String coordinate = coordinate(pluginType.getName(), pluginName);
        trackedCoordinates.add(coordinate);
        synchronized (activeReleaseMonitor) {
            ActiveRelease active = activeReleases.get(coordinate);
            if (active != null && isUsable(active)) {
                return action.apply(toResolvedPlugin(pluginType, pluginName, active));
            }
        }
        awaitColdLoad(pluginType.getName(), pluginName, coordinate);
        synchronized (activeReleaseMonitor) {
            ActiveRelease active = activeReleases.get(coordinate);
            if (active == null || !isUsable(active)) {
                throw new IllegalStateException("No usable cached release for " + coordinate);
            }
            return action.apply(toResolvedPlugin(pluginType, pluginName, active));
        }
    }

    public boolean lazyEnabled() {
        return properties.getPluginRuntime() != null && properties.getPluginRuntime().isLazyObjectStorage();
    }

    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> coordinates = new ArrayList<String>(activeReleases.keySet());
            coordinates.sort(String::compareTo);
            for (String coordinate : coordinates) {
                ActiveRelease active = activeReleases.get(coordinate);
                if (active != null) {
                    digest.update((coordinate + "=" + active.identity() + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fingerprint plugin runtime", ex);
        }
    }

    public Map<String, Object> statusSnapshot() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("mode", lazyEnabled() ? "LAZY_OBJECT_STORAGE" : "EAGER_LOCAL");
        status.put("channel", pluginProperties().getChannel());
        status.put("activePluginCount", activeReleases.size());
        status.put("cachedReleaseCount", cachedReleaseCount());
        status.put("trackedPluginCount", trackedCoordinates.size());
        status.put("lastSuccessfulRefreshAt", lastSuccessfulRefreshAt == null ? null : lastSuccessfulRefreshAt.toString());
        status.put("state", refreshErrors.isEmpty() ? "UP" : "DEGRADED");
        // OSS SDK failures can contain endpoint, bucket, key and request metadata. Heartbeats
        // expose only the bounded aggregate; full diagnostics remain in the local Worker log.
        status.put("failedRefreshCount", refreshErrors.size());
        return status;
    }

    public CompletableFuture<ResolvedPlugin> refreshNow(IPluginType pluginType, String pluginName) {
        String coordinate = coordinate(pluginType.getName(), pluginName);
        trackedCoordinates.add(coordinate);
        return refreshAsync(pluginType.getName(), pluginName, false)
                .thenApply(active -> new ResolvedPlugin(pluginType, pluginName,
                        active.directory, active.identity()));
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        scheduler.shutdownNow();
        downloadExecutor.shutdownNow();
        if (lazyEnabled()) {
            PluginRuntimeResolvers.reset();
        }
    }

    private ActiveRelease awaitColdLoad(String type, String name, String coordinate) {
        CompletableFuture<ActiveRelease> future = refreshAsync(type, name, true);
        int timeoutSeconds = positive(pluginProperties().getColdLoadTimeoutSeconds(), 300);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Plugin cold load was interrupted: " + coordinate, ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Plugin cold load timed out after " + timeoutSeconds
                    + " seconds: " + coordinate, ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new IllegalStateException("Plugin cold load failed: " + coordinate + ": " + cause.getMessage(), cause);
        }
    }

    private CompletableFuture<ActiveRelease> refreshAsync(String type, String name, boolean coldLoad) {
        String coordinate = coordinate(type, name);
        Instant nextAttempt = nextRefreshAttempts.get(coordinate);
        ActiveRelease active = activeReleases.get(coordinate);
        if (nextAttempt != null && nextAttempt.isAfter(Instant.now())) {
            if (active != null && isUsable(active)) {
                return CompletableFuture.completedFuture(active);
            }
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Plugin refresh is backing off until " + nextAttempt + ": " + coordinate));
        }
        CompletableFuture<ActiveRelease> created = new CompletableFuture<ActiveRelease>();
        CompletableFuture<ActiveRelease> existing = refreshes.putIfAbsent(coordinate, created);
        if (existing != null) {
            return existing;
        }
        try {
            downloadExecutor.execute(() -> {
                try {
                    created.complete(refresh(type, name, coldLoad));
                } catch (Throwable ex) {
                    created.completeExceptionally(ex);
                } finally {
                    refreshes.remove(coordinate, created);
                }
            });
        } catch (RuntimeException ex) {
            refreshes.remove(coordinate, created);
            created.completeExceptionally(ex);
        }
        return created;
    }

    private ActiveRelease refresh(String type, String name, boolean coldLoad) {
        String coordinate = coordinate(type, name);
        ActiveRelease previous = activeReleases.get(coordinate);
        try {
            String pointerKey = pluginBaseKey(type, name) + "/current.json";
            CloudObjectStorageService.ObjectInfo pointerInfo = objectStorage.stat(bucket(), pointerKey);
            if (pointerInfo.getSize() <= 0 || pointerInfo.getSize() > MAX_POINTER_BYTES) {
                throw new IllegalArgumentException("current.json size is invalid: " + pointerInfo.getSize());
            }
            if (!coldLoad && previous != null && hasText(pointerInfo.getEtag())
                    && pointerInfo.getEtag().equals(previous.pointerEtag)) {
                recordRefreshSuccess(coordinate);
                return previous;
            }
            PluginPointer pointer = readPointer(pointerKey, pointerInfo);
            validatePointer(pointer, type, name);
            if (previous != null && previous.release.equals(pointer.release)
                    && !previous.sha256.equalsIgnoreCase(pointer.sha256)) {
                throw new IllegalArgumentException("Immutable plugin release changed SHA-256: "
                        + coordinate + "@" + pointer.release);
            }
            if (previous != null && previous.matches(pointer) && isUsable(previous)) {
                previous.pointerEtag = pointerInfo.getEtag();
                persistState(previous);
                recordRefreshSuccess(coordinate);
                return previous;
            }
            ActiveRelease downloaded = materialize(pointer, type, name, pointerInfo.getEtag());
            synchronized (activeReleaseMonitor) {
                try {
                    persistState(downloaded);
                    activeReleases.put(coordinate, downloaded);
                    recordRefreshSuccess(coordinate);
                    log.info("Activated plugin release {} -> {}", coordinate, downloaded.release);
                    cleanupCacheLocked();
                    return downloaded;
                } finally {
                    pendingReleases.remove(downloaded.directory);
                }
            }
        } catch (Exception ex) {
            recordRefreshFailure(coordinate, ex);
            if (previous != null && isUsable(previous)) {
                log.warn("Plugin refresh failed; keeping last known good release {}@{}: {}",
                        coordinate, previous.release, rootMessage(ex));
                return previous;
            }
            throw new IllegalStateException("No usable cached release for " + coordinate, ex);
        }
    }

    private ActiveRelease materialize(PluginPointer pointer, String type, String name,
                                      String pointerEtag) throws Exception {
        String artifactKey = resolveArtifactKey(pointer, type, name);
        CloudObjectStorageService.ObjectInfo artifactInfo = objectStorage.stat(bucket(), artifactKey);
        long maxArtifact = positive(pluginProperties().getMaxArtifactBytes(), 512L * 1024L * 1024L);
        if (artifactInfo.getSize() < 0 || artifactInfo.getSize() > maxArtifact) {
            throw new IllegalArgumentException("Plugin artifact exceeds max size: " + artifactInfo.getSize());
        }
        if (pointer.size != null && pointer.size.longValue() != artifactInfo.getSize()) {
            throw new IllegalArgumentException("Plugin artifact size does not match current.json");
        }
        String safeRelease = safeSegment(pointer.release, "release");
        Path destination = cacheRoot.resolve(type).resolve(name)
                .resolve(safeRelease + "-" + pointer.sha256.toLowerCase()).toAbsolutePath().normalize();
        ensureWithin(cacheRoot, destination);
        assertReleaseNotReused(destination.getParent(), safeRelease, destination);
        ActiveRelease cached = reserveCachedRelease(pointer, type, name, pointerEtag,
                artifactKey, destination);
        if (cached != null) {
            return cached;
        }

        Path operation = stagingRoot.resolve(UUID.randomUUID().toString()).toAbsolutePath().normalize();
        ensureWithin(stagingRoot, operation);
        Path zip = operation.resolve("plugin.zip");
        Path content = operation.resolve("content");
        Files.createDirectories(content);
        try {
            objectStorage.downloadTo(bucket(), artifactKey, zip, artifactInfo.getSize());
            long actualSize = Files.size(zip);
            if (actualSize != artifactInfo.getSize() || actualSize > maxArtifact) {
                throw new IllegalArgumentException("Downloaded plugin artifact size is invalid");
            }
            String actualSha = sha256(zip);
            if (!actualSha.equalsIgnoreCase(pointer.sha256)) {
                throw new IllegalArgumentException("Plugin artifact SHA-256 mismatch");
            }
            extract(zip, content);
            validatePluginDirectory(content, type, name);
            long extractedBytes = directorySize(content);
            ActiveRelease downloaded = new ActiveRelease(type, name, pointer.release, actualSha,
                    destination, pointerEtag, artifactKey, Instant.now());
            synchronized (activeReleaseMonitor) {
                if (isValidPluginDirectory(destination, type, name)) {
                    pendingReleases.add(destination);
                    try {
                        requireCacheCapacityLocked(0L);
                    } catch (Exception ex) {
                        pendingReleases.remove(destination);
                        throw ex;
                    }
                    return downloaded;
                }
                if (Files.exists(destination)) {
                    if (pendingReleases.contains(destination)
                            || JarLoaderCenter.isDirectoryInUse(destination)
                            || isActiveDirectory(destination)) {
                        throw new IllegalStateException("Refusing to replace a protected plugin cache directory: "
                                + destination);
                    }
                    deleteTree(destination, cacheRoot);
                }
                requireCacheCapacityLocked(extractedBytes);
                Files.createDirectories(destination.getParent());
                moveAtomically(content, destination);
                pendingReleases.add(destination);
            }
            return downloaded;
        } finally {
            deleteTree(operation, stagingRoot);
        }
    }

    private ActiveRelease reserveCachedRelease(PluginPointer pointer, String type, String name,
                                               String pointerEtag, String artifactKey,
                                               Path destination) throws Exception {
        synchronized (activeReleaseMonitor) {
            if (!isValidPluginDirectory(destination, type, name)) {
                return null;
            }
            pendingReleases.add(destination);
            try {
                requireCacheCapacityLocked(0L);
            } catch (Exception ex) {
                pendingReleases.remove(destination);
                throw ex;
            }
            return new ActiveRelease(type, name, pointer.release, pointer.sha256.toLowerCase(),
                    destination, pointerEtag, artifactKey, Instant.now());
        }
    }

    private PluginPointer readPointer(String pointerKey,
                                      CloudObjectStorageService.ObjectInfo pointerInfo) throws Exception {
        Path pointer = Files.createTempFile(stagingRoot, "current-", ".json");
        try {
            objectStorage.downloadTo(bucket(), pointerKey, pointer, pointerInfo.getSize());
            if (Files.size(pointer) != pointerInfo.getSize()) {
                throw new IllegalArgumentException("Downloaded current.json size is invalid");
            }
            return objectMapper.readValue(pointer.toFile(), PluginPointer.class);
        } finally {
            Files.deleteIfExists(pointer);
        }
    }

    private void extract(Path zip, Path destination) throws Exception {
        long maxExtracted = positive(pluginProperties().getMaxExtractedBytes(), 1024L * 1024L * 1024L);
        int maxEntries = positive(pluginProperties().getMaxEntryCount(), 5000);
        long extracted = 0L;
        int entries = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries++;
                if (entries > maxEntries) {
                    throw new IllegalArgumentException("Plugin archive contains too many entries");
                }
                Path output = destination.resolve(entry.getName()).toAbsolutePath().normalize();
                ensureWithin(destination, output);
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                Files.createDirectories(output.getParent());
                try (OutputStream stream = Files.newOutputStream(output,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        extracted += read;
                        if (extracted > maxExtracted) {
                            throw new IllegalArgumentException("Plugin archive exceeds extracted size limit");
                        }
                        stream.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private void validatePluginDirectory(Path directory, String type, String name) throws Exception {
        String configName = "transformer".equals(type) ? "transformer.json" : "plugin.json";
        Path config = directory.resolve(configName).normalize();
        ensureWithin(directory, config);
        if (!Files.isRegularFile(config)) {
            throw new IllegalArgumentException("Plugin archive is missing " + configName);
        }
        Map<?, ?> metadata = objectMapper.readValue(config.toFile(), Map.class);
        Object configuredName = metadata.get("name");
        if (configuredName == null || !name.equals(String.valueOf(configuredName).trim())) {
            throw new IllegalArgumentException("Plugin metadata name does not match " + name);
        }
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            if (files.anyMatch(Files::isSymbolicLink)) {
                throw new IllegalArgumentException("Plugin archive must not contain symbolic links");
            }
        }
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            if (files.noneMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().toLowerCase().endsWith(".jar"))) {
                throw new IllegalArgumentException("Plugin archive does not contain a plugin JAR");
            }
        }
    }

    private boolean isValidPluginDirectory(Path directory, String type, String name) {
        try {
            validatePluginDirectory(directory, type, name);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void initializeRuntimeHome() throws IOException {
        Files.createDirectories(runtimeHome);
        Files.createDirectories(cacheRoot);
        Files.createDirectories(stagingRoot);
        cleanupStagingRoot();
        Files.createDirectories(stateRoot);
        Path conf = runtimeHome.resolve("conf");
        Files.createDirectories(conf);
        Path core = conf.resolve("core.json");
        if (!Files.exists(core)) {
            Files.writeString(core, CORE_CONFIG, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        Path pluginRoot = runtimeHome.resolve("plugin");
        for (String type : PLUGIN_TYPES) {
            Files.createDirectories(pluginRoot.resolve(type));
        }
    }

    private void cleanupStagingRoot() throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(stagingRoot)) {
            for (Path entry : entries) {
                deleteTree(entry, stagingRoot);
            }
        }
    }

    private void configureAggregationRuntimeHome() {
        String home = runtimeHome.toString();
        System.setProperty("aggregation.home", home);
        SystemConstants.HOME = home;
        SystemConstants.PLUGIN_HOME = runtimeHome.resolve("plugin").toString();
        SystemConstants.CORE_CONFIG = runtimeHome.resolve("conf").resolve("core.json").toString();
        LoadUtil.updateJarLoader();
    }

    private void restoreCachedState() throws IOException {
        if (!Files.isDirectory(stateRoot)) {
            return;
        }
        try (java.util.stream.Stream<Path> states = Files.walk(stateRoot, 3)) {
            states.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            LocalState state = objectMapper.readValue(path.toFile(), LocalState.class);
                            validateLocalState(state);
                            Path directory = Path.of(state.directory).toAbsolutePath().normalize();
                            ensureWithin(cacheRoot, directory);
                            Path expectedDirectory = cacheRoot.resolve(state.type).resolve(state.name)
                                    .resolve(state.release + "-" + state.sha256.toLowerCase())
                                    .toAbsolutePath().normalize();
                            if (!directory.equals(expectedDirectory)) {
                                throw new IllegalArgumentException("Cached plugin directory does not match state");
                            }
                            if (isValidPluginDirectory(directory, state.type, state.name)) {
                                ActiveRelease active = new ActiveRelease(state.type, state.name, state.release,
                                        state.sha256, directory, state.pointerEtag, state.artifact, state.activatedAt);
                                activeReleases.put(coordinate(state.type, state.name), active);
                                trackedCoordinates.add(coordinate(state.type, state.name));
                            }
                        } catch (Exception ex) {
                            log.warn("Ignoring invalid cached plugin state {}: {}", path, rootMessage(ex));
                        }
                    });
        }
    }

    private void persistState(ActiveRelease active) throws IOException {
        Path state = stateRoot.resolve(active.type).resolve(active.name + ".json").toAbsolutePath().normalize();
        ensureWithin(stateRoot, state);
        Files.createDirectories(state.getParent());
        Path temporary = state.resolveSibling(state.getFileName() + ".tmp-" + UUID.randomUUID());
        LocalState value = new LocalState(active, repositoryIdentity(), configuredRuntimeVersion());
        objectMapper.writeValue(temporary.toFile(), value);
        moveAtomically(temporary, state);
    }

    private void validateLocalState(LocalState state) {
        if (state == null || state.schemaVersion == null
                || state.schemaVersion.intValue() != STATE_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported cached plugin state schema");
        }
        if (!repositoryIdentity().equals(state.repository)) {
            throw new IllegalArgumentException("Cached plugin state belongs to another repository");
        }
        if (!configuredRuntimeVersion().equals(state.runtimeVersion)) {
            throw new IllegalArgumentException("Cached plugin state is incompatible with this runtime");
        }
        safeSegment(state.type, "state.type");
        safeSegment(state.name, "state.name");
        safeSegment(state.release, "state.release");
        if (!hasText(state.sha256) || !state.sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Cached plugin state SHA-256 is invalid");
        }
        requireText(state.directory, "state.directory");
    }

    private void scheduleNextRefresh() {
        if (!running.get()) {
            return;
        }
        int base = positive(pluginProperties().getRefreshIntervalSeconds(), 30);
        int jitter = Math.max(0, pluginProperties().getRefreshJitterSeconds() == null
                ? 10 : pluginProperties().getRefreshJitterSeconds());
        long delay = base + (jitter == 0 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextInt(jitter + 1));
        scheduler.schedule(() -> {
            try {
                for (String coordinate : new ArrayList<String>(trackedCoordinates)) {
                    int split = coordinate.indexOf('/');
                    if (split > 0) {
                        refreshAsync(coordinate.substring(0, split), coordinate.substring(split + 1), false);
                    }
                }
            } finally {
                scheduleNextRefresh();
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void cleanupCache() {
        synchronized (activeReleaseMonitor) {
            cleanupCacheLocked();
        }
    }

    private void cleanupCacheLocked() {
        try {
            cleanupCacheLocked(0L);
        } catch (Exception ex) {
            log.warn("Plugin cache cleanup failed: {}", rootMessage(ex));
        }
    }

    private void requireCacheCapacityLocked(long incomingBytes) throws IOException {
        if (incomingBytes < 0L) {
            throw new IllegalArgumentException("Incoming plugin cache size must not be negative");
        }
        long remainingBytes = cleanupCacheLocked(incomingBytes);
        long maxBytes = positive(pluginProperties().getCacheMaxBytes(), 10L * 1024L * 1024L * 1024L);
        if (exceedsCacheLimit(remainingBytes, incomingBytes, maxBytes)) {
            throw new IllegalStateException("Plugin cache limit of " + maxBytes
                    + " bytes cannot accommodate incoming release of " + incomingBytes
                    + " bytes; protected cache uses " + remainingBytes + " bytes");
        }
    }

    private long cleanupCacheLocked(long incomingBytes) throws IOException {
        List<Path> releases = new ArrayList<Path>();
        if (Files.isDirectory(cacheRoot)) {
            try (java.util.stream.Stream<Path> paths = Files.walk(cacheRoot, 3)) {
                paths.filter(Files::isDirectory)
                        .filter(path -> path.getNameCount() == cacheRoot.getNameCount() + 3)
                        .forEach(releases::add);
            }
        }
        releases.sort(Comparator.comparingLong(this::lastModified).reversed());
        int retained = Math.max(1, positive(pluginProperties().getRetainedReleases(), 2));
        Map<String, Integer> keptByCoordinate = new LinkedHashMap<String, Integer>();
        long totalBytes = directorySize(cacheRoot);
        long maxBytes = positive(pluginProperties().getCacheMaxBytes(), 10L * 1024L * 1024L * 1024L);
        for (Path release : releases) {
            String type = release.getParent().getParent().getFileName().toString();
            String name = release.getParent().getFileName().toString();
            String coordinate = coordinate(type, name);
            ActiveRelease active = activeReleases.get(coordinate);
            boolean activeDirectory = active != null && active.directory.equals(release);
            int kept = keptByCoordinate.getOrDefault(coordinate, 0);
            if (activeDirectory || pendingReleases.contains(release)
                    || JarLoaderCenter.isDirectoryInUse(release)) {
                keptByCoordinate.put(coordinate, kept + 1);
                continue;
            }
            boolean overRetention = kept >= retained;
            boolean overSize = exceedsCacheLimit(totalBytes, incomingBytes, maxBytes);
            if (!overRetention && !overSize) {
                keptByCoordinate.put(coordinate, kept + 1);
                continue;
            }
            long size = directorySize(release);
            deleteTree(release, cacheRoot);
            totalBytes = Math.max(0L, totalBytes - size);
        }
        return totalBytes;
    }

    private boolean isActiveDirectory(Path directory) {
        for (ActiveRelease active : activeReleases.values()) {
            if (active.directory.equals(directory)) {
                return true;
            }
        }
        return false;
    }

    private boolean exceedsCacheLimit(long cachedBytes, long incomingBytes, long maxBytes) {
        return incomingBytes > maxBytes || cachedBytes > maxBytes - incomingBytes;
    }

    private long directorySize(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return 0L;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            long total = 0L;
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path)) {
                    long size = Files.size(path);
                    if (Long.MAX_VALUE - total < size) {
                        return Long.MAX_VALUE;
                    }
                    total += size;
                }
            }
            return total;
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private void validatePointer(PluginPointer pointer, String type, String name) {
        if (pointer == null || pointer.schemaVersion == null || pointer.schemaVersion.intValue() != 1) {
            throw new IllegalArgumentException("Unsupported or missing current.json schemaVersion");
        }
        if (!type.equals(pointer.type) || !name.equals(pointer.name)) {
            throw new IllegalArgumentException("current.json plugin coordinate does not match request");
        }
        safeSegment(pointer.release, "release");
        if (!hasText(pointer.sha256) || !pointer.sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("current.json sha256 must contain 64 hexadecimal characters");
        }
        if (!hasText(pointer.artifact)) {
            throw new IllegalArgumentException("current.json artifact is required");
        }
        String expectedArtifact = "releases/" + pointer.release + "/plugin.zip";
        if (!expectedArtifact.equals(pointer.artifact.replace('\\', '/').trim())) {
            throw new IllegalArgumentException("current.json artifact must equal " + expectedArtifact);
        }
        if (pointer.size == null || pointer.size.longValue() <= 0) {
            throw new IllegalArgumentException("current.json size must be positive");
        }
        if (!hasText(pointer.runtimeVersion)) {
            throw new IllegalArgumentException("current.json runtimeVersion is required");
        }
        if (hasText(properties.getRuntimeVersion())
                && !properties.getRuntimeVersion().trim().equals(pointer.runtimeVersion.trim())) {
            throw new IllegalArgumentException("Plugin runtimeVersion " + pointer.runtimeVersion
                    + " is incompatible with Worker runtime " + properties.getRuntimeVersion().trim());
        }
        if (!hasText(pointer.updatedAt)) {
            throw new IllegalArgumentException("current.json updatedAt is required");
        }
        try {
            Instant.parse(pointer.updatedAt.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("current.json updatedAt must be an ISO-8601 instant", ex);
        }
    }

    private void recordRefreshSuccess(String coordinate) {
        lastSuccessfulRefreshAt = Instant.now();
        refreshErrors.remove(coordinate);
        refreshFailureCounts.remove(coordinate);
        nextRefreshAttempts.remove(coordinate);
    }

    private void recordRefreshFailure(String coordinate, Throwable error) {
        refreshErrors.put(coordinate, rootMessage(error));
        int failures = refreshFailureCounts.merge(coordinate, 1, Integer::sum);
        long delaySeconds = Math.min(300L, 5L << Math.min(6, Math.max(0, failures - 1)));
        nextRefreshAttempts.put(coordinate, Instant.now().plusSeconds(delaySeconds));
    }

    private int cachedReleaseCount() {
        if (cacheRoot == null || !Files.isDirectory(cacheRoot)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(cacheRoot, 3)) {
            return (int) paths.filter(Files::isDirectory)
                    .filter(path -> path.getNameCount() == cacheRoot.getNameCount() + 3)
                    .count();
        } catch (IOException ex) {
            return 0;
        }
    }

    private String resolveArtifactKey(PluginPointer pointer, String type, String name) {
        String artifact = pointer.artifact.replace('\\', '/').trim();
        if (artifact.startsWith("/") || artifact.contains("://") || artifact.contains("../")
                || artifact.equals("..")) {
            throw new IllegalArgumentException("current.json artifact must be a relative key below the plugin directory");
        }
        String key = pluginBaseKey(type, name) + "/" + trimSlashes(artifact);
        String requiredPrefix = pluginBaseKey(type, name) + "/releases/";
        if (!key.startsWith(requiredPrefix)) {
            throw new IllegalArgumentException("Plugin artifact must be stored below releases/");
        }
        return key;
    }

    private String pluginBaseKey(String type, String name) {
        return safePrefix() + "/"
                + safeSegment(pluginProperties().getChannel(), "channel") + "/"
                + safeSegment(type, "type") + "/" + safeSegment(name, "name");
    }

    private String bucket() {
        return hasText(pluginProperties().getBucket())
                ? pluginProperties().getBucket().trim() : objectStorage.resolveBucket();
    }

    private String repositoryIdentity() {
        return bucket() + "/" + safePrefix() + "/"
                + safeSegment(pluginProperties().getChannel(), "channel");
    }

    private String configuredRuntimeVersion() {
        return requireText(properties.getRuntimeVersion(), "studio.runtime-version");
    }

    private StudioPlatformProperties.PluginRuntimeProperties pluginProperties() {
        StudioPlatformProperties.PluginRuntimeProperties current = properties.getPluginRuntime();
        return current == null ? new StudioPlatformProperties.PluginRuntimeProperties() : current;
    }

    private boolean isUsable(ActiveRelease active) {
        return active != null && isValidPluginDirectory(active.directory, active.type, active.name);
    }

    private ResolvedPlugin toResolvedPlugin(IPluginType pluginType, String pluginName,
                                            ActiveRelease active) {
        return new ResolvedPlugin(pluginType, pluginName, active.directory, active.identity());
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTree(Path target, Path allowedRoot) throws IOException {
        if (target == null || !Files.exists(target)) {
            return;
        }
        Path normalizedRoot = allowedRoot.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        ensureWithin(normalizedRoot, normalizedTarget);
        if (normalizedTarget.equals(normalizedRoot)) {
            throw new IOException("Refusing to delete plugin runtime root " + normalizedRoot);
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(normalizedTarget)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void ensureWithin(Path root, Path candidate) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            throw new IOException("Illegal plugin runtime path outside " + normalizedRoot + ": " + candidate);
        }
    }

    private void assertReleaseNotReused(Path coordinateRoot, String release, Path destination) throws IOException {
        if (!Files.isDirectory(coordinateRoot)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(coordinateRoot)) {
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                int shaSeparator = fileName.length() - 65;
                if (shaSeparator > 0 && fileName.charAt(shaSeparator) == '-'
                        && release.equals(fileName.substring(0, shaSeparator))
                        && !entry.toAbsolutePath().normalize().equals(destination)) {
                    throw new IllegalArgumentException("Immutable plugin release already exists with another SHA-256: "
                            + release);
                }
            }
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String safeSegment(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[A-Za-z0-9._-]+") || normalized.equals(".") || normalized.equals("..")) {
            throw new IllegalArgumentException(name + " contains unsupported path characters");
        }
        return normalized;
    }

    private String safePrefix() {
        String normalized = trimSlashes(pluginProperties().getPrefix());
        String[] segments = normalized.split("/", -1);
        List<String> safeSegments = new ArrayList<String>(segments.length);
        for (String segment : segments) {
            safeSegments.add(safeSegment(segment, "prefix segment"));
        }
        return String.join("/", safeSegments);
    }

    private String coordinate(String type, String name) {
        return safeSegment(type, "type") + "/" + safeSegment(name, "name");
    }

    private String trimSlashes(String value) {
        String result = requireText(value, "prefix").replace('\\', '/');
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String requireText(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value.intValue() <= 0 ? fallback : value.intValue();
    }

    private long positive(Long value, long fallback) {
        return value == null || value.longValue() <= 0 ? fallback : value.longValue();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return new ThreadFactory() {
            private int index;

            @Override
            public synchronized Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + (++index));
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PluginPointer {
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LocalState {
        public Integer schemaVersion;
        public String repository;
        public String runtimeVersion;
        public String type;
        public String name;
        public String release;
        public String sha256;
        public String directory;
        public String pointerEtag;
        public String artifact;
        public Instant activatedAt;

        public LocalState() {
        }

        private LocalState(ActiveRelease active, String repository, String runtimeVersion) {
            this.schemaVersion = STATE_SCHEMA_VERSION;
            this.repository = repository;
            this.runtimeVersion = runtimeVersion;
            this.type = active.type;
            this.name = active.name;
            this.release = active.release;
            this.sha256 = active.sha256;
            this.directory = active.directory.toString();
            this.pointerEtag = active.pointerEtag;
            this.artifact = active.artifact;
            this.activatedAt = active.activatedAt;
        }
    }

    private static final class ActiveRelease {
        private final String type;
        private final String name;
        private final String release;
        private final String sha256;
        private final Path directory;
        private volatile String pointerEtag;
        private final String artifact;
        private final Instant activatedAt;

        private ActiveRelease(String type, String name, String release, String sha256,
                              Path directory, String pointerEtag, String artifact, Instant activatedAt) {
            this.type = type;
            this.name = name;
            this.release = release;
            this.sha256 = sha256;
            this.directory = directory;
            this.pointerEtag = pointerEtag;
            this.artifact = artifact;
            this.activatedAt = activatedAt == null ? Instant.now() : activatedAt;
        }

        private String identity() {
            return "sha256:" + sha256;
        }

        private boolean matches(PluginPointer pointer) {
            return release.equals(pointer.release) && sha256.equalsIgnoreCase(pointer.sha256);
        }
    }
}
