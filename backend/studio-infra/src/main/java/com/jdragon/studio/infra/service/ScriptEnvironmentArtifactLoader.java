package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

/** Loads Worker-side script artifacts without unrestricted network or local-file access. */
public class ScriptEnvironmentArtifactLoader {

    private static final int DEFAULT_MAX_ARTIFACT_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ARTIFACT_BYTES = 64 * 1024 * 1024;

    private final CloudObjectStorageService cloudObjectStorageService;
    private final RuntimeEndpointSecurityService endpointSecurityService;
    private final RuntimeEndpointHttpClient httpClient;
    private final StudioPlatformProperties properties;

    public ScriptEnvironmentArtifactLoader(CloudObjectStorageService cloudObjectStorageService,
                                           RuntimeEndpointSecurityService endpointSecurityService,
                                           RuntimeEndpointHttpClient httpClient,
                                           StudioPlatformProperties properties) {
        this.cloudObjectStorageService = cloudObjectStorageService;
        this.endpointSecurityService = endpointSecurityService;
        this.httpClient = httpClient;
        this.properties = properties == null ? new StudioPlatformProperties() : properties;
    }

    public InputStream open(String artifactUrl) throws Exception {
        String normalized = normalize(artifactUrl);
        if (normalized.startsWith("oss://")) {
            OssArtifact artifact = parseOssArtifact(normalized);
            byte[] body = cloudObjectStorageService.get(artifact.bucket, artifact.objectKey);
            if (body == null) {
                throw bad("Script dependency object-storage artifact is unavailable");
            }
            if (body.length > maxArtifactBytes()) {
                throw bad("Script dependency exceeds the configured artifact size limit");
            }
            return new ByteArrayInputStream(body);
        }
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return openHttp(normalized);
        }
        if (normalized.startsWith("file:")) {
            URI uri;
            try {
                uri = URI.create(normalized);
            } catch (RuntimeException ex) {
                throw bad("Script dependency local file URI is invalid");
            }
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getAuthority() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw bad("Script dependency local file URI is invalid");
            }
            try {
                return openLocal(Paths.get(uri));
            } catch (StudioException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw bad("Script dependency local file URI is invalid");
            }
        }
        try {
            return openLocal(Paths.get(normalized));
        } catch (StudioException ex) {
            throw ex;
        } catch (Exception ex) {
            throw bad("Script dependency local file cannot be opened");
        }
    }

    private InputStream openHttp(String artifactUrl) throws Exception {
        RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                endpointSecurityService.validateRequestTarget(artifactUrl);
        RuntimeEndpointHttpClient.Response response;
        try {
            response = httpClient.execute(
                    target,
                    "GET",
                    Collections.emptyMap(),
                    null,
                    timeoutMillis(scriptProperties().getArtifactConnectTimeoutSeconds(), 5),
                    timeoutMillis(scriptProperties().getArtifactReadTimeoutSeconds(), 30),
                    maxArtifactBytes());
        } catch (RuntimeEndpointSecurityService.ResponseTooLargeException ex) {
            throw bad("Script dependency exceeds the configured artifact size limit");
        } catch (Exception ex) {
            throw bad("Script dependency download failed");
        }
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            throw bad("Script dependency download returned HTTP " + response.getStatusCode());
        }
        return new ByteArrayInputStream(response.getBody());
    }

    private InputStream openLocal(Path configuredPath) throws Exception {
        StudioPlatformProperties.ScriptEnvironmentProperties script = scriptProperties();
        if (!script.isAllowLocalFiles()) {
            throw bad("Script dependency local files are disabled");
        }
        List<String> configuredRoots = script.getAllowedLocalRoots();
        if (configuredRoots == null || configuredRoots.isEmpty()) {
            throw bad("Script dependency local-file roots are not configured");
        }
        Path target = configuredPath.toAbsolutePath().normalize().toRealPath();
        if (!Files.isRegularFile(target)) {
            throw bad("Script dependency local path is not a regular file");
        }
        if (Files.size(target) > maxArtifactBytes()) {
            throw bad("Script dependency exceeds the configured artifact size limit");
        }
        for (String configuredRoot : configuredRoots) {
            if (configuredRoot == null || configuredRoot.trim().isEmpty()) {
                continue;
            }
            Path root;
            try {
                root = Paths.get(configuredRoot.trim()).toAbsolutePath().normalize().toRealPath();
            } catch (Exception ignored) {
                continue;
            }
            if (target.startsWith(root)) {
                return Files.newInputStream(target);
            }
        }
        throw bad("Script dependency local file is outside the configured roots");
    }

    private StudioPlatformProperties.ScriptEnvironmentProperties scriptProperties() {
        StudioPlatformProperties.ScriptEnvironmentProperties script = properties.getScriptEnvironment();
        return script == null ? new StudioPlatformProperties.ScriptEnvironmentProperties() : script;
    }

    private int timeoutMillis(Integer seconds, int fallbackSeconds) {
        int value = seconds == null ? fallbackSeconds : seconds.intValue();
        return Math.max(1, Math.min(value, 60)) * 1000;
    }

    int maxArtifactBytes() {
        Integer configured = scriptProperties().getMaxArtifactBytes();
        int value = configured == null ? DEFAULT_MAX_ARTIFACT_BYTES : configured.intValue();
        return Math.min(MAX_ARTIFACT_BYTES, Math.max(1024, value));
    }

    private String normalize(String artifactUrl) {
        if (artifactUrl == null || artifactUrl.trim().isEmpty()) {
            throw bad("Script dependency artifact URL is required");
        }
        return artifactUrl.trim();
    }

    private OssArtifact parseOssArtifact(String artifactUrl) {
        String value = artifactUrl.substring("oss://".length());
        int splitIndex = value.indexOf('/');
        if (splitIndex <= 0 || splitIndex >= value.length() - 1) {
            throw bad("Script dependency OSS artifact URL is invalid");
        }
        return new OssArtifact(value.substring(0, splitIndex), value.substring(splitIndex + 1));
    }

    private StudioException bad(String message) {
        return new StudioException(StudioErrorCode.BAD_REQUEST, message);
    }

    private static final class OssArtifact {
        private final String bucket;
        private final String objectKey;

        private OssArtifact(String bucket, String objectKey) {
            this.bucket = bucket;
            this.objectKey = objectKey;
        }
    }
}
