package com.jdragon.studio.flink.connector;

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.table.types.logical.utils.LogicalTypeChecks;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class AggregationLookupFunction extends TableFunction<RowData> {
    private final AggregationRuntimeHandle runtimeHandle;
    private final int[][] lookupKeys;
    private final DataType producedDataType;

    public AggregationLookupFunction(AggregationRuntimeHandle runtimeHandle, int[][] lookupKeys, DataType producedDataType) {
        this.runtimeHandle = runtimeHandle;
        this.lookupKeys = lookupKeys;
        this.producedDataType = producedDataType;
    }

    public void eval(Object... values) throws Exception {
        AggregationFlinkTableRuntime runtime = AggregationRuntimeResolver.resolve(runtimeHandle);
        List<String> keyNames = resolveLookupKeyNames(producedDataType, lookupKeys);
        List<DataType> keyTypes = resolveLookupKeyDataTypes(producedDataType, lookupKeys);
        if (isHttp(runtime) && containsNull(values)) {
            return;
        }
        HttpLookupPlan httpLookupPlan = buildHttpLookupPlan(runtime, keyNames, keyTypes, values);
        if (httpLookupPlan.isNoMatch()) {
            return;
        }
        AggregationFlinkTableRuntime lookupRuntime = copyForLookup(runtime,
                AggregationSourceUtil.buildLookupQuery(runtime, producedDataType, keyNames, values));
        lookupRuntime.setHttpPushdownFilters(httpLookupPlan.getPushdownFilters());
        AggregationRowDataConverter converter = new AggregationRowDataConverter(producedDataType);
        new StructuredPluginSourceStrategy().readRows(lookupRuntime, row -> emitLookupMatch(
                row,
                httpLookupPlan.getResidualValues(),
                httpLookupPlan.getResidualTypes(),
                matched -> collect(converter.convert(matched))));
        AggregationRuntimeResolver.updateAudit(runtimeHandle, lookupRuntime);
    }

    static HttpLookupPlan buildHttpLookupPlan(AggregationFlinkTableRuntime runtime,
                                              List<String> keyNames,
                                              Object[] values) {
        return buildHttpLookupPlan(runtime, keyNames, Collections.<DataType>emptyList(), values);
    }

    static HttpLookupPlan buildHttpLookupPlan(AggregationFlinkTableRuntime runtime,
                                              List<String> keyNames,
                                              List<DataType> keyTypes,
                                              Object[] values) {
        if (!isHttp(runtime) || keyNames == null || values == null) {
            return HttpLookupPlan.empty();
        }
        HttpPushdownMappingConfig config = HttpPushdownMappingConfig.from(
                runtime.getModelMetadata(), runtime.getPhysicalLocator());
        List<Map<String, Object>> pushdownFilters = new ArrayList<Map<String, Object>>(
                runtime.getHttpPushdownFilters());
        Map<String, Object> residualValues = new LinkedHashMap<String, Object>();
        Map<String, DataType> residualTypes = new LinkedHashMap<String, DataType>();
        for (int index = 0; index < keyNames.size() && index < values.length; index++) {
            String keyName = normalizeKeyName(keyNames.get(index));
            DataType keyType = keyTypes != null && index < keyTypes.size() ? keyTypes.get(index) : null;
            Object value = normalizeLookupValue(values[index], keyType);
            HttpPushdownMappingConfig.Mapping mapping = resolveLookupMapping(config, keyName);
            if (mapping == null) {
                String residualField = leafField(keyName);
                residualValues.put(residualField, value);
                if (keyType != null) {
                    residualTypes.put(residualField, keyType);
                }
                continue;
            }
            Map<String, Object> dynamicFilter = mapping.toPushdownPredicate("=", Collections.singletonList(value),
                    keyName + " = " + String.valueOf(value));
            if (!mergeHttpLookupFilter(pushdownFilters, dynamicFilter)) {
                return HttpLookupPlan.noMatch();
            }
        }
        HttpBodyPushdownValidator.validate(runtime, pushdownFilters);
        return new HttpLookupPlan(pushdownFilters, residualValues, residualTypes, false);
    }

    static List<String> resolveLookupKeyNames(DataType rowType, int[][] keys) {
        List<String> names = new ArrayList<String>();
        for (LookupKey lookupKey : resolveLookupKeys(rowType, keys)) {
            names.add(lookupKey.name);
        }
        return names;
    }

    static List<DataType> resolveLookupKeyDataTypes(DataType rowType, int[][] keys) {
        List<DataType> types = new ArrayList<DataType>();
        for (LookupKey lookupKey : resolveLookupKeys(rowType, keys)) {
            types.add(lookupKey.dataType);
        }
        return types;
    }

    private static List<LookupKey> resolveLookupKeys(DataType rowType, int[][] keys) {
        List<LookupKey> resolved = new ArrayList<LookupKey>();
        if (rowType == null || keys == null) {
            return resolved;
        }
        for (int[] key : keys) {
            DataType currentType = rowType;
            List<String> path = new ArrayList<String>();
            boolean valid = key != null && key.length > 0;
            if (valid) {
                for (int index : key) {
                    List<String> fieldNames = DataType.getFieldNames(currentType);
                    List<DataType> fieldTypes = DataType.getFieldDataTypes(currentType);
                    if (index < 0 || index >= fieldNames.size() || index >= fieldTypes.size()) {
                        valid = false;
                        break;
                    }
                    path.add(fieldNames.get(index));
                    currentType = fieldTypes.get(index);
                }
            }
            if (valid && !path.isEmpty()) {
                resolved.add(new LookupKey(String.join(".", path), currentType));
            }
        }
        return resolved;
    }

    private static boolean mergeHttpLookupFilter(List<Map<String, Object>> filters,
                                                 Map<String, Object> dynamicFilter) {
        String dynamicTarget = httpFilterTarget(dynamicFilter);
        boolean targetAlreadyPresent = false;
        for (Map<String, Object> existing : filters) {
            if (!dynamicTarget.equals(httpFilterTarget(existing))) {
                continue;
            }
            targetAlreadyPresent = true;
            if (!lookupValuesEqual(existing.get("values"), dynamicFilter.get("values"))) {
                return false;
            }
        }
        if (!targetAlreadyPresent) {
            filters.add(dynamicFilter);
        }
        return true;
    }

    private static String httpFilterTarget(Map<String, Object> filter) {
        String location = HttpPushdownMappingConfig.normalizeLocation(stringValue(filter.get("location")));
        if ("param".equals(location) || "query".equals(location)) {
            return "query." + stringValue(filter.get("requestParamName"));
        }
        if ("body".equals(location)) {
            return "body." + stringValue(filter.get("bodyPath"));
        }
        if ("header".equals(location)) {
            return "header." + stringValue(filter.get("headerName")).toLowerCase(java.util.Locale.ENGLISH);
        }
        if ("path".equals(location)) {
            return "path." + stringValue(filter.get("pathVariable"));
        }
        return location + "." + stringValue(filter.get("field"));
    }

    private static boolean lookupValuesEqual(Object left, Object right) {
        List<?> leftValues = left instanceof List<?> ? (List<?>) left : Collections.singletonList(left);
        List<?> rightValues = right instanceof List<?> ? (List<?>) right : Collections.singletonList(right);
        if (leftValues.size() != rightValues.size()) {
            return false;
        }
        for (int index = 0; index < leftValues.size(); index++) {
            if (!lookupValueEquals(leftValues.get(index), rightValues.get(index), null)) {
                return false;
            }
        }
        return true;
    }

    private static HttpPushdownMappingConfig.Mapping resolveLookupMapping(HttpPushdownMappingConfig config,
                                                                           String keyName) {
        String[] parts = keyName.split("\\.");
        if (parts.length >= 2 && HttpPushdownMappingConfig.isHttpLocation(parts[0])) {
            return config.findByLocationAndField(parts[0], String.join(".", java.util.Arrays.copyOfRange(parts, 1, parts.length)));
        }
        List<HttpPushdownMappingConfig.Mapping> mappings = config.findByField(keyName);
        if (mappings.size() > 1) {
            List<String> locations = new ArrayList<String>();
            for (HttpPushdownMappingConfig.Mapping mapping : mappings) {
                locations.add(mapping.getLocation());
            }
            throw new IllegalArgumentException("HTTP lookup 字段 " + keyName + " 同时映射到 "
                    + String.join(", ", locations) + "，无法确定请求参数位置");
        }
        return mappings.isEmpty() ? null : mappings.get(0);
    }

    static boolean matchesResidualLookup(Map<String, Object> row, Map<String, Object> residualValues) {
        return matchesResidualLookup(row, residualValues, Collections.<String, DataType>emptyMap());
    }

    static boolean matchesResidualLookup(Map<String, Object> row,
                                         Map<String, Object> residualValues,
                                         Map<String, DataType> residualTypes) {
        if (residualValues == null || residualValues.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> entry : residualValues.entrySet()) {
            DataType dataType = residualTypes == null ? null : residualTypes.get(entry.getKey());
            if (!lookupValueEquals(row == null ? null : row.get(entry.getKey()), entry.getValue(), dataType)) {
                return false;
            }
        }
        return true;
    }

    static boolean emitLookupMatch(Map<String, Object> row,
                                   Map<String, Object> residualValues,
                                   Consumer<Map<String, Object>> collector) {
        return emitLookupMatch(row, residualValues, Collections.<String, DataType>emptyMap(), collector);
    }

    static boolean emitLookupMatch(Map<String, Object> row,
                                   Map<String, Object> residualValues,
                                   Map<String, DataType> residualTypes,
                                   Consumer<Map<String, Object>> collector) {
        if (!matchesResidualLookup(row, residualValues, residualTypes)) {
            return true;
        }
        if (collector != null) {
            collector.accept(row);
        }
        return true;
    }

    private static boolean lookupValueEquals(Object actual, Object expected, DataType dataType) {
        actual = normalizeLookupValue(actual, dataType);
        expected = normalizeLookupValue(expected, dataType);
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (actual instanceof Number && expected instanceof Number) {
            try {
                return new BigDecimal(String.valueOf(actual)).compareTo(new BigDecimal(String.valueOf(expected))) == 0;
            } catch (NumberFormatException ignored) {
            }
        }
        return Objects.deepEquals(actual, expected) || String.valueOf(actual).equals(String.valueOf(expected));
    }

    private static Object normalizeLookupValue(Object value, DataType dataType) {
        if (value instanceof StringData) {
            value = value.toString();
        }
        if (value instanceof DecimalData) {
            value = ((DecimalData) value).toBigDecimal();
        }
        if (value instanceof TimestampData) {
            value = ((TimestampData) value).toLocalDateTime();
        }
        if (dataType == null) {
            return value;
        }
        LogicalTypeRoot typeRoot = dataType.getLogicalType().getTypeRoot();
        if (typeRoot == LogicalTypeRoot.DATE) {
            return normalizeDate(value);
        }
        if (typeRoot == LogicalTypeRoot.TIME_WITHOUT_TIME_ZONE) {
            return normalizeTime(value, temporalPrecision(dataType));
        }
        if (typeRoot == LogicalTypeRoot.TIMESTAMP_WITHOUT_TIME_ZONE
                || typeRoot == LogicalTypeRoot.TIMESTAMP_WITH_LOCAL_TIME_ZONE
                || typeRoot == LogicalTypeRoot.TIMESTAMP_WITH_TIME_ZONE) {
            return normalizeTimestamp(value, temporalPrecision(dataType));
        }
        return value;
    }

    private static Object normalizeDate(Object value) {
        if (value instanceof LocalDate) {
            return value;
        }
        if (value instanceof Date) {
            return ((Date) value).toLocalDate();
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toLocalDate();
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime().toLocalDate();
        }
        if (value instanceof Number) {
            return LocalDate.ofEpochDay(((Number) value).longValue());
        }
        if (value instanceof CharSequence) {
            String text = String.valueOf(value).trim();
            try {
                return LocalDate.parse(text.substring(0, 10));
            } catch (RuntimeException ignored) {
            }
        }
        return value;
    }

    private static Object normalizeTime(Object value, int precision) {
        LocalTime time = null;
        if (value instanceof LocalTime) {
            time = (LocalTime) value;
        } else if (value instanceof Time) {
            time = ((Time) value).toLocalTime();
        } else if (value instanceof LocalDateTime) {
            time = ((LocalDateTime) value).toLocalTime();
        } else if (value instanceof Timestamp) {
            time = ((Timestamp) value).toLocalDateTime().toLocalTime();
        } else if (value instanceof Number) {
            time = LocalTime.ofNanoOfDay(((Number) value).longValue() * 1_000_000L);
        } else if (value instanceof CharSequence) {
            try {
                time = LocalTime.parse(String.valueOf(value).trim());
            } catch (RuntimeException ignored) {
            }
        }
        return time == null ? value : time.withNano(truncateNanos(time.getNano(), precision));
    }

    private static Object normalizeTimestamp(Object value, int precision) {
        LocalDateTime timestamp = null;
        if (value instanceof LocalDateTime) {
            timestamp = (LocalDateTime) value;
        } else if (value instanceof Timestamp) {
            timestamp = ((Timestamp) value).toLocalDateTime();
        } else if (value instanceof Date) {
            timestamp = ((Date) value).toLocalDate().atStartOfDay();
        } else if (value instanceof LocalDate) {
            timestamp = ((LocalDate) value).atStartOfDay();
        } else if (value instanceof CharSequence) {
            String text = String.valueOf(value).trim();
            try {
                timestamp = LocalDateTime.parse(text.replace(' ', 'T'));
            } catch (RuntimeException ignored) {
            }
        }
        return timestamp == null
                ? value
                : timestamp.withNano(truncateNanos(timestamp.getNano(), precision));
    }

    private static int temporalPrecision(DataType dataType) {
        try {
            return LogicalTypeChecks.getPrecision(dataType.getLogicalType());
        } catch (RuntimeException ignored) {
            return 9;
        }
    }

    private static int truncateNanos(int nanos, int precision) {
        int boundedPrecision = Math.max(0, Math.min(9, precision));
        int factor = 1;
        for (int index = boundedPrecision; index < 9; index++) {
            factor *= 10;
        }
        return factor == 1 ? nanos : nanos / factor * factor;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean containsNull(Object[] values) {
        if (values == null) {
            return true;
        }
        for (Object value : values) {
            if (value == null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHttp(AggregationFlinkTableRuntime runtime) {
        return runtime != null && "http".equalsIgnoreCase(runtime.getPluginName());
    }

    private static String normalizeKeyName(String value) {
        return value == null ? "" : value.replace("`", "").replace("\"", "").trim();
    }

    private static String leafField(String value) {
        int index = value == null ? -1 : value.lastIndexOf('.');
        return index < 0 ? value : value.substring(index + 1);
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
        runtime.setMaxRows(isHttp(source) ? 0 : 1);
        runtime.setProducedDataType(source.getProducedDataType());
        runtime.setFieldNames(source.getFieldNames());
        runtime.setDataSourceDTO(source.getDataSourceDTO());
        runtime.setConnectionConfig(source.getConnectionConfig());
        runtime.setExtConfig(source.getExtConfig());
        runtime.setModelMetadata(source.getModelMetadata());
        runtime.setPushedFilters(source.getPushedFilters());
        runtime.setRemainingFilters(source.getRemainingFilters());
        runtime.setPathContextFilters(source.getPathContextFilters());
        runtime.setHttpPushdownFilters(source.getHttpPushdownFilters());
        runtime.setHttpFilterAlwaysFalse(source.isHttpFilterAlwaysFalse());
        return runtime;
    }

    static final class HttpLookupPlan {
        private final List<Map<String, Object>> pushdownFilters;
        private final Map<String, Object> residualValues;
        private final Map<String, DataType> residualTypes;
        private final boolean noMatch;

        private HttpLookupPlan(List<Map<String, Object>> pushdownFilters,
                               Map<String, Object> residualValues,
                               Map<String, DataType> residualTypes,
                               boolean noMatch) {
            this.pushdownFilters = pushdownFilters == null
                    ? new ArrayList<Map<String, Object>>()
                    : new ArrayList<Map<String, Object>>(pushdownFilters);
            this.residualValues = residualValues == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(residualValues);
            this.residualTypes = residualTypes == null
                    ? new LinkedHashMap<String, DataType>()
                    : new LinkedHashMap<String, DataType>(residualTypes);
            this.noMatch = noMatch;
        }

        static HttpLookupPlan empty() {
            return new HttpLookupPlan(Collections.<Map<String, Object>>emptyList(),
                    Collections.<String, Object>emptyMap(),
                    Collections.<String, DataType>emptyMap(),
                    false);
        }

        static HttpLookupPlan noMatch() {
            return new HttpLookupPlan(Collections.<Map<String, Object>>emptyList(),
                    Collections.<String, Object>emptyMap(),
                    Collections.<String, DataType>emptyMap(),
                    true);
        }

        List<Map<String, Object>> getPushdownFilters() {
            return new ArrayList<Map<String, Object>>(pushdownFilters);
        }

        Map<String, Object> getResidualValues() {
            return new LinkedHashMap<String, Object>(residualValues);
        }

        Map<String, DataType> getResidualTypes() {
            return new LinkedHashMap<String, DataType>(residualTypes);
        }

        boolean isNoMatch() {
            return noMatch;
        }
    }

    private static final class LookupKey {
        private final String name;
        private final DataType dataType;

        private LookupKey(String name, DataType dataType) {
            this.name = name;
            this.dataType = dataType;
        }
    }
}
