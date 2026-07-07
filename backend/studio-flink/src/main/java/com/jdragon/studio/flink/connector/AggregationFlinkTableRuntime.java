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
    private DataType producedDataType;
    private List<String> fieldNames = new ArrayList<String>();
    private BaseDataSourceDTO dataSourceDTO;
    private Configuration connectionConfig = Configuration.newDefault();
    private Configuration extConfig = Configuration.newDefault();
    private Map<String, Object> modelMetadata = new LinkedHashMap<String, Object>();

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
}
