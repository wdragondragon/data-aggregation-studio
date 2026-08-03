package com.jdragon.studio.flink.web.controller;

import com.jdragon.aggregation.pluginloader.JarLoaderCenter;
import com.jdragon.aggregation.pluginloader.constant.SystemConstants;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeResolvers;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.studio.flink.connector.AggregationFlinkRuntimeRegistry;
import com.jdragon.studio.flink.connector.AggregationFlinkTableRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkRuntimeControllerTest {
    private final String originalPluginHome = SystemConstants.PLUGIN_HOME;

    @AfterEach
    void restorePluginRuntime() {
        SystemConstants.PLUGIN_HOME = originalPluginHome;
        PluginRuntimeResolvers.reset();
        JarLoaderCenter.clearJarLoader();
    }

    @Test
    void streamsOnlyTheCapabilityBoundPluginDirectory() throws Exception {
        Path runtime = Files.createTempDirectory("flink-artifact-controller-");
        Path plugin = runtime.resolve("source").resolve("mysql8");
        Files.createDirectories(plugin);
        Files.writeString(plugin.resolve("plugin.json"), "{\"plugin\":{}}");
        Files.writeString(plugin.resolve("plugin.jar"), "plugin");
        SystemConstants.PLUGIN_HOME = runtime.toString();
        AggregationFlinkTableRuntime runtimeEntry = new AggregationFlinkTableRuntime();
        runtimeEntry.setPluginName("mysql8");
        String capability = AggregationFlinkRuntimeRegistry.registerCapability(runtimeEntry, 30);
        try {
            MockHttpServletResponse response = new MockHttpServletResponse();
            new FlinkRuntimeController().artifact(capability, "source", "mysql8", response);

            assertEquals("source/mysql8", response.getHeader("X-DataAggregation-Plugin-Coordinate"));
            assertTrue(response.getHeader("X-DataAggregation-Plugin-Identity").startsWith("local-"));
            assertTrue(contains(response.getContentAsByteArray(), "plugin.json"));
            assertTrue(contains(response.getContentAsByteArray(), "plugin.jar"));
            assertThrows(IllegalArgumentException.class,
                    () -> new FlinkRuntimeController().artifact(capability, "source", "mysql5",
                            new MockHttpServletResponse()));
        } finally {
            AggregationFlinkRuntimeRegistry.remove(capability);
        }
    }

    @Test
    void pinsArtifactIdentityWhenCapabilityIsIssuedBeforePointerChanges() throws Exception {
        Path runtime = Files.createTempDirectory("flink-artifact-pinned-");
        Path v1 = pluginDirectory(runtime.resolve("v1"), "v1");
        Path v2 = pluginDirectory(runtime.resolve("v2"), "v2");
        AtomicReference<ResolvedPlugin> active = new AtomicReference<ResolvedPlugin>(
                new ResolvedPlugin(SourcePluginType.SOURCE, "mysql8", v1, "release-v1"));
        PluginRuntimeResolvers.install((pluginType, pluginName) -> active.get());

        AggregationFlinkTableRuntime firstRuntime = new AggregationFlinkTableRuntime();
        firstRuntime.setPluginName("mysql8");
        String firstCapability = AggregationFlinkRuntimeRegistry.registerCapability(firstRuntime, 30);
        try {
            active.set(new ResolvedPlugin(SourcePluginType.SOURCE, "mysql8", v2, "release-v2"));

            MockHttpServletResponse firstResponse = new MockHttpServletResponse();
            new FlinkRuntimeController().artifact(firstCapability, "source", "mysql8", firstResponse);

            assertEquals("release-v1", firstResponse.getHeader("X-DataAggregation-Plugin-Identity"));
            assertEquals("v1", archiveEntry(firstResponse.getContentAsByteArray(), "marker.txt"));
            assertTrue(JarLoaderCenter.isDirectoryInUse(v1));

            AggregationFlinkTableRuntime secondRuntime = new AggregationFlinkTableRuntime();
            secondRuntime.setPluginName("mysql8");
            String secondCapability = AggregationFlinkRuntimeRegistry.registerCapability(secondRuntime, 30);
            try {
                MockHttpServletResponse secondResponse = new MockHttpServletResponse();
                new FlinkRuntimeController().artifact(secondCapability, "source", "mysql8", secondResponse);

                assertEquals("release-v2", secondResponse.getHeader("X-DataAggregation-Plugin-Identity"));
                assertEquals("v2", archiveEntry(secondResponse.getContentAsByteArray(), "marker.txt"));
            } finally {
                AggregationFlinkRuntimeRegistry.remove(secondCapability);
            }
        } finally {
            AggregationFlinkRuntimeRegistry.remove(firstCapability);
        }
        assertFalse(JarLoaderCenter.isDirectoryInUse(v1));
    }

    @Test
    void changesLocalArtifactIdentityWhenPluginContentsChangeInPlace() throws Exception {
        Path runtime = Files.createTempDirectory("flink-artifact-local-update-");
        Path plugin = pluginDirectory(runtime.resolve("source").resolve("mysql8"), "v1");
        SystemConstants.PLUGIN_HOME = runtime.toString();
        FlinkRuntimeController controller = new FlinkRuntimeController();

        AggregationFlinkTableRuntime firstRuntime = new AggregationFlinkTableRuntime();
        firstRuntime.setPluginName("mysql8");
        String firstCapability = AggregationFlinkRuntimeRegistry.registerCapability(firstRuntime, 30);
        try {
            MockHttpServletResponse firstResponse = new MockHttpServletResponse();
            controller.artifact(firstCapability, "source", "mysql8", firstResponse);
            String firstIdentity = firstResponse.getHeader("X-DataAggregation-Plugin-Identity");

            Files.writeString(plugin.resolve("marker.txt"), "v2");
            AggregationFlinkTableRuntime secondRuntime = new AggregationFlinkTableRuntime();
            secondRuntime.setPluginName("mysql8");
            String secondCapability = AggregationFlinkRuntimeRegistry.registerCapability(secondRuntime, 30);
            try {
                MockHttpServletResponse secondResponse = new MockHttpServletResponse();
                controller.artifact(secondCapability, "source", "mysql8", secondResponse);

                assertNotEquals(firstIdentity,
                        secondResponse.getHeader("X-DataAggregation-Plugin-Identity"));
                assertEquals("v2", archiveEntry(secondResponse.getContentAsByteArray(), "marker.txt"));
            } finally {
                AggregationFlinkRuntimeRegistry.remove(secondCapability);
            }
        } finally {
            AggregationFlinkRuntimeRegistry.remove(firstCapability);
        }
    }

    private boolean contains(byte[] archive, String entryName) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return true;
                }
            }
            return false;
        }
    }

    private String archiveEntry(byte[] archive, String entryName) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Missing archive entry: " + entryName);
    }

    private Path pluginDirectory(Path directory, String marker) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("plugin.json"), "{\"plugin\":{}}");
        Files.writeString(directory.resolve("plugin.jar"), "plugin");
        Files.writeString(directory.resolve("marker.txt"), marker);
        return directory;
    }
}
