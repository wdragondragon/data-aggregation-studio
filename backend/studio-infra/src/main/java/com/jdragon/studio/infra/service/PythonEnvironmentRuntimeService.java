package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.ArtifactStoreEntity;
import com.jdragon.studio.infra.entity.EnvironmentDependencyEntity;
import com.jdragon.studio.infra.entity.EnvironmentDependencyFileEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentEntity;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Prepares immutable, fingerprinted Worker-local venvs for Python script environments. */
public class PythonEnvironmentRuntimeService {
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$");
    private static final Pattern PACKAGE_VERSION = Pattern.compile(
            "^[A-Za-z0-9](?:[A-Za-z0-9.!+_~-]*[A-Za-z0-9])?$");
    private static final long PROCESS_TIMEOUT_SECONDS = 300L;
    private static final int MAX_DIAGNOSTIC_CHARACTERS = 64 * 1024;

    private final ScriptEnvironmentService environmentService;
    private final EnvironmentDependencyService dependencyService;
    private final ArtifactStoreService artifactStoreService;
    private final RuntimeEndpointSecurityService endpointSecurityService;
    private final StudioPlatformProperties properties;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<String, Object>();

    public PythonEnvironmentRuntimeService(ScriptEnvironmentService environmentService,
                                           EnvironmentDependencyService dependencyService,
                                           ArtifactStoreService artifactStoreService,
                                           RuntimeEndpointSecurityService endpointSecurityService,
                                           StudioPlatformProperties properties) {
        this.environmentService = environmentService;
        this.dependencyService = dependencyService;
        this.artifactStoreService = artifactStoreService;
        this.endpointSecurityService = endpointSecurityService;
        this.properties = properties;
    }

