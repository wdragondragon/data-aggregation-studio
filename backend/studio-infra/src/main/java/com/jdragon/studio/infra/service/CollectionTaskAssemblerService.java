package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionIncrementalDefinition;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CollectionTaskAssemblerService {

    private static final CollectionTaskRuntimeOptionMerger RUNTIME_OPTION_MERGER = new CollectionTaskRuntimeOptionMerger();
    private static final String CATEGORY_FILE_SYSTEM = "FILE_SYSTEM";
    private static final String MYSQL_BATCH_REWRITE_KEY = "rewriteBatchedStatements";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP_TYPE =
            new TypeReference<LinkedHashMap<String, String>>() {
            };

    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final EncryptionService encryptionService;
    private final HttpReaderOptionSecurityService httpReaderOptionSecurityService;
    private final PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService;
    private final CollectionTaskFieldMappingResolver fieldMappingResolver;
    private final CollectionTaskFileConfigSupport fileConfigSupport;
    private final CollectionTaskHttpConfigSupport httpConfigSupport;

    public CollectionTaskAssemblerService(DataSourceService dataSourceService,
                                          DataModelService dataModelService,
                                          EncryptionService encryptionService,
                                          PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService) {
        this(dataSourceService,
                dataModelService,
                encryptionService,
                pluginRuntimeOptionSchemaService,
                new StudioTransformerSupport(new ObjectMapper()),
                new HttpReaderOptionSecurityService(encryptionService));
    }

    @Autowired
    public CollectionTaskAssemblerService(DataSourceService dataSourceService,
                                          DataModelService dataModelService,
                                          EncryptionService encryptionService,
                                          PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService,
                                          StudioTransformerSupport transformerSupport,
                                          HttpReaderOptionSecurityService httpReaderOptionSecurityService) {
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.encryptionService = encryptionService;
        this.httpReaderOptionSecurityService = httpReaderOptionSecurityService;
        this.pluginRuntimeOptionSchemaService = pluginRuntimeOptionSchemaService;
        this.fieldMappingResolver = new CollectionTaskFieldMappingResolver(transformerSupport);
        this.fileConfigSupport = new CollectionTaskFileConfigSupport(fieldMappingResolver);
        this.httpConfigSupport = new CollectionTaskHttpConfigSupport(fieldMappingResolver);
    }

    public Map<String, Object> assemble(CollectionTaskDefinitionView definition) {
        if (definition == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Collection task definition is required");
        }
        if (definition.getTaskType() == CollectionTaskType.FUSION) {
            return assembleFusion(definition);
        }
        return assembleSingle(definition);
    }

    public Map<String, Object> assemblePreview(CollectionTaskDefinitionView definition) {
        Map<String, Object> config = assemble(definition);
        maskPreviewReader(config);
        return config;
    }

    public Map<String, Object> assembleWriter(Long datasourceId,
                                              Long modelId,
                                              List<String> targetFields,
                                              Map<String, Object> writerOptions) {
        DataSourceDefinition targetDatasource = requiredDatasource(datasourceId);
        DataModelDefinition targetModel = requiredModel(modelId);
        CollectionTaskTargetBinding binding = new CollectionTaskTargetBinding();
        binding.setDatasourceId(datasourceId);
        binding.setModelId(modelId);
        binding.setWriterOptions(writerOptions == null ? new LinkedHashMap<String, Object>() : writerOptions);
        return buildStandardWriter(binding, targetDatasource, targetModel, targetFields);
    }

    private Map<String, Object> assembleSingle(CollectionTaskDefinitionView definition) {
        CollectionTaskSourceBinding sourceBinding = definition.getSourceBindings().get(0);
        CollectionTaskTargetBinding targetBinding = definition.getTargetBinding();
        DataSourceDefinition sourceDatasource = requiredDatasource(sourceBinding.getDatasourceId());
        DataModelDefinition sourceModel = requiredModel(sourceBinding.getModelId());
        DataSourceDefinition targetDatasource = requiredDatasource(targetBinding.getDatasourceId());
        DataModelDefinition targetModel = requiredModel(targetBinding.getModelId());

        List<String> targetFields = fieldMappingResolver.resolveTargetFields(definition.getFieldMappings(), targetModel);
        List<String> sourceFields = fieldMappingResolver.resolveSingleSourceFields(definition.getFieldMappings(), sourceModel);

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("reader", buildStandardReader(sourceBinding, sourceDatasource, sourceModel, sourceFields, definition.getExecutionOptions()));
        List<Map<String, Object>> transformers = fieldMappingResolver.buildTransformers(definition.getFieldMappings(), targetFields);
        if (!transformers.isEmpty()) {
            config.put("transformer", transformers);
        }
        config.put("writer", buildStandardWriter(targetBinding, targetDatasource, targetModel, targetFields));
        return config;
    }

    private Map<String, Object> assembleFusion(CollectionTaskDefinitionView definition) {
        CollectionTaskTargetBinding targetBinding = definition.getTargetBinding();
        DataSourceDefinition targetDatasource = requiredDatasource(targetBinding.getDatasourceId());
        DataModelDefinition targetModel = requiredModel(targetBinding.getModelId());
        Map<String, Object> executionOptions = definition.getExecutionOptions() == null
                ? new LinkedHashMap<String, Object>()
                : definition.getExecutionOptions();

        List<String> targetFields = fieldMappingResolver.resolveTargetFields(definition.getFieldMappings(), targetModel);
        List<String> joinKeys = fieldMappingResolver.resolveJoinKeys(definition);
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        for (CollectionTaskSourceBinding sourceBinding : definition.getSourceBindings()) {
            DataSourceDefinition sourceDatasource = requiredDatasource(sourceBinding.getDatasourceId());
            DataModelDefinition sourceModel = requiredModel(sourceBinding.getModelId());
            List<String> sourceFields = fieldMappingResolver.resolveSourceFieldsByAlias(definition.getFieldMappings(), sourceBinding.getSourceAlias(), sourceModel, joinKeys);
            String pluginType = resolvePluginType(sourceDatasource.getTypeCode(), "reader");
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", sourceBinding.getSourceAlias());
            item.put("type", pluginType);
            item.putAll(buildReaderConfig(sourceBinding, sourceDatasource, sourceModel, sourceFields, pluginType));
            mergeModelReaderOptions(item, sourceModel, sourceDatasource.getTypeCode(), pluginType);
            mergeRuntimeOptions(item, readerOptionOverrides(sourceBinding, sourceModel, sourceDatasource.getTypeCode(), pluginType),
                    "reader", runtimeStringKeys(sourceDatasource.getTypeCode(), pluginType));
            normalizeReaderRuntimeConfig(item, sourceDatasource.getTypeCode(), pluginType);
            if (!isFileReader(sourceDatasource.getTypeCode(), pluginType) && !isHttpReader(sourceDatasource.getTypeCode(), pluginType)) {
                applyIncrementalOptions(item, sourceBinding, executionOptions);
            }
            sources.add(item);
        }

        Map<String, Object> join = new LinkedHashMap<String, Object>();
        join.put("keys", joinKeys);
        join.put("type", String.valueOf(executionOptions.getOrDefault("joinType", "LEFT")));

        List<Map<String, Object>> fieldMappings = new ArrayList<Map<String, Object>>();
        for (FieldMappingDefinition mapping : definition.getFieldMappings()) {
            if (mapping.getTargetField() == null || mapping.getTargetField().trim().isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            if (mapping.getExpression() != null && !mapping.getExpression().trim().isEmpty()) {
                item.put("type", "EXPRESSION");
                item.put("expression", mapping.getExpression());
            } else {
                item.put("type", "DIRECT");
                item.put("sourceField", mapping.getSourceAlias() + "." + mapping.getSourceField());
            }
            item.put("targetField", mapping.getTargetField());
            fieldMappings.add(item);
        }

        Map<String, Object> reader = new LinkedHashMap<String, Object>();
        reader.put("type", "fusion");
        Map<String, Object> readerConfig = new LinkedHashMap<String, Object>();
        readerConfig.put("sources", sources);
        readerConfig.put("join", join);
        readerConfig.put("fieldMappings", fieldMappings);
        mergeRuntimeOptions(readerConfig, RUNTIME_OPTION_MERGER.toMap(executionOptions.get("fusionReaderOptions")), "reader");
        reader.put("config", readerConfig);

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("reader", reader);
        List<Map<String, Object>> transformers = fieldMappingResolver.buildTransformers(definition.getFieldMappings(), targetFields);
        if (!transformers.isEmpty()) {
            config.put("transformer", transformers);
        }
        config.put("writer", buildStandardWriter(targetBinding, targetDatasource, targetModel, targetFields));
        return config;
    }

    private Map<String, Object> buildStandardReader(CollectionTaskSourceBinding binding,
                                                    DataSourceDefinition datasource,
                                                    DataModelDefinition model,
                                                    List<String> sourceFields,
                                                    Map<String, Object> executionOptions) {
        Map<String, Object> reader = new LinkedHashMap<String, Object>();
        String pluginType = resolvePluginType(datasource.getTypeCode(), "reader");
        reader.put("type", pluginType);
        Map<String, Object> readerConfig = buildReaderConfig(binding, datasource, model, sourceFields, pluginType);
        mergeModelReaderOptions(readerConfig, model, datasource.getTypeCode(), pluginType);
        mergeRuntimeOptions(readerConfig, readerOptionOverrides(binding, model, datasource.getTypeCode(), pluginType),
                "reader", runtimeStringKeys(datasource.getTypeCode(), pluginType));
        normalizeReaderRuntimeConfig(readerConfig, datasource.getTypeCode(), pluginType);
        if (!isFileReader(datasource.getTypeCode(), pluginType) && !isHttpReader(datasource.getTypeCode(), pluginType)) {
            applyIncrementalOptions(readerConfig, binding, executionOptions);
        }
        reader.put("config", readerConfig);
        return reader;
    }

    private Map<String, Object> buildStandardWriter(CollectionTaskTargetBinding binding,
                                                    DataSourceDefinition datasource,
                                                    DataModelDefinition model,
                                                    List<String> targetFields) {
        Map<String, Object> writer = new LinkedHashMap<String, Object>();
        String pluginType = resolvePluginType(datasource.getTypeCode(), "writer");
        writer.put("type", pluginType);
        Map<String, Object> writerConfig = buildWriterConfig(datasource, model, targetFields, pluginType);
        mergeRuntimeOptions(writerConfig, binding.getWriterOptions(), "writer", runtimeStringKeys(datasource.getTypeCode(), pluginType));
        normalizeWriterRuntimeConfig(writerConfig, datasource.getTypeCode(), pluginType);
        applyDefaultWriteMode(writerConfig, pluginType);
        writer.put("config", writerConfig);
        return writer;
    }

    private Map<String, Object> buildReaderConfig(CollectionTaskSourceBinding binding,
                                                  DataSourceDefinition datasource,
                                                  DataModelDefinition model,
                                                  List<String> sourceFields,
                                                  String pluginType) {
        Map<String, Object> readerConfig = new LinkedHashMap<String, Object>();
        readerConfig.put("sourceAlias", binding.getSourceAlias());
        if ("kafka".equalsIgnoreCase(pluginType)) {
            readerConfig.putAll(buildConnectionConfig(datasource));
            readerConfig.put("topic", resolveQueueTopic(model, readerConfig));
            readerConfig.put("columns", fieldMappingResolver.resolveColumnEntries(model, sourceFields, false));
            return readerConfig;
        }
        if ("rocketmq".equalsIgnoreCase(pluginType)) {
            Map<String, Object> connect = buildConnectionConfig(datasource);
            if (!connect.containsKey("topic")) {
                connect.put("topic", model.getPhysicalLocator());
            }
            readerConfig.put("connect", connect);
            readerConfig.put("columns", fieldMappingResolver.resolveColumnEntries(model, sourceFields, false));
            return readerConfig;
        }
        if ("influxdbv1".equalsIgnoreCase(pluginType)) {
            readerConfig.put("connect", buildConnectionConfig(datasource));
            readerConfig.put("measurement", model.getPhysicalLocator());
            readerConfig.put("columns", sourceFields);
            return readerConfig;
        }
        if (isHttpReader(datasource.getTypeCode(), pluginType)) {
            readerConfig.putAll(httpConfigSupport.buildReaderConfig(buildConnectionConfig(datasource), model, sourceFields));
            return readerConfig;
        }
        if (isFileReader(datasource.getTypeCode(), pluginType)) {
            readerConfig.putAll(fileConfigSupport.buildReaderConfig(buildConnectionConfig(datasource), model, sourceFields));
            return readerConfig;
        }
        readerConfig.put("connect", buildConnectionConfig(datasource));
        readerConfig.put("table", model.getPhysicalLocator());
        readerConfig.put("columns", sourceFields);
        return readerConfig;
    }

    private Map<String, Object> buildWriterConfig(DataSourceDefinition datasource,
                                                  DataModelDefinition model,
                                                  List<String> targetFields,
                                                  String pluginType) {
        Map<String, Object> writerConfig = new LinkedHashMap<String, Object>();
        if ("kafka".equalsIgnoreCase(pluginType)) {
            writerConfig.putAll(buildConnectionConfig(datasource));
            writerConfig.put("topic", resolveQueueTopic(model, writerConfig));
            writerConfig.put("columns", fieldMappingResolver.resolveColumnEntries(model, targetFields, false));
            return writerConfig;
        }
        if ("rocketmq".equalsIgnoreCase(pluginType)) {
            Map<String, Object> connect = buildConnectionConfig(datasource);
            if (!connect.containsKey("topic")) {
                connect.put("topic", model.getPhysicalLocator());
            }
            writerConfig.put("connect", connect);
            writerConfig.put("columns", fieldMappingResolver.resolveColumnEntries(model, targetFields, false));
            return writerConfig;
        }
        if ("influxdbv1".equalsIgnoreCase(pluginType)) {
            writerConfig.put("connect", buildConnectionConfig(datasource));
            writerConfig.put("measurement", model.getPhysicalLocator());
            writerConfig.put("columns", fieldMappingResolver.resolveColumnEntries(model, targetFields, true));
            return writerConfig;
        }
        if (isFileWriter(datasource.getTypeCode(), pluginType)) {
            writerConfig.putAll(fileConfigSupport.buildWriterConfig(buildConnectionConfig(datasource), model, targetFields));
            return writerConfig;
        }
        if (isHttpWriter(datasource.getTypeCode(), pluginType)) {
            writerConfig.putAll(httpConfigSupport.buildWriterConfig(buildConnectionConfig(datasource), model, targetFields));
            return writerConfig;
        }
        Map<String, Object> connect = buildConnectionConfig(datasource);
        applyMysqlWriterBatchOptions(connect, pluginType);
        writerConfig.put("connect", connect);
        writerConfig.put("table", model.getPhysicalLocator());
        writerConfig.put("columns", targetFields);
        return writerConfig;
    }

    private void applyIncrementalOptions(Map<String, Object> config,
                                         CollectionTaskSourceBinding sourceBinding,
                                         Map<String, Object> executionOptions) {
        CollectionIncrementalDefinition incremental = sourceBinding.getIncremental();
        if (incremental == null) {
            return;
        }
        Object collectionMode = executionOptions == null ? null : executionOptions.get("collectionMode");
        if (!isBlankValue(collectionMode) && !"INCREMENTAL".equalsIgnoreCase(String.valueOf(collectionMode))) {
            return;
        }
        boolean globalIncremental = !isBlankValue(collectionMode)
                && "INCREMENTAL".equalsIgnoreCase(String.valueOf(collectionMode));
        boolean sourceIncremental = Boolean.TRUE.equals(incremental.getEnabled());
        if (!globalIncremental && !sourceIncremental) {
            return;
        }
        if (isBlank(incremental.getIncrColumn())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Incremental column is required for source " + sourceBinding.getSourceAlias());
        }
        config.put("incrColumn", incremental.getIncrColumn());
        config.put("incrModel", isBlank(incremental.getIncrModel()) ? ">" : incremental.getIncrModel());
        if (incremental.getPkValue() != null && !String.valueOf(incremental.getPkValue()).trim().isEmpty()) {
            config.put("pkValue", incremental.getPkValue());
        }
    }

    private void mergeRuntimeOptions(Map<String, Object> config,
                                     Map<String, Object> runtimeOptions,
                                     String role) {
        RUNTIME_OPTION_MERGER.merge(config, runtimeOptions, role, reservedKeys(role));
    }

    private void mergeRuntimeOptions(Map<String, Object> config,
                                     Map<String, Object> runtimeOptions,
                                     String role,
                                     Set<String> preserveStringKeys) {
        RUNTIME_OPTION_MERGER.merge(config, runtimeOptions, role, preserveStringKeys, reservedKeys(role));
    }

    private void mergeModelReaderOptions(Map<String, Object> config,
                                         DataModelDefinition model,
                                         String datasourceTypeCode,
                                         String pluginType) {
        if (!isHttpReader(datasourceTypeCode, pluginType) || model == null || model.getTechnicalMetadata() == null) {
            return;
        }
        Map<String, Object> modelReaderOptions =
                RUNTIME_OPTION_MERGER.toMap(model.getTechnicalMetadata().get("readerOptions"));
        modelReaderOptions.remove("soapVersion");
        modelReaderOptions.remove("soapAction");
        mergeRuntimeOptions(config, modelReaderOptions, "reader", runtimeStringKeys(datasourceTypeCode, pluginType));
    }

    Map<String, Object> prepareReaderOptionOverrides(String datasourceTypeCode,
                                                      DataModelDefinition model,
                                                      Map<String, Object> readerOptions) {
        return prepareReaderOptionOverrides(datasourceTypeCode, model, readerOptions, null);
    }

    Map<String, Object> prepareReaderOptionOverrides(String datasourceTypeCode,
                                                      DataModelDefinition model,
                                                      Map<String, Object> readerOptions,
                                                      Map<String, Object> existingReaderOptions) {
        if (!"http".equalsIgnoreCase(datasourceTypeCode) || model == null) {
            return readerOptions == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(readerOptions);
        }
        return httpReaderOptionSecurityService.prepareReaderOptionOverrides(
                readerOptions, model.getTechnicalMetadata(), existingReaderOptions);
    }

    Map<String, Object> maskReaderOptionOverridesForView(String datasourceTypeCode,
                                                          DataModelDefinition model,
                                                          Map<String, Object> readerOptions) {
        if (!"http".equalsIgnoreCase(datasourceTypeCode)) {
            return readerOptions == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(readerOptions);
        }
        return httpReaderOptionSecurityService.maskReaderOptionOverridesForView(
                readerOptions,
                model == null ? Collections.<String, Object>emptyMap() : model.getTechnicalMetadata());
    }

    String maskHttpPhysicalLocator(String datasourceTypeCode, String physicalLocator) {
        return "http".equalsIgnoreCase(datasourceTypeCode)
                ? httpReaderOptionSecurityService.maskSensitiveUrl(physicalLocator)
                : physicalLocator;
    }

    private Map<String, Object> readerOptionOverrides(CollectionTaskSourceBinding binding,
                                                       DataModelDefinition model,
                                                       String datasourceTypeCode,
                                                       String pluginType) {
        if (!isHttpReader(datasourceTypeCode, pluginType)) {
            return binding.getReaderOptions();
        }
        return httpReaderOptionSecurityService.resolveReaderOptionOverrides(
                binding.getReaderOptions(), model.getTechnicalMetadata());
    }

    @SuppressWarnings("unchecked")
    private void maskPreviewReader(Map<String, Object> assembledConfig) {
        Object readerValue = assembledConfig.get("reader");
        if (!(readerValue instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> reader = (Map<String, Object>) readerValue;
        String readerType = String.valueOf(reader.get("type"));
        if ("fusion".equalsIgnoreCase(readerType)) {
            Object configValue = reader.get("config");
            if (!(configValue instanceof Map<?, ?>)) {
                return;
            }
            Object sourcesValue = ((Map<?, ?>) configValue).get("sources");
            if (!(sourcesValue instanceof List<?>)) {
                return;
            }
            for (Object sourceValue : (List<?>) sourcesValue) {
                if (sourceValue instanceof Map<?, ?>) {
                    Map<String, Object> source = (Map<String, Object>) sourceValue;
                    if (isHttpReader(null, String.valueOf(source.get("type")))) {
                        maskHttpReaderConfig(source);
                    }
                }
            }
            return;
        }
        if (!isHttpReader(null, readerType)) {
            return;
        }
        Object configValue = reader.get("config");
        if (configValue instanceof Map<?, ?>) {
            maskHttpReaderConfig((Map<String, Object>) configValue);
        }
    }

    private void maskHttpReaderConfig(Map<String, Object> readerConfig) {
        if (readerConfig.containsKey("url")) {
            readerConfig.put("url", httpReaderOptionSecurityService.maskSensitiveUrl(
                    String.valueOf(readerConfig.get("url"))));
        }
        Map<String, Object> sensitiveOptions = new LinkedHashMap<String, Object>();
        for (String key : new String[]{"header", "params", "requestBody"}) {
            if (readerConfig.containsKey(key)) {
                sensitiveOptions.put(key, readerConfig.get(key));
            }
        }
        if (sensitiveOptions.isEmpty()) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("readerOptions", sensitiveOptions);
        Map<String, Object> maskedMetadata = httpReaderOptionSecurityService.maskTechnicalMetadata(metadata);
        Map<String, Object> maskedOptions = RUNTIME_OPTION_MERGER.toMap(maskedMetadata.get("readerOptions"));
        for (Map.Entry<String, Object> entry : maskedOptions.entrySet()) {
            readerConfig.put(entry.getKey(), entry.getValue());
        }
    }

    private void applyDefaultWriteMode(Map<String, Object> writerConfig, String pluginType) {
        if (!isRdbmsWriter(pluginType) || !isBlankValue(writerConfig.get("writeMode"))) {
            return;
        }
        writerConfig.put("writeMode", "insert");
    }

    private boolean isRdbmsWriter(String pluginType) {
        return "mysql8".equalsIgnoreCase(pluginType)
                || "dm".equalsIgnoreCase(pluginType)
                || "postgresql".equalsIgnoreCase(pluginType);
    }

    private void applyMysqlWriterBatchOptions(Map<String, Object> connect, String pluginType) {
        if (!"mysql8".equalsIgnoreCase(pluginType) && !"mysql5".equalsIgnoreCase(pluginType)) {
            return;
        }
        Map<String, String> other = parseConnectionOther(connect.get("other"));
        if (containsKeyIgnoreCase(other, MYSQL_BATCH_REWRITE_KEY)) {
            connect.put("other", toConnectionOther(other));
            return;
        }
        other.put(MYSQL_BATCH_REWRITE_KEY, "true");
        connect.put("other", toConnectionOther(other));
    }

    private Map<String, String> parseConnectionOther(Object value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            return result;
        }
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            return result;
        }
        String text = ((String) value).trim();
        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                result.putAll(OBJECT_MAPPER.readValue(text, STRING_MAP_TYPE));
            } catch (Exception ignored) {
                return result;
            }
        }
        return result;
    }

    private String toConnectionOther(Map<String, String> other) {
        try {
            return OBJECT_MAPPER.writeValueAsString(other);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private boolean containsKeyIgnoreCase(Map<String, String> map, String key) {
        for (String item : map.keySet()) {
            if (item != null && item.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private void normalizeReaderRuntimeConfig(Map<String, Object> config, String datasourceTypeCode, String pluginType) {
        if (isHttpReader(datasourceTypeCode, pluginType)) {
            httpConfigSupport.normalizeReaderRuntimeConfig(config);
        }
    }

    private void normalizeWriterRuntimeConfig(Map<String, Object> config, String datasourceTypeCode, String pluginType) {
        if (isHttpWriter(datasourceTypeCode, pluginType)) {
            httpConfigSupport.normalizeWriterRuntimeConfig(config);
        }
    }

    private Set<String> runtimeStringKeys(String datasourceTypeCode, String pluginType) {
        if (!isHttpReader(datasourceTypeCode, pluginType) && !isHttpWriter(datasourceTypeCode, pluginType)) {
            return Collections.emptySet();
        }
        return httpConfigSupport.runtimeStringKeys();
    }

    private List<String> reservedKeys(String role) {
        return pluginRuntimeOptionSchemaService.reservedKeys(role);
    }

    private String resolvePluginType(String datasourceTypeCode, String role) {
        return pluginRuntimeOptionSchemaService.resolvePluginType(datasourceTypeCode, role);
    }

    private boolean isFileReader(String datasourceTypeCode, String pluginType) {
        String sourceCategory = pluginRuntimeOptionSchemaService.sourceCategory(datasourceTypeCode);
        return CATEGORY_FILE_SYSTEM.equalsIgnoreCase(sourceCategory)
                || "ftp".equalsIgnoreCase(pluginType)
                || "sftp".equalsIgnoreCase(pluginType)
                || "minio".equalsIgnoreCase(pluginType);
    }

    private boolean isHttpReader(String datasourceTypeCode, String pluginType) {
        return "http".equalsIgnoreCase(pluginType)
                || "httpreader".equalsIgnoreCase(pluginType)
                || "http".equalsIgnoreCase(datasourceTypeCode);
    }

    private boolean isHttpWriter(String datasourceTypeCode, String pluginType) {
        return "http".equalsIgnoreCase(pluginType) || "http".equalsIgnoreCase(datasourceTypeCode);
    }

    private boolean isFileWriter(String datasourceTypeCode, String pluginType) {
        return isFileReader(datasourceTypeCode, pluginType);
    }

    private String resolveQueueTopic(DataModelDefinition model, Map<String, Object> config) {
        Object topic = config.get("topic");
        if (topic != null && !String.valueOf(topic).trim().isEmpty()) {
            return String.valueOf(topic);
        }
        return model.getPhysicalLocator();
    }

    private Map<String, Object> buildConnectionConfig(DataSourceDefinition datasource) {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        if (datasource == null || datasource.getTechnicalMetadata() == null) {
            return config;
        }
        for (Map.Entry<String, Object> entry : datasource.getTechnicalMetadata().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && isEncrypted((String) value) && isSensitive(entry.getKey())) {
                config.put(entry.getKey(), decrypt((String) value));
            } else {
                config.put(entry.getKey(), value);
            }
        }
        return config;
    }

    private boolean isEncrypted(String value) {
        return value != null && value.startsWith("ENC(") && value.endsWith(")");
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("accesskey");
    }

    private String decrypt(String value) {
        return encryptionService.decrypt(value.substring(4, value.length() - 1));
    }

    private boolean isBlankValue(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private DataSourceDefinition requiredDatasource(Long datasourceId) {
        DataSourceDefinition datasource = dataSourceService.getInternal(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        return datasource;
    }

    private DataModelDefinition requiredModel(Long modelId) {
        return dataModelService.get(modelId);
    }
}
