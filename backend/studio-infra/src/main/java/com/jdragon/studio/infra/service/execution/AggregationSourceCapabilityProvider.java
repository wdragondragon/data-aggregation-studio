package com.jdragon.studio.infra.service.execution;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.jdragon.studio.dto.enums.ModelKind;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataModelDatasourceOptionView;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryOptionResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.core.spi.ModelDiscoveryProvider;
import com.jdragon.studio.core.spi.SourceCapabilityProvider;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.HttpReaderOptionNormalizer;
import com.jdragon.studio.infra.service.KafkaConfigurationSupport;
import com.jdragon.aggregation.commons.pagination.Table;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.datasource.AbstractDataSourcePlugin;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import com.jdragon.aggregation.datasource.ColumnInfo;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.datasource.TableInfo;
import com.jdragon.aggregation.datasource.file.FileHelper;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.aggregation.datasource.queue.QueueAbstract;
import com.jdragon.aggregation.pluginloader.LoadUtil;
import com.jdragon.aggregation.pluginloader.PluginClassLoaderCloseable;
import com.jdragon.aggregation.pluginloader.constant.SystemConstants;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeResolvers;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.jdragon.aggregation.pluginloader.spi.AbstractPlugin;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;

import static com.jdragon.studio.infra.service.execution.AggregationModelMetadataSupport.buildFileMetadata;
import static com.jdragon.studio.infra.service.execution.AggregationModelMetadataSupport.buildLightweightRelationalMetadata;
import static com.jdragon.studio.infra.service.execution.AggregationModelMetadataSupport.buildQueueMetadata;
import static com.jdragon.studio.infra.service.execution.AggregationModelMetadataSupport.buildRelationalMetadata;

