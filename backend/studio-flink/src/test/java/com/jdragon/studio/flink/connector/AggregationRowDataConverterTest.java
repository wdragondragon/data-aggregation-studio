package com.jdragon.studio.flink.connector;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.StringData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AggregationRowDataConverterTest {

    @Test
    void keepsDecimalScaleTimeAndBinaryPhysicalTypes() {
        AggregationRowDataConverter converter = new AggregationRowDataConverter(DataTypes.ROW(
                DataTypes.FIELD("amount", DataTypes.DECIMAL(18, 2)),
                DataTypes.FIELD("order_time", DataTypes.TIME()),
                DataTypes.FIELD("ship_time", DataTypes.TIME()),
                DataTypes.FIELD("payload", DataTypes.BYTES())));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("amount", new BigDecimal("4120.75"));
        row.put("order_time", Time.valueOf("12:34:56"));
        row.put("ship_time", "08:09:10");
        row.put("payload", new byte[]{1, 2, 3});

        GenericRowData converted = converter.convert(row);

        assertEquals(new BigDecimal("4120.75"), ((DecimalData) converted.getField(0)).toBigDecimal());
        assertEquals(millis("12:34:56"), converted.getField(1));
        assertEquals(millis("08:09:10"), converted.getField(2));
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) converted.getField(3));
    }

    @Test
    void acceptsNumericMillisForTimeFields() {
        AggregationRowDataConverter converter = new AggregationRowDataConverter(DataTypes.ROW(
                DataTypes.FIELD("order_time", DataTypes.TIME())));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("order_time", Integer.valueOf(45296000));

        GenericRowData converted = converter.convert(row);

        assertEquals(45296000, converted.getField(0));
    }

    @Test
    void acceptsTimestampPrefixForDateFields() {
        AggregationRowDataConverter converter = new AggregationRowDataConverter(DataTypes.ROW(
                DataTypes.FIELD("biz_date", DataTypes.DATE())));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("biz_date", "2026-07-12 10:15:30");

        GenericRowData converted = converter.convert(row);

        assertEquals(Math.toIntExact(LocalDate.of(2026, 7, 12).toEpochDay()), converted.getField(0));
    }

    @Test
    void convertsNestedRowValuesWhenPresent() {
        AggregationRowDataConverter converter = new AggregationRowDataConverter(DataTypes.ROW(
                DataTypes.FIELD("param", DataTypes.ROW(DataTypes.FIELD("customer_id", DataTypes.STRING())))));
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("customer_id", "C001");
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("param", nested);

        GenericRowData converted = converter.convert(row);
        GenericRowData param = (GenericRowData) converted.getField(0);

        assertEquals(StringData.fromString("C001"), param.getField(0));
    }

    @Test
    void convertsDottedNestedFieldNamesAsFlatMapKeys() {
        AggregationRowDataConverter converter = new AggregationRowDataConverter(DataTypes.ROW(
                DataTypes.FIELD("param", DataTypes.ROW(DataTypes.FIELD("customer.id", DataTypes.STRING())))));
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("customer.id", "C001");
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("param", nested);

        GenericRowData converted = converter.convert(row);
        GenericRowData param = (GenericRowData) converted.getField(0);

        assertEquals(StringData.fromString("C001"), param.getField(0));
    }

    @Test
    void failsFastWhenValueDoesNotMatchPhysicalType() {
        AggregationRowDataConverter converter = new AggregationRowDataConverter(DataTypes.ROW(
                DataTypes.FIELD("amount", DataTypes.DECIMAL(18, 2))));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("amount", "not-a-number");

        assertThrows(IllegalArgumentException.class, () -> converter.convert(row));
    }

    private int millis(String value) {
        return (int) (LocalTime.parse(value).toNanoOfDay() / 1000000L);
    }
}
