package com.jdragon.studio.infra.service.execution;

import com.jdragon.studio.dto.enums.ModelKind;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.core.spi.ModelDiscoveryProvider;
import com.jdragon.studio.core.spi.SourceCapabilityProvider;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.aggregation.commons.pagination.Table;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.datasource.AbstractDataSourcePlugin;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import com.jdragon.aggregation.datasource.SourcePluginType;
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

@Service
public class AggregationSourceCapabilityProvider implements SourceCapabilityProvider, ModelDiscoveryProvider {

    private final EncryptionService encryptionService;

    public AggregationSourceCapabilityProvider(StudioPlatformProperties properties, EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
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
                boolean success = ((FileHelper) plugin).connect(Configuration.from(decryptMetadata(definition.getTechnicalMetadata())));
                result.setSuccess(success);
                result.setMessage(success ? "Connection success" : "Connection failed");
                return result;
            }
            if (plugin instanceof QueueAbstract) {
                QueueAbstract queue = (QueueAbstract) plugin;
                queue.setPluginQueueConf(Configuration.from(decryptMetadata(definition.getTechnicalMetadata())));
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
                List<String> tableNames = sourcePlugin.getTableNames(toBaseDataSource(definition), "");
                for (String tableName : tableNames) {
                    DataModelDefinition model = new DataModelDefinition();
                    model.setDatasourceId(definition.getId());
                    model.setName(tableName);
                    model.setModelKind(ModelKind.TABLE);
                    model.setPhysicalLocator(tableName);
                    result.getModels().add(model);
                }
                result.setMessage("Discovered RDBMS objects");
                return result;
            }
            if (plugin instanceof FileHelper) {
                FileHelper fileHelper = (FileHelper) plugin;
                Map<String, Object> metadata = decryptMetadata(definition.getTechnicalMetadata());
                String rootPath = String.valueOf(metadata.getOrDefault("rootPath", "/"));
                String regex = String.valueOf(metadata.getOrDefault("pattern", ".*"));
                fileHelper.connect(Configuration.from(metadata));
                for (String fileName : fileHelper.listFile(rootPath, regex)) {
                    DataModelDefinition model = new DataModelDefinition();
                    model.setDatasourceId(definition.getId());
                    model.setName(fileName);
                    model.setModelKind(ModelKind.FILE);
                    model.setPhysicalLocator(fileName);
                    result.getModels().add(model);
                }
                result.setMessage("Discovered file models");
                return result;
            }
            if (plugin instanceof QueueAbstract) {
                Map<String, Object> metadata = decryptMetadata(definition.getTechnicalMetadata());
                DataModelDefinition model = new DataModelDefinition();
                model.setDatasourceId(definition.getId());
                model.setName(String.valueOf(metadata.getOrDefault("topic", metadata.getOrDefault("queue", definition.getName()))));
                model.setModelKind(ModelKind.TOPIC);
                model.setPhysicalLocator(model.getName());
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
                Map<String, Object> metadata = decryptMetadata(datasource.getTechnicalMetadata());
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

    private BaseDataSourceDTO toBaseDataSource(DataSourceDefinition definition) {
        Map<String, Object> metadata = decryptMetadata(definition.getTechnicalMetadata());
        BaseDataSourceDTO dto = new BaseDataSourceDTO();
        dto.setName(definition.getTypeCode());
        dto.setType(definition.getTypeCode());
        dto.setHost(asString(metadata.get("host")));
        dto.setPort(asString(metadata.get("port")));
        dto.setDatabase(asString(metadata.get("database")));
        dto.setUserName(asString(metadata.get("userName")));
        dto.setPassword(asString(metadata.get("password")));
        dto.setOther(asString(metadata.get("other")));
        dto.setBucket(asString(metadata.get("bucket")));
        dto.setJdbcUrl(asString(metadata.get("jdbcUrl")));
        dto.setDriverClassName(asString(metadata.get("driverClassName")));
        Object usePool = metadata.get("usePool");
        dto.setUsePool(usePool instanceof Boolean ? (Boolean) usePool : Boolean.parseBoolean(asString(usePool)));
        Map<String, String> extraParams = new LinkedHashMap<String, String>();
        Object candidate = metadata.get("extraParams");
        if (candidate instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) candidate;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                extraParams.put(String.valueOf(entry.getKey()), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        }
        dto.setExtraParams(extraParams);
        return dto;
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

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

