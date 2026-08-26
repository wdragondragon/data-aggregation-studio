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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CollectionTaskAssemblerService {

    private static final CollectionTaskRuntimeOptionMerger RUNTIME_OPTION_MERGER = new CollectionTaskRuntimeOptionMerger();
    private static final String CATEGORY_FILE_SYSTEM = "FILE_SYSTEM";
    private static final String MYSQL_BATCH_REWRITE_KEY = "rewriteBatchedStatements";
    private static final List<String> CONSISTENCY_OUTPUT_COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "rule_id", "record_id", "match_keys", "conflict_type", "differences", "payload"));
    private static final Set<String> CONSISTENCY_RUNTIME_OPTION_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "description", "enabled", "parallelFetch", "toleranceThreshold",
                    "conflictResolutionStrategy", "resolutionParams", "outputConfig", "cache",
                    "performance", "adaptiveMerge", "maxRecords")));
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
        return assemble(definition, false);
    }

    private Map<String, Object> assemble(CollectionTaskDefinitionView definition,
                                         boolean maskedDatasourceView) {
        if (definition == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Collection task definition is required");
        }
        if (definition.getTaskType() == CollectionTaskType.FUSION) {
            return assembleFusion(definition, maskedDatasourceView);
        }
        return assembleSingle(definition, maskedDatasourceView);
    }

    public Map<String, Object> assemblePreview(CollectionTaskDefinitionView definition) {
        Map<String, Object> config = assemble(definition, true);
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

    /**
     * Hydrates a resource-bound Studio consistency node into the legacy
     * DataAggregation reader/writer shape. Raw reader/writer configurations
     * remain untouched for standalone and existing workflow compatibility.
     */
    public Map<String, Object> assembleConsistency(Map<String, Object> definition) {
        Map<String, Object> stored = definition == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(definition);
        boolean hasReader = stored.get("reader") instanceof Map<?, ?>;
        boolean hasWriter = stored.get("writer") instanceof Map<?, ?>;
        if (hasReader || hasWriter) {
            if (!hasReader || !hasWriter) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Consistency raw configuration requires both reader and writer");
            }
            return stored;
        }

        Map<String, Object> left = requiredConsistencyBinding(stored, "leftBinding");
        Map<String, Object> right = requiredConsistencyBinding(stored, "rightBinding");
        Map<String, Object> output = requiredConsistencyBinding(stored, "outputBinding");
        List<String> matchKeys = requiredStringList(stored.get("matchKeys"), "Consistency matchKeys are required");
        List<String> compareFields = requiredStringList(stored.get("compareFields"),
                "Consistency compareFields are required");
        String ruleId = requiredText(stored.get("ruleId"), "Consistency ruleId is required");

        Map<String, Object> readerConfig = new LinkedHashMap<String, Object>();
        readerConfig.put("ruleId", ruleId);
        String ruleName = text(stored.get("ruleName"));
        if (ruleName != null) {
            readerConfig.put("ruleName", ruleName);
        }
        for (String key : CONSISTENCY_RUNTIME_OPTION_KEYS) {
            if (stored.containsKey(key)) {
                readerConfig.put(key, stored.get(key));
            }
        }
        readerConfig.put("autoApplyResolutions", Boolean.FALSE);
        readerConfig.put("allowDelete", Boolean.FALSE);
        readerConfig.put("matchKeys", matchKeys);
        readerConfig.put("compareFields", compareFields);
        Map<String, Object> leftSource = buildConsistencySource(
                left, "left", 10, 1.0d, matchKeys, compareFields);
        Map<String, Object> rightSource = buildConsistencySource(
                right, "right", 5, 0.8d, matchKeys, compareFields);
        if (String.valueOf(leftSource.get("sourceId")).equals(String.valueOf(rightSource.get("sourceId")))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Consistency source aliases must be different");
        }
        readerConfig.put("dataSources", Arrays.asList(leftSource, rightSource));

        Map<String, Object> reader = new LinkedHashMap<String, Object>();
        reader.put("type", "consistency");
        reader.put("config", readerConfig);

        Long outputDatasourceId = requiredLong(output.get("datasourceId"),
                "Consistency output datasourceId is required");
        Long outputModelId = requiredLong(output.get("modelId"),
                "Consistency output modelId is required");
        DataSourceDefinition outputDatasource = requiredRunnableDatasource(outputDatasourceId);
        DataModelDefinition outputModel = requiredModel(outputModelId);
        assertModelDatasource(outputModel, outputDatasourceId, "outputBinding");
        assertModelFields(outputModel, CONSISTENCY_OUTPUT_COLUMNS, "outputBinding columns");
        CollectionTaskTargetBinding outputTarget = new CollectionTaskTargetBinding();
        outputTarget.setDatasourceId(outputDatasourceId);
        outputTarget.setModelId(outputModelId);
        outputTarget.setWriterOptions(objectMap(output.get("writerOptions")));

        Map<String, Object> runtime = new LinkedHashMap<String, Object>();
        runtime.put("reader", reader);
        runtime.put("writer", buildStandardWriter(
                outputTarget, outputDatasource, outputModel, CONSISTENCY_OUTPUT_COLUMNS));
        return runtime;
    }

    private Map<String, Object> buildConsistencySource(Map<String, Object> binding,
                                                        String defaultAlias,
                                                        int defaultPriority,
                                                        double defaultWeight,
                                                        List<String> matchKeys,
                                                        List<String> compareFields) {
        Long datasourceId = requiredLong(binding.get("datasourceId"),
                "Consistency " + defaultAlias + " datasourceId is required");
        Long modelId = requiredLong(binding.get("modelId"),
                "Consistency " + defaultAlias + " modelId is required");
        DataSourceDefinition datasource = requiredRunnableDatasource(datasourceId);
        DataModelDefinition model = requiredModel(modelId);
        assertModelDatasource(model, datasourceId, defaultAlias + "Binding");
        assertModelFields(model, matchKeys, defaultAlias + "Binding matchKeys");
        assertModelFields(model, compareFields, defaultAlias + "Binding compareFields");

        Map<String, Object> source = new LinkedHashMap<String, Object>();
        String alias = text(binding.get("sourceAlias"));
        source.put("sourceId", alias == null ? defaultAlias : alias);
        source.put("sourceName", model.getName() == null ? model.getPhysicalLocator() : model.getName());
        source.put("datasourceType", datasource.getTypeCode());
        source.put("pluginName", pluginRuntimeOptionSchemaService.resolveSourcePlugin(datasource.getTypeCode()));
        source.put("connectionConfig", buildConnectionConfig(datasource));
        source.put("tableName", requiredText(model.getPhysicalLocator(),
                "Consistency source model physical locator is required"));
        source.put("priority", integerValue(binding.get("priority"), defaultPriority));
        source.put("confidenceWeight", doubleValue(binding.get("confidenceWeight"), defaultWeight));
        Map<String, Object> fieldMappings = objectMap(binding.get("fieldMappings"));
        if (!fieldMappings.isEmpty()) {
            source.put("fieldMappings", fieldMappings);
        }
        if (binding.get("maxRecords") != null) {
            source.put("maxRecords", integerValue(binding.get("maxRecords"), 0));
        }
        return source;
    }

    private void assertModelDatasource(DataModelDefinition model, Long datasourceId, String bindingName) {
        if (model.getDatasourceId() != null && !datasourceId.equals(model.getDatasourceId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Consistency " + bindingName + " model does not belong to datasource");
        }
    }

    private void assertModelFields(DataModelDefinition model, List<String> requiredFields, String label) {
        Set<String> available = modelFields(model);
        if (available.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<String>();
        for (String field : requiredFields) {
            if (!available.contains(field)) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    label + " were not found in model: " + String.join(", ", missing));
        }
    }

    private Set<String> modelFields(DataModelDefinition model) {
        Set<String> fields = new LinkedHashSet<String>();
        Object columns = model == null || model.getTechnicalMetadata() == null
                ? null : model.getTechnicalMetadata().get("columns");
        if (!(columns instanceof List<?>)) {
            return fields;
        }
        for (Object column : (List<?>) columns) {
            if (column instanceof Map<?, ?>) {
                Map<?, ?> item = (Map<?, ?>) column;
                String name = firstText(item.get("name"), item.get("columnName"), item.get("fieldName"));
                if (name != null) {
                    fields.add(name);
                }
            } else if (column != null && !String.valueOf(column).trim().isEmpty()) {
                fields.add(String.valueOf(column).trim());
            }
        }
        return fields;
    }

    private Map<String, Object> requiredConsistencyBinding(Map<String, Object> definition, String key) {
        Map<String, Object> binding = objectMap(definition.get(key));
        if (binding.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Consistency " + key + " is required");
        }
        return binding;
    }

    private List<String> requiredStringList(Object value, String message) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                String text = text(item);
                if (text != null && !result.contains(text)) {
                    result.add(text);
                }
            }
        } else if (value instanceof String) {
            for (String item : ((String) value).split(",")) {
                String text = text(item);
                if (text != null && !result.contains(text)) {
                    result.add(text);
                }
            }
        }
        if (result.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
        return result;
    }

    private Long requiredLong(Object value, String message) {
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        String text = text(value);
        if (text != null) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                // handled below
            }
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
    }

    private String requiredText(Object value, String message) {
        String text = text(value);
        if (text == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
        return text;
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String candidate = text(value);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private int integerValue(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = text(value);
        if (text == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String text = text(value);
        if (text == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private Map<String, Object> objectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map<?, ?>)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private DataSourceDefinition requiredRunnableDatasource(Long datasourceId) {
        return dataSourceService.requireRunnableForExecution(datasourceId);
    }

    private Map<String, Object> assembleSingle(CollectionTaskDefinitionView definition,
                                               boolean maskedDatasourceView) {
        CollectionTaskSourceBinding sourceBinding = definition.getSourceBindings().get(0);
        CollectionTaskTargetBinding targetBinding = definition.getTargetBinding();
        DataSourceDefinition sourceDatasource = requiredDatasource(
                sourceBinding.getDatasourceId(), maskedDatasourceView);
        DataModelDefinition sourceModel = requiredModel(sourceBinding.getModelId());
        DataSourceDefinition targetDatasource = requiredDatasource(
                targetBinding.getDatasourceId(), maskedDatasourceView);
        DataModelDefinition targetModel = requiredModel(targetBinding.getModelId());

        List<String> targetFields = fieldMappingResolver.resolveTargetFields(definition.getFieldMappings(), targetModel);
        List<String> sourceFields = fieldMappingResolver.resolveSingleSourceFields(definition.getFieldMappings(), sourceModel);

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("reader", buildStandardReader(sourceBinding, sourceDatasource, sourceModel, sourceFields, definition.getExecutionOptions()));
        List<Map<String, Object>> transformers = fieldMappingResolver.buildTransformers(definition.getFieldMappings(), targetFields);
        if (!transformers.isEmpty()) {
            config.put("transformer", transformers);
        }
        Map<String, Object> writer = buildStandardWriter(targetBinding, targetDatasource, targetModel, targetFields);
        Map<String, DataModelDefinition> sourceModels = new LinkedHashMap<String, DataModelDefinition>();
        sourceModels.put(sourceBinding.getSourceAlias(), sourceModel);
        enrichEFileWriterColumnRemarks(writer, definition.getFieldMappings(), sourceModels);
        config.put("writer", writer);
        return config;
    }

    private Map<String, Object> assembleFusion(CollectionTaskDefinitionView definition,
                                               boolean maskedDatasourceView) {
        CollectionTaskTargetBinding targetBinding = definition.getTargetBinding();
        DataSourceDefinition targetDatasource = requiredDatasource(
                targetBinding.getDatasourceId(), maskedDatasourceView);
        DataModelDefinition targetModel = requiredModel(targetBinding.getModelId());
        Map<String, Object> executionOptions = definition.getExecutionOptions() == null
                ? new LinkedHashMap<String, Object>()
                : definition.getExecutionOptions();

        List<String> targetFields = fieldMappingResolver.resolveTargetFields(definition.getFieldMappings(), targetModel);
        List<String> joinKeys = fieldMappingResolver.resolveJoinKeys(definition);
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        Map<String, DataModelDefinition> sourceModels = new LinkedHashMap<String, DataModelDefinition>();
        for (CollectionTaskSourceBinding sourceBinding : definition.getSourceBindings()) {
            DataSourceDefinition sourceDatasource = requiredDatasource(
                    sourceBinding.getDatasourceId(), maskedDatasourceView);
            DataModelDefinition sourceModel = requiredModel(sourceBinding.getModelId());
            sourceModels.put(sourceBinding.getSourceAlias(), sourceModel);
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
        Map<String, Object> writer = buildStandardWriter(targetBinding, targetDatasource, targetModel, targetFields);
        enrichEFileWriterColumnRemarks(writer, definition.getFieldMappings(), sourceModels);
        config.put("writer", writer);
        return config;
    }

    @SuppressWarnings("unchecked")
    private void enrichEFileWriterColumnRemarks(Map<String, Object> writer,
                                                List<FieldMappingDefinition> mappings,
                                                Map<String, DataModelDefinition> sourceModels) {
        Object rawConfig = writer == null ? null : writer.get("config");
        if (!(rawConfig instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> config = (Map<String, Object>) rawConfig;
        if (!"efile".equalsIgnoreCase(String.valueOf(config.get("fileType")))) {
            return;
        }
        Object rawColumns = config.get("columns");
        if (!(rawColumns instanceof List<?>)) {
            return;
        }
        Map<String, FieldMappingDefinition> mappingsByTarget = new LinkedHashMap<String, FieldMappingDefinition>();
        if (mappings != null) {
            for (FieldMappingDefinition mapping : mappings) {
                if (mapping == null || isBlank(mapping.getTargetField()) || !isBlank(mapping.getExpression())) {
                    continue;
                }
                mappingsByTarget.put(mapping.getTargetField().trim(), mapping);
            }
        }
        for (Object rawColumn : (List<?>) rawColumns) {
            if (!(rawColumn instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> column = (Map<String, Object>) rawColumn;
            if (!isBlankValue(column.get("remarks"))) {
                continue;
            }
            String targetField = text(column.get("name"));
            FieldMappingDefinition mapping = targetField == null ? null : mappingsByTarget.get(targetField);
            if (mapping == null || isBlank(mapping.getSourceField())) {
                continue;
            }
            DataModelDefinition sourceModel = sourceModels.get(mapping.getSourceAlias());
            if (sourceModel == null && sourceModels.size() == 1) {
                sourceModel = sourceModels.values().iterator().next();
            }
            String remarks = fieldMappingResolver.resolveFieldRemarks(sourceModel, mapping.getSourceField());
            if (!isBlank(remarks)) {
                column.put("remarks", remarks);
            }
        }
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
            readerConfig.putAll(buildKafkaJobConfig(datasource));
            readerConfig.put("topic", resolveQueueTopic(model, readerConfig));
            ensureKafkaGroupId(readerConfig, datasource, model, binding);
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
            writerConfig.putAll(buildKafkaJobConfig(datasource));
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
        if (model != null && model.getPhysicalLocator() != null
                && !String.valueOf(model.getPhysicalLocator()).trim().isEmpty()) {
            return String.valueOf(model.getPhysicalLocator()).trim();
        }
        Object topic = config.get("topic");
        if (topic != null && !String.valueOf(topic).trim().isEmpty()) {
            return String.valueOf(topic);
        }
        return model == null ? null : model.getPhysicalLocator();
    }

    /**
     * Kafka job plugins use camel-case option names while datasource metadata
     * follows Kafka client property names (for example bootstrap.servers and
     * group.id). Normalize the aliases before the reader/writer configuration
     * reaches the plugin; otherwise Properties.put receives null values and
     * the job fails during plugin initialization.
     */
    private Map<String, Object> buildKafkaJobConfig(DataSourceDefinition datasource) {
        return normalizeKafkaJobConfig(buildConnectionConfig(datasource));
    }

    static Map<String, Object> normalizeKafkaJobConfig(Map<String, Object> raw) {
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        if (raw == null) {
            return normalized;
        }
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (!"bootstrap.servers".equals(key)
                    && !"group.id".equals(key)
                    && !"groupId".equals(key)) {
                normalized.put(key, entry.getValue());
            }
        }
        if (!normalized.containsKey("bootstrapServers")) {
            Object bootstrapServers = raw.get("bootstrap.servers");
            if (bootstrapServers != null) {
                normalized.put("bootstrapServers", bootstrapServers);
            }
        }
        return normalized;
    }

    private void ensureKafkaGroupId(Map<String, Object> config,
                                    DataSourceDefinition datasource,
                                    DataModelDefinition model,
                                    CollectionTaskSourceBinding binding) {
        Object configured = config.get("groupId");
        if (configured != null && !String.valueOf(configured).trim().isEmpty()) {
            return;
        }
        String datasourcePart = datasource == null || datasource.getId() == null
                ? "unknown-datasource" : String.valueOf(datasource.getId());
        String modelPart = model == null || model.getId() == null
                ? (model == null ? "unknown-model" : String.valueOf(model.getPhysicalLocator()))
                : String.valueOf(model.getId());
        String aliasPart = binding == null || binding.getSourceAlias() == null
                ? "source" : binding.getSourceAlias();
        String raw = "studio-" + datasourcePart + "-" + modelPart + "-" + aliasPart;
        config.put("groupId", raw.replaceAll("[^A-Za-z0-9._-]", "_"));
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
        return requiredDatasource(datasourceId, false);
    }

    private DataSourceDefinition requiredDatasource(Long datasourceId,
                                                    boolean maskedDatasourceView) {
        DataSourceDefinition datasource = maskedDatasourceView
                ? dataSourceService.get(datasourceId)
                : dataSourceService.getInternal(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        return datasource;
    }

    private DataModelDefinition requiredModel(Long modelId) {
        return dataModelService.get(modelId);
    }
}
