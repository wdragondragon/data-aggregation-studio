package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.datasource.AbstractDataSourcePlugin;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.pluginloader.PluginClassLoaderCloseable;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class StructuredPluginSourceStrategy implements AggregationSourceStrategy {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String HTTP_PUSHDOWN_FILTERS_KEY = "__studio_http_pushdown_filters";
    static final String HTTP_MAX_ROWS_KEY = "__studio_http_max_rows";

    @Override
    public void readRows(AggregationFlinkTableRuntime runtime, AggregationRowEmitter emitter) throws Exception {
        // Validate and short-circuit before resolving a plugin. These paths are pure connector
        // semantics and must remain usable when the bundled runtime is intentionally absent.
        if (runtime.isHttpFilterAlwaysFalse()) {
            return;
        }
        HttpBodyPushdownValidator.validate(runtime, runtime.getHttpPushdownFilters());
        ConnectorPluginRuntimeBootstrap.runWithReady(runtime, () -> {
            try (PluginClassLoaderCloseable loader =
                         PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, runtime.getPluginName())) {
                AbstractDataSourcePlugin plugin = loader.loadPlugin();
                BaseDataSourceDTO dto = AggregationSourceUtil.copyDataSource(runtime.getDataSourceDTO());
                attachHttpRuntimeParams(runtime, dto);
                String query = AggregationSourceUtil.buildQuery(runtime, runtime.getProducedDataType());
                plugin.scanQuery(dto, query, true,
                        row -> emitOrStop(emitter, attachHttpResultContext(runtime, row)));
            } catch (StopSourceScanException stop) {
                // Used to stop plugins whose scan callback cannot otherwise be interrupted.
            }
        });
    }

    static void emitOrStop(AggregationRowEmitter emitter, Map<String, Object> row) {
        if (!emitter.emit(row)) {
            throw new StopSourceScanException();
        }
    }

    void attachHttpRuntimeParams(AggregationFlinkTableRuntime runtime, BaseDataSourceDTO dto) throws Exception {
        if (runtime == null || dto == null || !"http".equalsIgnoreCase(runtime.getPluginName())) {
            return;
        }
        Map<String, String> extraParams = dto.getExtraParams() == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(dto.getExtraParams());
        extraParams.put(HTTP_PUSHDOWN_FILTERS_KEY,
                OBJECT_MAPPER.writeValueAsString(runtime.getHttpPushdownFilters()));
        if (runtime.getMaxRows() != null && runtime.getMaxRows() > 0) {
            extraParams.put(HTTP_MAX_ROWS_KEY, String.valueOf(runtime.getMaxRows()));
        } else {
            extraParams.remove(HTTP_MAX_ROWS_KEY);
        }
        dto.setExtraParams(extraParams);
    }

    static Map<String, Object> attachHttpResultContext(AggregationFlinkTableRuntime runtime,
                                                        Map<String, Object> sourceRow) {
        if (runtime == null || !"http".equalsIgnoreCase(runtime.getPluginName())) {
            return sourceRow;
        }
        Map<String, Object> row = sourceRow == null
                ? new LinkedHashMap<String, Object>()
                : deepCopyWithoutInternalContext(sourceRow);
        List<Map<String, Object>> filters = runtime.getHttpPushdownFilters();
        if (filters == null) {
            return row;
        }
        for (Map<String, Object> filter : filters) {
            String location = text(filter == null ? null : filter.get("location"));
            String resultField = text(filter == null ? null : filter.get("resultField"));
            Object value = firstValue(filter == null ? null : filter.get("values"));
            if (!HttpPushdownMappingConfig.isHttpLocation(location) || resultField.isEmpty()) {
                continue;
            }
            String normalizedLocation = HttpPushdownMappingConfig.normalizeLocation(location);
            putLocationValue(row, normalizedLocation, resultField, value);
            if ("param".equals(normalizedLocation)) {
                putLocationValue(row, "query", resultField, value);
            } else if ("query".equals(normalizedLocation)) {
                putLocationValue(row, "param", resultField, value);
            }
            String topLevelField = HttpPushdownMappingConfig.leafField(resultField);
            if (!topLevelField.isEmpty()
                    && !isPhysicalModelField(runtime, topLevelField)
                    && isUniquelyMappedVirtualField(runtime, topLevelField)) {
                row.put(topLevelField, value);
            }
        }
        return row;
    }

    private static boolean isUniquelyMappedVirtualField(AggregationFlinkTableRuntime runtime, String field) {
        if (runtime == null || field == null || field.trim().isEmpty()) {
            return false;
        }
        HttpPushdownMappingConfig config = HttpPushdownMappingConfig.from(
                runtime.getModelMetadata(), runtime.getPhysicalLocator());
        return config.findByField(field).size() == 1;
    }

    private static boolean isPhysicalModelField(AggregationFlinkTableRuntime runtime, String field) {
        if (runtime == null || runtime.getModelMetadata() == null || field == null) {
            return false;
        }
        Object columnsValue = runtime.getModelMetadata().get("columns");
        if (!(columnsValue instanceof List<?>)) {
            return false;
        }
        for (Object columnValue : (List<?>) columnsValue) {
            if (!(columnValue instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> column = (Map<?, ?>) columnValue;
            String name = text(column.get("name"));
            if (name.isEmpty()) {
                name = text(column.get("columnName"));
            }
            if (field.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> deepCopyWithoutInternalContext(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        if (source == null) {
            return copy;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            if (key.startsWith(HttpPushdownMappingConfig.INTERNAL_CONTEXT_FIELD_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            copy.put(key, value instanceof Map<?, ?>
                    ? deepCopyWithoutInternalContext((Map<?, ?>) value)
                    : value);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void putLocationValue(Map<String, Object> row, String location, String path, Object value) {
        Object existing = row.get(location);
        if (row.containsKey(location) && !(existing instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> target;
        if (existing instanceof Map<?, ?>) {
            target = (Map<String, Object>) existing;
        } else {
            target = new LinkedHashMap<String, Object>();
            row.put(location, target);
        }
        if (!"body".equals(HttpPushdownMappingConfig.normalizeLocation(location))) {
            target.put(path, value);
            return;
        }
        List<String> parts = HttpPushdownMappingConfig.splitBodyPath(path);
        for (int index = 0; index < parts.size(); index++) {
            String part = parts.get(index).trim();
            if (part.isEmpty()) {
                continue;
            }
            if (index == parts.size() - 1) {
                target.put(part, value);
                return;
            }
            Object child = target.get(part);
            if (!(child instanceof Map<?, ?>)) {
                child = new LinkedHashMap<String, Object>();
                target.put(part, child);
            }
            target = (Map<String, Object>) child;
        }
    }

    private static Object firstValue(Object values) {
        if (!(values instanceof List<?>) || ((List<?>) values).isEmpty()) {
            return null;
        }
        return ((List<?>) values).get(0);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
