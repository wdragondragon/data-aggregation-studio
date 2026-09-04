package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import org.apache.flink.table.types.DataType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AggregationFlinkTableRuntime implements Serializable {
    private String runtimeRef;
    private Long datasourceId;
    private Long modelId;
    private String pluginName;
    private String tableName;
    private String physicalLocator;
    private String scanSql;
    private String scanMode = "bounded";
    private Integer maxRows;
    // Only populated from the existing task-scoped capability, never serialized into audit payloads.
    private transient String pluginRuntimeEndpoint;
    private transient String pluginRuntimeToken;
    private transient AutoCloseable runtimeResource;
    private DataType producedDataType;
    private List<String> fieldNames = new ArrayList<String>();
    private BaseDataSourceDTO dataSourceDTO;
    private Configuration connectionConfig = Configuration.newDefault();
    private Configuration extConfig = Configuration.newDefault();
    private Map<String, Object> modelMetadata = new LinkedHashMap<String, Object>();
    private List<String> pushedFilters = new ArrayList<String>();
    private List<String> remainingFilters = new ArrayList<String>();
    private List<FilePathPushdownFilter> pathContextFilters = new ArrayList<FilePathPushdownFilter>();
    private List<Map<String, Object>> httpPushdownFilters = new ArrayList<Map<String, Object>>();
    private boolean httpFilterAlwaysFalse;
    private List<String> resolvedSourceSql = new ArrayList<String>();
    private List<String> resolvedFilePaths = new ArrayList<String>();

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

    public String getPluginRuntimeEndpoint() {
        return pluginRuntimeEndpoint;
    }

    public void setPluginRuntimeEndpoint(String pluginRuntimeEndpoint) {
        this.pluginRuntimeEndpoint = pluginRuntimeEndpoint;
    }

    public String getPluginRuntimeToken() {
        return pluginRuntimeToken;
    }

    public void setPluginRuntimeToken(String pluginRuntimeToken) {
        this.pluginRuntimeToken = pluginRuntimeToken;
    }

    void setRuntimeResource(AutoCloseable runtimeResource) {
        this.runtimeResource = runtimeResource;
    }

    void closeRuntimeResource() {
        AutoCloseable resource = runtimeResource;
        runtimeResource = null;
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // Runtime cleanup must not hide the original query result or failure.
        }
    }

    public DataType getProducedDataType() {
        return producedDataType;
    }

    public void setProducedDataType(DataType producedDataType) {
        this.producedDataType = producedDataType;
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

    public Configuration getConnectionConfig() {
        return connectionConfig;
    }

    public void setConnectionConfig(Configuration connectionConfig) {
        this.connectionConfig = connectionConfig == null ? Configuration.newDefault() : connectionConfig;
    }

    public Configuration getExtConfig() {
        return extConfig;
    }

    public void setExtConfig(Configuration extConfig) {
        this.extConfig = extConfig == null ? Configuration.newDefault() : extConfig;
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

    public List<FilePathPushdownFilter> getPathContextFilters() {
        return pathContextFilters;
    }

    public void setPathContextFilters(List<FilePathPushdownFilter> pathContextFilters) {
        this.pathContextFilters = pathContextFilters == null
                ? new ArrayList<FilePathPushdownFilter>()
                : new ArrayList<FilePathPushdownFilter>(pathContextFilters);
    }

    public List<Map<String, Object>> getHttpPushdownFilters() {
        return httpPushdownFilters;
    }

    public void setHttpPushdownFilters(List<Map<String, Object>> httpPushdownFilters) {
        this.httpPushdownFilters = httpPushdownFilters == null
                ? new ArrayList<Map<String, Object>>()
                : new ArrayList<Map<String, Object>>(httpPushdownFilters);
    }

    public boolean isHttpFilterAlwaysFalse() {
        return httpFilterAlwaysFalse;
    }

    public void setHttpFilterAlwaysFalse(boolean httpFilterAlwaysFalse) {
        this.httpFilterAlwaysFalse = httpFilterAlwaysFalse;
    }

    public List<String> getResolvedSourceSql() {
        return resolvedSourceSql;
    }

    public void setResolvedSourceSql(List<String> resolvedSourceSql) {
        this.resolvedSourceSql = resolvedSourceSql == null ? new ArrayList<String>() : new ArrayList<String>(resolvedSourceSql);
    }

    public void addResolvedSourceSql(String sql) {
        if (sql != null && !sql.trim().isEmpty()) {
            this.resolvedSourceSql.add(sql);
        }
    }

    public List<String> getResolvedFilePaths() {
        return resolvedFilePaths;
    }

    public void setResolvedFilePaths(List<String> resolvedFilePaths) {
        this.resolvedFilePaths = resolvedFilePaths == null ? new ArrayList<String>() : new ArrayList<String>(resolvedFilePaths);
    }

    public void addResolvedFilePath(String path) {
        if (path != null && !path.trim().isEmpty()) {
            this.resolvedFilePaths.add(path);
        }
    }
}