    public String resolvePythonExecutable(Long environmentId) throws Exception {
        String basePython = properties.getPython() == null ? null : properties.getPython().getExecutable();
        if (environmentId == null) {
            return basePython;
        }
        if (!StringUtils.hasText(basePython)) {
            throw new IllegalStateException("Python executable is not configured on the Worker");
        }

        ScriptEnvironmentEntity environment = environmentService.requireEnabledEnvironment(environmentId);
        List<EnvironmentDependencyEntity> dependencies = pythonDependencies(environment.getId());
        String installMode = normalizeInstallMode(environment.getPythonInstallMode());
        ArtifactStoreEntity repository = resolveRepository(environment, installMode);
        List<String> requirements = requirements(dependencies);
        String pythonIdentity = basePythonIdentity(basePython);
        String fingerprint = fingerprint(environment, installMode, repository, requirements, pythonIdentity);
        String lockKey = environment.getId() + ":" + fingerprint;

        synchronized (locks.computeIfAbsent(lockKey, ignored -> new Object())) {
            Path cacheBase = pythonCacheBase();
            Path root = cacheBase.resolve(String.valueOf(environment.getId()))
                    .resolve(String.valueOf(environment.getEnvironmentVersion()))
                    .resolve(fingerprint)
                    .normalize();
            ensureInside(cacheBase, root);
            Path marker = root.resolve("install-success");
            Path python = pythonExecutable(root.resolve(".venv"));
            if (Files.isRegularFile(python) && markerMatches(marker, fingerprint)) {
                return python.toString();
            }

            deleteDirectory(root, cacheBase);
            Files.createDirectories(root);
            Files.writeString(root.resolve("root-requirements.txt"),
                    requirements.isEmpty() ? "" : String.join(System.lineSeparator(), requirements) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            run(Arrays.asList(basePython, "-m", "venv", root.resolve(".venv").toString()),
                    root, "create venv", Collections.emptyMap(), Collections.emptyList());
            if (ScriptEnvironmentService.PYTHON_INSTALL_MODE_PYPI_LIVE.equals(installMode)) {
                installFromPyPi(python, root, repository, requirements);
            } else {
                installLocalArtifacts(python, environment, root);
            }
            run(Arrays.asList(python.toString(), "-m", "pip", "check"),
                    root, "check dependencies", Collections.emptyMap(), Collections.emptyList());
            String installed = run(Arrays.asList(python.toString(), "-m", "pip", "freeze", "--all"),
                    root, "record installed packages", Collections.emptyMap(), Collections.emptyList());
            Files.writeString(root.resolve("installed-packages.txt"), installed, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(marker, fingerprint, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return python.toString();
        }
    }

    private Path pythonCacheBase() {
        String configured = properties.getScriptEnvironment() == null
                ? null : properties.getScriptEnvironment().getPythonCacheDir();
        return StringUtils.hasText(configured)
                ? Paths.get(configured.trim()).toAbsolutePath().normalize()
                : Paths.get("runtime", "python-environments").toAbsolutePath().normalize();
    }

    private List<EnvironmentDependencyEntity> pythonDependencies(Long environmentId) {
        List<EnvironmentDependencyEntity> result = new ArrayList<EnvironmentDependencyEntity>();
        for (EnvironmentDependencyEntity dependency : environmentService.listEnabledDependencies(environmentId)) {
            if ("PYTHON".equalsIgnoreCase(dependency.getScriptType())) {
                result.add(dependency);
            }
        }
        return result;
    }

    private ArtifactStoreEntity resolveRepository(ScriptEnvironmentEntity environment, String installMode) {
        if (!ScriptEnvironmentService.PYTHON_INSTALL_MODE_PYPI_LIVE.equals(installMode)) {
            return null;
        }
        if (environment.getPythonRepositoryId() == null) {
            throw new IllegalStateException("PYPI_LIVE environment has no Python repository");
        }
        ArtifactStoreEntity repository = artifactStoreService.requireEnabled(environment.getPythonRepositoryId());
        if ("OSS".equalsIgnoreCase(repository.getProvider()) || !StringUtils.hasText(repository.getSimpleIndexUrl())) {
            throw new IllegalStateException("PYPI_LIVE requires an enabled PyPI repository with a Simple index URL");
        }
        endpointSecurityService.validateRequestTarget(repository.getSimpleIndexUrl());
        return repository;
    }

    private List<String> requirements(List<EnvironmentDependencyEntity> dependencies) {
        Map<String, String> versions = new LinkedHashMap<String, String>();
        for (EnvironmentDependencyEntity dependency : dependencies) {
            String name = requiredPart(dependency.getName(), PACKAGE_NAME, "package name");
            String version = requiredPart(dependency.getVersion(), PACKAGE_VERSION, "package version");
            String normalizedName = name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
            String previous = versions.put(normalizedName, version);
            if (previous != null && !previous.equals(version)) {
                throw new IllegalStateException("Multiple root versions selected for Python package " + name);
            }
        }
        List<String> result = new ArrayList<String>();
        for (Map.Entry<String, String> entry : versions.entrySet()) {
            result.add(entry.getKey() + "==" + entry.getValue());
        }
        return result;
    }

    private String requiredPart(String value, Pattern pattern, String label) {
        String normalized = value == null ? null : value.trim();
        if (!StringUtils.hasText(normalized) || !pattern.matcher(normalized).matches()) {
            throw new IllegalStateException("Invalid Python " + label + ": " + value);
        }
        return normalized;
    }

    private void installLocalArtifacts(Path python, ScriptEnvironmentEntity environment, Path root) throws Exception {
        List<Path> artifacts = download(environment, root.resolve("artifacts"));
        if (artifacts.isEmpty()) {
            return;
        }
        List<String> install = new ArrayList<String>(Arrays.asList(
                python.toString(), "-m", "pip", "install", "--no-input",
                "--disable-pip-version-check", "--no-cache-dir", "--no-index"));
        for (Path artifact : artifacts) {
            install.add(artifact.toString());
        }
        run(install, root, "install local dependencies", Collections.emptyMap(), Collections.emptyList());
    }

    private void installFromPyPi(Path python,
                                 Path root,
                                 ArtifactStoreEntity repository,
                                 List<String> requirements) throws Exception {
        if (requirements.isEmpty()) {
            return;
        }
        String username = artifactStoreService.username(repository);
        String secret = artifactStoreService.secret(repository);
        String authenticatedUrl = authenticatedUrl(repository.getSimpleIndexUrl(), username, secret);
        URI cleanUri = URI.create(repository.getSimpleIndexUrl());
        Path pipConfig = root.resolve("pip-runtime.conf");
        StringBuilder config = new StringBuilder()
                .append("[global]\n")
                .append("index-url = ").append(authenticatedUrl).append('\n')
                .append("disable-pip-version-check = true\n")
                .append("no-input = true\n")
                .append("no-cache-dir = true\n")
                .append("no-index = false\n")
                .append("extra-index-url =\n")
                .append("find-links =\n")
                .append("timeout = 60\n")
                .append("retries = 3\n");
        if ("http".equalsIgnoreCase(cleanUri.getScheme())) {
            config.append("trusted-host = ").append(cleanUri.getHost()).append('\n');
        } else {
            config.append("trusted-host =\n");
        }
        Files.writeString(pipConfig, config.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            securePermissions(pipConfig);
            Map<String, String> environment = Collections.singletonMap("PIP_CONFIG_FILE", pipConfig.toString());
            List<String> redactions = new ArrayList<String>();
            addRedaction(redactions, authenticatedUrl);
            addRedaction(redactions, username);
            addRedaction(redactions, secret);
            addRedaction(redactions, urlEncode(username));
            addRedaction(redactions, urlEncode(secret));
            List<String> install = new ArrayList<String>(Arrays.asList(
                    python.toString(), "-m", "pip", "install", "--no-input", "--disable-pip-version-check"));
            install.addAll(requirements);
            run(install, root, "install PyPI dependencies", environment, redactions);
        } finally {
            Files.deleteIfExists(pipConfig);
        }
    }

    private List<Path> download(ScriptEnvironmentEntity environment, Path directory) throws Exception {
        Files.createDirectories(directory);
        List<Path> result = new ArrayList<Path>();
        for (EnvironmentDependencyEntity dependency : environmentService.listEnabledDependencies(environment.getId())) {
            if (!"PYTHON".equalsIgnoreCase(dependency.getScriptType())) {
                continue;
            }
            for (EnvironmentDependencyFileEntity file : dependencyService.listRuntimeArtifacts(dependency.getId())) {
                String name = file.getOriginalFileName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".whl") && !lower.endsWith(".tar.gz")) {
                    continue;
                }
                Path target = directory.resolve(file.getId() + "-"
                        + name.replaceAll("[^A-Za-z0-9._-]", "_")).normalize();
                ensureInside(directory, target);
                if (!Files.exists(target)) {
                    Files.write(target, dependencyService.downloadRuntimeFile(dependency, file),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                result.add(target);
            }
        }
        return result;
    }

    private String basePythonIdentity(String executable) throws Exception {
        return run(Arrays.asList(executable, "-c",
                        "import platform,sys;print('|'.join([sys.implementation.name,platform.python_version(),sys.platform,platform.machine()]))"),
                Paths.get(".").toAbsolutePath().normalize(), "inspect Python runtime",
                Collections.emptyMap(), Collections.emptyList()).trim();
    }

    private String fingerprint(ScriptEnvironmentEntity environment,
                               String installMode,
                               ArtifactStoreEntity repository,
                               List<String> requirements,
                               String pythonIdentity) throws Exception {
        StringBuilder value = new StringBuilder()
                .append(environment.getId()).append('\n')
                .append(environment.getEnvironmentVersion()).append('\n')
                .append(installMode).append('\n')
                .append(pythonIdentity).append('\n');
        if (repository != null) {
            value.append(repository.getId()).append('\n')
                    .append(repository.getConfigVersion()).append('\n')
                    .append(repository.getSimpleIndexUrl()).append('\n');
        }
        for (String requirement : requirements) {
            value.append(requirement).append('\n');
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : digest) {
            hex.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return hex.toString();
    }

    private String run(List<String> command,
                       Path directory,
                       String action,
                       Map<String, String> environment,
                       List<String> redactions) throws Exception {
        Files.createDirectories(directory);
        Path outputFile = Files.createTempFile(directory, "process-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile());
            clearInheritedPipSettings(builder.environment());
            builder.environment().putAll(environment);
            Process process = builder.start();
            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            if (!finished || process.exitValue() != 0) {
                throw new IllegalStateException("Failed to " + action + ": " + redact(output, redactions));
            }
            return output;
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private void clearInheritedPipSettings(Map<String, String> environment) {
        environment.remove("PIP_INDEX_URL");
        environment.remove("PIP_EXTRA_INDEX_URL");
        environment.remove("PIP_NO_INDEX");
        environment.remove("PIP_FIND_LINKS");
        environment.remove("PIP_TRUSTED_HOST");
        environment.remove("PIP_CONFIG_FILE");
    }

    private String authenticatedUrl(String cleanUrl, String username, String secret) {
        if (!StringUtils.hasText(username) && !StringUtils.hasText(secret)) {
            return cleanUrl;
        }
        URI uri = URI.create(cleanUrl);
        StringBuilder result = new StringBuilder(uri.getScheme()).append("://")
                .append(urlEncode(username)).append(':').append(urlEncode(secret)).append('@')
                .append(uri.getRawAuthority());
        if (uri.getRawPath() != null) {
            result.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            result.append('?').append(uri.getRawQuery());
        }
        return result.toString();
    }

    private String urlEncode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void addRedaction(List<String> redactions, String value) {
        if (StringUtils.hasText(value)) {
            redactions.add(value);
        }
    }

    private String redact(String output, List<String> redactions) {
        String sanitized = output == null ? "" : output;
        for (String value : redactions) {
            if (StringUtils.hasText(value)) {
                sanitized = sanitized.replace(value, "***");
            }
        }
        if (sanitized.length() <= MAX_DIAGNOSTIC_CHARACTERS) {
            return sanitized;
        }
        return "... output truncated ...\n"
                + sanitized.substring(sanitized.length() - MAX_DIAGNOSTIC_CHARACTERS);
    }

    private void securePermissions(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows ACLs inherit from the Worker runtime directory.
        }
    }

    private boolean markerMatches(Path marker, String fingerprint) {
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            return fingerprint.equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
        } catch (IOException ignored) {
            return false;
        }
    }

    private void deleteDirectory(Path target, Path cacheBase) throws IOException {
        ensureInside(cacheBase, target);
        if (!Files.exists(target)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(target)) {
            paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private void ensureInside(Path parent, Path child) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (normalizedChild.equals(normalizedParent) || !normalizedChild.startsWith(normalizedParent)) {
            throw new IllegalStateException("Python runtime path escaped its cache directory");
        }
    }

    private String normalizeInstallMode(String value) {
        return ScriptEnvironmentService.PYTHON_INSTALL_MODE_PYPI_LIVE.equalsIgnoreCase(value)
                ? ScriptEnvironmentService.PYTHON_INSTALL_MODE_PYPI_LIVE
                : ScriptEnvironmentService.PYTHON_INSTALL_MODE_LOCAL_ARTIFACT;
    }

    private Path pythonExecutable(Path venv) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? venv.resolve("Scripts").resolve("python.exe") : venv.resolve("bin").resolve("python");
    }
}
