package com.jdragon.studio.flink.service;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.DataType;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AggregationFlinkDataTypeMapper {
    private AggregationFlinkDataTypeMapper() {
    }

    static DataType rowType(Map<String, Object> technicalMetadata, String pluginName) {
        List<DataTypes.Field> fields = new ArrayList<DataTypes.Field>();
        Object columns = technicalMetadata == null ? null : technicalMetadata.get("columns");
        if (columns instanceof List<?>) {
            Set<String> seen = new LinkedHashSet<String>();
            for (Object item : (List<?>) columns) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Map<?, ?> column = (Map<?, ?>) item;
                String name = firstText(column.get("name"), column.get("columnName"));
                if (name == null || !seen.add(name)) {
                    continue;
                }
                fields.add(DataTypes.FIELD(name, mapType(column)));
            }
        }
        if (fields.isEmpty()) {
            fields.add(DataTypes.FIELD("payload", DataTypes.STRING()));
        }
        return DataTypes.ROW(fields);
    }

    private static DataType mapType(Map<?, ?> column) {
        Integer dataType = asInteger(column.get("dataType"));
        String typeName = firstText(column.get("type"), column.get("typeName"));
        Integer size = asInteger(firstNonNull(column.get("size"), column.get("columnSize")));
        Integer scale = asInteger(firstNonNull(column.get("scale"), column.get("decimalDigits")));
        if (dataType != null) {
            return mapSqlType(dataType, size, scale);
        }
        return mapTypeName(typeName, size, scale);
    }

    private static DataType mapSqlType(int dataType, Integer size, Integer scale) {
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
        if (type.equals("date")) {
            return DataTypes.DATE();
        }
        if (type.contains("time")) {
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
}
