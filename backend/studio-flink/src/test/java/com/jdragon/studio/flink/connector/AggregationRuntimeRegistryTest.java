package com.jdragon.studio.flink.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregationRuntimeRegistryTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void runtimeHandleKeepsLegacySerialVersionUid() {
        assertEquals(-4997852490667400347L,
                ObjectStreamClass.lookup(AggregationRuntimeHandle.class).getSerialVersionUID());
    }

    @Test
    void runtimeHandleSummaryDoesNotExposeCapabilityTokens() {
        String localToken = "local-sensitive-runtime-token";
        AggregationRuntimeHandle local = AggregationRuntimeHandle.local(localToken);
        assertEquals(true, local.summary().startsWith("local-"));
        assertEquals(false, local.summary().contains(localToken));

        String remoteToken = "remote-sensitive-runtime-token";
        AggregationRuntimeHandle remote = AggregationRuntimeHandle.remote(
                "https://runtime.example.test", remoteToken);
        assertEquals(true, remote.summary().startsWith("remote-"));
        assertEquals(false, remote.summary().contains(remoteToken));
    }

    @Test
    void resolvesRuntimeAsJsonFriendlyPayloadAndUpdatesAudit() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setDatasourceId(1L);
        runtime.setModelId(10L);
        runtime.setPluginName("mysql8");
        BaseDataSourceDTO dto = new BaseDataSourceDTO();
        dto.setHost("127.0.0.1");
        dto.setPassword("secret");
        runtime.setDataSourceDTO(dto);

        String ref = AggregationFlinkRuntimeRegistry.register(runtime, 300);
        try {
            AggregationFlinkTableRuntimePayload payload = AggregationFlinkRuntimeRegistry.resolvePayload(ref);
            assertNull(payload.getRuntimeRef());
            AggregationFlinkTableRuntime resolved = payload.toRuntime();
            assertEquals("mysql8", resolved.getPluginName());
            assertEquals("secret", resolved.getDataSourceDTO().getPassword());

            resolved.setPushedFilters(Collections.singletonList("biz_date = '2026-07-05'"));
            resolved.addResolvedSourceSql("SELECT * FROM t WHERE biz_date = '2026-07-05'");
            AggregationFlinkRuntimeRegistry.updateAudit(ref, AggregationFlinkTableRuntimePayload.fromRuntime(resolved));

            AggregationFlinkTableRuntime updated = AggregationFlinkRuntimeRegistry.required(ref);
            assertEquals(Collections.singletonList("biz_date = '2026-07-05'"), updated.getPushedFilters());
            assertEquals(Collections.singletonList("SELECT * FROM t WHERE biz_date = '2026-07-05'"),
                    updated.getResolvedSourceSql());
        } finally {
            AggregationFlinkRuntimeRegistry.remove(ref);
        }
    }

    @Test
    void rejectsMissingRuntimeToken() {
        String capability = "secret-missing-runtime-ref";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AggregationFlinkRuntimeRegistry.required(capability));
        assertFalse(error.getMessage().contains(capability));
    }

    @Test
    void managedFileCapabilityIsScopedAndClosesItsRuntimeLifecycle() throws Exception {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        Path scopedFile = tempDir.resolve("scoped.conf");
        Files.writeString(scopedFile, "scoped", StandardCharsets.UTF_8);
        AtomicBoolean closed = new AtomicBoolean(false);
        String ref = AggregationFlinkRuntimeRegistry.register(runtime, 300,
                Collections.singletonMap(7L, scopedFile), () -> closed.set(true));
        try {
            assertEquals(scopedFile, AggregationFlinkRuntimeRegistry.requiredManagedFile(ref, 7L));
            assertThrows(IllegalStateException.class,
                    () -> AggregationFlinkRuntimeRegistry.requiredManagedFile(ref, 8L));
        } finally {
            AggregationFlinkRuntimeRegistry.remove(ref);
        }
        assertTrue(closed.get());
    }

    @Test
    void remoteRuntimeDownloadsManagedFileAndPassesOnlyLocalPathToPluginRuntime() throws Exception {
        byte[] managedContent = new byte[]{0x05, 0x02, 0x01, 0x02};
        String sha = hex(MessageDigest.getInstance("SHA-256").digest(managedContent));
        AggregationFlinkTableRuntime baseRuntime = new AggregationFlinkTableRuntime();
        baseRuntime.setPluginName("kafka");
        BaseDataSourceDTO dto = new BaseDataSourceDTO();
        dto.setKeytabPath("managed-file://701");
        baseRuntime.setDataSourceDTO(dto);
        baseRuntime.setConnectionConfig(Configuration.from(Collections.singletonMap(
                "kerberosKeytabFilePath", "managed-file://701")));

        AtomicReference<String> downloadCapability = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/flink/runtime/resolve", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, successBody(AggregationFlinkTableRuntimePayload.fromRuntime(baseRuntime)));
        });
        server.createContext("/api/flink/runtime/managed-file", exchange -> {
            downloadCapability.set(exchange.getRequestHeaders().getFirst(
                    AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER));
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("X-Studio-Managed-File-Sha256", sha);
            exchange.getResponseHeaders().set("X-Studio-Managed-File-Size", String.valueOf(managedContent.length));
            exchange.sendResponseHeaders(200, managedContent.length);
            exchange.getResponseBody().write(managedContent);
            exchange.close();
        });
        server.start();
        AggregationFlinkTableRuntime resolved = null;
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            resolved = AggregationRemoteRuntimeClient.resolve(
                    endpoint, "managed-runtime-token");
            Path localKeytab = Path.of(resolved.getDataSourceDTO().getKeytabPath());
            assertTrue(Files.isRegularFile(localKeytab));
            assertEquals("managed-runtime-token", downloadCapability.get());
            assertEquals(sha, hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(localKeytab))));
            assertEquals(localKeytab.toString(),
                    resolved.getConnectionConfig().getString("kerberosKeytabFilePath"));
        } finally {
            if (resolved != null) resolved.closeRuntimeResource();
            server.stop(0);
        }
    }

    @Test
    void remoteRuntimeActivatesManagedKrb5ConfigurationAndPassesMergedPath() throws Exception {
        byte[] managedContent = krb5("REMOTE.TEST", "kdc.remote.test").getBytes(StandardCharsets.UTF_8);
        String sha = hex(MessageDigest.getInstance("SHA-256").digest(managedContent));
        AggregationFlinkTableRuntime baseRuntime = new AggregationFlinkTableRuntime();
        baseRuntime.setPluginName("kafka");
        BaseDataSourceDTO dto = new BaseDataSourceDTO();
        dto.setKrb5File("managed-file://702");
        baseRuntime.setDataSourceDTO(dto);
        baseRuntime.setConnectionConfig(Configuration.from(Collections.singletonMap(
                "krb5Conf", "managed-file://702")));

        HttpServer server = managedFileServer(baseRuntime, managedContent, sha);
        String previousKrb5 = System.getProperty("java.security.krb5.conf");
        AggregationFlinkTableRuntime resolved = null;
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            resolved = AggregationRemoteRuntimeClient.resolve(endpoint, "managed-krb5-token");
            Path merged = Path.of(resolved.getDataSourceDTO().getKrb5File());
            assertTrue(Files.isRegularFile(merged));
            assertEquals(merged.toString(), System.getProperty("java.security.krb5.conf"));
            assertEquals(merged.toString(), resolved.getConnectionConfig().getString("krb5Conf"));
            String content = Files.readString(merged, StandardCharsets.UTF_8);
            assertTrue(content.contains("default_realm = REMOTE.TEST"));
            assertTrue(content.contains("REMOTE.TEST = { kdc=kdc.remote.test }"));
        } finally {
            if (resolved != null) resolved.closeRuntimeResource();
            restoreProperty("java.security.krb5.conf", previousKrb5);
            server.stop(0);
        }
    }

    @Test
    void remoteKerberosConflictDoesNotReplaceActiveConfiguration() throws Exception {
        Path first = tempDir.resolve("first-krb5.conf");
        Path conflicting = tempDir.resolve("conflicting-krb5.conf");
        Files.writeString(first, krb5("CONFLICT.TEST", "kdc-one.test"), StandardCharsets.UTF_8);
        Files.writeString(conflicting, krb5("CONFLICT.TEST", "kdc-two.test"), StandardCharsets.UTF_8);
        Path stable = tempDir.resolve("kerberos").resolve("krb5-merged.conf");
        RemoteKerberosConfigRegistry registry = new RemoteKerberosConfigRegistry(stable);
        String previousKrb5 = System.getProperty("java.security.krb5.conf");
        RemoteKerberosConfigRegistry.Activation activation = registry.activate(Collections.singletonList(first));
        try {
            String activeContent = Files.readString(stable, StandardCharsets.UTF_8);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> registry.activate(Collections.singletonList(conflicting)));
            assertTrue(error.getMessage().contains("CONFLICT.TEST"));
            assertEquals(activeContent, Files.readString(stable, StandardCharsets.UTF_8));
            assertEquals(stable.toString(), System.getProperty("java.security.krb5.conf"));
        } finally {
            activation.close();
            restoreProperty("java.security.krb5.conf", previousKrb5);
        }
    }

    @Test
    void remoteManagedFileCacheRemovesIdleEntriesButKeepsLeasesAndKerberosDirectory() throws Exception {
        Path root = tempDir.resolve("remote-cache");
        Path idle = root.resolve("1").resolve("sha-idle").resolve("managed-file.bin");
        Path active = root.resolve("2").resolve("sha-active").resolve("managed-file.bin");
        Path merged = root.resolve("kerberos").resolve("krb5-merged.conf");
        Files.createDirectories(idle.getParent());
        Files.createDirectories(active.getParent());
        Files.createDirectories(merged.getParent());
        Files.writeString(idle, "idle", StandardCharsets.UTF_8);
        Files.writeString(active, "active", StandardCharsets.UTF_8);
        Files.writeString(merged, "stable", StandardCharsets.UTF_8);
        Instant now = Instant.parse("2026-09-04T00:00:00Z");
        FileTime old = FileTime.from(now.minus(Duration.ofDays(2)));
        Files.setLastModifiedTime(idle, old);

        RemoteManagedFileCache.CacheLease lease = RemoteManagedFileCache.acquire(active);
        try {
            Files.setLastModifiedTime(active, old);
            Files.setLastModifiedTime(merged, old);
            RemoteManagedFileCache.cleanup(root, now, Duration.ofHours(24), 0L);
            assertFalse(Files.exists(idle));
            assertTrue(Files.isRegularFile(active));
            assertTrue(Files.isRegularFile(merged));
        } finally {
            lease.close();
        }
    }

    @Test
    void ignoresPayloadFieldsAddedByNewerStudioVersions() throws Exception {
        JsonNode payload = OBJECT_MAPPER.valueToTree(new AggregationFlinkTableRuntimePayload());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("futureRuntimeField", "future-value");

        AggregationFlinkTableRuntimePayload resolved = OBJECT_MAPPER.treeToValue(
                payload, AggregationFlinkTableRuntimePayload.class);

        assertEquals("bounded", resolved.getScanMode());
    }

    @Test
    void serializesRemoteRuntimeStateAndKeepsAuditWorking() throws Exception {
        AggregationFlinkTableRuntime baseRuntime = new AggregationFlinkTableRuntime();
        baseRuntime.setPluginName("http");
        baseRuntime.setRuntimeRef("payload-runtime-secret");
        Map<String, Object> modelMetadata = new LinkedHashMap<String, Object>();
        modelMetadata.put("readerOptions", Collections.singletonMap("header",
                "{\"Authorization\":\"Bearer remote-secret\"}"));
        baseRuntime.setModelMetadata(modelMetadata);
        BaseDataSourceDTO dataSourceDTO = new BaseDataSourceDTO();
        dataSourceDTO.setPassword("remote-password");
        baseRuntime.setDataSourceDTO(dataSourceDTO);
        baseRuntime.setHttpFilterAlwaysFalse(true);
        AtomicReference<JsonNode> auditRequest = new AtomicReference<JsonNode>();
        AtomicReference<String> resolveCapability = new AtomicReference<String>();
        AtomicReference<String> auditCapability = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/flink/runtime/resolve", exchange -> {
            resolveCapability.set(exchange.getRequestHeaders().getFirst(
                    AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER));
            exchange.getRequestBody().readAllBytes();
            respond(exchange, successBody(AggregationFlinkTableRuntimePayload.fromRuntime(baseRuntime)));
        });
        server.createContext("/api/flink/runtime/audit", exchange -> {
            auditCapability.set(exchange.getRequestHeaders().getFirst(
                    AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER));
            auditRequest.set(OBJECT_MAPPER.readTree(exchange.getRequestBody()));
            respond(exchange, successBody(Boolean.TRUE));
        });
        server.start();
        try {
            AggregationRuntimeHandle handle = AggregationRuntimeHandle.remote(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "runtime-token");
            AggregationFlinkTableRuntime planned = AggregationRuntimeResolver.resolve(handle);
            assertNull(planned.getRuntimeRef());
            assertEquals(false, planned.isHttpFilterAlwaysFalse());
            planned.setPushedFilters(Collections.singletonList("customer_id = 'C001'"));
            planned.setHttpPushdownFilters(Collections.singletonList(
                    httpFilter("customer_id", "C001")));
            planned.setHttpFilterAlwaysFalse(true);
            AggregationRuntimeResolver.captureRuntimeState(handle, planned);

            AggregationRuntimeHandle restored = roundTrip(handle);
            AggregationFlinkTableRuntime resolved = AggregationRuntimeResolver.resolve(restored);
            assertEquals(Collections.singletonList("customer_id = 'C001'"), resolved.getPushedFilters());
            assertEquals(true, resolved.isHttpFilterAlwaysFalse());
            assertEquals(Collections.singletonList("C001"),
                    resolved.getHttpPushdownFilters().get(0).get("values"));

            resolved.addResolvedSourceSql("SELECT * FROM remote_http");
            AggregationRuntimeResolver.updateAudit(restored, resolved);
            assertEquals("runtime-token", resolveCapability.get());
            assertEquals("runtime-token", auditCapability.get());
            assertFalse(auditRequest.get().has("token"));
            assertFalse(auditRequest.get().path("runtime").hasNonNull("runtimeRef"));
            assertEquals("C001", auditRequest.get().path("runtime")
                    .path("httpPushdownFilters").path(0).path("values").path(0).asText());
            assertEquals(true, auditRequest.get().path("runtime").path("httpFilterAlwaysFalse").asBoolean());
            assertEquals("SELECT * FROM remote_http", auditRequest.get().path("runtime")
                    .path("resolvedSourceSql").path(0).asText());
            assertEquals(false, auditRequest.get().toString().contains("remote-secret"));
            assertEquals(false, auditRequest.get().toString().contains("remote-password"));
        } finally {
            server.stop(0);
        }
    }

    private Map<String, Object> httpFilter(String field, String value) {
        Map<String, Object> filter = new LinkedHashMap<String, Object>();
        filter.put("field", field);
        filter.put("location", "param");
        filter.put("requestParamName", field);
        filter.put("operator", "=");
        filter.put("values", Collections.singletonList(value));
        return filter;
    }

    private AggregationRuntimeHandle roundTrip(AggregationRuntimeHandle handle) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(handle);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (AggregationRuntimeHandle) input.readObject();
        }
    }

    private byte[] successBody(Object data) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("code", "SUCCESS");
        body.put("data", data);
        return OBJECT_MAPPER.writeValueAsBytes(body);
    }

    private void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private HttpServer managedFileServer(AggregationFlinkTableRuntime runtime, byte[] content,
                                         String sha) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/flink/runtime/resolve", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, successBody(AggregationFlinkTableRuntimePayload.fromRuntime(runtime)));
        });
        server.createContext("/api/flink/runtime/managed-file", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("X-Studio-Managed-File-Sha256", sha);
            exchange.getResponseHeaders().set("X-Studio-Managed-File-Size", String.valueOf(content.length));
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        return server;
    }

    private String krb5(String realm, String kdc) {
        return "[libdefaults]\n"
                + "  default_realm = " + realm + "\n"
                + "  dns_lookup_kdc = false\n"
                + "[realms]\n"
                + "  " + realm + " = {\n"
                + "    kdc = " + kdc + "\n"
                + "  }\n"
                + "[domain_realm]\n"
                + "  ." + realm.toLowerCase(java.util.Locale.ROOT) + " = " + realm + "\n";
    }

    private void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
