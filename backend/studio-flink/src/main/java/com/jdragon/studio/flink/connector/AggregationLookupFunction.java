package com.jdragon.studio.flink.connector;

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.table.types.DataType;

import java.util.ArrayList;
import java.util.List;

public class AggregationLookupFunction extends TableFunction<RowData> {
    private final String runtimeRef;
    private final int[][] lookupKeys;
    private final DataType producedDataType;

    public AggregationLookupFunction(String runtimeRef, int[][] lookupKeys, DataType producedDataType) {
        this.runtimeRef = runtimeRef;
        this.lookupKeys = lookupKeys;
        this.producedDataType = producedDataType;
    }

    public void eval(Object... values) throws Exception {
        AggregationFlinkTableRuntime runtime = AggregationFlinkRuntimeRegistry.required(runtimeRef);
        List<String> fieldNames = DataType.getFieldNames(producedDataType);
        List<String> keyNames = new ArrayList<String>();
        if (lookupKeys != null) {
            for (int[] key : lookupKeys) {
                if (key != null && key.length > 0 && key[0] >= 0 && key[0] < fieldNames.size()) {
                    keyNames.add(fieldNames.get(key[0]));
                }
            }
        }
        AggregationFlinkTableRuntime lookupRuntime = copyForLookup(runtime,
                AggregationSourceUtil.buildLookupQuery(runtime, producedDataType, keyNames, values));
        AggregationRowDataConverter converter = new AggregationRowDataConverter(producedDataType);
        new StructuredPluginSourceStrategy().readRows(lookupRuntime, row -> {
            collect(converter.convert(row));
            return true;
        });
    }

    private AggregationFlinkTableRuntime copyForLookup(AggregationFlinkTableRuntime source, String lookupSql) {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setRuntimeRef(source.getRuntimeRef());
        runtime.setDatasourceId(source.getDatasourceId());
        runtime.setModelId(source.getModelId());
        runtime.setPluginName(source.getPluginName());
        runtime.setTableName(source.getTableName());
        runtime.setPhysicalLocator(source.getPhysicalLocator());
        runtime.setScanSql(lookupSql);
        runtime.setScanMode(source.getScanMode());
        runtime.setMaxRows(1);
        runtime.setProducedDataType(source.getProducedDataType());
        runtime.setFieldNames(source.getFieldNames());
        runtime.setDataSourceDTO(source.getDataSourceDTO());
        runtime.setConnectionConfig(source.getConnectionConfig());
        runtime.setExtConfig(source.getExtConfig());
        runtime.setModelMetadata(source.getModelMetadata());
        return runtime;
    }
}
