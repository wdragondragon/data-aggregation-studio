package com.jdragon.studio.infra.service.execution;

import com.jdragon.studio.dto.model.DataModelDefinition;

import java.util.Map;

final class AggregationFileModelPathSupport {

    private AggregationFileModelPathSupport() {
    }

    static String resolveFilePreviewPath(DataModelDefinition model) {
        if (model == null) {
            return "";
        }
        Map<String, Object> metadata = model.getTechnicalMetadata();
        String rootPath = metadata == null ? null : asString(metadata.get("rootPath"));
        String fileName = metadata == null ? null : asString(metadata.get("fileName"));
        String physicalLocator = model.getPhysicalLocator();
        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = physicalLocator;
        }
        if (rootPath == null || rootPath.trim().isEmpty()) {
            return fileName == null ? "" : fileName;
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            return rootPath;
        }
        String normalizedRoot = rootPath.replace('\\', '/');
        String normalizedName = fileName.replace('\\', '/');
        while (normalizedRoot.endsWith("/") && normalizedRoot.length() > 1) {
            normalizedRoot = normalizedRoot.substring(0, normalizedRoot.length() - 1);
        }
        while (normalizedName.startsWith("/")) {
            normalizedName = normalizedName.substring(1);
        }
        if ("/".equals(normalizedRoot)) {
            return "/" + normalizedName;
        }
        return normalizedRoot + "/" + normalizedName;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
