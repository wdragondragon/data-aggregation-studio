package com.jdragon.studio.flink.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.jdragon.studio.flink.connector.AggregationFlinkRuntimeRegistry;
import com.jdragon.studio.flink.connector.AggregationFlinkTableRuntimePayload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Tag(name = "Flink Runtime", description = "Internal DataAggregation Flink runtime callback APIs")
@RestController
@ConditionalOnClass(name = "com.jdragon.studio.worker.bootstrap.StudioWorkerApplication")
@RequestMapping("/api/flink/runtime")
public class FlinkRuntimeController {
    private static final String PLUGIN_IDENTITY_HEADER = "X-DataAggregation-Plugin-Identity";
    private static final String PLUGIN_COORDINATE_HEADER = "X-DataAggregation-Plugin-Coordinate";
    private static final String MANAGED_FILE_SHA256_HEADER = "X-Studio-Managed-File-Sha256";
    private static final String MANAGED_FILE_SIZE_HEADER = "X-Studio-Managed-File-Size";

    @Operation(summary = "Resolve short-lived DataAggregation runtime for remote Flink connector")
    @PostMapping("/resolve")
    public Result<AggregationFlinkTableRuntimePayload> resolve(
            @RequestHeader(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER) String token) {
        return Result.success(AggregationFlinkRuntimeRegistry.resolvePayload(token));
    }

    @Operation(summary = "Update pushdown audit for remote Flink connector")
    @PostMapping("/audit")
    public Result<Boolean> audit(
            @RequestHeader(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER) String token,
            @RequestBody RuntimeAuditRequest request) {
        AggregationFlinkRuntimeRegistry.updateAudit(token, request.getRuntime());
        return Result.success(Boolean.TRUE);
    }

    @Operation(summary = "Download the task-scoped source plugin artifact for a remote Flink connector")
    @GetMapping(value = "/plugin/artifact", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public void artifact(@RequestHeader(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER) String token,
                         @RequestParam("type") String type,
                         @RequestParam("name") String name,
                         HttpServletResponse response) throws IOException {
        if (!"source".equals(type)) {
            throw new IllegalArgumentException("Only source plugins are available to the Flink connector");
        }
        String pluginName = requireText(name, "plugin name");
        try (AggregationFlinkRuntimeRegistry.PluginArtifactLease lease =
                     AggregationFlinkRuntimeRegistry.acquirePinnedPlugin(token, SourcePluginType.SOURCE, pluginName)) {
            ResolvedPlugin plugin = lease.getPlugin();
            if (!Files.isDirectory(plugin.getDirectory())) {
                throw new IllegalStateException("Resolved plugin directory is unavailable");
            }
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(PLUGIN_IDENTITY_HEADER, artifactIdentity(plugin));
            response.setHeader(PLUGIN_COORDINATE_HEADER, plugin.getCoordinate());
            try (ZipOutputStream archive = new ZipOutputStream(response.getOutputStream())) {
                writePluginDirectory(plugin.getDirectory(), archive);
            }
        }
    }

    @Operation(summary = "Download a task-scoped managed file for a remote Flink connector")
    @GetMapping(value = "/managed-file", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public void managedFile(
            @RequestHeader(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER) String token,
            @RequestParam("id") Long fileId,
            HttpServletResponse response) throws IOException {
        Path path = AggregationFlinkRuntimeRegistry.requiredManagedFile(token, fileId);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(MANAGED_FILE_SHA256_HEADER, sha256(path));
        response.setHeader(MANAGED_FILE_SIZE_HEADER, String.valueOf(Files.size(path)));
        response.setContentLengthLong(Files.size(path));
        try (InputStream input = Files.newInputStream(path)) {
            input.transferTo(response.getOutputStream());
        }
    }

    private void writePluginDirectory(Path directory, ZipOutputStream archive) throws IOException {
        try (var files = Files.walk(directory)) {
            Iterator<Path> iterator = files.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                String entryName = directory.relativize(file).toString().replace('\\', '/');
                archive.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, archive);
                archive.closeEntry();
            }
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private String artifactIdentity(ResolvedPlugin plugin) throws IOException {
        String value = requireText(plugin.getIdentity(), "plugin identity");
        if (value.startsWith("local:")) {
            return localArtifactIdentity(plugin.getDirectory());
        }
        if (value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")) {
            return value;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "identity-" + hex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create a connector artifact identity", ex);
        }
    }

    private String localArtifactIdentity(Path directory) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files;
            try (var paths = Files.walk(directory)) {
                files = paths.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> relativeName(directory, path)))
                        .toList();
            }
            byte[] buffer = new byte[8192];
            for (Path file : files) {
                byte[] relativePath = relativeName(directory, file).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(relativePath.length).array());
                digest.update(relativePath);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(Files.size(file)).array());
                try (InputStream input = Files.newInputStream(file)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return "local-" + hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String relativeName(Path directory, Path file) {
        return directory.relativize(file).toString().replace('\\', '/');
    }

    private String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public static class RuntimeAuditRequest {
        private AggregationFlinkTableRuntimePayload runtime;

        public AggregationFlinkTableRuntimePayload getRuntime() {
            return runtime;
        }

        public void setRuntime(AggregationFlinkTableRuntimePayload runtime) {
            this.runtime = runtime;
        }
    }
}
