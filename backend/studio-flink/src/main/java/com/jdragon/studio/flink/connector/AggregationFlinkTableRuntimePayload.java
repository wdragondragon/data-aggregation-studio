package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AggregationFlinkTableRuntimePayload {
    private String runtimeRef;
    private Long datasourceId;
    private Long modelId;
    private String pluginName;
    private String tableName;
    private String physicalLocator;
    private String scanSql;
    private String scanMode = "bounded";
    private Integer maxRows;
    private List<String> fieldNames = new ArrayList<String>();
    private BaseDataSourceDTO dataSourceDTO;
    private String connectionConfigJson = "{}";
    private String extConfigJson = "{}";
    private Map<String, Object> modelMetadata = new LinkedHashMap<String, Object>();
    private List<String> pushedFilters = new ArrayList<String>();
    private List<String> remainingFilters = new ArrayList<String>();
    private List<Map<String, Object>> pathContextFilters = new ArrayList<Map<String, Object>>();
    private List<String> resolvedSourceSql = new ArrayList<String>();
    private List<String> resolvedFilePaths = new ArrayList<String>();

    public static AggregationFlinkTableRuntimePayload fromRuntime(AggregationFlinkTableRuntime runtime) {
        AggregationFlinkTableRuntimePayload payload = new AggregationFlinkTableRuntimePayload();
        if (runtime == null) {
            return payload;
        }
        payload.setRuntimeRef(runtime.getRuntimeRef());
        payload.setDatasourceId(runtime.getDatasourceId());
        payload.setModelId(runtime.getModelId());
        payload.setPluginName(runtime.getPluginName());
        payload.setTableName(runtime.getTableName());
        payload.setPhysicalLocator(runtime.getPhysicalLocator());
        payload.setScanSql(runtime.getScanSql());
        payload.setScanMode(runtime.getScanMode());
        payload.setMaxRows(runtime.getMaxRows());
        payload.setFieldNames(runtime.getFieldNames());
        payload.setDataSourceDTO(runtime.getDataSourceDTO());
        payload.setConnectionConfigJson(runtime.getConnectionConfig() == null ? "{}" : runtime.getConnectionConfig().toJSON());
        payload.setExtConfigJson(runtime.getExtConfig() == null ? "{}" : runtime.getExtConfig().toJSON());
        payload.setModelMetadata(runtime.getModelMetadata());
        payload.setPushedFilters(runtime.getPushedFilters());
        payload.setRemainingFilters(runtime.getRemainingFilters());
        List<Map<String, Object>> pathFilters = new ArrayList<Map<String, Object>>();
        if (runtime.getPathContextFilters() != null) {
            for (FilePathPushdownFilter filter : runtime.getPathContextFilters()) {
                pathFilters.add(filter.asMap());
            }
        }
        payload.setPathContextFilters(pathFilters);
        payload.setResolvedSourceSql(runtime.getResolvedSourceSql());
        payload.setResolvedFilePaths(runtime.getResolvedFilePaths());
        return payload;
    }

    public AggregationFlinkTableRuntime toRuntime() {
        AggregationFlinkTableRuntime runtime = new AggregationFlinkTableRuntime();
        runtime.setRuntimeRef(runtimeRef);
        runtime.setDatasourceId(datasourceId);
        runtime.setModelId(modelId);
        runtime.setPluginName(pluginName);
        runtime.setTableName(tableName);
        runtime.setPhysicalLocator(physicalLocator);
        runtime.setScanSql(scanSql);
        runtime.setScanMode(scanMode);
        runtime.setMaxRows(maxRows);
        runtime.setFieldNames(fieldNames);
        runtime.setDataSourceDTO(dataSourceDTO);
        runtime.setConnectionConfig(Configuration.from(hasText(connectionConfigJson) ? connectionConfigJson : "{}"));
        runtime.setExtConfig(Configuration.from(hasText(extConfigJson) ? extConfigJson : "{}"));
        runtime.setModelMetadata(modelMetadata);
        runtime.setPushedFilters(pushedFilters);
        runtime.setRemainingFilters(remainingFilters);
        runtime.setPathContextFilters(toPathFilters(pathContextFilters));
        runtime.setResolvedSourceSql(resolvedSourceSql);
        runtime.setResolvedFilePaths(resolvedFilePaths);
        return runtime;
    }

    public void mergeAuditInto(AggregationFlinkTableRuntime runtime) {
        if (runtime == null) {
            return;
        }
        runtime.setPushedFilters(pushedFilters);
        runtime.setRemainingFilters(remainingFilters);
        runtime.setPathContextFilters(toPathFilters(pathContextFilters));
        runtime.setResolvedSourceSql(resolvedSourceSql);
        runtime.setResolvedFilePaths(resolvedFilePaths);
    }

    private List<FilePathPushdownFilter> toPathFilters(List<Map<String, Object>> rawFilters) {
        List<FilePathPushdownFilter> filters = new ArrayList<FilePathPushdownFilter>();
        if (rawFilters == null) {
            return filters;
        }
        for (Map<String, Object> raw : rawFilters) {
            if (raw == null) {
                continue;
            }
            filters.add(new FilePathPushdownFilter(
                    stringValue(raw.get("field")),
                    stringValue(raw.get("displayName")),
                    stringValue(raw.get("operator")),
                    stringList(raw.get("values")),
                    stringValue(raw.get("expression"))));
        }
        return filters;
    }

    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                result.add(stringValue(item));
            }
            return result;
        }
        if (value != null) {
            result.add(stringValue(value));
        }
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public String getRuntimeRef() {
        return runtimeRef;
    }

    public void setRuntimeRef(String runtimeRef) {
        this.runtimeRef = runtimeRef;
    }

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }

    public Long getModelId() {
        return modelId;
    }

    public void setModelId(Long modelId) {
        this.modelId = modelId;
    }

    public String getPluginName() {
        return pluginName;
    }

    public void setPluginName(String pluginName) {
        this.pluginName = pluginName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getPhysicalLocator() {
        return physicalLocator;
    }

    public void setPhysicalLocator(String physicalLocator) {
        this.physicalLocator = physicalLocator;
    }

    public String getScanSql() {
        return scanSql;
    }

    public void setScanSql(String scanSql) {
        this.scanSql = scanSql;
    }

    public String getScanMode() {
        return scanMode;
    }

    public void setScanMode(String scanMode) {
        this.scanMode = scanMode;
    }

    public Integer getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(Integer maxRows) {
        this.maxRows = maxRows;
    }

    public List<String> getFieldNames() {
        return fieldNames;
    }

    public void setFieldNames(List<String> fieldNames) {
        this.fieldNames = fieldNames == null ? new ArrayList<String>() : new ArrayList<String>(fieldNames);
    }

    public BaseDataSourceDTO getDataSourceDTO() {
        return dataSourceDTO;
    }

    public void setDataSourceDTO(BaseDataSourceDTO dataSourceDTO) {
        this.dataSourceDTO = dataSourceDTO;
    }

    public String getConnectionConfigJson() {
        return connectionConfigJson;
    }

    public void setConnectionConfigJson(String connectionConfigJson) {
        this.connectionConfigJson = hasText(connectionConfigJson) ? connectionConfigJson : "{}";
    }

    public String getExtConfigJson() {
        return extConfigJson;
    }

    public void setExtConfigJson(String extConfigJson) {
        this.extConfigJson = hasText(extConfigJson) ? extConfigJson : "{}";
    }

    public Map<String, Object> getModelMetadata() {
        return modelMetadata;
    }

    public void setModelMetadata(Map<String, Object> modelMetadata) {
        this.modelMetadata = modelMetadata == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(modelMetadata);
    }

    public List<String> getPushedFilters() {
        return pushedFilters;
    }

    public void setPushedFilters(List<String> pushedFilters) {
        this.pushedFilters = pushedFilters == null ? new ArrayList<String>() : new ArrayList<String>(pushedFilters);
    }

    public List<String> getRemainingFilters() {
        return remainingFilters;
    }

    public void setRemainingFilters(List<String> remainingFilters) {
        this.remainingFilters = remainingFilters == null ? new ArrayList<String>() : new ArrayList<String>(remainingFilters);
    }

    public List<Map<String, Object>> getPathContextFilters() {
        return pathContextFilters;
    }

    public void setPathContextFilters(List<Map<String, Object>> pathContextFilters) {
        this.pathContextFilters = pathContextFilters == null
                ? new ArrayList<Map<String, Object>>()
                : new ArrayList<Map<String, Object>>(pathContextFilters);
    }

    public List<String> getResolvedSourceSql() {
        return resolvedSourceSql;
    }

    public void setResolvedSourceSql(List<String> resolvedSourceSql) {
        this.resolvedSourceSql = resolvedSourceSql == null ? new ArrayList<String>() : new ArrayList<String>(resolvedSourceSql);
    }

    public List<String> getResolvedFilePaths() {
        return resolvedFilePaths;
    }

    public void setResolvedFilePaths(List<String> resolvedFilePaths) {
        this.resolvedFilePaths = resolvedFilePaths == null ? new ArrayList<String>() : new ArrayList<String>(resolvedFilePaths);
    }
}
