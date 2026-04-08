package com.jdragon.studio.infra.service.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.PluginCategory;
import com.jdragon.studio.dto.model.PluginAssetDescriptor;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PluginCatalogScanner {

    private static final String[] DESCRIPTOR_FILES = new String[]{
            "plugin.json", "transformer.json"
    };

    private static final String[] TEMPLATE_FILES = new String[]{
            "template.json", "plugin_job_template.json"
    };

    private final ObjectMapper objectMapper;
    private final StudioPlatformProperties properties;

    public PluginCatalogScanner(ObjectMapper objectMapper, StudioPlatformProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<PluginAssetDescriptor> scan() {
        final Map<String, Path> pluginDirectories = new LinkedHashMap<String, Path>();
        List<Path> roots = resolveScanRoots();

        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new FileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (isDescriptorFile(file)) {
                            Path pluginDirectory = file.getParent();
                            if (pluginDirectory != null) {
                                pluginDirectories.put(pluginDirectory.toAbsolutePath().normalize().toString(), pluginDirectory.toAbsolutePath().normalize());
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ignored) {
            }
        }
        Map<String, PluginAssetDescriptor> deduplicated = new LinkedHashMap<String, PluginAssetDescriptor>();
        for (Path pluginDirectory : pluginDirectories.values()) {
            try {
                PluginAssetDescriptor descriptor = readDescriptor(pluginDirectory);
                if (descriptor != null) {
                    String key = String.valueOf(descriptor.getPluginCategory()) + "::" + String.valueOf(descriptor.getPluginName()).toLowerCase();
                    PluginAssetDescriptor existing = deduplicated.get(key);
                    if (existing == null || shouldReplace(existing, descriptor)) {
                        deduplicated.put(key, descriptor);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        List<PluginAssetDescriptor> descriptors = new ArrayList<PluginAssetDescriptor>(deduplicated.values());
        Collections.sort(descriptors, new Comparator<PluginAssetDescriptor>() {
            @Override
            public int compare(PluginAssetDescriptor left, PluginAssetDescriptor right) {
                int categoryCompare = String.valueOf(left.getPluginCategory()).compareToIgnoreCase(String.valueOf(right.getPluginCategory()));
                if (categoryCompare != 0) {
                    return categoryCompare;
                }
                return String.valueOf(left.getPluginName()).compareToIgnoreCase(String.valueOf(right.getPluginName()));
            }
        });
        return descriptors;
    }

    private boolean shouldReplace(PluginAssetDescriptor existing, PluginAssetDescriptor candidate) {
        String existingPath = existing.getAssetPath() == null ? "" : existing.getAssetPath().replace('\\', '/');
        String candidatePath = candidate.getAssetPath() == null ? "" : candidate.getAssetPath().replace('\\', '/');
        boolean existingPackaged = existingPath.contains("/package_all/aggregation/plugin/");
        boolean candidatePackaged = candidatePath.contains("/package_all/aggregation/plugin/");
        if (existingPackaged != candidatePackaged) {
            return candidatePackaged;
        }
        if (candidatePath.length() != existingPath.length()) {
            return candidatePath.length() < existingPath.length();
        }
        return candidatePath.compareToIgnoreCase(existingPath) < 0;
    }

    private List<Path> resolveScanRoots() {
        List<Path> roots = new ArrayList<Path>();
        Set<String> normalizedRoots = new LinkedHashSet<String>();

        Path aggregationHome = Paths.get(properties.getAggregationHome()).toAbsolutePath().normalize();
        if (Files.exists(aggregationHome)) {
            registerRoot(roots, normalizedRoots, aggregationHome.resolve("package_all").resolve("aggregation").resolve("plugin"));
            registerRoot(roots, normalizedRoots, aggregationHome.resolve("aggregation").resolve("plugin"));
            registerRoot(roots, normalizedRoots, aggregationHome.resolve("plugin"));
        }

        if (roots.isEmpty() && Files.exists(aggregationHome) && normalizedRoots.add(aggregationHome.toString())) {
            roots.add(aggregationHome);
        }

        if (roots.isEmpty()) {
            Path current = Paths.get(".").toAbsolutePath().normalize();
            if (Files.exists(current) && normalizedRoots.add(current.toString())) {
                roots.add(current);
            }
        }
        return roots;
    }

    private void registerRoot(List<Path> roots, Set<String> normalizedRoots, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (Files.exists(normalized) && normalizedRoots.add(normalized.toString())) {
            roots.add(normalized);
        }
    }

    private boolean isDescriptorFile(Path file) {
        String fileName = file.getFileName().toString();
        for (String descriptorFile : DESCRIPTOR_FILES) {
            if (descriptorFile.equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        for (String templateFile : TEMPLATE_FILES) {
            if (templateFile.equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        return false;
    }

    private PluginAssetDescriptor readDescriptor(Path pluginDirectory) throws IOException {
        Path assetJar = resolveAssetJar(pluginDirectory);
        if (assetJar == null) {
            return null;
        }

        Path descriptorFile = findFile(pluginDirectory, DESCRIPTOR_FILES);
        Path templateFile = findFile(pluginDirectory, TEMPLATE_FILES);
        Map<String, Object> descriptorJson = readJson(descriptorFile);
        Map<String, Object> templateJson = readJson(templateFile);

        PluginAssetDescriptor descriptor = new PluginAssetDescriptor();
        descriptor.setCode(assetJar.toAbsolutePath().toString());
        descriptor.setAssetPath(assetJar.toAbsolutePath().toString());
        descriptor.setAssetType("jar");
        descriptor.setExecutable(isExecutable(pluginDirectory));
        descriptor.setPluginCategory(resolveCategory(pluginDirectory));
        descriptor.setPluginName(resolvePluginName(pluginDirectory, descriptorJson, templateJson));
        descriptor.setMetadata(copyOrEmpty(descriptorJson == null || descriptorJson.isEmpty() ? templateJson : descriptorJson));
        descriptor.setTemplate(copyOrEmpty(templateJson == null || templateJson.isEmpty() ? descriptorJson : templateJson));
        return descriptor;
    }

    private Path resolveAssetJar(Path pluginDirectory) throws IOException {
        List<Path> jars = new ArrayList<Path>();
        for (Path child : listChildren(pluginDirectory)) {
            if (Files.isRegularFile(child) && child.getFileName().toString().toLowerCase().endsWith(".jar")) {
                jars.add(child);
            }
        }
        if (jars.isEmpty()) {
            return null;
        }
        Collections.sort(jars, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.getFileName().toString().compareToIgnoreCase(right.getFileName().toString());
            }
        });
        return jars.get(0);
    }

    private Path findFile(Path pluginDirectory, String[] fileNames) throws IOException {
        for (String fileName : fileNames) {
            Path candidate = pluginDirectory.resolve(fileName);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private List<Path> listChildren(Path directory) throws IOException {
        List<Path> children = new ArrayList<Path>();
        java.util.stream.Stream<Path> stream = Files.list(directory);
        try {
            for (java.util.Iterator<Path> iterator = stream.iterator(); iterator.hasNext(); ) {
                children.add(iterator.next());
            }
        } finally {
            stream.close();
        }
        return children;
    }

    private Map<String, Object> readJson(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        return objectMapper.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {
        });
    }

    private Map<String, Object> copyOrEmpty(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(source);
    }

    private boolean isExecutable(Path pluginDirectory) {
        String normalized = pluginDirectory.toString().replace('\\', '/');
        return normalized.contains("/reader/")
                || normalized.contains("/writer/")
                || normalized.contains("/plugin/reader/")
                || normalized.contains("/plugin/writer/")
                || normalized.contains("/plugin/source/");
    }

    private PluginCategory resolveCategory(Path pluginDirectory) {
        String normalized = pluginDirectory.toString().replace('\\', '/');
        if (normalized.contains("/plugin/source/") || normalized.contains("/data-source-handler-")) {
            return PluginCategory.SOURCE;
        }
        if (normalized.contains("/plugin/reader/") || normalized.contains("/reader/")) {
            return PluginCategory.READER;
        }
        if (normalized.contains("/plugin/writer/") || normalized.contains("/writer/")) {
            return PluginCategory.WRITER;
        }
        if (normalized.contains("/plugin/transformer/") || normalized.contains("/transformer/")) {
            return PluginCategory.TRANSFORMER;
        }
        return PluginCategory.REPORT;
    }

    private String resolvePluginName(Path pluginDirectory, Map<String, Object> descriptorJson, Map<String, Object> templateJson) {
        Object name = descriptorJson == null ? null : descriptorJson.get("name");
        if (name == null && templateJson != null) {
            name = templateJson.get("name");
        }
        if (name != null) {
            return String.valueOf(name);
        }
        return pluginDirectory.getFileName() == null ? pluginDirectory.toString() : pluginDirectory.getFileName().toString();
    }
}

