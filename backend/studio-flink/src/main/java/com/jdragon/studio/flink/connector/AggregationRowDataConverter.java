package com.jdragon.studio.flink.connector;

import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
                    DecimalType decimalType = (DecimalType) type.getLogicalType();
                    return DecimalData.fromBigDecimal(decimal.setScale(decimalType.getScale(), RoundingMode.HALF_UP),
                            decimalType.getPrecision(),
                            decimalType.getScale());
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
                        return millisOfDay(((Time) value).toLocalTime());
                    }
                    if (value instanceof LocalTime) {
                        return millisOfDay((LocalTime) value);
                    }
                    if (value instanceof Number) {
                        return ((Number) value).intValue();
                    }
                    return millisOfDay(LocalTime.parse(String.valueOf(value)));
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
                case ROW:
                    return convertRowValue(value, type);
                default:
                    return StringData.fromString(String.valueOf(value));
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to convert value '" + value + "' to Flink type "
                    + type.getLogicalType().asSerializableString(), ex);
        }
    }

    private Object convertRowValue(Object value, DataType type) {
        if (value instanceof GenericRowData) {
            return value;
        }
        List<String> nestedNames = DataType.getFieldNames(type);
        List<DataType> nestedTypes = DataType.getFieldDataTypes(type);
        GenericRowData rowData = new GenericRowData(nestedNames.size());
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (int i = 0; i < nestedNames.size(); i++) {
                rowData.setField(i, convertValue(map.get(nestedNames.get(i)), nestedTypes.get(i)));
            }
            return rowData;
        }
        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            for (int i = 0; i < nestedNames.size() && i < list.size(); i++) {
                rowData.setField(i, convertValue(list.get(i), nestedTypes.get(i)));
            }
            return rowData;
        }
        return null;
    }

    private int millisOfDay(LocalTime time) {
        return (int) (time.toNanoOfDay() / 1000000L);
    }
}
