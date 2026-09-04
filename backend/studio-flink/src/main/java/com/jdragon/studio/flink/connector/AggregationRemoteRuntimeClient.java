package com.jdragon.studio.flink.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AggregationRemoteRuntimeClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern MANAGED_FILE_URI = Pattern.compile("^managed-file://([1-9][0-9]*)$");
    private static final String MANAGED_FILE_SHA256_HEADER = "X-Studio-Managed-File-Sha256";
    private static final String MANAGED_FILE_SIZE_HEADER = "X-Studio-Managed-File-Size";
    private static final Set<String> KRB5_FIELD_KEYS = Set.of("krb5conf", "krb5file");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private AggregationRemoteRuntimeClient() {
    }

    static AggregationFlinkTableRuntime resolve(String endpoint, String token) {
        try {
            JsonNode response = post(endpoint, "/api/flink/runtime/resolve", token,
                    new LinkedHashMap<String, Object>());
            JsonNode data = response.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw new IllegalStateException("runtime resolve returned empty data");
            }
            AggregationFlinkTableRuntime runtime = OBJECT_MAPPER
                    .treeToValue(data, AggregationFlinkTableRuntimePayload.class).toRuntime();
            materializeManagedFiles(runtime, endpoint, token);
            return runtime;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to resolve DataAggregation Flink runtime from studio-flink: "
                    + ex.getMessage(), ex);
        }
    }

    static void updateAudit(String endpoint, String token, AggregationFlinkTableRuntime runtime) {
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("runtime", AggregationFlinkTableRuntimePayload.auditFromRuntime(runtime));
            post(endpoint, "/api/flink/runtime/audit", token, body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to update DataAggregation Flink runtime audit: "
                    + ex.getMessage(), ex);
        }
    }

    private static JsonNode post(String endpoint, String path, String token, Object body) throws Exception {
        String payload = OBJECT_MAPPER.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(resolveUri(endpoint, path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER, token)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        JsonNode json = OBJECT_MAPPER.readTree(response.body());
        String code = json.path("code").asText("SUCCESS");
        if (!"SUCCESS".equalsIgnoreCase(code) && !"0".equals(code)) {
            throw new IllegalStateException(json.path("message").asText("runtime endpoint returned " + code));
        }
        return json;
    }

    private static void materializeManagedFiles(AggregationFlinkTableRuntime runtime,
                                                 String endpoint, String token) throws Exception {
        if (runtime == null) return;
        Map<String, Path> resolved = new LinkedHashMap<String, Path>();
        List<RemoteManagedFileCache.CacheLease> cacheLeases =
                new ArrayList<RemoteManagedFileCache.CacheLease>();
        RemoteKerberosConfigRegistry.Activation kerberosActivation = null;
        try {
            Map<String, Object> connection = readConfiguration(runtime.getConnectionConfig());
            Map<String, Object> extension = readConfiguration(runtime.getExtConfig());
            Map<String, Object> modelMetadata = runtime.getModelMetadata();
            Set<String> krb5Uris = new LinkedHashSet<String>();
            collectKrb5ManagedUris(connection, krb5Uris);
            collectKrb5ManagedUris(extension, krb5Uris);
            collectKrb5ManagedUris(modelMetadata, krb5Uris);

            BaseDataSourceDTO dto = runtime.getDataSourceDTO();
            if (dto != null && isManagedFileUri(dto.getKrb5File())) {
                krb5Uris.add(dto.getKrb5File().trim());
            }
            if (dto != null) collectKrb5ManagedUris(dto.getExtraParams(), krb5Uris);

            connection = castMap(replaceManagedUris(
                    connection, endpoint, token, resolved, cacheLeases));
            extension = castMap(replaceManagedUris(
                    extension, endpoint, token, resolved, cacheLeases));
            modelMetadata = castMap(replaceManagedUris(
                    modelMetadata, endpoint, token, resolved, cacheLeases));

            if (dto != null) {
                dto.setKeytabPath(replaceManagedText(dto.getKeytabPath(), endpoint, token,
                        resolved, cacheLeases));
                dto.setKrb5File(replaceManagedText(dto.getKrb5File(), endpoint, token,
                        resolved, cacheLeases));
                if (dto.getExtraParams() != null) {
                    Map<String, String> extra = new LinkedHashMap<String, String>();
                    for (Map.Entry<String, String> entry : dto.getExtraParams().entrySet()) {
                        extra.put(entry.getKey(), replaceManagedText(entry.getValue(), endpoint,
                                token, resolved, cacheLeases));
                    }
                    dto.setExtraParams(extra);
                }
            }

            List<Path> krb5Sources = new ArrayList<Path>();
            for (String uri : krb5Uris) {
                Path source = resolved.get(uri);
                if (source != null && !krb5Sources.contains(source)) krb5Sources.add(source);
            }
            if (!krb5Sources.isEmpty()) {
                kerberosActivation = RemoteKerberosConfigRegistry.activateManagedFiles(krb5Sources);
                String mergedPath = kerberosActivation.getMergedPath().toString();
                Set<String> originalPaths = new LinkedHashSet<String>();
                for (Path source : krb5Sources) originalPaths.add(source.toString());
                connection = castMap(replaceExactPaths(connection, originalPaths, mergedPath));
                extension = castMap(replaceExactPaths(extension, originalPaths, mergedPath));
                modelMetadata = castMap(replaceExactPaths(modelMetadata, originalPaths, mergedPath));
                if (dto != null && originalPaths.contains(dto.getKrb5File())) {
                    dto.setKrb5File(mergedPath);
                }
                if (dto != null && dto.getExtraParams() != null) {
                    Map<String, String> extra = new LinkedHashMap<String, String>();
                    for (Map.Entry<String, String> entry : dto.getExtraParams().entrySet()) {
                        extra.put(entry.getKey(), originalPaths.contains(entry.getValue())
                                ? mergedPath : entry.getValue());
                    }
                    dto.setExtraParams(extra);
                }
            }

            runtime.setConnectionConfig(Configuration.from(connection));
            runtime.setExtConfig(Configuration.from(extension));
            runtime.setModelMetadata(modelMetadata);
            runtime.setRuntimeResource(new RemoteRuntimeResource(kerberosActivation, cacheLeases));
        } catch (Exception ex) {
            closeRuntimeResources(kerberosActivation, cacheLeases);
            throw ex;
        }
    }

    private static Map<String, Object> readConfiguration(Configuration configuration) throws Exception {
        if (configuration == null) return new LinkedHashMap<String, Object>();
        return OBJECT_MAPPER.readValue(configuration.toJSON(),
                new TypeReference<Map<String, Object>>() { });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?>
                ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private static Object replaceManagedUris(Object value, String endpoint, String token,
                                             Map<String, Path> resolved,
                                             List<RemoteManagedFileCache.CacheLease> cacheLeases)
            throws Exception {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()),
                        replaceManagedUris(entry.getValue(), endpoint, token, resolved, cacheLeases));
            }
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(replaceManagedUris(item, endpoint, token, resolved, cacheLeases));
            }
            return result;
        }
        if (value instanceof String) {
            return replaceManagedText((String) value, endpoint, token, resolved, cacheLeases);
        }
        return value;
    }

    private static String replaceManagedText(String value, String endpoint, String token,
                                             Map<String, Path> resolved,
                                             List<RemoteManagedFileCache.CacheLease> cacheLeases)
            throws Exception {
        if (value == null) return null;
        Matcher matcher = MANAGED_FILE_URI.matcher(value.trim());
        if (!matcher.matches()) return value;
        Path cached = resolved.get(value.trim());
        if (cached == null) {
            cached = downloadManagedFile(endpoint, token, Long.parseLong(matcher.group(1)));
            resolved.put(value.trim(), cached);
            cacheLeases.add(RemoteManagedFileCache.acquire(cached));
        }
        return cached.toString();
    }

    private static void collectKrb5ManagedUris(Object value, Set<String> result) {
        if (!(value instanceof Map<?, ?>)) return;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            Object nested = entry.getValue();
            String key = String.valueOf(entry.getKey()).trim().toLowerCase(java.util.Locale.ROOT);
            if (KRB5_FIELD_KEYS.contains(key) && nested instanceof String
                    && isManagedFileUri((String) nested)) {
                result.add(((String) nested).trim());
            }
            if (nested instanceof Map<?, ?>) collectKrb5ManagedUris(nested, result);
            else if (nested instanceof List<?>) {
                for (Object item : (List<?>) nested) collectKrb5ManagedUris(item, result);
            }
        }
    }

    private static boolean isManagedFileUri(String value) {
        return value != null && MANAGED_FILE_URI.matcher(value.trim()).matches();
    }

    private static Object replaceExactPaths(Object value, Set<String> sources, String target) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), replaceExactPaths(entry.getValue(), sources, target));
            }
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) result.add(replaceExactPaths(item, sources, target));
            return result;
        }
        return value instanceof String && sources.contains(value) ? target : value;
    }

    private static void closeRuntimeResources(RemoteKerberosConfigRegistry.Activation activation,
                                              List<RemoteManagedFileCache.CacheLease> cacheLeases) {
        try {
            if (activation != null) activation.close();
        } catch (RuntimeException ignored) {
            // Cache leases must still be released when Kerberos reconfiguration fails.
        } finally {
            for (int index = cacheLeases.size() - 1; index >= 0; index--) {
                cacheLeases.get(index).close();
            }
        }
    }

    private static Path downloadManagedFile(String endpoint, String token, long fileId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(resolveUri(endpoint,
                        "/api/flink/runtime/managed-file?id=" + fileId))
                .timeout(Duration.ofSeconds(30))
                .header(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER, token)
                .GET()
                .build();
        HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("managed file download returned HTTP " + response.statusCode());
        }
        byte[] content = response.body();
        String expectedSha = response.headers().firstValue(MANAGED_FILE_SHA256_HEADER)
                .orElseThrow(() -> new IllegalStateException("managed file response has no SHA-256"));
        long expectedSize = Long.parseLong(response.headers().firstValue(MANAGED_FILE_SIZE_HEADER)
                .orElseThrow(() -> new IllegalStateException("managed file response has no size")));
        if (content.length != expectedSize) {
            throw new IllegalStateException("managed file size verification failed");
        }
        String actualSha = hex(MessageDigest.getInstance("SHA-256").digest(content));
        if (!actualSha.equalsIgnoreCase(expectedSha)) {
            throw new IllegalStateException("managed file SHA-256 verification failed");
        }
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "studio-flink-managed-files")
                .toAbsolutePath().normalize();
        Path target = root.resolve(String.valueOf(fileId)).resolve(actualSha).resolve("managed-file.bin")
                .toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("managed file cache path escapes cache root");
        }
        if (!Files.isRegularFile(target) || Files.size(target) != expectedSize
                || !sha256(target).equalsIgnoreCase(actualSha)) {
            Files.createDirectories(target.getParent());
            applyOwnerOnlyPermissions(target.getParent(), true);
            Path temporary = target.getParent().resolve("." + UUID.randomUUID() + ".tmp");
            Files.write(temporary, content);
            applyOwnerOnlyPermissions(temporary, false);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            applyOwnerOnlyPermissions(target, false);
        }
        return target;
    }

    private static void applyOwnerOnlyPermissions(Path path, boolean directory) throws Exception {
        if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS) != null) {
            EnumSet<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
            AclEntry entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(view.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            view.setAcl(Collections.singletonList(entry));
            return;
        }
        if (!directory) Files.deleteIfExists(path);
        throw new IllegalStateException("managed file cache permissions cannot be restricted to the runtime owner");
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    private static URI resolveUri(String endpoint, String path) {
        String base = endpoint == null ? "" : endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static final class RemoteRuntimeResource implements AutoCloseable {
        private final RemoteKerberosConfigRegistry.Activation activation;
        private final List<RemoteManagedFileCache.CacheLease> cacheLeases;
        private boolean closed;

        private RemoteRuntimeResource(RemoteKerberosConfigRegistry.Activation activation,
                                      List<RemoteManagedFileCache.CacheLease> cacheLeases) {
            this.activation = activation;
            this.cacheLeases = new ArrayList<RemoteManagedFileCache.CacheLease>(cacheLeases);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            closeRuntimeResources(activation, cacheLeases);
        }
    }
}