@Slf4j
public class AggregationSourceCapabilityProvider implements SourceCapabilityProvider, ModelDiscoveryProvider {
    private static final String HTTP_READER_CONFIG_KEY = "__studio_http_reader_config";
    private static final int MAX_LOG_MESSAGE_LENGTH = 2 * 1024;


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
        configureAggregationHome(properties.getAggregationHome());
    }

    private void configureAggregationHome(String aggregationHome) {
        if (aggregationHome == null || aggregationHome.trim().isEmpty()) {
            aggregationHome = System.getProperty("aggregation.home");
        }
        if (aggregationHome == null || aggregationHome.trim().isEmpty()) {
            return;
        }
        String normalizedHome = new File(aggregationHome.trim()).getAbsolutePath();
        System.setProperty("aggregation.home", normalizedHome);
        SystemConstants.HOME = normalizedHome;
        SystemConstants.PLUGIN_HOME = new File(normalizedHome, "plugin").getPath();
        SystemConstants.CORE_CONFIG = new File(new File(normalizedHome, "conf"), "core.json").getPath();
        LoadUtil.updateJarLoader();
    }

    @Override
    public boolean supports(String typeCode) {
        return typeCode != null && !typeCode.trim().isEmpty();
    }

    /** Opens a binary file session using Worker-only decrypted datasource metadata. */
    public TransferFileSystem openTransferFileSystem(DataSourceDefinition definition) throws Exception {
        if (definition == null || definition.getTypeCode() == null) {
            throw new IllegalArgumentException("File datasource definition is required");
        }
        AbstractPlugin plugin;
        try (PluginClassLoaderCloseable loader = PluginClassLoaderCloseable
                .newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, definition.getTypeCode())) {
            plugin = loader.loadPlugin();
        }
        if (!(plugin instanceof TransferFileSystem)) {
            closeQuietly(plugin);
            throw new IllegalArgumentException("Datasource plugin does not support binary file transfer: "
                    + definition.getTypeCode());
        }
        TransferFileSystem fileSystem = (TransferFileSystem) plugin;
        try {
            Map<String, Object> metadata = normalizePluginMetadata(definition.getTypeCode(),
                    decryptMetadata(definition.getTechnicalMetadata()));
            if (!fileSystem.connect(Configuration.from(metadata))) {
                closeQuietly(fileSystem);
                throw new IllegalStateException("File datasource connection returned false: "
                        + definition.getTypeCode());
            }
            return fileSystem;
        } catch (Exception exception) {
            closeQuietly(fileSystem);
            throw exception;
        }
    }

    private void closeQuietly(Object value) {
        if (!(value instanceof AutoCloseable)) {
            return;
        }
        try {
            ((AutoCloseable) value).close();
        } catch (Exception exception) {
            log.warn("Failed to close file transfer datasource plugin", exception);
        }
    }

    @Override
    public ConnectionTestResult testConnection(DataSourceDefinition definition) {
        long startedAt = System.nanoTime();
        if (isHttpDatasource(definition)) {
            return testHttpConnection(definition);
        }
        String datasourceId = definition == null || definition.getId() == null
                ? null : String.valueOf(definition.getId());
        String datasourceType = definition == null ? null : definition.getTypeCode();
        log.info("[DATASOURCE_PROBE_START] datasourceId={} datasourceType={}",
                datasourceId, datasourceType);
        try (PluginClassLoaderCloseable loader = PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, definition.getTypeCode())) {
            AbstractPlugin plugin = loader.loadPlugin();
            if (plugin instanceof AbstractDataSourcePlugin) {
                boolean success = ((AbstractDataSourcePlugin) plugin).connectTest(toBaseDataSource(definition));
                return finishConnectionTest(definition, success,
                        success ? "Connection success" : "Connection failed", startedAt);
            }
            if (plugin instanceof FileHelper) {
                boolean success = ((FileHelper) plugin).connect(Configuration.from(normalizePluginMetadata(definition.getTypeCode(), decryptMetadata(definition.getTechnicalMetadata()))));
                return finishConnectionTest(definition, success,
                        success ? "Connection success" : "Connection failed", startedAt);
            }
            if (plugin instanceof QueueAbstract) {
                QueueAbstract queue = (QueueAbstract) plugin;
                try {
                    queue.setPluginQueueConf(Configuration.from(normalizePluginMetadata(definition.getTypeCode(), decryptMetadata(definition.getTechnicalMetadata()))));
                    queue.init();
                    boolean success = queue.checkConnectivity();
                    return finishConnectionTest(definition, success,
                            success ? "Connection success" : "Connection failed", startedAt);
                } finally {
                    destroyQuietly(queue);
                }
            }
            return finishConnectionTest(definition, false, "Unsupported plugin type", startedAt);
        } catch (Exception e) {
            String message = userFriendlyErrorMessage(e);
            log.error("[DATASOURCE_PROBE_FAILED] datasourceId={} datasourceType={} "
                            + "exceptionType={} message={} durationMillis={}",
                    datasourceId, datasourceType, e.getClass().getName(),
                    safeLogMessage(message), elapsedMillis(startedAt), e);
            return finishConnectionTest(definition, false, message, startedAt);
        }
    }

    private ConnectionTestResult finishConnectionTest(DataSourceDefinition definition,
                                                       boolean success,
                                                       String message,
                                                       long startedAt) {
        ConnectionTestResult result = new ConnectionTestResult();
        result.setSuccess(success);
        result.setMessage(message);
        if (success) {
            log.info("[DATASOURCE_PROBE_COMPLETED] datasourceId={} datasourceType={} "
                            + "success=true durationMillis={}",
                    definition == null ? null : definition.getId(),
                    definition == null ? null : definition.getTypeCode(),
                    elapsedMillis(startedAt));
        } else {
            log.warn("[DATASOURCE_PROBE_COMPLETED] datasourceId={} datasourceType={} "
                            + "success=false message={} durationMillis={}",
                    definition == null ? null : definition.getId(),
                    definition == null ? null : definition.getTypeCode(),
                    safeLogMessage(message), elapsedMillis(startedAt));
        }
        return result;
    }

    private void destroyQuietly(AbstractPlugin plugin) {
        try {
            plugin.destroy();
        } catch (Exception exception) {
            log.warn("Failed to destroy datasource probe plugin. pluginType={}",
                    plugin == null ? null : plugin.getClass().getName(), exception);
        }
    }

    private String safeLogMessage(String message) {
        String sanitized = StudioSensitiveLogSanitizer.sanitizeSingleLine(
                message, MAX_LOG_MESSAGE_LENGTH);
        return sanitized == null || sanitized.isBlank() ? "Unknown error" : sanitized;
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAt));
    }

    @Override
    public ModelDiscoveryResult discoverModels(DataSourceDefinition definition) {
        return discoverModels(definition, null);
    }

    @Override
    public ModelDiscoveryResult discoverModels(DataSourceDefinition definition, String keyword) {
        return discoverModels(definition, keyword, null, null);
    }

    public ModelDiscoveryOptionResult discoverModelOptions(DataSourceDefinition definition,
                                                           String keyword,
                                                           Integer pageNo,
                                                           Integer pageSize) {
        ModelDiscoveryOptionResult result = new ModelDiscoveryOptionResult();
        if (isHttpDatasource(definition)) {
            result.setPageNo(resolvePageNo(pageNo));
            result.setPageSize(resolvePageSize(pageSize, 1));
            result.setTotal(0L);
            result.setHasMore(false);
            result.setMessage("HTTP models are maintained manually");
            return result;
        }
        try (PluginClassLoaderCloseable loader = PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, definition.getTypeCode())) {
            AbstractPlugin plugin = loader.loadPlugin();
            if (plugin instanceof AbstractDataSourcePlugin) {
                AbstractDataSourcePlugin sourcePlugin = (AbstractDataSourcePlugin) plugin;
                BaseDataSourceDTO datasource = toBaseDataSource(definition);
                String normalizedKeyword = keyword == null ? "" : keyword.trim();
                List<String> tableNames = sourcePlugin.getTableNames(datasource, normalizedKeyword);
                List<String> pagedTableNames = paginate(tableNames, result, pageNo, pageSize);
                for (String tableName : pagedTableNames) {
                    result.getModels().add(toDiscoveryOption(definition, tableName, ModelKind.TABLE, tableName));
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
                    result.getModels().add(toDiscoveryOption(definition, fileName, ModelKind.FILE, fileName));
                }
                result.setMessage("Discovered file models");
                return result;
            }
            if (plugin instanceof QueueAbstract) {
                if (isKafkaDatasource(definition)) {
                    List<String> topics = discoverKafkaTopics((QueueAbstract) plugin, definition);
                    List<String> filteredTopics = filterQueueNames(topics, keyword);
                    List<String> pagedTopics = paginate(filteredTopics, result, pageNo, pageSize);
                    for (String topic : pagedTopics) {
                        result.getModels().add(toDiscoveryOption(definition, topic, ModelKind.TOPIC, topic));
                    }
                    result.setMessage("Discovered Kafka topics");
                    return result;
                }
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
                result.getModels().add(toDiscoveryOption(definition, modelName, ModelKind.TOPIC, modelName));
                result.setTotal(1L);
                result.setMessage("Queue model synthesized from datasource metadata");
                return result;
            }
            result.setMessage("No model discovery provider");
        } catch (Exception e) {
            result.setMessage(userFriendlyErrorMessage(e));
        }
        return result;
    }

    @Override
    public ModelDiscoveryResult discoverModels(DataSourceDefinition definition,
                                               String keyword,
                                               Integer pageNo,
                                               Integer pageSize) {
        ModelDiscoveryResult result = new ModelDiscoveryResult();
        if (isHttpDatasource(definition)) {
            result.setPageNo(resolvePageNo(pageNo));
            result.setPageSize(resolvePageSize(pageSize, 1));
            result.setTotal(0L);
            result.setHasMore(false);
            result.setMessage("HTTP models are maintained manually");
            return result;
        }
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
                if (isKafkaDatasource(definition)) {
                    List<String> topics = discoverKafkaTopics((QueueAbstract) plugin, definition);
                    List<String> filteredTopics = filterQueueNames(topics, keyword);
                    List<String> pagedTopics = paginate(filteredTopics, result, pageNo, pageSize);
                    for (String topic : pagedTopics) {
                        DataModelDefinition model = new DataModelDefinition();
                        model.setDatasourceId(definition.getId());
                        model.setName(topic);
                        model.setModelKind(ModelKind.TOPIC);
                        model.setPhysicalLocator(topic);
                        model.setTechnicalMetadata(buildQueueMetadata(definition,
                                normalizePluginMetadata(definition.getTypeCode(), decryptMetadata(definition.getTechnicalMetadata())), topic));
                        model.setBusinessMetadata(buildEmptyBusinessMetadata());
                        result.getModels().add(model);
                    }
                    result.setMessage("Discovered Kafka topics");
                    return result;
                }
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
            result.setMessage(userFriendlyErrorMessage(e));
        }
        return result;
    }

    private DataModelDatasourceOptionView toDiscoveryOption(DataSourceDefinition definition,
                                                            String name,
                                                            ModelKind modelKind,
                                                            String physicalLocator) {
        DataModelDatasourceOptionView view = new DataModelDatasourceOptionView();
        view.setDatasourceId(definition == null ? null : definition.getId());
        view.setName(name);
        view.setModelKind(modelKind);
        view.setPhysicalLocator(physicalLocator);
        return view;
    }

    private boolean isKafkaDatasource(DataSourceDefinition definition) {
        return definition != null && "kafka".equalsIgnoreCase(definition.getTypeCode());
    }

    private List<String> discoverKafkaTopics(QueueAbstract queue, DataSourceDefinition definition) throws Exception {
        try {
            queue.setPluginQueueConf(Configuration.from(normalizePluginMetadata(
                    definition.getTypeCode(), decryptMetadata(definition.getTechnicalMetadata()))));
            queue.init();
            Method listTopic = queue.getClass().getMethod("listTopic");
            Object value = listTopic.invoke(queue);
            Set<String> topics = new HashSet<String>();
            if (value instanceof Set<?>) {
                for (Object item : (Set<?>) value) {
                    if (item != null && !String.valueOf(item).trim().isEmpty()) {
                        String topic = String.valueOf(item).trim();
                        if (!topic.startsWith("__")) {
                            topics.add(topic);
                        }
                    }
                }
            }
            List<String> result = new ArrayList<String>(topics);
            result.sort(Comparator.naturalOrder());
            return result;
        } finally {
            destroyQuietly(queue);
        }
    }

    private List<String> filterQueueNames(List<String> names, String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (normalizedKeyword.isEmpty()) {
            return names == null ? new ArrayList<String>() : names;
        }
        List<String> filtered = new ArrayList<String>();
        if (names != null) {
            for (String name : names) {
                if (name != null && name.toLowerCase(Locale.ROOT).contains(normalizedKeyword)) {
                    filtered.add(name);
                }
            }
        }
        return filtered;
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

    private List<String> paginate(List<String> names,
                                  ModelDiscoveryOptionResult result,
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
                results.add(new HydrationResult(resolvePhysicalLocator(item), null, mostSpecificErrorMessage(e, null)));
            }
            return results;
        }
    }

    @Override
    public List<Map<String, Object>> preview(DataSourceDefinition datasource, DataModelDefinition model, int limit) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (isHttpDatasource(datasource)) {
            return previewHttp(datasource, model, limit);
        }
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
                Map<String, Object> connectionMetadata = normalizePluginMetadata(datasource.getTypeCode(), decryptMetadata(datasource.getTechnicalMetadata()));
                Map<String, Object> fileMetadata = new LinkedHashMap<String, Object>(connectionMetadata);
                if (model != null && model.getTechnicalMetadata() != null) {
                    fileMetadata.putAll(model.getTechnicalMetadata());
                }
                fileHelper.connect(Configuration.from(connectionMetadata));
                fileHelper.readFile(AggregationFileModelPathSupport.resolveFilePreviewPath(model),
                        String.valueOf(fileMetadata.getOrDefault("fileType", "csv")),
                        row -> {
                            if (rows.size() < limit) {
                                rows.add(new LinkedHashMap<String, Object>(row));
                            }
                        },
                        Configuration.from(fileMetadata));
            }
        } catch (Exception e) {
            log.warn("Failed to preview datasource model. datasourceId={}, modelId={}, reason={}",
                    datasource == null ? null : datasource.getId(),
                    model == null ? null : model.getId(),
                    e.getMessage());
            if (datasource != null && "odps".equalsIgnoreCase(datasource.getTypeCode())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "ODPS样例数据预览失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return rows;
    }

    private List<Map<String, Object>> previewHttp(DataSourceDefinition datasource,
                                                  DataModelDefinition model,
                                                  int limit) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        requireHttpSourcePlugin(datasource == null ? null : datasource.getTypeCode());
        try (PluginClassLoaderCloseable loader = PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, datasource.getTypeCode())) {
            AbstractPlugin plugin = loader.loadPlugin();
            if (!(plugin instanceof AbstractDataSourcePlugin)) {
                return rows;
            }
            Map<String, Object> datasourceMetadata = normalizePluginMetadata(datasource.getTypeCode(),
                    decryptMetadata(datasource.getTechnicalMetadata()));
            Map<String, Object> modelMetadata = model == null || model.getTechnicalMetadata() == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(model.getTechnicalMetadata());
            BaseDataSourceDTO dataSourceDTO = toBaseDataSource(datasource);
            attachHttpReaderConfig(dataSourceDTO, datasourceMetadata, model, modelMetadata);
            Table<Map<String, Object>> table = ((AbstractDataSourcePlugin) plugin)
                    .dataModelPreview(dataSourceDTO, model == null ? null : model.getPhysicalLocator(), String.valueOf(limit));
            if (table != null && table.getBodies() != null) {
                int safeLimit = limit <= 0 ? table.getBodies().size() : limit;
                for (Map<String, Object> row : table.getBodies()) {
                    if (rows.size() >= safeLimit) {
                        break;
                    }
                    rows.add(row);
                }
            }
            return rows;
        } catch (Exception e) {
            log.warn("Failed to preview HTTP datasource model. datasourceId={}, modelId={}, exception={}",
                    datasource == null ? null : datasource.getId(),
                    model == null ? null : model.getId(),
                    e.getClass().getSimpleName());
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "HTTP样例数据预览失败: " + userFriendlyErrorMessage(e));
        }
    }

    private void requireHttpSourcePlugin(String pluginName) {
        String name = isBlank(pluginName) ? "http" : pluginName.trim();
        ResolvedPlugin resolvedPlugin = LoadUtil.resolvePlugin(SourcePluginType.SOURCE, name);
        File pluginDirectory = resolvedPlugin.getDirectory().toFile();
        if (!pluginDirectory.isDirectory()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "HTTP source plugin directory does not exist: " + pluginDirectory.getAbsolutePath());
        }
        if (PluginRuntimeResolvers.isLocalResolver()) {
            LoadUtil.updateJarLoader(SourcePluginType.SOURCE, name);
        }
    }

    private void attachHttpReaderConfig(BaseDataSourceDTO dto,
                                        Map<String, Object> datasourceMetadata,
                                        DataModelDefinition model,
                                        Map<String, Object> modelMetadata) {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("url", resolveHttpUrl(datasourceMetadata, model, modelMetadata));
        boolean soap = isSoapProtocol(modelMetadata);
        config.put("mode", soap ? "POST" : firstText(modelMetadata.get("mode"), "GET").toUpperCase(Locale.ENGLISH));
        String protocolMode = soap ? "SOAP" : resolveProtocolMode(modelMetadata);
        config.put("protocolMode", protocolMode);
        config.put("soapVersion", firstText(modelMetadata.get("soapVersion"), "SOAP_11"));
        putIfPresent(config, "soapAction", modelMetadata.get("soapAction"));
        config.put("soapFaultFail", Boolean.TRUE);
        config.put("contentType", resolveHttpContentType(protocolMode, String.valueOf(config.get("soapVersion"))));
        config.put("header", "{}");
        config.put("params", "{}");
        config.put("requestBody", "");
        config.put("resultType", resolveHttpResultType(modelMetadata, protocolMode));
        putIfPresent(config, "totalCodePath", modelMetadata.get("totalCodePath"));
        Map<String, Object> responseStatus = resolveHttpResponseStatus(modelMetadata);
        if (!responseStatus.isEmpty()) {
            config.put("responseStatus", responseStatus);
        }
        config.put("pageRead", Boolean.FALSE);
        config.put("pageSize", Integer.valueOf(500));
        config.put("columns", modelMetadata.get("columns"));
        HttpReaderOptionNormalizer.mergeInto(config, modelMetadata.get("readerOptions"));
        HttpReaderOptionNormalizer.enforceProtocolContract(config);
        Map<String, String> extraParams = dto.getExtraParams() == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(dto.getExtraParams());
        extraParams.put(HTTP_READER_CONFIG_KEY, JSONObject.toJSONString(config));
        dto.setExtraParams(extraParams);
    }

    private String resolveHttpUrl(Map<String, Object> datasourceMetadata,
                                  DataModelDefinition model,
                                  Map<String, Object> modelMetadata) {
        String requestPath = model == null ? null : model.getPhysicalLocator();
        if (isBlank(requestPath)) {
            requestPath = firstText(modelMetadata.get("requestPath"), modelMetadata.get("physicalName"));
        }
        if (!isBlank(requestPath) && isAbsoluteHttpUrl(requestPath)) {
            return requestPath.trim();
        }
        String baseUrl = firstText(datasourceMetadata == null ? null : datasourceMetadata.get("url"),
                datasourceMetadata == null ? null : datasourceMetadata.get("endpoint"));
        if (isBlank(baseUrl)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP datasource url is required");
        }
        if (isBlank(requestPath)) {
            return baseUrl;
        }
        return joinHttpUrl(baseUrl, requestPath.trim());
    }

    private String joinHttpUrl(String baseUrl, String requestPath) {
        boolean baseEndsWithSlash = baseUrl.endsWith("/");
        boolean pathStartsWithSlash = requestPath.startsWith("/");
        if (baseEndsWithSlash && pathStartsWithSlash) {
            return baseUrl + requestPath.substring(1);
        }
        if (!baseEndsWithSlash && !pathStartsWithSlash) {
            return baseUrl + "/" + requestPath;
        }
        return baseUrl + requestPath;
    }

    private boolean isAbsoluteHttpUrl(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private boolean isSoapProtocol(Map<String, Object> metadata) {
        return "SOAP".equalsIgnoreCase(resolveProtocolMode(metadata));
    }

    private String resolveProtocolMode(Map<String, Object> metadata) {
        Object protocolMode = metadata == null ? null : metadata.get("protocolMode");
        if (!isBlankValue(protocolMode)) {
            return String.valueOf(protocolMode).trim().toUpperCase(Locale.ENGLISH);
        }
        String resultType = firstText(metadata == null ? null : metadata.get("resultType"), "json");
        if ("xml".equalsIgnoreCase(resultType)) {
            return "REST_XML";
        }
        if ("soap".equalsIgnoreCase(resultType)) {
            return "SOAP";
        }
        return "REST_JSON";
    }

    private String resolveSoapContentType(String soapVersion) {
        return "SOAP_12".equalsIgnoreCase(soapVersion)
                ? "application/soap+xml;charset=UTF-8"
                : "text/xml;charset=UTF-8";
    }

    private String resolveHttpContentType(String protocolMode, String soapVersion) {
        if ("SOAP".equalsIgnoreCase(protocolMode)) {
            return resolveSoapContentType(soapVersion);
        }
        return "REST_XML".equalsIgnoreCase(protocolMode)
                ? "application/xml;charset=UTF-8"
                : "application/json;charset=utf-8";
    }

    private String resolveHttpResultType(Map<String, Object> metadata, String protocolMode) {
        if ("SOAP".equalsIgnoreCase(protocolMode)) {
            return "soap";
        }
        if ("REST_XML".equalsIgnoreCase(protocolMode)) {
            return "xml";
        }
        if ("REST_JSON".equalsIgnoreCase(protocolMode)) {
            return "json";
        }
        return firstText(metadata == null ? null : metadata.get("resultType"), "json")
                .toLowerCase(Locale.ENGLISH);
    }

    private Map<String, Object> resolveHttpResponseStatus(Map<String, Object> metadata) {
        Object statusPath = metadata == null ? null : metadata.get("businessStatusPath");
        Object statusCode = metadata == null ? null : metadata.get("businessStatusCode");
        boolean hasPath = !isBlankValue(statusPath);
        boolean hasCode = !isBlankValue(statusCode);
        if (!hasPath && !hasCode) {
            return Collections.emptyMap();
        }
        if (!hasPath || !hasCode) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP business status path and code must be configured together");
        }
        Map<String, Object> responseStatus = new LinkedHashMap<String, Object>();
        responseStatus.put("path", String.valueOf(statusPath).trim());
        responseStatus.put("code", String.valueOf(statusCode).trim());
        return responseStatus;
    }

    private ConnectionTestResult testHttpConnection(DataSourceDefinition definition) {
        ConnectionTestResult result = new ConnectionTestResult();
        String url = httpConnectionUrl(definition);
        if (url == null || url.trim().isEmpty()) {
            result.setSuccess(false);
            result.setMessage("HTTP datasource url is required");
            return result;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                result.setSuccess(false);
                result.setMessage("HTTP datasource url must start with http:// or https://");
                return result;
            }
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();
            result.setSuccess(statusCode == 200);
            result.setMessage("HTTP status " + statusCode);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage(userFriendlyErrorMessage(e));
        }
        return result;
    }

    private String httpConnectionUrl(DataSourceDefinition definition) {
        if (definition == null) {
            return null;
        }
        Map<String, Object> metadata = decryptMetadata(definition.getTechnicalMetadata());
        return firstText(metadata.get("url"), metadata.get("endpoint"));
    }

    private boolean isHttpDatasource(DataSourceDefinition definition) {
        return definition != null && "http".equalsIgnoreCase(definition.getTypeCode());
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
            String message = mostSpecificErrorMessage(itemException, originalBatchException);
            return new HydrationResult(resolvePhysicalLocator(item), null, message);
        }
    }

    private String mostSpecificErrorMessage(Throwable primary, Throwable fallback) {
        String message = deepestMessage(primary);
        if (message != null && !message.trim().isEmpty()) {
            return message;
        }
        message = deepestMessage(fallback);
        return message == null || message.trim().isEmpty() ? null : message;
    }

    private String userFriendlyErrorMessage(Throwable throwable) {
        String message = deepestMessage(throwable);
        if (message == null || message.trim().isEmpty()) {
            return throwable == null ? "Unknown error" : throwable.getClass().getSimpleName();
        }
        return stripExceptionClassPrefix(message.trim());
    }

    private String deepestMessage(Throwable throwable) {
        Throwable current = unwrapInvocationTarget(throwable);
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().trim().isEmpty()) {
                message = current.getMessage().trim();
            }
            Throwable cause = unwrapInvocationTarget(current.getCause());
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        return message;
    }

    private String stripExceptionClassPrefix(String message) {
        String result = message;
        while (result != null) {
            int separator = result.indexOf(": ");
            if (separator <= 0) {
                return result;
            }
            String prefix = result.substring(0, separator);
            if (!looksLikeExceptionClass(prefix)) {
                return result;
            }
            result = result.substring(separator + 2).trim();
        }
        return message;
    }

    private boolean looksLikeExceptionClass(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return false;
        }
        String simpleName = prefix;
        int dotIndex = simpleName.lastIndexOf('.');
        if (dotIndex >= 0) {
            simpleName = simpleName.substring(dotIndex + 1);
        }
        return simpleName.endsWith("Exception") || simpleName.endsWith("Error");
    }

    private Throwable unwrapInvocationTarget(Throwable throwable) {
        if (throwable instanceof InvocationTargetException) {
            Throwable target = ((InvocationTargetException) throwable).getTargetException();
            return target == null ? throwable : target;
        }
        return throwable;
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
            Map<String, List<TableInfo>> converted = convertTypedListMap(value, TableInfo.class);
            if (converted != null) {
                return converted;
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
            Map<String, List<ColumnInfo>> converted = convertTypedListMap(value, ColumnInfo.class);
            if (converted != null) {
                return converted;
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

    private <T> Map<String, List<T>> convertTypedListMap(Object candidate, Class<T> itemType) {
        if (!(candidate instanceof Map<?, ?>)) {
            return null;
        }
        Map<?, ?> source = (Map<?, ?>) candidate;
        Map<String, List<T>> result = new LinkedHashMap<String, List<T>>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            List<T> items = convertTypedList(entry.getValue(), itemType);
            if (items == null) {
                return null;
            }
            result.put(String.valueOf(entry.getKey()), items);
        }
        return result;
    }

    private <T> List<T> convertTypedList(Object candidate, Class<T> itemType) {
        if (candidate == null) {
            return Collections.emptyList();
        }
        if (!(candidate instanceof List<?>)) {
            return null;
        }
        List<?> source = (List<?>) candidate;
        List<T> result = new ArrayList<T>(source.size());
        for (Object item : source) {
            if (!itemType.isInstance(item)) {
                return null;
            }
            result.add(itemType.cast(item));
        }
        return result;
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

    private Map<String, Object> buildEmptyBusinessMetadata() {
        return businessMetaModelMetadataService.emptyMetadata();
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
            return KafkaConfigurationSupport.normalizeDatasourceMetadata(normalized);
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

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || isBlankValue(value)) {
            return;
        }
        target.put(key, value);
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (!isBlankValue(value)) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isBlankValue(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
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

