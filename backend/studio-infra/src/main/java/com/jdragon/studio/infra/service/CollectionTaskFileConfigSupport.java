package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataModelDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CollectionTaskFileConfigSupport {

    private static final Object NO_VALUE = null;

    private final CollectionTaskFieldMappingResolver fieldMappingResolver;

    CollectionTaskFileConfigSupport(CollectionTaskFieldMappingResolver fieldMappingResolver) {
        this.fieldMappingResolver = fieldMappingResolver;
    }

    Map<String, Object> buildReaderConfig(Map<String, Object> datasourceConnect,
                                          DataModelDefinition model,
                                          List<String> sourceFields) {
        Map<String, Object> readerConfig = new LinkedHashMap<String, Object>();
        readerConfig.put("connect", datasourceConnect);
        Map<String, Object> metadata = model == null || model.getTechnicalMetadata() == null
                ? Collections.<String, Object>emptyMap()
                : model.getTechnicalMetadata();
        Object rootPath = firstPresent(metadata, "rootPath");
        if (isBlankValue(rootPath) && model != null) {
            rootPath = model.getPhysicalLocator();
        }
        putIfPresent(readerConfig, "rootPath", rootPath, "/");
        putIfPresent(readerConfig, "partitionType", metadata.get("partitionType"), "glob");
        putIfPresent(readerConfig, "partition", firstPresent(metadata, "partition", "pattern"), "*");
        String fileType = resolveFileType(metadata.get("fileType"));
        putIfPresent(readerConfig, "fileType", fileType, "csv");
        putIfPresent(readerConfig, "encoding", metadata.get("encoding"), "UTF-8");
        putIfPresent(readerConfig, "delimiter", metadata.get("delimiter"), null);
        List<String> dataTags = fieldMappingResolver.resolveFileDataTags(model);
        if (!dataTags.isEmpty()) {
            if (!isEFileType(fileType)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "File model sourceKind=TAG is only supported for efile fileType");
            }
            readerConfig.put("dataTag", dataTags);
        }
        readerConfig.put("columns", fieldMappingResolver.resolveFileColumnEntries(model, sourceFields, dataTags));
        return readerConfig;
    }

    Map<String, Object> buildWriterConfig(Map<String, Object> datasourceConnect,
                                          DataModelDefinition model,
                                          List<String> targetFields) {
        Map<String, Object> writerConfig = new LinkedHashMap<String, Object>();
        writerConfig.put("connect", datasourceConnect);
        Map<String, Object> metadata = model == null || model.getTechnicalMetadata() == null
                ? Collections.<String, Object>emptyMap()
                : model.getTechnicalMetadata();
        Object rootPath = firstPresent(metadata, "rootPath");
        putIfPresent(writerConfig, "rootPath", rootPath, "/");
        Object fileName = firstPresent(metadata, "fileName");
        if (isBlankValue(fileName) && model != null) {
            fileName = model.getPhysicalLocator();
        }
        putIfPresent(writerConfig, "fileName", fileName, null);
        String fileType = resolveFileType(metadata.get("fileType"));
        putIfPresent(writerConfig, "fileType", fileType, "csv");
        putIfPresent(writerConfig, "encoding", metadata.get("encoding"), "UTF-8");
        putIfPresent(writerConfig, "delimiter", metadata.get("delimiter"), null);
        Map<String, Object> efileOptions = resolveEFileOptions(metadata);
        if (!efileOptions.isEmpty()) {
            writerConfig.put("efile", efileOptions);
        }
        List<Map<String, Object>> columns = fieldMappingResolver.resolveFileWriterColumnEntries(model, targetFields);
        if (fieldMappingResolver.hasTagFileField(columns) && !isEFileType(fileType)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "File model sourceKind=TAG is only supported for efile fileType");
        }
        writerConfig.put("columns", columns);
        return writerConfig;
    }

    private Map<String, Object> resolveEFileOptions(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (metadata == null || metadata.isEmpty()) {
            return result;
        }
        Object nested = metadata.get("efile");
        if (nested instanceof Map<?, ?>) {
            Map<?, ?> nestedMap = (Map<?, ?>) nested;
            putIfPresent(result, "entity", nestedMap.get("entity"), null);
            putIfPresent(result, "type", nestedMap.get("type"), null);
            putIfPresent(result, "dataTime", nestedMap.get("dataTime"), null);
            putIfPresent(result, "tableName", nestedMap.get("tableName"), null);
            putIfPresent(result, "tableCode", nestedMap.get("tableCode"), null);
            putIfPresent(result, "planDate", nestedMap.get("planDate"), null);
        }
        putIfPresent(result, "entity", firstPresent(metadata, "efile.entity"), result.get("entity"));
        putIfPresent(result, "type", firstPresent(metadata, "efile.type"), result.get("type"));
        putIfPresent(result, "dataTime", firstPresent(metadata, "efile.dataTime"), result.get("dataTime"));
        putIfPresent(result, "tableName", firstPresent(metadata, "efile.tableName"), result.get("tableName"));
        putIfPresent(result, "tableCode", firstPresent(metadata, "efile.tableCode"), result.get("tableCode"));
        putIfPresent(result, "planDate", firstPresent(metadata, "efile.planDate"), result.get("planDate"));
        return result;
    }

    private Object firstPresent(Map<String, Object> metadata, String... keys) {
        if (metadata == null || keys == null) {
            return NO_VALUE;
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (!isBlankValue(value)) {
                return value;
            }
        }
        return NO_VALUE;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value, Object defaultValue) {
        if (!isBlankValue(value)) {
            target.put(key, value);
        } else if (defaultValue != null) {
            target.put(key, defaultValue);
        }
    }

    private String resolveFileType(Object value) {
        return isBlankValue(value) ? "csv" : String.valueOf(value).trim().toLowerCase(Locale.ENGLISH);
    }

    private boolean isEFileType(String fileType) {
        return "efile".equalsIgnoreCase(fileType);
    }

    private boolean isBlankValue(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }
}
