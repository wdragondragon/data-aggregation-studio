package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import org.apache.flink.table.types.DataType;

import java.util.ArrayList;
import java.util.List;

final class AggregationSourceUtil {
    private AggregationSourceUtil() {
    }

    static String buildQuery(AggregationFlinkTableRuntime runtime, DataType producedDataType) {
        if (hasText(runtime.getScanSql())) {
            return applyLimit(runtime.getScanSql(), runtime.getMaxRows());
        }
        String table = runtime.getPhysicalLocator();
        if (!hasText(table)) {
            table = runtime.getTableName();
        }
        if (!hasText(table)) {
            throw new IllegalArgumentException("DataAggregation table or scan.sql is required");
        }
        List<String> columns = producedDataType == null
                ? runtime.getFieldNames()
                : DataType.getFieldNames(producedDataType);
        String select = columns == null || columns.isEmpty() ? "*" : joinColumns(columns);
        return applyLimit("SELECT " + select + " FROM " + table, runtime.getMaxRows());
    }

    static String buildLookupQuery(AggregationFlinkTableRuntime runtime, DataType producedDataType, List<String> keyNames, Object[] values) {
        String base = buildQuery(runtime, producedDataType);
        if (keyNames == null || keyNames.isEmpty() || values == null || values.length == 0) {
            return applyLimit(base, firstPositive(runtime.getMaxRows(), 1));
        }
        String trimmed = stripTrailingSemicolon(base);
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < keyNames.size() && i < values.length; i++) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append(keyNames.get(i)).append(" = ").append(literal(values[i]));
        }
        String upper = trimmed.toUpperCase();
        String query = upper.contains(" WHERE ") ? trimmed + " AND " + where : trimmed + " WHERE " + where;
        return applyLimit(query, firstPositive(runtime.getMaxRows(), 1));
    }

    static String applyLimit(String query, Integer limit) {
        if (limit == null || limit <= 0 || query == null) {
            return query;
        }
        String upper = query.toUpperCase();
        if (upper.contains(" LIMIT ") || upper.contains(" FETCH ") || upper.contains(" ROWNUM ")) {
            return query;
        }
        return stripTrailingSemicolon(query) + " LIMIT " + limit;
    }

    static BaseDataSourceDTO copyDataSource(BaseDataSourceDTO source) {
        BaseDataSourceDTO target = new BaseDataSourceDTO();
        if (source == null) {
            return target;
        }
        target.setName(source.getName());
        target.setType(source.getType());
        target.setHost(source.getHost());
        target.setPort(source.getPort());
        target.setDatabase(source.getDatabase());
        target.setUserName(source.getUserName());
        target.setPassword(source.getPassword());
        target.setOther(source.getOther());
        target.setUsePool(source.isUsePool());
        target.setExtraParams(source.getExtraParams());
        target.setBucket(source.getBucket());
        target.setPrincipal(source.getPrincipal());
        target.setKeytabPath(source.getKeytabPath());
        target.setKrb5File(source.getKrb5File());
        target.setJdbcUrl(source.getJdbcUrl());
        target.setDriverClassName(source.getDriverClassName());
        return target;
    }

    static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String joinColumns(List<String> columns) {
        List<String> safe = new ArrayList<String>();
        for (String column : columns) {
            if (hasText(column)) {
                safe.add(column);
            }
        }
        return safe.isEmpty() ? "*" : String.join(", ", safe);
    }

    private static String stripTrailingSemicolon(String query) {
        String trimmed = query == null ? "" : query.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static int firstPositive(Integer first, int fallback) {
        return first != null && first > 0 ? first : fallback;
    }

    private static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }
}
