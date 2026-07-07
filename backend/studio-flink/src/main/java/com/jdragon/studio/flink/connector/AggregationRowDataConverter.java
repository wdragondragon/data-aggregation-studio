package com.jdragon.studio.flink.connector;

import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

class AggregationRowDataConverter {
    private final List<String> fieldNames;
    private final List<DataType> fieldTypes;

    AggregationRowDataConverter(DataType producedDataType) {
        this.fieldNames = DataType.getFieldNames(producedDataType);
        this.fieldTypes = DataType.getFieldDataTypes(producedDataType);
    }

    GenericRowData convert(Map<String, Object> row) {
        GenericRowData data = new GenericRowData(fieldNames.size());
        for (int i = 0; i < fieldNames.size(); i++) {
            Object value = row == null ? null : row.get(fieldNames.get(i));
            data.setField(i, convertValue(value, fieldTypes.get(i)));
        }
        return data;
    }

    private Object convertValue(Object value, DataType type) {
        if (value == null) {
            return null;
        }
        LogicalTypeRoot root = type.getLogicalType().getTypeRoot();
        try {
            switch (root) {
                case CHAR:
                case VARCHAR:
                    return StringData.fromString(String.valueOf(value));
                case BOOLEAN:
                    return value instanceof Boolean ? value : Boolean.parseBoolean(String.valueOf(value));
                case TINYINT:
                    return value instanceof Number ? ((Number) value).byteValue() : Byte.parseByte(String.valueOf(value));
                case SMALLINT:
                    return value instanceof Number ? ((Number) value).shortValue() : Short.parseShort(String.valueOf(value));
                case INTEGER:
                    return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
                case BIGINT:
                    return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value));
                case FLOAT:
                    return value instanceof Number ? ((Number) value).floatValue() : Float.parseFloat(String.valueOf(value));
                case DOUBLE:
                    return value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value));
                case DECIMAL:
                    BigDecimal decimal = value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value));
                    return DecimalData.fromBigDecimal(decimal, 38, Math.max(0, decimal.scale()));
                case DATE:
                    if (value instanceof Date) {
                        return (int) ((Date) value).toLocalDate().toEpochDay();
                    }
                    if (value instanceof LocalDate) {
                        return (int) ((LocalDate) value).toEpochDay();
                    }
                    return (int) LocalDate.parse(String.valueOf(value).substring(0, 10)).toEpochDay();
                case TIME_WITHOUT_TIME_ZONE:
                    if (value instanceof Time) {
                        return (int) ((Time) value).toLocalTime().toNanoOfDay() / 1000000;
                    }
                    return (int) (Time.valueOf(String.valueOf(value)).toLocalTime().toNanoOfDay() / 1000000);
                case TIMESTAMP_WITHOUT_TIME_ZONE:
                case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                    if (value instanceof Timestamp) {
                        return TimestampData.fromTimestamp((Timestamp) value);
                    }
                    if (value instanceof LocalDateTime) {
                        return TimestampData.fromLocalDateTime((LocalDateTime) value);
                    }
                    return TimestampData.fromLocalDateTime(LocalDateTime.parse(String.valueOf(value).replace(' ', 'T')));
                case BINARY:
                case VARBINARY:
                    return value instanceof byte[] ? value : String.valueOf(value).getBytes();
                default:
                    return StringData.fromString(String.valueOf(value));
            }
        } catch (Exception ignored) {
            return StringData.fromString(String.valueOf(value));
        }
    }
}
