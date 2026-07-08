package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.pluginloader.LoadUtil;
import com.jdragon.aggregation.pluginloader.constant.SystemConstants;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class ConnectorPluginRuntimeBootstrap {
    private static final String RESOURCE_ROOT = "dataaggregation-plugin-runtime";
    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);
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
            URI uri = jarPath.toUri();
            throw new IllegalStateException("Bundled plugin runtime resource not found in " + uri);
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(path -> {
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
