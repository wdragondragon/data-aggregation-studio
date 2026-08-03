package com.jdragon.studio.flink.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeResolvers;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorPluginRuntimeBootstrapTest {

    @AfterEach
    void resetPluginResolver() {
        PluginRuntimeResolvers.reset();
    }

    @Test
    void downloadsTaskScopedArtifactAndPinsEachReleaseIdentity() throws Exception {
        AtomicReference<String> identity = new AtomicReference<String>("codex-e2e-v1-aaaaaaaa");
        AtomicReference<String> marker = new AtomicReference<String>("v1");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ConnectorPluginRuntimeBootstrap.ARTIFACT_PATH, exchange -> {
            requests.incrementAndGet();
            assertEquals("runtime-capability", exchange.getRequestHeaders().getFirst(
                    AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER));
            exchange.getResponseHeaders().set(ConnectorPluginRuntimeBootstrap.PLUGIN_IDENTITY_HEADER, identity.get());
            exchange.getResponseHeaders().set(ConnectorPluginRuntimeBootstrap.PLUGIN_COORDINATE_HEADER, "source/mysql8");
            byte[] archive = pluginArchive(marker.get());
            exchange.sendResponseHeaders(200, archive.length);
            exchange.getResponseBody().write(archive);
            exchange.close();
        });
        server.start();
        try {
            AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
            runtime.setPluginName("mysql8");
            runtime.setPluginRuntimeEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            runtime.setPluginRuntimeToken("runtime-capability");

            AtomicReference<ResolvedPlugin> v1 = new AtomicReference<ResolvedPlugin>();
            ConnectorPluginRuntimeBootstrap.runWithReady(runtime, () -> v1.set(
                    PluginRuntimeResolvers.resolve(SourcePluginType.SOURCE, "mysql8")));
            assertEquals("codex-e2e-v1-aaaaaaaa", v1.get().getIdentity());
            assertEquals("v1", java.nio.file.Files.readString(v1.get().getDirectory().resolve("marker.txt")));

            identity.set("codex-e2e-v2-bbbbbbbb");
            marker.set("v2");
            AtomicReference<ResolvedPlugin> v2 = new AtomicReference<ResolvedPlugin>();
            ConnectorPluginRuntimeBootstrap.runWithReady(runtime, () -> v2.set(
                    PluginRuntimeResolvers.resolve(SourcePluginType.SOURCE, "mysql8")));
            assertEquals("codex-e2e-v2-bbbbbbbb", v2.get().getIdentity());
            assertNotEquals(v1.get().getDirectory(), v2.get().getDirectory());
            assertEquals("v1", java.nio.file.Files.readString(v1.get().getDirectory().resolve("marker.txt")));
            assertEquals("v2", java.nio.file.Files.readString(v2.get().getDirectory().resolve("marker.txt")));
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lookupRuntimeKeepsTaskCapabilityAndUsesItsPinnedIdentityWithoutLeakingTheToken() throws Exception {
        Map<String, String> identities = Map.of(
                "lookup-task-v1-capability", "lookup-task-v1-aaaaaaaa",
                "lookup-task-v2-capability", "lookup-task-v2-bbbbbbbb");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ConnectorPluginRuntimeBootstrap.ARTIFACT_PATH, exchange -> {
            requests.incrementAndGet();
            String capability = exchange.getRequestHeaders().getFirst(
                    AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER);
            String identity = identities.get(capability);
            if (identity == null) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set(ConnectorPluginRuntimeBootstrap.PLUGIN_IDENTITY_HEADER, identity);
            exchange.getResponseHeaders().set(ConnectorPluginRuntimeBootstrap.PLUGIN_COORDINATE_HEADER, "source/mysql8");
            byte[] archive = pluginArchive(capability.contains("v1") ? "v1" : "v2");
            exchange.sendResponseHeaders(200, archive.length);
            exchange.getResponseBody().write(archive);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            ResolvedPlugin v1 = resolveLookupPlugin(endpoint, "lookup-task-v1-capability");
            ResolvedPlugin v2 = resolveLookupPlugin(endpoint, "lookup-task-v2-capability");

            assertEquals("lookup-task-v1-aaaaaaaa", v1.getIdentity());
            assertEquals("lookup-task-v2-bbbbbbbb", v2.getIdentity());
            assertNotEquals(v1.getDirectory(), v2.getDirectory());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static ResolvedPlugin resolveLookupPlugin(String endpoint, String capability) throws Exception {
        AggregationFlinkTableRuntime source = new AggregationFlinkTableRuntime();
        source.setPluginName("mysql8");
        source.setPluginRuntimeEndpoint(endpoint);
        source.setPluginRuntimeToken(capability);

        AggregationFlinkTableRuntime lookup = AggregationLookupFunction.copyForLookup(
                source, "SELECT * FROM lookup_table WHERE id = 1");
        assertEquals(endpoint, lookup.getPluginRuntimeEndpoint());
        assertEquals(capability, lookup.getPluginRuntimeToken());

        ObjectMapper objectMapper = new ObjectMapper();
        String runtimePayload = objectMapper.writeValueAsString(
                AggregationFlinkTableRuntimePayload.fromRuntime(lookup));
        String auditPayload = objectMapper.writeValueAsString(
                AggregationFlinkTableRuntimePayload.auditFromRuntime(lookup));
        assertFalse(runtimePayload.contains(capability));
        assertFalse(auditPayload.contains(capability));
        assertFalse(runtimePayload.contains("pluginRuntimeToken"));
        assertFalse(auditPayload.contains("pluginRuntimeToken"));

        AtomicReference<ResolvedPlugin> resolved = new AtomicReference<ResolvedPlugin>();
        ConnectorPluginRuntimeBootstrap.runWithReady(lookup, () -> resolved.set(
                PluginRuntimeResolvers.resolve(SourcePluginType.SOURCE, "mysql8")));
        return resolved.get();
    }

    private static byte[] pluginArchive(String marker) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            archive.putNextEntry(new ZipEntry("plugin.json"));
            archive.write("{\"plugin\":{}}".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("plugin.jar"));
            archive.write(new byte[]{0x50, 0x4b, 0x03, 0x04});
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("marker.txt"));
            archive.write(marker.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        assertTrue(bytes.size() > 0);
        return bytes.toByteArray();
    }
}
