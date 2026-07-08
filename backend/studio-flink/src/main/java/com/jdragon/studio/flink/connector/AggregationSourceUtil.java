package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import org.apache.flink.table.types.DataType;

import java.util.ArrayList;
import java.util.List;

final class AggregationSourceUtil {
    private AggregationSourceUtil() {
    }

    static String buildQuery(AggregationFlinkTableRuntime runtime, DataType producedDataType) {
        String query = applyLimit(appendWhere(buildBaseQuery(runtime, producedDataType), runtime.getPushedFilters()),
                runtime.getMaxRows());
        runtime.addResolvedSourceSql(query);
        return query;
    }

    private static String buildBaseQuery(AggregationFlinkTableRuntime runtime, DataType producedDataType) {
        if (hasText(runtime.getScanSql())) {
            String scanSql = stripTrailingSemicolon(runtime.getScanSql());
            if (runtime.getPushedFilters() != null && !runtime.getPushedFilters().isEmpty()) {
                return "SELECT * FROM (" + scanSql + ") da_flink_src";
            }
            return scanSql;
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
        return "SELECT " + select + " FROM " + table;
    }

    static String buildLookupQuery(AggregationFlinkTableRuntime runtime, DataType producedDataType, List<String> keyNames, Object[] values) {
        List<String> whereParts = new ArrayList<String>();
        if (runtime.getPushedFilters() != null) {
            whereParts.addAll(runtime.getPushedFilters());
        }
        if (keyNames == null || keyNames.isEmpty() || values == null || values.length == 0) {
            String query = applyLimit(appendWhere(buildBaseQuery(runtime, producedDataType), whereParts),
                    firstPositive(runtime.getMaxRows(), 1));
            runtime.addResolvedSourceSql(query);
            return query;
        }
        for (int i = 0; i < keyNames.size() && i < values.length; i++) {
            whereParts.add(keyNames.get(i) + " = " + literal(values[i]));
        }
        String query = applyLimit(appendWhere(buildBaseQuery(runtime, producedDataType), whereParts),
                firstPositive(runtime.getMaxRows(), 1));
        runtime.addResolvedSourceSql(query);
        return query;
    }

    static String applyLimit(String query, Integer limit) {
        if (limit == null || limit <= 0 || query == null) {
            return query;
        }
        String upper = query.toUpperCase();
        if (containsTopLevelKeyword(upper, "LIMIT")
                || containsTopLevelKeyword(upper, "FETCH")
                || containsTopLevelKeyword(upper, "ROWNUM")) {
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

    private static String appendWhere(String query, List<String> whereParts) {
        if (whereParts == null || whereParts.isEmpty()) {
            return query;
        }
        List<String> safeParts = new ArrayList<String>();
        for (String part : whereParts) {
            if (hasText(part)) {
                safeParts.add("(" + part + ")");
            }
        }
        if (safeParts.isEmpty()) {
            return query;
        }
        String trimmed = stripTrailingSemicolon(query);
        return containsTopLevelKeyword(trimmed.toUpperCase(), "WHERE")
                ? trimmed + " AND " + String.join(" AND ", safeParts)
                : trimmed + " WHERE " + String.join(" AND ", safeParts);
    }

    private static boolean containsTopLevelKeyword(String query, String keyword) {
        if (query == null || keyword == null || keyword.isEmpty()) {
            return false;
        }
        String normalizedKeyword = keyword.toUpperCase();
        int depth = 0;
        char quote = 0;
        for (int index = 0; index < query.length(); index++) {
            char current = query.charAt(index);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                continue;
            }
            if (current == '(') {
                depth++;
                continue;
            }
            if (current == ')' && depth > 0) {
                depth--;
                continue;
            }
            if (depth == 0 && regionMatchesKeyword(query, index, normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean regionMatchesKeyword(String query, int index, String keyword) {
        int end = index + keyword.length();
        if (end > query.length() || !query.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        boolean before = index == 0 || !isIdentifierPart(query.charAt(index - 1));
        boolean after = end >= query.length() || !isIdentifierPart(query.charAt(end));
        return before && after;
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
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
