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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AggregationSourceCapabilityProvider implements SourceCapabilityProvider, ModelDiscoveryProvider {

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
        ModelDiscoveryResult result = new ModelDiscoveryResult();
        try (PluginClassLoaderCloseable loader = PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, definition.getTypeCode())) {
            AbstractPlugin plugin = loader.loadPlugin();
            if (plugin instanceof AbstractDataSourcePlugin) {
                AbstractDataSourcePlugin sourcePlugin = (AbstractDataSourcePlugin) plugin;
                BaseDataSourceDTO datasource = toBaseDataSource(definition);
                List<String> tableNames = sourcePlugin.getTableNames(datasource, "");
                for (String tableName : tableNames) {
                    DataModelDefinition model = new DataModelDefinition();
                    List<TableInfo> tableInfos = sourcePlugin.getTableInfos(datasource, tableName);
                    TableInfo tableInfo = firstTableInfo(tableInfos, tableName);
                    List<ColumnInfo> columns = sourcePlugin.getColumns(datasource, tableName);
                    model.setDatasourceId(definition.getId());
                    model.setName(tableName);
                    model.setModelKind(resolveModelKind(tableInfo));
                    model.setPhysicalLocator(tableName);
                    model.setTechnicalMetadata(buildRelationalMetadata(definition, tableName, tableInfo, columns));
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
                fileHelper.connect(Configuration.from(metadata));
                for (String fileName : fileHelper.listFile(rootPath, regex)) {
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
                DataModelDefinition model = new DataModelDefinition();
                model.setDatasourceId(definition.getId());
                model.setName(String.valueOf(metadata.getOrDefault("topic", metadata.getOrDefault("queue", definition.getName()))));
                model.setModelKind(ModelKind.TOPIC);
                model.setPhysicalLocator(model.getName());
                model.setTechnicalMetadata(buildQueueMetadata(definition, metadata, model.getName()));
                model.setBusinessMetadata(buildEmptyBusinessMetadata());
                result.getModels().add(model);
                result.setMessage("Queue model synthesized from datasource metadata");
                return result;
            }
            result.setMessage("No model discovery provider");
        } catch (Exception e) {
            result.setMessage(e.getMessage());
        }
        return result;
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

