package com.jdragon.studio.nacos.compat.config;

import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NacosConfigContentSupport {

    private static final YamlPropertySourceLoader YAML_LOADER = new YamlPropertySourceLoader();

    private static final PropertiesPropertySourceLoader PROPERTIES_LOADER = new PropertiesPropertySourceLoader();

    private NacosConfigContentSupport() {
    }

    public static Map<String, Object> load(String name, String fileExtension, String content) throws IOException {
        String extension = normalizeExtension(fileExtension);
        ByteArrayResource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8), name) {
            @Override
            public String getFilename() {
                return name + "." + extension;
            }
        };
        List<PropertySource<?>> propertySources;
        if ("yaml".equals(extension) || "yml".equals(extension)) {
            propertySources = YAML_LOADER.load(name, resource);
        }
        else {
            propertySources = PROPERTIES_LOADER.load(name, resource);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (PropertySource<?> propertySource : propertySources) {
            Object source = propertySource.getSource();
            if (source instanceof Map<?, ?> sourceMap) {
                sourceMap.forEach((key, value) -> merged.put(String.valueOf(key), unwrapValue(value)));
            }
        }
        return merged;
    }

    private static Object unwrapValue(Object value) {
        if (value instanceof OriginTrackedValue originTrackedValue) {
            return originTrackedValue.getValue();
        }
        return value;
    }

    private static String normalizeExtension(String fileExtension) {
        if (fileExtension == null || fileExtension.isBlank()) {
            return "properties";
        }
        return fileExtension.trim().toLowerCase();
    }

}
