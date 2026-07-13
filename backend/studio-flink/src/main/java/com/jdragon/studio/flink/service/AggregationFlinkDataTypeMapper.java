package com.jdragon.studio.flink.service;

import com.jdragon.studio.flink.connector.FilePathPushdownConfig;
import com.jdragon.studio.flink.connector.HttpPushdownMappingConfig;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.DataType;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AggregationFlinkDataTypeMapper {
    private AggregationFlinkDataTypeMapper() {
    }

    static DataType rowType(Map<String, Object> technicalMetadata, String pluginName) {
        return rowType(technicalMetadata, pluginName, null);
    }

    static DataType rowType(Map<String, Object> technicalMetadata,
                            String pluginName,
                            String physicalLocator) {
        List<DataTypes.Field> fields = new ArrayList<DataTypes.Field>();
        Set<String> seen = new LinkedHashSet<String>();
        Map<String, DataType> physicalFieldTypes = new LinkedHashMap<String, DataType>();
        Object columns = technicalMetadata == null ? null : technicalMetadata.get("columns");
        if (columns instanceof List<?>) {
            for (Object item : (List<?>) columns) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Map<?, ?> column = (Map<?, ?>) item;
                String name = firstText(column.get("name"), column.get("columnName"));
                if (name == null || !seen.add(name)) {
                    continue;
                }
                DataType type = mapType(column);
                physicalFieldTypes.put(name, type);
                fields.add(DataTypes.FIELD(name, type));
            }
        }
        if (fields.isEmpty()) {
            DataType payloadType = DataTypes.STRING();
            fields.add(DataTypes.FIELD("payload", payloadType));
            seen.add("payload");
            physicalFieldTypes.put("payload", payloadType);
        }
        FilePathPushdownConfig pathConfig = FilePathPushdownConfig.from(technicalMetadata);
        if (pathConfig.isEnabled()) {
            for (FilePathPushdownConfig.Context context : pathConfig.getContexts()) {
                if (seen.add(context.getField())) {
                    fields.add(DataTypes.FIELD(context.getField(), context.toDataType()));
                }
            }
        }
        if ("http".equalsIgnoreCase(pluginName)) {
            assertNoHttpNamespaceConflicts(physicalFieldTypes.keySet());
            addHttpPushdownFields(fields, seen, physicalFieldTypes, technicalMetadata, physicalLocator);
        }
        return DataTypes.ROW(fields);
    }

    private static void assertNoHttpNamespaceConflicts(Set<String> physicalFields) {
        List<String> conflicts = new ArrayList<String>();
        for (String physicalField : physicalFields) {
            String normalizedField = physicalField == null ? "" : physicalField.trim().toLowerCase(Locale.ENGLISH);
            String namespace = normalizedField.contains(".")
                    ? normalizedField.substring(0, normalizedField.indexOf('.'))
                    : normalizedField;
            if (HttpPushdownMappingConfig.isHttpLocation(namespace)) {
                conflicts.add(physicalField);
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException("HTTP 模型物理字段 " + String.join(", ", conflicts)
                    + " 与保留参数命名空间 param/query/body/header/path 冲突，"
                    + "请在建模时重命名物理字段后再规划 SQL");
        }
    }

    private static void addHttpPushdownFields(List<DataTypes.Field> fields,
                                              Set<String> seen,
                                              Map<String, DataType> physicalFieldTypes,
                                              Map<String, Object> technicalMetadata,
                                              String physicalLocator) {
        HttpPushdownMappingConfig config = HttpPushdownMappingConfig.from(technicalMetadata, physicalLocator);
        Map<String, Set<String>> configuredFieldsByLocation = config.fieldsByLocation();
        for (String location : HttpPushdownMappingConfig.httpLocations()) {
            Set<String> locationFields = new LinkedHashSet<String>();
            Set<String> configuredFields = configuredFieldsByLocation.get(location);
            if (configuredFields != null) {
                locationFields.addAll(configuredFields);
            }
            if ("query".equals(location)) {
                Set<String> paramFields = configuredFieldsByLocation.get("param");
                if (paramFields != null) {
                    locationFields.addAll(paramFields);
                }
            }
            for (String physicalField : physicalFieldTypes.keySet()) {
                if (!HttpPushdownMappingConfig.isSensitiveRequestField(physicalField)) {
                    locationFields.add(physicalField);
                }
            }
            List<DataTypes.Field> nestedFields = "body".equals(location)
                    ? nestedPathFields(locationFields, physicalFieldTypes)
                    : flatNestedFields(locationFields, physicalFieldTypes);
            if (!nestedFields.isEmpty() && seen.add(location)) {
                fields.add(DataTypes.FIELD(location, DataTypes.ROW(nestedFields)));
            }
        }
        for (String field : config.mappedFields()) {
            if (field != null && !field.trim().isEmpty() && seen.add(field)) {
                fields.add(DataTypes.FIELD(field, physicalFieldTypes.getOrDefault(field, DataTypes.STRING())));
            }
        }
    }

    private static List<DataTypes.Field> flatNestedFields(Set<String> fieldNames,
                                                           Map<String, DataType> physicalFieldTypes) {
        List<DataTypes.Field> fields = new ArrayList<DataTypes.Field>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String field : fieldNames) {
            if (field == null || field.trim().isEmpty() || !seen.add(field)) {
                continue;
            }
            fields.add(DataTypes.FIELD(field, physicalFieldTypes.getOrDefault(field, DataTypes.STRING())));
        }
        return fields;
    }

    private static List<DataTypes.Field> nestedPathFields(Set<String> paths,
                                                           Map<String, DataType> physicalFieldTypes) {
        FieldNode root = new FieldNode();
        for (String rawPath : paths) {
            String path = rawPath == null ? "" : rawPath.trim();
            if (path.isEmpty()) {
                continue;
            }
            List<String> parts = HttpPushdownMappingConfig.splitBodyPath(path);
            FieldNode current = root;
            for (int index = 0; index < parts.size(); index++) {
                String part = parts.get(index).trim();
                if (part.isEmpty()) {
                    continue;
                }
                current = current.children.computeIfAbsent(part, ignored -> new FieldNode());
                if (index == parts.size() - 1) {
                    current.type = physicalFieldTypes.getOrDefault(part,
                            physicalFieldTypes.getOrDefault(path, DataTypes.STRING()));
                }
            }
        }
        return root.toFields();
    }

    private static final class FieldNode {
        private final Map<String, FieldNode> children = new LinkedHashMap<String, FieldNode>();
        private DataType type;

        private List<DataTypes.Field> toFields() {
            List<DataTypes.Field> fields = new ArrayList<DataTypes.Field>();
            for (Map.Entry<String, FieldNode> entry : children.entrySet()) {
                FieldNode child = entry.getValue();
                DataType fieldType = child.children.isEmpty()
                        ? (child.type == null ? DataTypes.STRING() : child.type)
                        : DataTypes.ROW(child.toFields());
                fields.add(DataTypes.FIELD(entry.getKey(), fieldType));
            }
            return fields;
        }
    }

    private static DataType mapType(Map<?, ?> column) {
        Integer dataType = asInteger(column.get("dataType"));
        String typeName = firstText(column.get("type"), column.get("typeName"));
        Integer size = asInteger(firstNonNull(column.get("size"), column.get("columnSize")));
        Integer scale = asInteger(firstNonNull(column.get("scale"), column.get("decimalDigits")));
        if (dataType != null) {
            return mapSqlType(dataType, typeName, size, scale);
        }
        return mapTypeName(typeName, size, scale);
    }

    private static DataType mapSqlType(int dataType, String typeName, Integer size, Integer scale) {
        String normalizedTypeName = normalize(typeName);
        if ("year".equals(normalizedTypeName)) {
            return DataTypes.INT();
        }
        switch (dataType) {
            case Types.BIT:
            case Types.BOOLEAN:
                return DataTypes.BOOLEAN();
            case Types.TINYINT:
                return DataTypes.TINYINT();
            case Types.SMALLINT:
                return DataTypes.SMALLINT();
            case Types.INTEGER:
                return DataTypes.INT();
            case Types.BIGINT:
                return DataTypes.BIGINT();
            case Types.FLOAT:
            case Types.REAL:
                return DataTypes.FLOAT();
            case Types.DOUBLE:
                return DataTypes.DOUBLE();
            case Types.NUMERIC:
            case Types.DECIMAL:
                return DataTypes.DECIMAL(safePrecision(size), safeScale(scale));
            case Types.DATE:
                return DataTypes.DATE();
            case Types.TIME:
                return DataTypes.TIME();
            case Types.TIMESTAMP:
            case -101:
            case -102:
                return DataTypes.TIMESTAMP(3);
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return DataTypes.BYTES();
            default:
                return DataTypes.STRING();
        }
    }

    private static DataType mapTypeName(String typeName, Integer size, Integer scale) {
        String type = typeName == null ? "" : typeName.toLowerCase(Locale.ENGLISH);
        if (type.contains("bool")) {
            return DataTypes.BOOLEAN();
        }
        if (type.equals("tinyint")) {
            return DataTypes.TINYINT();
        }
        if (type.equals("smallint")) {
            return DataTypes.SMALLINT();
        }
        if (type.equals("int") || type.equals("integer")) {
            return DataTypes.INT();
        }
        if (type.contains("bigint") || type.equals("long")) {
            return DataTypes.BIGINT();
        }
        if (type.equals("float")) {
            return DataTypes.FLOAT();
        }
        if (type.equals("double") || type.contains("real")) {
            return DataTypes.DOUBLE();
        }
        if (type.contains("decimal") || type.contains("numeric")) {
            return DataTypes.DECIMAL(safePrecision(size), safeScale(scale));
        }
        if (type.equals("year")) {
            return DataTypes.INT();
        }
        if (type.equals("date")) {
            return DataTypes.DATE();
        }
        if (type.equals("time")) {
            return DataTypes.TIME();
        }
        if (type.contains("timestamp") || type.contains("datetime")) {
            return DataTypes.TIMESTAMP(3);
        }
        if (type.contains("binary") || type.contains("blob")) {
            return DataTypes.BYTES();
        }
        return DataTypes.STRING();
    }

    private static int safePrecision(Integer value) {
        return value == null || value < 1 || value > 38 ? 38 : value;
    }

    private static int safeScale(Integer value) {
        return value == null || value < 0 || value > 18 ? 0 : value;
    }

    private static String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
    }
}
