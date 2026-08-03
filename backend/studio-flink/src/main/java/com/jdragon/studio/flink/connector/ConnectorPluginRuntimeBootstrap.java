package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.pluginloader.LoadUtil;
import com.jdragon.aggregation.pluginloader.constant.SystemConstants;
import com.jdragon.aggregation.pluginloader.runtime.LocalPluginRuntimeResolver;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeResolver;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeResolvers;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Initializes the connector's local plugin view. Remote Flink jobs fetch a plugin archive from
 * the Worker through the existing short-lived capability, never directly from object storage.
 */
final class ConnectorPluginRuntimeBootstrap {
    static final String ARTIFACT_PATH = "/api/flink/runtime/plugin/artifact";
    static final String PLUGIN_IDENTITY_HEADER = "X-DataAggregation-Plugin-Identity";
    static final String PLUGIN_COORDINATE_HEADER = "X-DataAggregation-Plugin-Coordinate";

    private static final String RESOURCE_ROOT = "dataaggregation-plugin-runtime";
    private static final long MAX_ARTIFACT_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_FILES = 5000;
    private static final int REMOTE_ARTIFACT_TIMEOUT_SECONDS = 300;
    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);
    private static final Object REMOTE_CACHE_LOCK = new Object();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ThreadBoundPluginRuntimeResolver REMOTE_RESOLVER =
            new ThreadBoundPluginRuntimeResolver();

    private static volatile Path runtimeHome;

    private ConnectorPluginRuntimeBootstrap() {
    }

    static void ensureReady(String pluginName) {
        if (hasPlugin(SystemConstants.PLUGIN_HOME, pluginName)) {
            return;
        }
        synchronized (ConnectorPluginRuntimeBootstrap.class) {
            if (!BOOTSTRAPPED.get()) {
                Path extracted = extractBundledRuntime();
                SystemConstants.HOME = extracted.toString();
                SystemConstants.PLUGIN_HOME = extracted.resolve("plugin").toString();
                SystemConstants.CORE_CONFIG = extracted.resolve("conf").resolve("core.json").toString();
                runtimeHome = extracted;
                LoadUtil.updateJarLoader();
                BOOTSTRAPPED.set(true);
            }
        }
        if (!hasPlugin(SystemConstants.PLUGIN_HOME, pluginName)) {
            throw new IllegalStateException("DataAggregation source plugin '" + pluginName
                    + "' is not bundled in connector runtime. runtimeHome=" + runtimeHome);
        }
    }

    static void runWithReady(AggregationFlinkTableRuntime runtime,
                             PluginOperation operation) throws Exception {
        if (runtime == null) {
            throw new IllegalArgumentException("DataAggregation Flink runtime is required");
        }
        if (!hasText(runtime.getPluginRuntimeEndpoint()) || !hasText(runtime.getPluginRuntimeToken())) {
            ensureReady(runtime.getPluginName());
            operation.run();
            return;
        }
        ResolvedPlugin plugin = fetchRemotePlugin(runtime);
        REMOTE_RESOLVER.run(plugin, operation);
    }

    private static ResolvedPlugin fetchRemotePlugin(AggregationFlinkTableRuntime runtime) throws Exception {
        String pluginName = requireText(runtime.getPluginName(), "plugin name");
        HttpRequest request = HttpRequest.newBuilder(artifactUri(runtime.getPluginRuntimeEndpoint(), pluginName))
                .timeout(Duration.ofSeconds(REMOTE_ARTIFACT_TIMEOUT_SECONDS))
                .header(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER,
                        runtime.getPluginRuntimeToken())
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw new IllegalStateException("Worker did not provide the requested DataAggregation plugin (HTTP "
                    + response.statusCode() + ")");
        }
        String identity = requireSafeIdentity(response.headers().firstValue(PLUGIN_IDENTITY_HEADER).orElse(null));
        String coordinate = response.headers().firstValue(PLUGIN_COORDINATE_HEADER).orElse(null);
        String expectedCoordinate = SourcePluginType.SOURCE.getName() + "/" + pluginName;
        if (!expectedCoordinate.equals(coordinate)) {
            closeQuietly(response.body());
            throw new IllegalStateException("Worker returned an unexpected DataAggregation plugin coordinate");
        }
        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declaredLength > MAX_ARTIFACT_BYTES) {
            closeQuietly(response.body());
            throw new IllegalStateException("Worker plugin artifact exceeds the connector limit");
        }
        Path destination = remoteCacheRoot().resolve(identity).toAbsolutePath().normalize();
        if (isValidPluginDirectory(destination)) {
            closeQuietly(response.body());
            return new ResolvedPlugin(SourcePluginType.SOURCE, pluginName, destination, identity);
        }
        synchronized (REMOTE_CACHE_LOCK) {
            if (!isValidPluginDirectory(destination)) {
                materializeRemotePlugin(response.body(), destination);
            } else {
                closeQuietly(response.body());
            }
        }
        return new ResolvedPlugin(SourcePluginType.SOURCE, pluginName, destination, identity);
    }

    private static URI artifactUri(String endpoint, String pluginName) {
        String base = requireText(endpoint, "Worker runtime endpoint");
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String query = "type=source&name=" + URLEncoder.encode(pluginName, StandardCharsets.UTF_8);
        return URI.create(base + ARTIFACT_PATH + "?" + query);
    }

    private static void materializeRemotePlugin(InputStream input, Path destination) throws IOException {
        Path cacheRoot = remoteCacheRoot();
        Files.createDirectories(cacheRoot);
        Path staging = Files.createTempDirectory(cacheRoot, ".staging-");
        Path archive = staging.resolve("plugin.zip");
        Path content = staging.resolve("content");
        try {
            try (InputStream source = input;
                 OutputStream target = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
                copyLimited(source, target, MAX_ARTIFACT_BYTES);
            }
            extractPluginArchive(archive, content);
            if (!isValidPluginDirectory(content)) {
                throw new IllegalArgumentException("Worker artifact does not contain a valid plugin directory");
            }
            try {
                Files.move(content, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(content, destination);
            }
        } finally {
            deleteTree(staging);
        }
    }

    private static void copyLimited(InputStream source, OutputStream target, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = source.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("Plugin artifact exceeds the connector limit");
            }
            target.write(buffer, 0, read);
        }
    }

    private static void extractPluginArchive(Path archive, Path content) throws IOException {
        Files.createDirectories(content);
        int files = 0;
        long expanded = 0L;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path output = content.resolve(entry.getName()).normalize();
                if (!output.startsWith(content)) {
                    throw new IOException("Illegal plugin archive entry");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                files++;
                if (files > MAX_FILES) {
                    throw new IOException("Plugin archive contains too many files");
                }
                Files.createDirectories(output.getParent());
                try (OutputStream target = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        expanded += read;
                        if (expanded > MAX_UNCOMPRESSED_BYTES) {
                            throw new IOException("Plugin archive expands beyond the connector limit");
                        }
                        target.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static Path remoteCacheRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "dataaggregation-flink-plugin-runtime", "remote")
                .toAbsolutePath().normalize();
    }

    private static boolean isValidPluginDirectory(Path directory) {
        if (!Files.isDirectory(directory) || !Files.isRegularFile(directory.resolve("plugin.json"))) {
            return false;
        }
        try (var files = Files.walk(directory)) {
            return files.anyMatch(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"));
        } catch (IOException ex) {
            return false;
        }
    }

    private static String requireSafeIdentity(String value) {
        String identity = requireText(value, "Worker plugin identity");
        if (!identity.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")) {
            throw new IllegalStateException("Worker returned an unsafe DataAggregation plugin identity");
        }
        return identity;
    }

    private static String requireText(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // The response was not selected for download.
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary cache cleanup must not mask the caller's real error.
                }
            });
        } catch (IOException ignored) {
            // Temporary cache cleanup is best effort.
        }
    }

    private static boolean hasPlugin(String pluginHome, String pluginName) {
        if (pluginHome == null || pluginName == null || pluginName.trim().isEmpty()) {
            return false;
        }
        return Files.isDirectory(Paths.get(pluginHome, "source", pluginName));
    }

    private static Path extractBundledRuntime() {
        try {
            Path target = Files.createTempDirectory("dataaggregation-flink-plugin-runtime-");
            URL location = ConnectorPluginRuntimeBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
            Path codeSource = Paths.get(location.toURI());
            if (Files.isDirectory(codeSource)) {
                Path resourceRoot = codeSource.resolve(RESOURCE_ROOT);
                if (Files.isDirectory(resourceRoot)) {
                    copyDirectory(resourceRoot, target);
                    return target;
                }
            } else {
                extractFromJar(codeSource, target);
                return target;
            }
            throw new IllegalStateException("Bundled plugin runtime resource not found: " + RESOURCE_ROOT);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize bundled DataAggregation plugin runtime: "
                    + ex.getMessage(), ex);
        }
    }

    private static void extractFromJar(Path jarPath, Path target) throws IOException {
        String prefix = RESOURCE_ROOT + "/";
        boolean found = false;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(prefix)) {
                    continue;
                }
                found = true;
                Path relative = Paths.get(name.substring(prefix.length()));
                if (relative.toString().isEmpty()) {
                    continue;
                }
                Path output = target.resolve(relative).normalize();
                if (!output.startsWith(target)) {
                    throw new IOException("Illegal plugin runtime entry: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(jarFile.getInputStream(entry), output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (!found) {
            throw new IllegalStateException("Bundled plugin runtime resource not found in " + jarPath.toUri());
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            paths.forEach(path -> {
                try {
                    Path relative = source.relativize(path);
                    Path output = target.resolve(relative).normalize();
                    if (!output.startsWith(target)) {
                        throw new IOException("Illegal plugin runtime path: " + path);
                    }
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(output);
                    } else {
                        Files.createDirectories(output.getParent());
                        Files.copy(path, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException ex) {
                    throw new IllegalStateException("Failed to copy bundled plugin runtime file: " + path, ex);
                }
            });
        }
    }

    @FunctionalInterface
    interface PluginOperation {
        void run() throws Exception;
    }

    private static final class ThreadBoundPluginRuntimeResolver implements PluginRuntimeResolver {
        private final LocalPluginRuntimeResolver localResolver = new LocalPluginRuntimeResolver();
        private final InheritableThreadLocal<Deque<ResolvedPlugin>> bindings =
                new InheritableThreadLocal<Deque<ResolvedPlugin>>() {
                    @Override
                    protected Deque<ResolvedPlugin> initialValue() {
                        return new ArrayDeque<ResolvedPlugin>();
                    }

                    @Override
                    protected Deque<ResolvedPlugin> childValue(Deque<ResolvedPlugin> parentValue) {
                        return new ArrayDeque<ResolvedPlugin>(parentValue);
                    }
                };

        private void run(ResolvedPlugin plugin, PluginOperation operation) throws Exception {
            PluginRuntimeResolvers.install(this);
            Deque<ResolvedPlugin> current = bindings.get();
            current.push(plugin);
            try {
                operation.run();
            } finally {
                current.pop();
                if (current.isEmpty()) {
                    bindings.remove();
                }
            }
        }

        @Override
        public ResolvedPlugin resolve(com.jdragon.aggregation.pluginloader.type.IPluginType pluginType,
                                      String pluginName) {
            Deque<ResolvedPlugin> current = bindings.get();
            ResolvedPlugin resolved = current.isEmpty() ? null : current.peek();
            if (resolved != null && resolved.getPluginType().getName().equals(pluginType.getName())
                    && resolved.getPluginName().equals(pluginName)) {
                return resolved;
            }
            return localResolver.resolve(pluginType, pluginName);
        }
    }
}
