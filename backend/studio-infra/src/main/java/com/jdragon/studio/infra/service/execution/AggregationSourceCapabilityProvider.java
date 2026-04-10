package com.jdragon.studio.infra.service.execution;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.jdragon.studio.dto.enums.ModelKind;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.core.spi.ModelDiscoveryProvider;
import com.jdragon.studio.core.spi.SourceCapabilityProvider;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.aggregation.commons.pagination.Table;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.datasource.AbstractDataSourcePlugin;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import com.jdragon.aggregation.datasource.ColumnInfo;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.datasource.TableInfo;
import com.jdragon.aggregation.datasource.file.FileHelper;
import com.jdragon.aggregation.datasource.queue.QueueAbstract;
import com.jdragon.aggregation.pluginloader.PluginClassLoaderCloseable;
import com.jdragon.aggregation.pluginloader.constant.SystemConstants;
import com.jdragon.aggregation.pluginloader.spi.AbstractPlugin;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AggregationSourceCapabilityProvider implements SourceCapabilityProvider, ModelDiscoveryProvider {

    public static class HydrationResult {
        private final String physicalLocator;
        private final DataModelDefinition definition;
        private final String errorMessage;

        public HydrationResult(String physicalLocator, DataModelDefinition definition, String errorMessage) {
            this.physicalLocator = physicalLocator;
            this.definition = definition;
            this.errorMessage = errorMessage;
        }

        public String getPhysicalLocator() {
            return physicalLocator;
        }

        public DataModelDefinition getDefinition() {
            return definition;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public boolean isSuccess() {
            return definition != null && (errorMessage == null || errorMessage.trim().isEmpty());
        }
    }

    private final EncryptionService encryptionService;
    private final BusinessMetaModelMetadataService businessMetaModelMetadataService;

    public AggregationSourceCapabilityProvider(StudioPlatformProperties properties,
                                               EncryptionService encryptionService,
                                               BusinessMetaModelMetadataService businessMetaModelMetadataService) {
        this.encryptionService = encryptionService;
        this.businessMetaModelMetadataService = businessMetaModelMetadataService;
        System.setProperty("aggregation.home", properties.getAggregationHome());
        SystemConstants.HOME = properties.getAggregationHome();
    }

    @Override
    public boolean supports(String typeCode) {
        return typeCode != null && !typeCode.trim().isEmpty();
    }

    @Override
    public ConnectionTestResult testConnection(DataSourceDefinition definition) {
        ConnectionTestResult result = new ConnectionTestResult();
        try (PluginClassLoaderCloseable loader = PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, definition.getTypeCode())) {
            AbstractPlugin plugin = loader.loadPlugin();
            if (plugin instanceof AbstractDataSourcePlugin) {
                boolean success = ((AbstractDataSourcePlugin) plugin).connectTest(toBaseDataSource(definition));
                result.setSuccess(success);
                result.setMessage(success ? "Connection success" : "Connection failed");
                return result;
            }
            if (plugin instanceof FileHelper) {
                boolean success = ((FileHelper) plugin).connect(Configuration.from(normalizePluginMetadata(definition.getTypeCode(), decryptMetadata(definition.getTechnicalMetadata()))));
                result.setSuccess(success);
                result.setMessage(success ? "Connection success" : "Connection failed");
                return result;
            }
            if (plugin instanceof QueueAbstract) {
                QueueAbstract queue = (QueueAbstract) plugin;
                queue.setPluginQueueConf(Configuration.from(normalizePluginMetadata(definition.getTypeCode(), decryptMetadata(definition.getTechnicalMetadata()))));
                queue.init();
                result.setSuccess(true);
                result.setMessage("Queue plugin initialized");
                return result;
            }
            result.setSuccess(false);
            result.setMessage("Unsupported plugin type");
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage(e.getMessage());
        }
        return result;
    }

    @Override
    public ModelDiscoveryResult discoverModels(DataSourceDefinition definition) {
        return discoverModels(definition, null);
    }

    @Override
    public ModelDiscoveryResult discoverModels(DataSourceDefinition definition, String keyword) {
        return discoverModels(definition, keyword, null, null);
    }

    @Override
    public ModelDiscoveryResult discoverModels(DataSourceDefinition definition,
                                               String keyword,
                                               Integer pageNo,
                                               Integer pageSize) {
        ModelDiscoveryResult result = new ModelDiscoveryResult();
        try (PluginClassLoaderCloseable loader = PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, definition.getTypeCode())) {
            AbstractPlugin plugin = loader.loadPlugin();
            if (plugin instanceof AbstractDataSourcePlugin) {
                AbstractDataSourcePlugin sourcePlugin = (AbstractDataSourcePlugin) plugin;
                BaseDataSourceDTO datasource = toBaseDataSource(definition);
                String normalizedKeyword = keyword == null ? "" : keyword.trim();
                List<String> tableNames = sourcePlugin.getTableNames(datasource, normalizedKeyword);
                List<String> pagedTableNames = paginate(tableNames, result, pageNo, pageSize);
                for (String tableName : pagedTableNames) {
                    DataModelDefinition model = new DataModelDefinition();
                    model.setDatasourceId(definition.getId());
                    model.setName(tableName);
                    model.setModelKind(ModelKind.TABLE);
                    model.setPhysicalLocator(tableName);
                    model.setTechnicalMetadata(buildLightweightRelationalMetadata(definition, tableName));
                    model.setBusinessMetadata(buildEmptyBusinessMetadata());
                    result.getModels().add(model);
                }
                result.setMessage("Discovered RDBMS objects");
                return result;
            }
            if (plugin instanceof FileHelper) {
                FileHelper fileHelper = (FileHelper) plugin;
                Map<String, Object> metadata = normalizePluginMetadata(definition.getTypeCode(), decryptMetadata(definition.getTechnicalMetadata()));
                String rootPath = String.valueOf(metadata.getOrDefault("rootPath", "/"));
                String regex = String.valueOf(metadata.getOrDefault("pattern", ".*"));
                String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
                fileHelper.connect(Configuration.from(metadata));
                List<String> fileNames = new ArrayList<String>();
                for (String fileName : fileHelper.listFile(rootPath, regex)) {
                    if (normalizedKeyword.isEmpty() || fileName.toLowerCase().contains(normalizedKeyword)) {
                        fileNames.add(fileName);
                    }
                }
                List<String> pagedFileNames = paginate(fileNames, result, pageNo, pageSize);
                for (String fileName : pagedFileNames) {
                    DataModelDefinition model = new DataModelDefinition();
                    model.setDatasourceId(definition.getId());
                    model.setName(fileName);
                    model.setModelKind(ModelKind.FILE);
                    model.setPhysicalLocator(fileName);
                    model.setTechnicalMetadata(buildFileMetadata(definition, metadata, rootPath, regex, fileName));
                    model.setBusinessMetadata(buildEmptyBusinessMetadata());
                    result.getModels().add(model);
                }
                result.setMessage("Discovered file models");
                return result;
            }
            if (plugin instanceof QueueAbstract) {
                Map<String, Object> metadata = normalizePluginMetadata(definition.getTypeCode(), decryptMetadata(definition.getTechnicalMetadata()));
                String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
                String modelName = String.valueOf(metadata.getOrDefault("topic", metadata.getOrDefault("queue", definition.getName())));
                result.setPageNo(resolvePageNo(pageNo));
                result.setPageSize(resolvePageSize(pageSize, 1));
                result.setTotal(0L);
                result.setHasMore(false);
                if (!normalizedKeyword.isEmpty() && !modelName.toLowerCase().contains(normalizedKeyword)) {
                    result.setMessage("Queue model does not match keyword");
                    return result;
                }
                DataModelDefinition model = new DataModelDefinition();
                model.setDatasourceId(definition.getId());
                model.setName(modelName);
                model.setModelKind(ModelKind.TOPIC);
                model.setPhysicalLocator(model.getName());
                model.setTechnicalMetadata(buildQueueMetadata(definition, metadata, model.getName()));
                model.setBusinessMetadata(buildEmptyBusinessMetadata());
                result.getModels().add(model);
                result.setTotal(1L);
                result.setMessage("Queue model synthesized from datasource metadata");
                return result;
            }
            result.setMessage("No model discovery provider");
        } catch (Exception e) {
            result.setMessage(e.getMessage());
        }
        return result;
    }

    private List<String> paginate(List<String> names,
                                  ModelDiscoveryResult result,
                                  Integer pageNo,
                                  Integer pageSize) {
        List<String> source = names == null ? new ArrayList<String>() : names;
        if (pageNo == null && pageSize == null) {
            result.setTotal(source.size());
            result.setPageNo(1);
            result.setPageSize(source.size());
            result.setHasMore(false);
            return new ArrayList<String>(source);
        }
        int safePageNo = resolvePageNo(pageNo);
        int safePageSize = resolvePageSize(pageSize, source.size());
        int total = source.size();
        int offset = Math.max(0, (safePageNo - 1) * safePageSize);
        int end = Math.min(total, offset + safePageSize);
        result.setTotal(total);
        result.setPageNo(safePageNo);
        result.setPageSize(safePageSize);
        result.setHasMore(end < total);
        if (offset >= total) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(source.subList(offset, end));
    }

    private int resolvePageNo(Integer pageNo) {
        return pageNo == null || pageNo.intValue() < 1 ? 1 : pageNo.intValue();
    }

    private int resolvePageSize(Integer pageSize, int defaultSize) {
        int safeDefault = defaultSize <= 0 ? 200 : defaultSize;
        int safePageSize = pageSize == null || pageSize.intValue() < 1 ? safeDefault : pageSize.intValue();
        return Math.min(safePageSize, 1000);
    }

    public DataModelDefinition hydrateDiscoveredModel(DataSourceDefinition definition, DataModelDefinition definitionModel) {
        if (definition == null || definitionModel == null) {
            return definitionModel;
        }
        try (PluginClassLoaderCloseable loader = PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, definition.getTypeCode())) {
            AbstractPlugin plugin = loader.loadPlugin();
            if (!(plugin instanceof AbstractDataSourcePlugin)) {
                return definitionModel;
            }
            AbstractDataSourcePlugin sourcePlugin = (AbstractDataSourcePlugin) plugin;
            BaseDataSourceDTO datasource = toBaseDataSource(definition);
            String physicalLocator = definitionModel.getPhysicalLocator();
            String resolvedTableName = physicalLocator == null || physicalLocator.trim().isEmpty()
                    ? definitionModel.getName()
                    : physicalLocator;
            List<TableInfo> tableInfos = sourcePlugin.getTableInfos(datasource, resolvedTableName);
            TableInfo tableInfo = firstTableInfo(tableInfos, resolvedTableName);
            List<ColumnInfo> columns = sourcePlugin.getColumns(datasource, resolvedTableName);
            definitionModel.setModelKind(resolveModelKind(tableInfo));
            definitionModel.setTechnicalMetadata(buildRelationalMetadata(definition, resolvedTableName, tableInfo, columns));
            if (definitionModel.getBusinessMetadata() == null) {
                definitionModel.setBusinessMetadata(buildEmptyBusinessMetadata());
            }
            return definitionModel;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load model metadata for " + definitionModel.getPhysicalLocator(), e);
        }
    }

    public List<HydrationResult> hydrateDiscoveredModels(DataSourceDefinition definition,
                                                         List<DataModelDefinition> definitionModels) {
        List<HydrationResult> results = new ArrayList<HydrationResult>();
        if (definition == null || definitionModels == null || definitionModels.isEmpty()) {
            return results;
        }
        try (PluginClassLoaderCloseable loader = PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, definition.getTypeCode())) {
            AbstractPlugin plugin = loader.loadPlugin();
            if (!(plugin instanceof AbstractDataSourcePlugin)) {
                for (DataModelDefinition item : definitionModels) {
                    results.add(new HydrationResult(resolvePhysicalLocator(item), item, null));
                }
                return results;
            }
            AbstractDataSourcePlugin sourcePlugin = (AbstractDataSourcePlugin) plugin;
            BaseDataSourceDTO datasource = toBaseDataSource(definition);
            List<String> locators = new ArrayList<String>();
            Map<String, DataModelDefinition> definitionsByLocator = new LinkedHashMap<String, DataModelDefinition>();
            for (DataModelDefinition item : definitionModels) {
                String locator = resolvePhysicalLocator(item);
                if (locator == null || locator.trim().isEmpty()) {
                    results.add(new HydrationResult(locator, null, "Physical locator is required"));
                    continue;
                }
                locators.add(locator);
                definitionsByLocator.put(locator, item);
            }
            if (locators.isEmpty()) {
                return results;
            }
            try {
                Map<String, List<TableInfo>> tableInfoMap = loadTableInfoMap(sourcePlugin, datasource, locators);
                Map<String, List<ColumnInfo>> columnMap = loadColumnMap(sourcePlugin, datasource, locators);
                for (String locator : locators) {
                    DataModelDefinition source = definitionsByLocator.get(locator);
                    try {
                        List<TableInfo> tableInfos = tableInfoMap == null ? Collections.<TableInfo>emptyList()
                                : tableInfoMap.get(locator);
                        List<ColumnInfo> columns = columnMap == null ? Collections.<ColumnInfo>emptyList()
                                : columnMap.get(locator);
                        TableInfo tableInfo = firstTableInfo(tableInfos, locator);
                        DataModelDefinition hydrated = cloneDefinition(source);
                        hydrated.setModelKind(resolveModelKind(tableInfo));
                        hydrated.setTechnicalMetadata(buildRelationalMetadata(definition, locator, tableInfo, columns));
                        if (hydrated.getBusinessMetadata() == null) {
                            hydrated.setBusinessMetadata(buildEmptyBusinessMetadata());
                        }
                        results.add(new HydrationResult(locator, hydrated, null));
                    } catch (Exception itemException) {
                        results.add(hydrateIndividually(definition, source, itemException));
                    }
                }
                return results;
            } catch (Exception batchException) {
                for (String locator : locators) {
                    results.add(hydrateIndividually(definition, definitionsByLocator.get(locator), batchException));
                }
                return results;
            }
        } catch (Exception e) {
            for (DataModelDefinition item : definitionModels) {
                results.add(new HydrationResult(resolvePhysicalLocator(item), null, e.getMessage()));
            }
            return results;
        }
    }

    @Override
    public List<Map<String, Object>> preview(DataSourceDefinition datasource, DataModelDefinition model, int limit) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        try (PluginClassLoaderCloseable loader = PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, datasource.getTypeCode())) {
            AbstractPlugin plugin = loader.loadPlugin();
            if (plugin instanceof AbstractDataSourcePlugin) {
                Table<Map<String, Object>> table = ((AbstractDataSourcePlugin) plugin)
                        .dataModelPreview(toBaseDataSource(datasource), model.getPhysicalLocator(), String.valueOf(limit));
                if (table != null && table.getBodies() != null) {
                    rows.addAll(table.getBodies());
                }
            } else if (plugin instanceof FileHelper) {
                FileHelper fileHelper = (FileHelper) plugin;
                Map<String, Object> metadata = normalizePluginMetadata(datasource.getTypeCode(), decryptMetadata(datasource.getTechnicalMetadata()));
                fileHelper.connect(Configuration.from(metadata));
                fileHelper.readFile(model.getPhysicalLocator(),
                        String.valueOf(metadata.getOrDefault("fileType", "csv")),
                        row -> {
                            if (rows.size() < limit) {
                                rows.add(new LinkedHashMap<String, Object>(row));
                            }
                        },
                        Configuration.from(metadata));
            }
        } catch (Exception ignored) {
        }
        return rows;
    }

    private TableInfo firstTableInfo(List<TableInfo> tableInfos, String tableName) {
        if (tableInfos == null || tableInfos.isEmpty()) {
            return null;
        }
        for (TableInfo tableInfo : tableInfos) {
            if (tableName != null && tableName.equalsIgnoreCase(tableInfo.getTableName())) {
                return tableInfo;
            }
        }
        return tableInfos.get(0);
    }

    private HydrationResult hydrateIndividually(DataSourceDefinition definition,
                                                DataModelDefinition item,
                                                Exception originalBatchException) {
        try {
            return new HydrationResult(resolvePhysicalLocator(item), hydrateDiscoveredModel(definition, cloneDefinition(item)), null);
        } catch (Exception itemException) {
            String message = itemException.getMessage();
            if ((message == null || message.trim().isEmpty()) && originalBatchException != null) {
                message = originalBatchException.getMessage();
            }
            return new HydrationResult(resolvePhysicalLocator(item), null, message);
        }
    }

    private DataModelDefinition cloneDefinition(DataModelDefinition source) {
        if (source == null) {
            return null;
        }
        DataModelDefinition target = new DataModelDefinition();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setProjectId(source.getProjectId());
        target.setDeleted(source.getDeleted());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
        target.setDatasourceId(source.getDatasourceId());
        target.setName(source.getName());
        target.setModelKind(source.getModelKind());
        target.setPhysicalLocator(source.getPhysicalLocator());
        target.setSchemaVersionId(source.getSchemaVersionId());
        target.setTechnicalMetadata(source.getTechnicalMetadata() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(source.getTechnicalMetadata()));
        target.setBusinessMetadata(source.getBusinessMetadata() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(source.getBusinessMetadata()));
        return target;
    }

    private String resolvePhysicalLocator(DataModelDefinition definitionModel) {
        if (definitionModel == null) {
            return null;
        }
        String physicalLocator = definitionModel.getPhysicalLocator();
        if (physicalLocator != null && !physicalLocator.trim().isEmpty()) {
            return physicalLocator.trim();
        }
        return definitionModel.getName();
    }

    private Map<String, List<TableInfo>> loadTableInfoMap(AbstractDataSourcePlugin sourcePlugin,
                                                          BaseDataSourceDTO datasource,
                                                          List<String> locators) throws Exception {
        Method batchMethod = findBatchMethod(sourcePlugin.getClass(), "getTableInfos");
        if (batchMethod != null) {
            Object value = batchMethod.invoke(sourcePlugin, datasource, locators);
            if (value instanceof Map) {
                return (Map<String, List<TableInfo>>) value;
            }
        }
        Map<String, List<TableInfo>> result = new LinkedHashMap<String, List<TableInfo>>();
        for (String locator : locators) {
            result.put(locator, sourcePlugin.getTableInfos(datasource, locator));
        }
        return result;
    }

    private Map<String, List<ColumnInfo>> loadColumnMap(AbstractDataSourcePlugin sourcePlugin,
                                                        BaseDataSourceDTO datasource,
                                                        List<String> locators) throws Exception {
        Method batchMethod = findBatchMethod(sourcePlugin.getClass(), "getColumns");
        if (batchMethod != null) {
            Object value = batchMethod.invoke(sourcePlugin, datasource, locators);
            if (value instanceof Map) {
                return (Map<String, List<ColumnInfo>>) value;
            }
        }
        Map<String, List<ColumnInfo>> result = new LinkedHashMap<String, List<ColumnInfo>>();
        for (String locator : locators) {
            result.put(locator, sourcePlugin.getColumns(datasource, locator));
        }
        return result;
    }

    private Method findBatchMethod(Class<?> pluginClass, String methodName) {
        if (pluginClass == null || methodName == null || methodName.trim().isEmpty()) {
            return null;
        }
        try {
            Method method = pluginClass.getMethod(methodName, BaseDataSourceDTO.class, List.class);
            method.setAccessible(true);
            return method;
        } catch (Exception ignored) {
            return null;
        }
    }

    private ModelKind resolveModelKind(TableInfo tableInfo) {
        if (tableInfo == null || tableInfo.getTableType() == null) {
            return ModelKind.TABLE;
        }
        String tableType = tableInfo.getTableType().toUpperCase();
        if (tableType.contains("VIEW")) {
            return ModelKind.VIEW;
        }
        return ModelKind.TABLE;
    }

    private Map<String, Object> buildRelationalMetadata(DataSourceDefinition definition,
                                                        String tableName,
                                                        TableInfo tableInfo,
                                                        List<ColumnInfo> columns) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceType", definition.getTypeCode());
        metadata.put("discoveryMode", "AUTO");
        metadata.put("physicalName", tableName);
        metadata.put("columnCount", columns == null ? 0 : columns.size());
        metadata.put("columns", toColumnMetadata(columns));
        if (tableInfo != null) {
            putIfPresent(metadata, "catalog", tableInfo.getTableCat());
            putIfPresent(metadata, "schema", tableInfo.getTableSchem());
            putIfPresent(metadata, "tableType", tableInfo.getTableType());
            putIfPresent(metadata, "remarks", tableInfo.getRemarks());
            metadata.put("partitioned", tableInfo.isPartitioned());
            if (tableInfo.getExternalTable() != null) {
                metadata.put("externalTable", tableInfo.getExternalTable());
            }
        }
        return metadata;
    }

    private Map<String, Object> buildLightweightRelationalMetadata(DataSourceDefinition definition, String tableName) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceType", definition.getTypeCode());
        metadata.put("discoveryMode", "AUTO");
        metadata.put("physicalName", tableName);
        return metadata;
    }

    private List<Map<String, Object>> toColumnMetadata(List<ColumnInfo> columns) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (columns == null) {
            return items;
        }
        for (ColumnInfo column : columns) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            putIfPresent(item, "name", column.getColumnName());
            putIfPresent(item, "type", column.getTypeName());
            if (column.getColumnSize() > 0) {
                item.put("size", column.getColumnSize());
            }
            if (column.getDecimalDigits() > 0) {
                item.put("scale", column.getDecimalDigits());
            }
            putIfPresent(item, "nullable", column.getIsNullable());
            putIfPresent(item, "primaryKey", column.getIsPrimaryKey());
            putIfPresent(item, "autoIncrement", column.getIsAutoincrement());
            putIfPresent(item, "remarks", column.getRemarks());
            putIfPresent(item, "defaultValue", column.getColumnDef());
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private Map<String, Object> buildFileMetadata(DataSourceDefinition definition,
                                                  Map<String, Object> datasourceMetadata,
                                                  String rootPath,
                                                  String regex,
                                                  String fileName) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceType", definition.getTypeCode());
        metadata.put("discoveryMode", "AUTO");
        putIfPresent(metadata, "physicalName", fileName);
        putIfPresent(metadata, "rootPath", rootPath);
        putIfPresent(metadata, "pattern", regex);
        putIfPresent(metadata, "fileName", fileName);
        putIfPresent(metadata, "fileType", datasourceMetadata.get("fileType"));
        putIfPresent(metadata, "encoding", datasourceMetadata.get("encoding"));
        putIfPresent(metadata, "delimiter", datasourceMetadata.get("delimiter"));
        return metadata;
    }

    private Map<String, Object> buildQueueMetadata(DataSourceDefinition definition,
                                                   Map<String, Object> datasourceMetadata,
                                                   String queueName) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceType", definition.getTypeCode());
        metadata.put("discoveryMode", "AUTO");
        putIfPresent(metadata, "physicalName", queueName);
        putIfPresent(metadata, "queueName", queueName);
        putIfPresent(metadata, "topic", datasourceMetadata.get("topic"));
        putIfPresent(metadata, "queue", datasourceMetadata.get("queue"));
        putIfPresent(metadata, "brokers", datasourceMetadata.get("brokers"));
        putIfPresent(metadata, "consumerGroup", datasourceMetadata.get("consumerGroup"));
        putIfPresent(metadata, "tag", datasourceMetadata.get("tag"));
        return metadata;
    }

    private Map<String, Object> buildEmptyBusinessMetadata() {
        return businessMetaModelMetadataService.emptyMetadata();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String && ((String) value).trim().isEmpty()) {
            return;
        }
        if (value instanceof Set && ((Set<?>) value).isEmpty()) {
            return;
        }
        if (value instanceof List && ((List<?>) value).isEmpty()) {
            return;
        }
        target.put(key, value);
    }

    private BaseDataSourceDTO toBaseDataSource(DataSourceDefinition definition) {
        Map<String, Object> metadata = normalizePluginMetadata(definition.getTypeCode(), decryptMetadata(definition.getTechnicalMetadata()));
        BaseDataSourceDTO dto = new BaseDataSourceDTO();
        dto.setName(definition.getTypeCode());
        dto.setType(definition.getTypeCode());
        dto.setHost(firstNonBlank(asString(metadata.get("host")), asString(metadata.get("endpoint"))));
        dto.setPort(asString(metadata.get("port")));
        dto.setDatabase(firstNonBlank(asString(metadata.get("database")), firstNonBlank(asString(metadata.get("projectName")), asString(metadata.get("org")))));
        dto.setUserName(firstNonBlank(asString(metadata.get("userName")),
                firstNonBlank(asString(metadata.get("username")),
                        firstNonBlank(asString(metadata.get("accessId")), asString(metadata.get("aliyunAccessId"))))));
        dto.setPassword(firstNonBlank(asString(metadata.get("password")),
                firstNonBlank(asString(metadata.get("token")),
                        firstNonBlank(asString(metadata.get("accessKeySecret")), asString(metadata.get("aliyunAccessKey"))))));
        dto.setOther(asJsonString(metadata.get("other")));
        dto.setBucket(firstNonBlank(asString(metadata.get("bucket")), asString(metadata.get("bucketName"))));
        dto.setPrincipal(firstNonBlank(asString(metadata.get("principal")), asString(metadata.get("kerberosPrincipal"))));
        dto.setKeytabPath(firstNonBlank(asString(metadata.get("keytabPath")), asString(metadata.get("kerberosKeytabFilePath"))));
        dto.setKrb5File(firstNonBlank(asString(metadata.get("krb5File")), asString(metadata.get("krb5Conf"))));
        dto.setJdbcUrl(asString(metadata.get("jdbcUrl")));
        dto.setDriverClassName(asString(metadata.get("driverClassName")));
        Object usePool = metadata.get("usePool");
        dto.setUsePool(usePool instanceof Boolean ? (Boolean) usePool : Boolean.parseBoolean(asString(usePool)));
        dto.setExtraParams(asStringMap(metadata.get("extraParams")));
        return dto;
    }

    private Map<String, Object> normalizePluginMetadata(String typeCode, Map<String, Object> metadata) {
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        if (metadata != null) {
            normalized.putAll(metadata);
        }
        String type = typeCode == null ? "" : typeCode.trim().toLowerCase();
        if ("ftp".equals(type) || "sftp".equals(type)) {
            copyIfMissing(normalized, "host", "endpoint");
            copyIfMissing(normalized, "username", "userName");
            return normalized;
        }
        if ("kafka".equals(type)) {
            copyIfMissing(normalized, "bootstrap.servers", "brokers");
            copyIfMissing(normalized, "group.id", "consumerGroup");
            copyIfMissing(normalized, "username", "userName");
            return normalized;
        }
        if ("rabbitmq".equals(type)) {
            copyIfMissing(normalized, "username", "userName");
            copyIfMissing(normalized, "queueName", "queue");
            return normalized;
        }
        if ("rocketmq".equals(type)) {
            copyIfMissing(normalized, "namesrvAddr", "brokers");
            copyIfMissing(normalized, "topic", "queue");
            return normalized;
        }
        if ("influxdb".equals(type) || "influxdbv1".equals(type) || "odps".equals(type)) {
            copyIfMissing(normalized, "host", "endpoint");
            return normalized;
        }
        if ("tbds-hive3".equals(type)) {
            copyIfMissing(normalized, "principal", "kerberosPrincipal");
            copyIfMissing(normalized, "keytabPath", "kerberosKeytabFilePath");
            copyIfMissing(normalized, "krb5File", "krb5Conf");
        }
        return normalized;
    }

    private Map<String, Object> decryptMetadata(Map<String, Object> metadata) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        if (metadata == null) {
            return copy;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && isSensitive(entry.getKey()) && String.valueOf(value).startsWith("ENC(") && String.valueOf(value).endsWith(")")) {
                String cipher = String.valueOf(value).substring(4, String.valueOf(value).length() - 1);
                copy.put(entry.getKey(), encryptionService.decrypt(cipher));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("accesskey")
                || normalized.contains("token");
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        return second;
    }

    private void copyIfMissing(Map<String, Object> target, String key, String alias) {
        if (target == null || key == null || alias == null) {
            return;
        }
        Object current = target.get(key);
        if (current != null && !String.valueOf(current).trim().isEmpty()) {
            return;
        }
        Object aliasValue = target.get(alias);
        if (aliasValue == null || String.valueOf(aliasValue).trim().isEmpty()) {
            return;
        }
        target.put(key, aliasValue);
    }

    private String asJsonString(Object candidate) {
        if (candidate instanceof Map || candidate instanceof List) {
            return JSONObject.toJSONString(candidate);
        }
        return asString(candidate);
    }

    private Map<String, String> asStringMap(Object candidate) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (candidate instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) candidate;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
            return result;
        }
        if (candidate instanceof String) {
            String json = ((String) candidate).trim();
            if (!json.isEmpty()) {
                try {
                    Map<String, String> parsed = JSONObject.parseObject(json, new TypeReference<LinkedHashMap<String, String>>() {
                    });
                    if (parsed != null) {
                        result.putAll(parsed);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

