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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PluginCatalogScanner {

    private static final String[] TARGET_FILES = new String[]{
            "plugin.json", "template.json", "plugin_job_template.json", "transformer.json"
    };

    private final ObjectMapper objectMapper;
    private final StudioPlatformProperties properties;

    public PluginCatalogScanner(ObjectMapper objectMapper, StudioPlatformProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<PluginAssetDescriptor> scan() {
        List<PluginAssetDescriptor> assets = new ArrayList<PluginAssetDescriptor>();
        List<Path> roots = new ArrayList<Path>();
        roots.add(Paths.get(properties.getAggregationHome()));
        roots.add(Paths.get("."));

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
                        String fileName = file.getFileName().toString();
                        for (String candidate : TARGET_FILES) {
                            if (candidate.equals(fileName)) {
                                assets.add(readDescriptor(file));
                                break;
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
        return assets;
    }

    private PluginAssetDescriptor readDescriptor(Path file) throws IOException {
        Map<String, Object> json = objectMapper.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {
        });
        PluginAssetDescriptor descriptor = new PluginAssetDescriptor();
        descriptor.setCode(file.toAbsolutePath().toString());
        descriptor.setAssetPath(file.toAbsolutePath().toString());
        descriptor.setAssetType(file.getFileName().toString());
        descriptor.setExecutable(isExecutable(file));
        descriptor.setPluginCategory(resolveCategory(file));
        descriptor.setPluginName(resolvePluginName(file, json));
        descriptor.setMetadata(new LinkedHashMap<String, Object>(json));
        descriptor.setTemplate(new LinkedHashMap<String, Object>(json));
        return descriptor;
    }

    private boolean isExecutable(Path file) {
        String normalized = file.toString().replace('\\', '/');
        return normalized.contains("/reader/")
                || normalized.contains("/writer/")
                || normalized.contains("/plugin/reader/")
                || normalized.contains("/plugin/writer/")
                || normalized.contains("/plugin/source/");
    }

    private PluginCategory resolveCategory(Path file) {
        String normalized = file.toString().replace('\\', '/');
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

    private String resolvePluginName(Path file, Map<String, Object> json) {
        Object name = json.get("name");
        if (name != null) {
            return String.valueOf(name);
        }
        return file.getParent() == null ? file.getFileName().toString() : file.getParent().getFileName().toString();
    }
}

