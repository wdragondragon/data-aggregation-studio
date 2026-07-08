package com.jdragon.studio.flink.execution;

import com.jdragon.studio.flink.connector.AggregationFlinkTableRuntime;
import org.apache.flink.table.types.DataType;

import java.util.List;

public final class FlinkTableDdlBuilder {
    private FlinkTableDdlBuilder() {
    }

    public static String buildCreateTemporaryTableDdl(String flinkTableName,
                                                      AggregationFlinkTableRuntime runtime,
                                                      FlinkRuntimeConnectorAccess access) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TEMPORARY TABLE ").append(quoteIdentifier(flinkTableName)).append(" (");
        List<String> fields = runtime.getFieldNames();
        List<org.apache.flink.table.types.DataType> fieldTypes =
                DataType.getFieldDataTypes(runtime.getProducedDataType());
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(quoteIdentifier(fields.get(i))).append(" ").append(fieldTypes.get(i).getLogicalType().asSerializableString());
        }
        ddl.append(") WITH (");
        boolean first = true;
        first = appendOption(ddl, "connector", "dataaggregation", first);
        if (access.isRemote()) {
            first = appendOption(ddl, "runtime.endpoint", access.getRuntimeEndpoint(), first);
            first = appendOption(ddl, "runtime.token", access.getRuntimeToken(), first);
        } else {
            first = appendOption(ddl, "runtime.ref", access.getRuntimeRef(), first);
        }
        first = appendOption(ddl, "datasource.id", String.valueOf(runtime.getDatasourceId()), first);
        first = appendOption(ddl, "model.id", String.valueOf(runtime.getModelId()), first);
        first = appendOption(ddl, "plugin.name", runtime.getPluginName(), first);
        first = appendOption(ddl, "scan.mode", runtime.getScanMode(), first);
        if (runtime.getMaxRows() != null && runtime.getMaxRows() > 0) {
            appendOption(ddl, "scan.max-rows", String.valueOf(runtime.getMaxRows()), first);
        }
        ddl.append(")");
        return ddl.toString();
    }

    private static boolean appendOption(StringBuilder ddl, String key, String value, boolean first) {
        if (!first) {
            ddl.append(", ");
        }
        ddl.append("'").append(key).append("' = '").append(value == null ? "" : value.replace("'", "''")).append("'");
        return false;
    }

    private static String quoteIdentifier(String value) {
        return "`" + value.replace("`", "``") + "`";
    }
}
