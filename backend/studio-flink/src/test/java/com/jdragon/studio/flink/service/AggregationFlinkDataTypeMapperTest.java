package com.jdragon.studio.flink.service;

import org.apache.flink.table.types.DataType;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AggregationFlinkDataTypeMapperTest {

    @Test
    void mapsTechnicalColumnsToFlinkRowType() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("columns", Arrays.asList(
                column("id", Types.INTEGER, null, null, null),
                column("amount", Types.DECIMAL, null, 18, 2),
                column("created_at", Types.TIMESTAMP, null, null, null),
                column("payload", null, "json", null, null)
        ));

        DataType rowType = AggregationFlinkDataTypeMapper.rowType(metadata, "mysql8");
        List<String> names = DataType.getFieldNames(rowType);
        List<DataType> types = DataType.getFieldDataTypes(rowType);

        assertEquals(Arrays.asList("id", "amount", "created_at", "payload"), names);
        assertEquals("INT", types.get(0).getLogicalType().asSerializableString());
        assertEquals("DECIMAL(18, 2)", types.get(1).getLogicalType().asSerializableString());
        assertEquals("TIMESTAMP(3)", types.get(2).getLogicalType().asSerializableString());
        assertEquals("VARCHAR(2147483647)", types.get(3).getLogicalType().asSerializableString());
    }

    @Test
    void fallsBackToPayloadWhenMetadataHasNoColumns() {
        DataType rowType = AggregationFlinkDataTypeMapper.rowType(new LinkedHashMap<String, Object>(), "kafka");

        assertEquals(Arrays.asList("payload"), DataType.getFieldNames(rowType));
        assertEquals("VARCHAR(2147483647)",
                DataType.getFieldDataTypes(rowType).get(0).getLogicalType().asSerializableString());
    }

    @Test
    void addsVirtualFilePathContextFieldsOutsideRowColumns() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("columns", Arrays.asList(column("update_time", Types.DATE, null, null, null)));
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("field", "__path_inbound_date");
        context.put("displayName", "入库时间");
        context.put("type", "DATE");
        context.put("pathExpressions", Arrays.asList("wbsj/events/$getCurrentTime('yyyy','-1d')"));
        Map<String, Object> pushdown = new LinkedHashMap<String, Object>();
        pushdown.put("enabled", true);
        pushdown.put("required", true);
        pushdown.put("contexts", Arrays.asList(context));
        metadata.put("filePathPushdown", pushdown);

        DataType rowType = AggregationFlinkDataTypeMapper.rowType(metadata, "sftp");

        assertEquals(Arrays.asList("update_time", "__path_inbound_date"), DataType.getFieldNames(rowType));
        assertEquals("DATE", DataType.getFieldDataTypes(rowType).get(1).getLogicalType().asSerializableString());
    }

    private Map<String, Object> column(String name, Integer dataType, String typeName, Integer size, Integer scale) {
        Map<String, Object> column = new LinkedHashMap<String, Object>();
        column.put("name", name);
        if (dataType != null) {
            column.put("dataType", dataType);
        }
        if (typeName != null) {
            column.put("type", typeName);
        }
        if (size != null) {
            column.put("size", size);
        }
        if (scale != null) {
            column.put("scale", scale);
        }
        return column;
    }
}
