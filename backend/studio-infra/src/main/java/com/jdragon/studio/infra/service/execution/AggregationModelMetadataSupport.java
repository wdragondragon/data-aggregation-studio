package com.jdragon.studio.infra.service.execution;

import com.jdragon.aggregation.datasource.ColumnInfo;
import com.jdragon.aggregation.datasource.TableInfo;
import com.jdragon.studio.dto.model.DataSourceDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AggregationModelMetadataSupport {

    private AggregationModelMetadataSupport() {
    }

    static Map<String, Object> buildRelationalMetadata(DataSourceDefinition definition,
                                                       String tableName,
                                                       TableInfo tableInfo,
                                                       List<ColumnInfo> columns) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceType", definition.getTypeCode());
        metadata.put("discoveryMode", "AUTO");
        metadata.put("physicalName", tableName);
        metadata.put("columnCount", columns == null ? 0 : columns.size());
        metadata.put("columns", toColumnMetadata(columns));
        if (tableInfo != null) {
            putIfPresent(metadata, "catalog", tableInfo.getTableCat());
            putIfPresent(metadata, "schema", tableInfo.getTableSchem());
            putIfPresent(metadata, "tableType", tableInfo.getTableType());
            putIfPresent(metadata, "remarks", tableInfo.getRemarks());
            metadata.put("partitioned", tableInfo.isPartitioned());
            if (tableInfo.getExternalTable() != null) {
                metadata.put("externalTable", tableInfo.getExternalTable());
            }
        }
        return metadata;
    }

    static Map<String, Object> buildLightweightRelationalMetadata(DataSourceDefinition definition, String tableName) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceType", definition.getTypeCode());
        metadata.put("discoveryMode", "AUTO");
        metadata.put("physicalName", tableName);
        return metadata;
    }

    static Map<String, Object> buildFileMetadata(DataSourceDefinition definition,
                                                 Map<String, Object> datasourceMetadata,
                                                 String rootPath,
                                                 String regex,
                                                 String fileName) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceType", definition.getTypeCode());
        metadata.put("discoveryMode", "AUTO");
        putIfPresent(metadata, "physicalName", fileName);
        putIfPresent(metadata, "rootPath", rootPath);
        putIfPresent(metadata, "pattern", regex);
        putIfPresent(metadata, "fileName", fileName);
        putIfPresent(metadata, "fileType", datasourceMetadata.get("fileType"));
        putIfPresent(metadata, "encoding", datasourceMetadata.get("encoding"));
        putIfPresent(metadata, "delimiter", datasourceMetadata.get("delimiter"));
        return metadata;
    }

    static Map<String, Object> buildQueueMetadata(DataSourceDefinition definition,
                                                  Map<String, Object> datasourceMetadata,
                                                  String queueName) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceType", definition.getTypeCode());
        metadata.put("discoveryMode", "AUTO");
        putIfPresent(metadata, "physicalName", queueName);
        if ("kafka".equalsIgnoreCase(definition.getTypeCode())) {
            return metadata;
        }
        putIfPresent(metadata, "queueName", queueName);
        // Queue/topic is the model locator. Do not copy a datasource-level
        // topic into every model, otherwise one legacy topic can override the
        // explicitly selected model object during task assembly.
        putIfPresent(metadata, "topic", queueName);
        putIfPresent(metadata, "queue", queueName);
        putIfPresent(metadata, "brokers", datasourceMetadata.get("brokers"));
        putIfPresent(metadata, "consumerGroup", datasourceMetadata.get("consumerGroup"));
        putIfPresent(metadata, "tag", datasourceMetadata.get("tag"));
        return metadata;
    }

    private static List<Map<String, Object>> toColumnMetadata(List<ColumnInfo> columns) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (columns == null) {
            return items;
        }
        for (ColumnInfo column : columns) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            putIfPresent(item, "name", column.getColumnName());
            putIfPresent(item, "type", column.getTypeName());
            if (column.getColumnSize() > 0) {
                item.put("size", column.getColumnSize());
            }
            if (column.getDecimalDigits() > 0) {
                item.put("scale", column.getDecimalDigits());
            }
            putIfPresent(item, "nullable", column.getIsNullable());
            putIfPresent(item, "primaryKey", column.getIsPrimaryKey());
            putIfPresent(item, "autoIncrement", column.getIsAutoincrement());
            if (column.isPartitionColumn()) {
                item.put("partitionColumn", Boolean.TRUE);
            }
            putIfPresent(item, "remarks", column.getRemarks());
            putIfPresent(item, "defaultValue", column.getColumnDef());
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String && ((String) value).trim().isEmpty()) {
            return;
        }
        if (value instanceof Set && ((Set<?>) value).isEmpty()) {
            return;
        }
        if (value instanceof List && ((List<?>) value).isEmpty()) {
            return;
        }
        target.put(key, value);
    }
}
