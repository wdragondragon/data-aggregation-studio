package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.JavaImportHint;
import com.jdragon.studio.dto.model.JavaImportHintResponse;
import com.jdragon.studio.dto.model.JavaMemberHint;
import com.jdragon.studio.dto.model.JavaMemberHintResponse;
import com.jdragon.studio.infra.entity.EnvironmentDependencyEntity;
import com.jdragon.studio.infra.entity.EnvironmentDependencyFileEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentEntity;
import com.jdragon.studio.infra.script.java.JavaDataScript;
import com.jdragon.studio.infra.script.java.JavaDataScriptContext;
import com.jdragon.studio.infra.script.java.JavaDataScriptResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ScriptEnvironmentRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(ScriptEnvironmentRuntimeService.class);
    private static final int DEFAULT_HINT_LIMIT = 100;
    private static final int MAX_HINT_LIMIT = 500;
    private static final String SOURCE_APPLICATION = "APPLICATION";
    private static final String SOURCE_DEPENDENCY = "DEPENDENCY";
    private static final String SOURCE_JDK = "JDK";
    private static final String INSTANCE_ID = UUID.randomUUID().toString();
    private static final long CACHE_CLEANUP_INITIAL_DELAY_MS = 300000L;
    private static final long CACHE_CLEANUP_FIXED_DELAY_MS = 300000L;

    private final ScriptEnvironmentService environmentService;
    private final EnvironmentDependencyService dependencyService;
    private final ScriptEnvironmentArtifactLoader artifactLoader;
    private final Map<String, RuntimeClassLoaderHolder> classLoaderCache = new ConcurrentHashMap<String, RuntimeClassLoaderHolder>();
    private final Map<String, RuntimeClassLoaderHolder> retiredClassLoaderCache = new ConcurrentHashMap<String, RuntimeClassLoaderHolder>();
    private final Map<String, JavaImportHintResponse> importHintCache = new ConcurrentHashMap<String, JavaImportHintResponse>();
    private final Map<String, JavaMemberHintResponse> memberHintCache = new ConcurrentHashMap<String, JavaMemberHintResponse>();
    private final Set<Path> buildingEnvironmentDirs = ConcurrentHashMap.newKeySet();

    public ScriptEnvironmentRuntimeService(ScriptEnvironmentService environmentService,
                                           EnvironmentDependencyService dependencyService,
                                           ScriptEnvironmentArtifactLoader artifactLoader) {
        this.environmentService = environmentService;
        this.dependencyService = dependencyService;
        this.artifactLoader = artifactLoader;
    }

    public RuntimeLease resolveRuntime(Long environmentId) {
        ScriptEnvironmentEntity environment = environmentService.requireEnabledEnvironment(environmentId);
        String cacheKey = cacheKey(environment);
        while (true) {
            RuntimeClassLoaderHolder cached = classLoaderCache.get(cacheKey);
            if (cached != null) {
                RuntimeLease lease = cached.tryAcquire(this);
                if (lease != null) {
                    retireOlderRuntimes(environment.getId(), environment.getEnvironmentVersion());
                    return lease;
                }
                classLoaderCache.remove(cacheKey, cached);
                continue;
            }
            RuntimeClassLoaderHolder created = buildRuntime(environment);
            RuntimeClassLoaderHolder previous = classLoaderCache.putIfAbsent(cacheKey, created);
            if (previous == null) {
                RuntimeLease lease = created.tryAcquire(this);
                if (lease != null) {
                    retireOlderRuntimes(environment.getId(), environment.getEnvironmentVersion());
                    return lease;
                }
                retireCachedHolder(cacheKey, created);
                continue;
            }
            closeRuntimeOnly(created);
        }
    }

    public JavaImportHintResponse importHints(Long environmentId, String keyword, Integer limit) {
        try (RuntimeLease lease = resolveRuntime(environmentId)) {
            RuntimeClassLoaderHolder runtime = lease.getRuntime();
            String cacheKey = runtime.getEnvironmentId() + ":" + runtime.getEnvironmentVersion();
            JavaImportHintResponse cached = importHintCache.get(cacheKey);
            if (cached == null) {
                cached = buildImportHintResponse(runtime);
                JavaImportHintResponse previous = importHintCache.putIfAbsent(cacheKey, cached);
                if (previous != null) {
                    cached = previous;
                }
            }
            JavaImportHintResponse response = new JavaImportHintResponse();
            response.setEnvironmentId(cached.getEnvironmentId());
            response.setEnvironmentVersion(cached.getEnvironmentVersion());
            response.setGeneratedAt(cached.getGeneratedAt());
            response.setClasses(filterHints(cached.getClasses(), keyword, normalizeLimit(limit)));
            return response;
        }
    }

    public JavaMemberHintResponse memberHints(Long environmentId,
                                              String className,
                                              String keyword,
                                              Boolean staticOnly,
                                              Integer limit) {
        try (RuntimeLease lease = resolveRuntime(environmentId)) {
            RuntimeClassLoaderHolder runtime = lease.getRuntime();
            String normalizedClassName = normalizeClassName(className);
            String normalizedKeyword = normalizeKeyword(keyword);
            boolean staticOnlyValue = Boolean.TRUE.equals(staticOnly);
            int normalizedLimit = normalizeLimit(limit);
            String cacheKey = runtime.getEnvironmentId()
                    + ":" + runtime.getEnvironmentVersion()
                    + ":" + normalizedClassName
                    + ":" + staticOnlyValue
                    + ":" + normalizedKeyword
                    + ":" + normalizedLimit;
            JavaMemberHintResponse cached = memberHintCache.get(cacheKey);
            if (cached == null) {
                cached = buildMemberHintResponse(runtime, normalizedClassName, normalizedKeyword, staticOnlyValue, normalizedLimit);
                JavaMemberHintResponse previous = memberHintCache.putIfAbsent(cacheKey, cached);
                if (previous != null) {
                    cached = previous;
                }
            }
            JavaMemberHintResponse response = new JavaMemberHintResponse();
            response.setEnvironmentId(cached.getEnvironmentId());
            response.setEnvironmentVersion(cached.getEnvironmentVersion());
            response.setClassName(cached.getClassName());
            response.setGeneratedAt(cached.getGeneratedAt());
            response.setMembers(new ArrayList<JavaMemberHint>(cached.getMembers()));
            return response;
        }
    }

    public void clearEnvironment(Long environmentId) {
        if (environmentId == null) {
            clearAll();
            return;
        }
        String prefix = environmentId + ":";
        for (String key : new ArrayList<String>(classLoaderCache.keySet())) {
            if (key.startsWith(prefix)) {
                retireCachedHolder(key, classLoaderCache.get(key));
            }
        }
        for (String key : new ArrayList<String>(importHintCache.keySet())) {
            if (key.startsWith(prefix)) {
                importHintCache.remove(key);
            }
        }
        for (String key : new ArrayList<String>(memberHintCache.keySet())) {
            if (key.startsWith(prefix)) {
                memberHintCache.remove(key);
            }
        }
        JavaDataDevelopmentExecutor.clearCompiledCache(environmentId, null);
    }

    public void clearAll() {
        for (String key : new ArrayList<String>(classLoaderCache.keySet())) {
            retireCachedHolder(key, classLoaderCache.get(key));
        }
        importHintCache.clear();
        memberHintCache.clear();
        JavaDataDevelopmentExecutor.clearCompiledCache();
    }

    @Scheduled(initialDelay = CACHE_CLEANUP_INITIAL_DELAY_MS, fixedDelay = CACHE_CLEANUP_FIXED_DELAY_MS)
    public void cleanupRuntimeCache() {
        cleanupRetiredRuntimeCache();
        cleanupOrphanEnvironmentDirectories();
    }

    private RuntimeClassLoaderHolder buildRuntime(ScriptEnvironmentEntity environment) {
        Path environmentDir = cacheRoot().resolve(String.valueOf(environment.getId()))
                .resolve(String.valueOf(environment.getEnvironmentVersion()));
        buildingEnvironmentDirs.add(environmentDir);
        try {
            Files.createDirectories(environmentDir);
            List<ResolvedJar> jars = resolveJars(environment, environmentDir);
            List<URL> urls = new ArrayList<URL>();
            for (ResolvedJar jar : jars) {
                urls.add(jar.path.toUri().toURL());
            }
            ClassLoader appClassLoader = Thread.currentThread().getContextClassLoader();
            if (appClassLoader == null) {
                appClassLoader = ScriptEnvironmentRuntimeService.class.getClassLoader();
            }
            ClassLoader parent = environment.getUseApplicationParent() == null || environment.getUseApplicationParent().intValue() == 1
                    ? appClassLoader
                    : new ScriptApiParentClassLoader(appClassLoader);
            URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]), parent);
            return new RuntimeClassLoaderHolder(environment.getId(), environment.getEnvironmentVersion(), environmentDir, classLoader, jars,
                    environment.getUseApplicationParent() == null || environment.getUseApplicationParent().intValue() == 1);
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Failed to prepare script environment: " + ex.getMessage(), ex);
        } finally {
            buildingEnvironmentDirs.remove(environmentDir);
        }
    }

    private List<ResolvedJar> resolveJars(ScriptEnvironmentEntity environment, Path environmentDir) throws Exception {
        List<ResolvedJar> result = new ArrayList<ResolvedJar>();
        for (EnvironmentDependencyEntity dependency : environmentService.listEnabledDependencies(environment.getId())) {
            List<EnvironmentDependencyFileEntity> runtimeArtifacts = dependencyService.listRuntimeArtifacts(dependency.getId());
            if (!runtimeArtifacts.isEmpty()) {
                for (EnvironmentDependencyFileEntity runtimeArtifact : runtimeArtifacts) {
                    result.add(new ResolvedJar(dependency.getId(), downloadRuntimeArtifact(dependency, runtimeArtifact, environmentDir)));
                }
                continue;
            }
            Path artifact = downloadArtifact(dependency, environmentDir);
            if ("JAR".equalsIgnoreCase(dependency.getArtifactType())) {
                result.add(new ResolvedJar(dependency.getId(), artifact));
            } else if ("ZIP".equalsIgnoreCase(dependency.getArtifactType())) {
                result.addAll(extractJars(dependency, artifact, environmentDir.resolve("zip-" + dependency.getId())));
            }
        }
        return result;
    }

    private Path downloadRuntimeArtifact(EnvironmentDependencyEntity dependency,
                                         EnvironmentDependencyFileEntity file,
                                         Path environmentDir) throws Exception {
        Path artifactDir = environmentDir.resolve("runtime-artifacts").resolve(String.valueOf(dependency.getId()));
        Files.createDirectories(artifactDir);
        Path target = artifactDir.resolve(file.getId() + ".jar");
        if (!Files.exists(target)) {
            try (InputStream inputStream = artifactLoader.open(file.getObjectUrl())) {
                Files.copy(inputStream, target);
            }
        }
        verifyChecksum(dependency.getName() + "/" + file.getOriginalFileName(), file.getChecksum(), target);
        return target;
    }

    private Path downloadArtifact(EnvironmentDependencyEntity dependency, Path environmentDir) throws Exception {
        Path artifactDir = environmentDir.resolve("artifacts");
        Files.createDirectories(artifactDir);
        String extension = "ZIP".equalsIgnoreCase(dependency.getArtifactType()) ? ".zip" : ".jar";
        Path target = artifactDir.resolve(dependency.getId() + extension);
        if (!Files.exists(target)) {
            try (InputStream inputStream = artifactLoader.open(dependency.getArtifactUrl())) {
                Files.copy(inputStream, target);
            }
        }
        verifyChecksum(dependency, target);
        return target;
    }


    private void verifyChecksum(EnvironmentDependencyEntity dependency, Path artifact) throws Exception {
        if (dependency.getChecksum() == null || dependency.getChecksum().trim().isEmpty()) {
            return;
        }
        verifyChecksum(dependency.getName(), dependency.getChecksum(), artifact);
    }

    private void verifyChecksum(String displayName, String checksum, Path artifact) throws Exception {
        if (checksum == null || checksum.trim().isEmpty()) {
            return;
        }
        String expected = checksum.trim().toLowerCase(Locale.ROOT);
        String actual = sha256(artifact);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Checksum mismatch for dependency " + displayName);
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream inputStream = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read = inputStream.read(buffer);
            while (read >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
                read = inputStream.read(buffer);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte item : digest.digest()) {
            builder.append(String.format(Locale.ENGLISH, "%02x", item));
        }
        return builder.toString();
    }

    private List<ResolvedJar> extractJars(EnvironmentDependencyEntity dependency, Path zipPath, Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        List<ResolvedJar> result = new ArrayList<ResolvedJar>();
        long extractedBytes = 0L;
        long extractionLimit = artifactLoader.maxArtifactBytes();
        try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null) {
                if (!entry.isDirectory() && entry.getName() != null && entry.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    String fileName = sanitizeFileName(entry.getName());
                    Path jarPath = targetDir.resolve(fileName);
                    long remainingBytes = extractionLimit - extractedBytes;
                    if (remainingBytes <= 0L || entry.getSize() > remainingBytes) {
                        throw new IllegalArgumentException("ZIP dependency exceeds the configured extraction size limit");
                    }
                    extractedBytes += copyZipEntry(zipInputStream, jarPath, remainingBytes);
                    result.add(new ResolvedJar(dependency.getId(), jarPath));
                }
                zipInputStream.closeEntry();
                entry = zipInputStream.getNextEntry();
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("ZIP dependency contains no JAR files: " + dependency.getName());
        }
        return result;
    }

    private String sanitizeFileName(String entryName) {
        String normalized = entryName.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        String fileName = index < 0 ? normalized : normalized.substring(index + 1);
        return fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Path cacheRoot() {
        return Paths.get("runtime", "script-environments", INSTANCE_ID).toAbsolutePath().normalize();
    }

    private JavaImportHintResponse buildImportHintResponse(RuntimeClassLoaderHolder runtime) {
        Map<String, JavaImportHint> hints = new LinkedHashMap<String, JavaImportHint>();
        addScriptApiHints(hints);
        scanJdkClasses(hints);
        if (runtime.isUseApplicationParent()) {
            scanApplicationClasspath(hints, runtime.getClassLoader().getParent());
        }
        for (ResolvedJar jar : runtime.getJars()) {
            scanJar(hints, jar.path, SOURCE_DEPENDENCY, jar.dependencyId);
        }
        List<JavaImportHint> classes = new ArrayList<JavaImportHint>(hints.values());
        Collections.sort(classes, new Comparator<JavaImportHint>() {
            @Override
            public int compare(JavaImportHint left, JavaImportHint right) {
                return String.valueOf(left.getQualifiedName()).compareToIgnoreCase(String.valueOf(right.getQualifiedName()));
            }
        });
        JavaImportHintResponse response = new JavaImportHintResponse();
        response.setEnvironmentId(runtime.getEnvironmentId());
        response.setEnvironmentVersion(runtime.getEnvironmentVersion());
        response.setGeneratedAt(LocalDateTime.now());
        response.setClasses(classes);
        return response;
    }

    private void addScriptApiHints(Map<String, JavaImportHint> hints) {
        addHint(hints, JavaDataScript.class.getName(), SOURCE_APPLICATION, null);
        addHint(hints, JavaDataScriptContext.class.getName(), SOURCE_APPLICATION, null);
        addHint(hints, JavaDataScriptResult.class.getName(), SOURCE_APPLICATION, null);
        addHint(hints, "com.jdragon.studio.infra.script.java.JavaDataScriptServices", SOURCE_APPLICATION, null);
        addHint(hints, "com.jdragon.studio.infra.script.java.JavaDataScriptLogger", SOURCE_APPLICATION, null);
        addHint(hints, "com.jdragon.studio.dto.model.DataSourceDefinition", SOURCE_APPLICATION, null);
        addHint(hints, "com.jdragon.studio.dto.model.DataModelDefinition", SOURCE_APPLICATION, null);
        addHint(hints, "com.jdragon.studio.dto.model.SqlExecutionResultView", SOURCE_APPLICATION, null);
    }

    private void scanApplicationClasspath(Map<String, JavaImportHint> hints, ClassLoader applicationClassLoader) {
        Set<Path> paths = collectApplicationClasspathPaths(applicationClassLoader);
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                scanClassDirectory(hints, path);
            } else if (path.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                scanJar(hints, path, SOURCE_APPLICATION, null);
            }
        }
    }

    private Set<Path> collectApplicationClasspathPaths(ClassLoader applicationClassLoader) {
        Set<Path> paths = new LinkedHashSet<Path>();
        addClasspathPropertyPaths(paths);
        addClassLoaderPaths(paths, applicationClassLoader);
        addClassLoaderPaths(paths, Thread.currentThread().getContextClassLoader());
        addClassLoaderPaths(paths, ScriptEnvironmentRuntimeService.class.getClassLoader());
        addClassLoaderPaths(paths, ClassLoader.getSystemClassLoader());
        expandManifestClasspath(paths);
        addStudioModuleOutputPaths(paths);
        return paths;
    }

    private void addClasspathPropertyPaths(Set<Path> paths) {
        String classPath = System.getProperty("java.class.path");
        if (classPath != null) {
            String[] items = classPath.split(File.pathSeparator);
            for (String item : items) {
                if (item != null && !item.trim().isEmpty()) {
                    addPath(paths, Paths.get(item.trim()));
                }
            }
        }
    }

    private void addClassLoaderPaths(Set<Path> paths, ClassLoader classLoader) {
        ClassLoader current = classLoader;
        Set<ClassLoader> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<ClassLoader, Boolean>());
        while (current != null && seen.add(current)) {
            if (current instanceof URLClassLoader) {
                URL[] urls = ((URLClassLoader) current).getURLs();
                if (urls != null) {
                    for (URL url : urls) {
                        addUrlPath(paths, url);
                    }
                }
            }
            current = current.getParent();
        }
    }

    private void addUrlPath(Set<Path> paths, URL url) {
        if (url == null) {
            return;
        }
        try {
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                addPath(paths, Paths.get(url.toURI()));
                return;
            }
            if ("jar".equalsIgnoreCase(url.getProtocol())) {
                String value = url.toString();
                int separator = value.indexOf("!/");
                if (separator > 0) {
                    addUrlPath(paths, new URL(value.substring("jar:".length(), separator)));
                }
            }
        } catch (Exception ignored) {
            // Classpath URL discovery is best-effort.
        }
    }

    private void addPath(Set<Path> paths, Path path) {
        if (path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            paths.add(normalized);
        }
    }

    private void expandManifestClasspath(Set<Path> paths) {
        Set<Path> expanded = new LinkedHashSet<Path>(paths);
        Set<Path> visited = new LinkedHashSet<Path>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Path path : new ArrayList<Path>(expanded)) {
                if (!visited.add(path) || !Files.isRegularFile(path)
                        || !path.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                for (Path manifestPath : readManifestClasspath(path)) {
                    if (expanded.add(manifestPath)) {
                        changed = true;
                    }
                }
            }
        }
        paths.clear();
        paths.addAll(expanded);
    }

    private List<Path> readManifestClasspath(Path jarPath) {
        List<Path> result = new ArrayList<Path>();
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return result;
            }
            String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.trim().isEmpty()) {
                return result;
            }
            String[] items = classPath.trim().split("\\s+");
            for (String item : items) {
                Path path = resolveManifestClasspathEntry(jarPath, item);
                if (path != null && Files.exists(path)) {
                    result.add(path.toAbsolutePath().normalize());
                }
            }
        } catch (IOException ignored) {
            // Import hints are best-effort.
        }
        return result;
    }

    private Path resolveManifestClasspathEntry(Path jarPath, String item) {
        if (item == null || item.trim().isEmpty()) {
            return null;
        }
        String value = item.trim();
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute()) {
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    return Paths.get(uri);
                }
                return null;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to relative path handling.
        }
        Path parent = jarPath.getParent();
        return parent == null ? Paths.get(value) : parent.resolve(value);
    }

    private void addStudioModuleOutputPaths(Set<Path> paths) {
        Path backendRoot = findBackendRoot();
        if (backendRoot == null) {
            return;
        }
        String[] moduleNames = {
                "studio-server",
                "studio-infra",
                "studio-core",
                "studio-dto",
                "studio-commons",
                "studio-nacos-compat-core",
                "studio-worker"
        };
        for (String moduleName : moduleNames) {
            addPath(paths, backendRoot.resolve(moduleName).resolve("target").resolve("classes"));
        }
    }

    private Path findBackendRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (int index = 0; index < 8 && current != null; index++) {
            if (Files.isDirectory(current.resolve("studio-server"))
                    && Files.isDirectory(current.resolve("studio-infra"))) {
                return current;
            }
            if (Files.isDirectory(current.resolve("backend").resolve("studio-server"))
                    && Files.isDirectory(current.resolve("backend").resolve("studio-infra"))) {
                return current.resolve("backend");
            }
            current = current.getParent();
        }
        return null;
    }

    private void scanJdkClasses(Map<String, JavaImportHint> hints) {
        FileSystem fileSystem;
        try {
            fileSystem = FileSystems.getFileSystem(URI.create("jrt:/"));
        } catch (FileSystemNotFoundException ex) {
            return;
        }
        Path modules = fileSystem.getPath("/modules");
        if (!Files.exists(modules)) {
            return;
        }
        try {
            Files.walk(modules)
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
                    .forEach(path -> {
                        Path relative = modules.relativize(path);
                        if (relative.getNameCount() > 1) {
                            String className = relative.subpath(1, relative.getNameCount())
                                    .toString()
                                    .replace('/', '.')
                                    .replace('\\', '.');
                            className = className.substring(0, className.length() - ".class".length());
                            if (isJdkApiClassName(className)) {
                                addClassName(hints, className, SOURCE_JDK, null);
                            }
                        }
                    });
        } catch (IOException ignored) {
            // JDK import hints are best-effort.
        }
    }

    private void scanClassDirectory(Map<String, JavaImportHint> hints, Path root) {
        try {
            Files.walk(root)
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
                    .forEach(path -> {
                        String relative = root.relativize(path).toString().replace(File.separatorChar, '.');
                        if (relative.endsWith(".class")) {
                            addClassName(hints, relative.substring(0, relative.length() - ".class".length()), SOURCE_APPLICATION, null);
                        }
                    });
        } catch (IOException ignored) {
            // Import hints are best-effort.
        }
    }

    private void scanJar(Map<String, JavaImportHint> hints, Path jarPath, String source, Long dependencyId) {
        if (jarPath == null || !Files.exists(jarPath)) {
            return;
        }
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName() == null || !entry.getName().endsWith(".class")) {
                    continue;
                }
                String entryName = entry.getName();
                if (entryName.startsWith("BOOT-INF/classes/")) {
                    entryName = entryName.substring("BOOT-INF/classes/".length());
                }
                addClassName(hints, entryName.replace('/', '.').substring(0, entryName.length() - ".class".length()), source, dependencyId);
            }
        } catch (IOException ignored) {
            // Import hints are best-effort.
        }
    }

    private void addClassName(Map<String, JavaImportHint> hints, String className, String source, Long dependencyId) {
        if (!isImportableClassName(className)) {
            return;
        }
        addHint(hints, className, source, dependencyId);
    }

    private boolean isImportableClassName(String className) {
        return className != null
                && className.indexOf('$') < 0
                && className.indexOf('-') < 0
                && className.indexOf('/') < 0
                && className.indexOf('\\') < 0
                && className.indexOf('.') > 0
                && !className.endsWith(".module-info")
                && !className.endsWith(".package-info")
                && className.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+");
    }

    private boolean isJdkApiClassName(String className) {
        return className != null
                && (className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("org.w3c.dom.")
                || className.startsWith("org.xml.sax."))
                && !className.contains(".internal.")
                && !className.contains(".impl.")
                && !className.contains(".doc-files.");
    }

    private void addHint(Map<String, JavaImportHint> hints, String qualifiedName, String source, Long dependencyId) {
        if (qualifiedName == null || hints.containsKey(qualifiedName)) {
            return;
        }
        int index = qualifiedName.lastIndexOf('.');
        JavaImportHint hint = new JavaImportHint();
        hint.setQualifiedName(qualifiedName);
        hint.setSimpleName(index < 0 ? qualifiedName : qualifiedName.substring(index + 1));
        hint.setPackageName(index < 0 ? "" : qualifiedName.substring(0, index));
        hint.setSource(source);
        hint.setEnvironmentDependencyId(dependencyId);
        hints.put(qualifiedName, hint);
    }

    private JavaMemberHintResponse buildMemberHintResponse(RuntimeClassLoaderHolder runtime,
                                                           String className,
                                                           String keyword,
                                                           boolean staticOnly,
                                                           int limit) {
        JavaMemberHintResponse response = new JavaMemberHintResponse();
        response.setEnvironmentId(runtime.getEnvironmentId());
        response.setEnvironmentVersion(runtime.getEnvironmentVersion());
        response.setClassName(className);
        response.setGeneratedAt(LocalDateTime.now());
        if (className == null || className.isEmpty()) {
            return response;
        }
        Class<?> javaClass;
        try {
            javaClass = Class.forName(className, false, runtime.getClassLoader());
        } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
            return response;
        }
        List<JavaMemberHint> members = new ArrayList<JavaMemberHint>();
        try {
            for (Method method : javaClass.getMethods()) {
                JavaMemberHint hint = toMethodHint(method, staticOnly);
                if (hint != null && matchesMemberKeyword(hint, keyword)) {
                    members.add(hint);
                }
            }
            for (Field field : javaClass.getFields()) {
                JavaMemberHint hint = toFieldHint(field, staticOnly);
                if (hint != null && matchesMemberKeyword(hint, keyword)) {
                    members.add(hint);
                }
            }
        } catch (LinkageError | SecurityException ignored) {
            return response;
        }
        Collections.sort(members, new Comparator<JavaMemberHint>() {
            @Override
            public int compare(JavaMemberHint left, JavaMemberHint right) {
                String leftValue = String.valueOf(left.getName()) + ":" + String.valueOf(left.getDisplaySignature());
                String rightValue = String.valueOf(right.getName()) + ":" + String.valueOf(right.getDisplaySignature());
                return leftValue.compareToIgnoreCase(rightValue);
            }
        });
        if (members.size() > limit) {
            response.setMembers(new ArrayList<JavaMemberHint>(members.subList(0, limit)));
        } else {
            response.setMembers(members);
        }
        return response;
    }

    private JavaMemberHint toMethodHint(Method method, boolean staticOnly) {
        if (method == null
                || method.isBridge()
                || method.isSynthetic()
                || method.getDeclaringClass() == Object.class) {
            return null;
        }
        boolean staticMember = Modifier.isStatic(method.getModifiers());
        if (staticOnly != staticMember) {
            return null;
        }
        JavaMemberHint hint = new JavaMemberHint();
        hint.setName(method.getName());
        hint.setKind("METHOD");
        hint.setStaticMember(Boolean.valueOf(staticMember));
        hint.setReturnType(typeName(method.getReturnType()));
        hint.setDeclaringClass(method.getDeclaringClass().getName());
        List<String> parameterTypes = new ArrayList<String>();
        List<String> parameterNames = new ArrayList<String>();
        Class<?>[] types = method.getParameterTypes();
        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < types.length; index++) {
            parameterTypes.add(typeName(types[index]));
            String parameterName = index < parameters.length && parameters[index].isNamePresent()
                    ? parameters[index].getName()
                    : "arg" + index;
            parameterNames.add(parameterName);
        }
        hint.setParameterTypes(parameterTypes);
        hint.setParameterNames(parameterNames);
        hint.setDisplaySignature(buildMethodSignature(method.getName(), parameterTypes, parameterNames));
        hint.setInsertText(buildMethodInsertText(method.getName(), parameterNames));
        return hint;
    }

    private JavaMemberHint toFieldHint(Field field, boolean staticOnly) {
        if (field == null || field.isSynthetic()) {
            return null;
        }
        boolean staticMember = Modifier.isStatic(field.getModifiers());
        if (staticOnly != staticMember) {
            return null;
        }
        JavaMemberHint hint = new JavaMemberHint();
        hint.setName(field.getName());
        hint.setKind("FIELD");
        hint.setStaticMember(Boolean.valueOf(staticMember));
        hint.setReturnType(typeName(field.getType()));
        hint.setDeclaringClass(field.getDeclaringClass().getName());
        hint.setDisplaySignature(field.getName());
        hint.setInsertText(field.getName());
        return hint;
    }

    private boolean matchesMemberKeyword(JavaMemberHint hint, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return true;
        }
        String name = hint.getName() == null ? "" : hint.getName().toLowerCase(Locale.ROOT);
        String signature = hint.getDisplaySignature() == null ? "" : hint.getDisplaySignature().toLowerCase(Locale.ROOT);
        return name.contains(keyword) || signature.contains(keyword);
    }

    private String buildMethodSignature(String methodName, List<String> parameterTypes, List<String> parameterNames) {
        StringBuilder builder = new StringBuilder(methodName).append('(');
        for (int index = 0; index < parameterTypes.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(simpleTypeName(parameterTypes.get(index)));
            if (index < parameterNames.size()) {
                builder.append(' ').append(parameterNames.get(index));
            }
        }
        return builder.append(')').toString();
    }

    private String buildMethodInsertText(String methodName, List<String> parameterNames) {
        StringBuilder builder = new StringBuilder(methodName).append('(');
        for (int index = 0; index < parameterNames.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append("${").append(index + 1).append(':').append(escapeSnippetPlaceholder(parameterNames.get(index))).append('}');
        }
        return builder.append(')').toString();
    }

    private String escapeSnippetPlaceholder(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "arg";
        }
        return value.replace("\\", "\\\\").replace("}", "\\}").replace("$", "\\$");
    }

    private String typeName(Class<?> type) {
        if (type == null) {
            return "";
        }
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        return type.getName();
    }

    private String simpleTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "";
        }
        if (typeName.endsWith("[]")) {
            return simpleTypeName(typeName.substring(0, typeName.length() - 2)) + "[]";
        }
        int index = typeName.lastIndexOf('.');
        return index < 0 ? typeName : typeName.substring(index + 1);
    }

    private List<JavaImportHint> filterHints(List<JavaImportHint> hints, String keyword, int limit) {
        String normalizedKeyword = normalizeKeyword(keyword);
        List<JavaImportHint> result = new ArrayList<JavaImportHint>();
        for (JavaImportHint hint : hints) {
            if (!normalizedKeyword.isEmpty()) {
                String simpleName = hint.getSimpleName() == null ? "" : hint.getSimpleName().toLowerCase(Locale.ROOT);
                String qualifiedName = hint.getQualifiedName() == null ? "" : hint.getQualifiedName().toLowerCase(Locale.ROOT);
                if (!simpleName.contains(normalizedKeyword) && !qualifiedName.contains(normalizedKeyword)) {
                    continue;
                }
            }
            result.add(hint);
        }
        Collections.sort(result, new Comparator<JavaImportHint>() {
            @Override
            public int compare(JavaImportHint left, JavaImportHint right) {
                int value = Integer.compare(importHintMatchRank(left, normalizedKeyword), importHintMatchRank(right, normalizedKeyword));
                if (value != 0) {
                    return value;
                }
                value = Integer.compare(importHintSourceRank(left), importHintSourceRank(right));
                if (value != 0) {
                    return value;
                }
                value = String.valueOf(left.getSimpleName()).compareToIgnoreCase(String.valueOf(right.getSimpleName()));
                if (value != 0) {
                    return value;
                }
                return String.valueOf(left.getQualifiedName()).compareToIgnoreCase(String.valueOf(right.getQualifiedName()));
            }
        });
        if (result.size() > limit) {
            return new ArrayList<JavaImportHint>(result.subList(0, limit));
        }
        return result;
    }

    private int importHintMatchRank(JavaImportHint hint, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return 0;
        }
        String simpleName = hint.getSimpleName() == null ? "" : hint.getSimpleName().toLowerCase(Locale.ROOT);
        String qualifiedName = hint.getQualifiedName() == null ? "" : hint.getQualifiedName().toLowerCase(Locale.ROOT);
        if (simpleName.equals(keyword)) {
            return 0;
        }
        if (simpleName.startsWith(keyword)) {
            return 1;
        }
        if (simpleName.contains(keyword)) {
            return 2;
        }
        if (qualifiedName.startsWith(keyword)) {
            return 3;
        }
        return 4;
    }

    private int importHintSourceRank(JavaImportHint hint) {
        String source = hint.getSource() == null ? "" : hint.getSource();
        String qualifiedName = hint.getQualifiedName() == null ? "" : hint.getQualifiedName();
        if (qualifiedName.startsWith("com.jdragon.studio.infra.script.java.")) {
            return 0;
        }
        if (SOURCE_DEPENDENCY.equals(source)) {
            return 1;
        }
        if (qualifiedName.startsWith("com.jdragon.studio.")) {
            return 2;
        }
        if (qualifiedName.startsWith("com.jdragon.aggregation.")) {
            return 3;
        }
        if (SOURCE_JDK.equals(source) || qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) {
            return 4;
        }
        return 5;
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeClassName(String className) {
        if (className == null) {
            return "";
        }
        return className.trim();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit.intValue() < 1) {
            return DEFAULT_HINT_LIMIT;
        }
        return Math.min(limit.intValue(), MAX_HINT_LIMIT);
    }

    private String cacheKey(ScriptEnvironmentEntity environment) {
        return environment.getId() + ":" + environment.getEnvironmentVersion();
    }

    private String cacheKey(Long environmentId, Long environmentVersion) {
        return environmentId + ":" + environmentVersion;
    }

    private void retireOlderRuntimes(Long environmentId, Long activeVersion) {
        String activeKey = cacheKey(environmentId, activeVersion);
        String prefix = environmentId + ":";
        for (String key : new ArrayList<String>(classLoaderCache.keySet())) {
            if (key.startsWith(prefix) && !activeKey.equals(key)) {
                retireCachedHolder(key, classLoaderCache.get(key));
            }
        }
        removeStaleHintCaches(environmentId, activeVersion);
    }

    private void retireCachedHolder(String key, RuntimeClassLoaderHolder holder) {
        if (holder == null) {
            return;
        }
        classLoaderCache.remove(key, holder);
        holder.retire();
        retiredClassLoaderCache.put(key, holder);
        JavaDataDevelopmentExecutor.clearCompiledCache(holder.getEnvironmentId(), holder.getEnvironmentVersion());
        cleanupRetiredHolder(key, holder);
    }

    private void closeRuntimeOnly(RuntimeClassLoaderHolder holder) {
        if (holder == null) {
            return;
        }
        holder.retire();
        JavaDataDevelopmentExecutor.clearCompiledCache(holder.getEnvironmentId(), holder.getEnvironmentVersion());
        closeQuietly(holder.markClosedForCleanup());
    }

    private void release(RuntimeClassLoaderHolder holder) {
        if (holder == null) {
            return;
        }
        if (holder.release() && holder.isRetired()) {
            cleanupRetiredHolder(cacheKey(holder.getEnvironmentId(), holder.getEnvironmentVersion()), holder);
        }
    }

    private void cleanupRetiredRuntimeCache() {
        for (Map.Entry<String, RuntimeClassLoaderHolder> entry : new ArrayList<Map.Entry<String, RuntimeClassLoaderHolder>>(retiredClassLoaderCache.entrySet())) {
            cleanupRetiredHolder(entry.getKey(), entry.getValue());
        }
    }

    private void cleanupRetiredHolder(String key, RuntimeClassLoaderHolder holder) {
        if (holder == null || !holder.isReadyForCleanup()) {
            return;
        }
        boolean deleted = cleanupHolderResources(holder);
        if (deleted) {
            retiredClassLoaderCache.remove(key, holder);
        }
    }

    private boolean cleanupHolderResources(RuntimeClassLoaderHolder holder) {
        Closeable closeable = holder.markClosedForCleanup();
        closeQuietly(closeable);
        return deleteDirectoryQuietly(holder.getEnvironmentDir());
    }

    private void removeStaleHintCaches(Long environmentId, Long activeVersion) {
        String activePrefix = environmentId + ":" + activeVersion;
        String environmentPrefix = environmentId + ":";
        for (String key : new ArrayList<String>(importHintCache.keySet())) {
            if (key.startsWith(environmentPrefix) && !activePrefix.equals(key)) {
                importHintCache.remove(key);
            }
        }
        for (String key : new ArrayList<String>(memberHintCache.keySet())) {
            if (key.startsWith(environmentPrefix) && !key.startsWith(activePrefix + ":")) {
                memberHintCache.remove(key);
            }
        }
    }

    private void cleanupOrphanEnvironmentDirectories() {
        Path root = cacheRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        Set<Path> protectedDirs = protectedEnvironmentDirs();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(environmentDir -> cleanupOrphanVersionDirectories(environmentDir, protectedDirs));
        } catch (IOException ex) {
            log.warn("Failed to scan script environment runtime cache root {}", root, ex);
        }
    }

    private void cleanupOrphanVersionDirectories(Path environmentDir, Set<Path> protectedDirs) {
        try (Stream<Path> stream = Files.list(environmentDir)) {
            stream.filter(Files::isDirectory).forEach(versionDir -> {
                Path normalized = versionDir.toAbsolutePath().normalize();
                if (!protectedDirs.contains(normalized)) {
                    deleteDirectoryQuietly(normalized);
                }
            });
        } catch (IOException ex) {
            log.warn("Failed to scan script environment runtime cache directory {}", environmentDir, ex);
        }
    }

    private Set<Path> protectedEnvironmentDirs() {
        Set<Path> result = new LinkedHashSet<Path>();
        for (RuntimeClassLoaderHolder holder : classLoaderCache.values()) {
            result.add(holder.getEnvironmentDir().toAbsolutePath().normalize());
        }
        for (RuntimeClassLoaderHolder holder : retiredClassLoaderCache.values()) {
            result.add(holder.getEnvironmentDir().toAbsolutePath().normalize());
        }
        for (Path path : buildingEnvironmentDirs) {
            result.add(path.toAbsolutePath().normalize());
        }
        return result;
    }

    private long copyZipEntry(InputStream input, Path target, long maxBytes) throws Exception {
        long total = 0L;
        byte[] buffer = new byte[8192];
        try (OutputStream output = Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("ZIP dependency exceeds the configured extraction size limit");
                }
                output.write(buffer, 0, read);
            }
        } catch (Exception ex) {
            Files.deleteIfExists(target);
            throw ex;
        }
        return total;
    }

    private boolean deleteDirectoryQuietly(Path directory) {
        if (directory == null) {
            return true;
        }
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(cacheRoot())) {
            log.warn("Skip deleting script environment cache outside current instance root: {}", normalized);
            return false;
        }
        if (!Files.exists(normalized)) {
            return true;
        }
        try {
            Files.walkFileTree(normalized, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return !Files.exists(normalized);
        } catch (IOException ex) {
            log.warn("Failed to delete script environment runtime cache directory {}", normalized, ex);
            return false;
        }
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Cache cleanup should not mask the triggering operation.
        }
    }

    public static final class RuntimeClassLoaderHolder {
        private final Long environmentId;
        private final Long environmentVersion;
        private final Path environmentDir;
        private final URLClassLoader classLoader;
        private final List<ResolvedJar> jars;
        private final boolean useApplicationParent;
        private final AtomicInteger activeCount = new AtomicInteger(0);
        private volatile boolean retired;
        private volatile boolean closed;

        private RuntimeClassLoaderHolder(Long environmentId,
                                          Long environmentVersion,
                                          Path environmentDir,
                                          URLClassLoader classLoader,
                                          List<ResolvedJar> jars,
                                          boolean useApplicationParent) {
            this.environmentId = environmentId;
            this.environmentVersion = environmentVersion;
            this.environmentDir = environmentDir;
            this.classLoader = classLoader;
            this.jars = jars;
            this.useApplicationParent = useApplicationParent;
        }

        private synchronized RuntimeLease tryAcquire(ScriptEnvironmentRuntimeService owner) {
            if (retired || closed) {
                return null;
            }
            activeCount.incrementAndGet();
            return new RuntimeLease(owner, this);
        }

        private synchronized boolean release() {
            int current = activeCount.decrementAndGet();
            if (current < 0) {
                activeCount.set(0);
                return false;
            }
            return current == 0;
        }

        private synchronized void retire() {
            retired = true;
        }

        private synchronized boolean isReadyForCleanup() {
            return retired && activeCount.get() == 0;
        }

        private synchronized Closeable markClosedForCleanup() {
            if (!isReadyForCleanup() || closed) {
                return null;
            }
            closed = true;
            return classLoader;
        }

        private boolean isRetired() {
            return retired;
        }

        public Long getEnvironmentId() {
            return environmentId;
        }

        public Long getEnvironmentVersion() {
            return environmentVersion;
        }

        private Path getEnvironmentDir() {
            return environmentDir;
        }

        public URLClassLoader getClassLoader() {
            return classLoader;
        }

        public List<Path> getJarPaths() {
            List<Path> paths = new ArrayList<Path>();
            for (ResolvedJar jar : jars) {
                paths.add(jar.path);
            }
            return paths;
        }

        private List<ResolvedJar> getJars() {
            return jars;
        }

        public boolean isUseApplicationParent() {
            return useApplicationParent;
        }
    }

    public static final class RuntimeLease implements AutoCloseable {
        private final ScriptEnvironmentRuntimeService owner;
        private final RuntimeClassLoaderHolder runtime;
        private volatile boolean closed;

        private RuntimeLease(ScriptEnvironmentRuntimeService owner, RuntimeClassLoaderHolder runtime) {
            this.owner = owner;
            this.runtime = runtime;
        }

        public RuntimeClassLoaderHolder getRuntime() {
            return runtime;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.release(runtime);
        }
    }

    private static final class ResolvedJar {
        private final Long dependencyId;
        private final Path path;

        private ResolvedJar(Long dependencyId, Path path) {
            this.dependencyId = dependencyId;
            this.path = path;
        }
    }

    private static final class ScriptApiParentClassLoader extends ClassLoader {
        private static final String[] ALLOWED_PREFIXES = new String[]{
                "java.",
                "javax.",
                "jakarta.",
                "org.slf4j.",
                "com.jdragon.studio.infra.script.java.",
                "com.jdragon.studio.dto.",
        };

        private ScriptApiParentClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!isAllowed(name)) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }

        private static boolean isAllowed(String name) {
            if (name == null) {
                return false;
            }
            for (String prefix : ALLOWED_PREFIXES) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }
}
