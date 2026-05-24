package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.jdragon.studio.dto.model.TransformerBinding;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class CollectionTaskAssemblerService {

    private static final ObjectMapper RUNTIME_OPTION_OBJECT_MAPPER = new ObjectMapper();
    private static final CollectionTaskRuntimeOptionMerger RUNTIME_OPTION_MERGER = new CollectionTaskRuntimeOptionMerger();
    private static final String CATEGORY_FILE_SYSTEM = "FILE_SYSTEM";
    private static final String FILE_FIELD_SOURCE_KIND_TAG = "TAG";

    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final EncryptionService encryptionService;
    private final PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService;

    public CollectionTaskAssemblerService(DataSourceService dataSourceService,
                                          DataModelService dataModelService,
                                          EncryptionService encryptionService,
                                          PluginRuntimeOptionSchemaService pluginRuntimeOptionSchemaService) {
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.encryptionService = encryptionService;
        this.pluginRuntimeOptionSchemaService = pluginRuntimeOptionSchemaService;
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

    private Map<String, Object> assembleSingle(CollectionTaskDefinitionView definition) {
        CollectionTaskSourceBinding sourceBinding = definition.getSourceBindings().get(0);
        CollectionTaskTargetBinding targetBinding = definition.getTargetBinding();
        DataSourceDefinition sourceDatasource = requiredDatasource(sourceBinding.getDatasourceId());
        DataModelDefinition sourceModel = requiredModel(sourceBinding.getModelId());
        DataSourceDefinition targetDatasource = requiredDatasource(targetBinding.getDatasourceId());
        DataModelDefinition targetModel = requiredModel(targetBinding.getModelId());

        List<String> targetFields = resolveTargetFields(definition.getFieldMappings(), targetModel);
        List<String> sourceFields = resolveSingleSourceFields(definition.getFieldMappings(), sourceModel);

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("reader", buildStandardReader(sourceBinding, sourceDatasource, sourceModel, sourceFields, definition.getExecutionOptions()));
        List<Map<String, Object>> transformers = buildTransformers(definition.getFieldMappings(), targetFields);
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

        List<String> targetFields = resolveTargetFields(definition.getFieldMappings(), targetModel);
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        for (CollectionTaskSourceBinding sourceBinding : definition.getSourceBindings()) {
            DataSourceDefinition sourceDatasource = requiredDatasource(sourceBinding.getDatasourceId());
            DataModelDefinition sourceModel = requiredModel(sourceBinding.getModelId());
            List<String> sourceFields = resolveSourceFieldsByAlias(definition.getFieldMappings(), sourceBinding.getSourceAlias(), sourceModel);
            String pluginType = resolvePluginType(sourceDatasource.getTypeCode(), "reader");
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", sourceBinding.getSourceAlias());
            item.put("type", pluginType);
            item.putAll(buildReaderConfig(sourceBinding, sourceDatasource, sourceModel, sourceFields, pluginType));
            mergeRuntimeOptions(item, sourceBinding.getReaderOptions(), "reader", runtimeStringKeys(sourceDatasource.getTypeCode(), pluginType));
            normalizeReaderRuntimeConfig(item, sourceDatasource.getTypeCode(), pluginType);
            if (!isFileReader(sourceDatasource.getTypeCode(), pluginType) && !isHttpReader(sourceDatasource.getTypeCode(), pluginType)) {
                applyIncrementalOptions(item, sourceBinding, executionOptions);
            }
            sources.add(item);
        }

        Map<String, Object> join = new LinkedHashMap<String, Object>();
        join.put("keys", resolveJoinKeys(definition));
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
        List<Map<String, Object>> transformers = buildTransformers(definition.getFieldMappings(), targetFields);
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
        mergeRuntimeOptions(readerConfig, binding.getReaderOptions(), "reader", runtimeStringKeys(datasource.getTypeCode(), pluginType));
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
            readerConfig.put("columns", resolveColumnEntries(model, sourceFields, false));
            return readerConfig;
        }
        if ("rocketmq".equalsIgnoreCase(pluginType)) {
            Map<String, Object> connect = buildConnectionConfig(datasource);
            if (!connect.containsKey("topic")) {
                connect.put("topic", model.getPhysicalLocator());
            }
            readerConfig.put("connect", connect);
            readerConfig.put("columns", resolveColumnEntries(model, sourceFields, false));
            return readerConfig;
        }
        if ("influxdbv1".equalsIgnoreCase(pluginType)) {
            readerConfig.put("connect", buildConnectionConfig(datasource));
            readerConfig.put("measurement", model.getPhysicalLocator());
            readerConfig.put("columns", sourceFields);
            return readerConfig;
        }
        if (isHttpReader(datasource.getTypeCode(), pluginType)) {
            readerConfig.putAll(buildHttpReaderConfig(datasource, model, sourceFields));
            return readerConfig;
        }
        if (isFileReader(datasource.getTypeCode(), pluginType)) {
            readerConfig.putAll(buildFileReaderConfig(datasource, model, sourceFields));
            return readerConfig;
        }
        readerConfig.put("connect", buildConnectionConfig(datasource));
        readerConfig.put("table", model.getPhysicalLocator());
        readerConfig.put("columns", sourceFields);
        return readerConfig;
    }

    private Map<String, Object> buildFileReaderConfig(DataSourceDefinition datasource,
                                                      DataModelDefinition model,
                                                      List<String> sourceFields) {
        Map<String, Object> readerConfig = new LinkedHashMap<String, Object>();
        readerConfig.put("connect", buildConnectionConfig(datasource));
        Map<String, Object> metadata = model == null || model.getTechnicalMetadata() == null
                ? Collections.<String, Object>emptyMap()
                : model.getTechnicalMetadata();
        Object rootPath = firstPresent(metadata, "rootPath");
        if (isBlankValue(rootPath) && model != null) {
            rootPath = model.getPhysicalLocator();
        }
        putIfPresent(readerConfig, "rootPath", rootPath, "/");
        putIfPresent(readerConfig, "partitionType", metadata.get("partitionType"), "glob");
        putIfPresent(readerConfig, "partition", firstPresent(metadata, "partition", "pattern"), "*");
        String fileType = resolveFileType(metadata.get("fileType"));
        putIfPresent(readerConfig, "fileType", fileType, "csv");
        putIfPresent(readerConfig, "encoding", metadata.get("encoding"), "UTF-8");
        putIfPresent(readerConfig, "delimiter", metadata.get("delimiter"), null);
        List<String> dataTags = resolveFileDataTags(model);
        if (!dataTags.isEmpty()) {
            if (!isEFileType(fileType)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "File model sourceKind=TAG is only supported for efile fileType");
            }
            readerConfig.put("dataTag", dataTags);
        }
        readerConfig.put("columns", resolveFileColumnEntries(model, sourceFields, dataTags));
        return readerConfig;
    }

    private Map<String, Object> buildHttpReaderConfig(DataSourceDefinition datasource,
                                                      DataModelDefinition model,
                                                      List<String> sourceFields) {
        Map<String, Object> readerConfig = new LinkedHashMap<String, Object>();
        Map<String, Object> metadata = model == null || model.getTechnicalMetadata() == null
                ? Collections.<String, Object>emptyMap()
                : model.getTechnicalMetadata();
        readerConfig.put("url", resolveHttpUrl(datasource, model, metadata));
        readerConfig.put("mode", resolveHttpMode(metadata));
        readerConfig.put("contentType", "application/json;charset=utf-8");
        readerConfig.put("header", "{}");
        readerConfig.put("params", "{}");
        readerConfig.put("requestBody", "");
        readerConfig.put("resultType", resolveHttpResultType(metadata));
        putIfPresent(readerConfig, "totalCodePath", metadata.get("totalCodePath"), null);
        putIfPresent(readerConfig, "responseStatus", resolveHttpResponseStatus(metadata), null);
        readerConfig.put("pageRead", Boolean.FALSE);
        readerConfig.put("pageSize", Integer.valueOf(500));
        readerConfig.put("columns", resolveHttpColumnEntries(model, sourceFields));
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
            writerConfig.put("columns", resolveColumnEntries(model, targetFields, false));
            return writerConfig;
        }
        if ("rocketmq".equalsIgnoreCase(pluginType)) {
            Map<String, Object> connect = buildConnectionConfig(datasource);
            if (!connect.containsKey("topic")) {
                connect.put("topic", model.getPhysicalLocator());
            }
            writerConfig.put("connect", connect);
            writerConfig.put("columns", resolveColumnEntries(model, targetFields, false));
            return writerConfig;
        }
        if ("influxdbv1".equalsIgnoreCase(pluginType)) {
            writerConfig.put("connect", buildConnectionConfig(datasource));
            writerConfig.put("measurement", model.getPhysicalLocator());
            writerConfig.put("columns", resolveColumnEntries(model, targetFields, true));
            return writerConfig;
        }
        if (isFileWriter(datasource.getTypeCode(), pluginType)) {
            writerConfig.putAll(buildFileWriterConfig(datasource, model, targetFields));
            return writerConfig;
        }
        if (isHttpWriter(datasource.getTypeCode(), pluginType)) {
            writerConfig.putAll(buildHttpWriterConfig(datasource, model, targetFields));
            return writerConfig;
        }
        writerConfig.put("connect", buildConnectionConfig(datasource));
        writerConfig.put("table", model.getPhysicalLocator());
        writerConfig.put("columns", targetFields);
        return writerConfig;
    }

    private Map<String, Object> buildFileWriterConfig(DataSourceDefinition datasource,
                                                      DataModelDefinition model,
                                                      List<String> targetFields) {
        Map<String, Object> writerConfig = new LinkedHashMap<String, Object>();
        writerConfig.put("connect", buildConnectionConfig(datasource));
        Map<String, Object> metadata = model == null || model.getTechnicalMetadata() == null
                ? Collections.<String, Object>emptyMap()
                : model.getTechnicalMetadata();
        Object rootPath = firstPresent(metadata, "rootPath");
        putIfPresent(writerConfig, "rootPath", rootPath, "/");
        Object fileName = firstPresent(metadata, "fileName");
        if (isBlankValue(fileName) && model != null) {
            fileName = model.getPhysicalLocator();
        }
        putIfPresent(writerConfig, "fileName", fileName, null);
        String fileType = resolveFileType(metadata.get("fileType"));
        putIfPresent(writerConfig, "fileType", fileType, "csv");
        putIfPresent(writerConfig, "encoding", metadata.get("encoding"), "UTF-8");
        putIfPresent(writerConfig, "delimiter", metadata.get("delimiter"), null);
        Map<String, Object> efileOptions = resolveEFileOptions(metadata);
        if (!efileOptions.isEmpty()) {
            writerConfig.put("efile", efileOptions);
        }
        List<Map<String, Object>> columns = resolveFileWriterColumnEntries(model, targetFields);
        if (hasTagFileField(columns) && !isEFileType(fileType)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "File model sourceKind=TAG is only supported for efile fileType");
        }
        writerConfig.put("columns", columns);
        return writerConfig;
    }

    private Map<String, Object> buildHttpWriterConfig(DataSourceDefinition datasource,
                                                       DataModelDefinition model,
                                                       List<String> targetFields) {
        Map<String, Object> writerConfig = new LinkedHashMap<String, Object>();
        Map<String, Object> metadata = model == null || model.getTechnicalMetadata() == null
                ? Collections.<String, Object>emptyMap()
                : model.getTechnicalMetadata();
        writerConfig.put("url", resolveHttpUrl(datasource, model, metadata));
        writerConfig.put("mode", resolveHttpWriterMode(metadata));
        writerConfig.put("contentType", "application/json;charset=utf-8");
        writerConfig.put("header", "{}");
        writerConfig.put("params", "{}");
        writerConfig.put("requestBody", "");
        writerConfig.put("payloadMode", "object");
        writerConfig.put("includeTotal", Boolean.FALSE);
        writerConfig.put("batchSize", Integer.valueOf(500));
        writerConfig.put("retryTimes", Integer.valueOf(3));
        writerConfig.put("retryIntervalMs", Long.valueOf(1000L));
        writerConfig.put("connectTimeoutMs", Integer.valueOf(3000));
        writerConfig.put("socketTimeoutMs", Integer.valueOf(3000));
        writerConfig.put("columns", resolveHttpWriterColumnEntries(model, targetFields));
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

    private void normalizeReaderRuntimeConfig(Map<String, Object> config, String datasourceTypeCode, String pluginType) {
        if (isHttpReader(datasourceTypeCode, pluginType)) {
            normalizeHttpReaderRuntimeConfig(config);
        }
    }

    private void normalizeWriterRuntimeConfig(Map<String, Object> config, String datasourceTypeCode, String pluginType) {
        if (isHttpWriter(datasourceTypeCode, pluginType)) {
            normalizeHttpWriterRuntimeConfig(config);
        }
    }

    private void normalizeHttpReaderRuntimeConfig(Map<String, Object> config) {
        normalizeHttpStringOption(config, "contentType", "application/json;charset=utf-8");
        normalizeHttpJsonObjectString(config, "header", "HTTP reader");
        normalizeHttpJsonObjectString(config, "params", "HTTP reader");
        normalizeHttpStringOption(config, "requestBody", "");
    }

    private void normalizeHttpWriterRuntimeConfig(Map<String, Object> config) {
        normalizeHttpStringOption(config, "contentType", "application/json;charset=utf-8");
        normalizeHttpJsonObjectString(config, "header", "HTTP writer");
        normalizeHttpJsonObjectString(config, "params", "HTTP writer");
        normalizeHttpStringOption(config, "requestBody", "");
        if (isBlankValue(config.get("payloadMode"))) {
            config.put("payloadMode", "object");
        }
        if (isBlankValue(config.get("batchSize"))) {
            config.put("batchSize", Integer.valueOf(500));
        }
        boolean includeTotal = booleanValue(config.get("includeTotal"));
        config.put("includeTotal", Boolean.valueOf(includeTotal));
        if (includeTotal && isBlankValue(config.get("totalNodePath"))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP writer totalNodePath is required when includeTotal is true");
        }
    }

    private void normalizeHttpStringOption(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        if (isBlankValue(value)) {
            config.put(key, defaultValue);
            return;
        }
        if (value instanceof String) {
            config.put(key, value);
            return;
        }
        try {
            config.put(key, RUNTIME_OPTION_OBJECT_MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            config.put(key, String.valueOf(value));
        }
    }

    private void normalizeHttpJsonObjectString(Map<String, Object> config, String key, String label) {
        Object value = config.get(key);
        String text;
        if (isBlankValue(value)) {
            text = "{}";
        } else if (value instanceof String) {
            text = ((String) value).trim();
        } else {
            try {
                text = RUNTIME_OPTION_OBJECT_MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, label + " " + key + " must be a JSON object string");
            }
        }
        try {
            JsonNode node = RUNTIME_OPTION_OBJECT_MAPPER.readTree(text);
            if (node == null || !node.isObject()) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, label + " " + key + " must be a JSON object string");
            }
        } catch (StudioException e) {
            throw e;
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, label + " " + key + " must be a JSON object string");
        }
        config.put(key, text);
    }

    private Set<String> runtimeStringKeys(String datasourceTypeCode, String pluginType) {
        if (!isHttpReader(datasourceTypeCode, pluginType) && !isHttpWriter(datasourceTypeCode, pluginType)) {
            return Collections.emptySet();
        }
        Set<String> keys = new LinkedHashSet<String>();
        keys.add("header");
        keys.add("params");
        keys.add("requestbody");
        return keys;
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
        return "http".equalsIgnoreCase(pluginType) || "http".equalsIgnoreCase(datasourceTypeCode);
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

    private List<Map<String, Object>> resolveColumnEntries(DataModelDefinition model,
                                                           List<String> fields,
                                                           boolean influxTypes) {
        List<String> selectedFields = fields == null || fields.isEmpty() ? resolveModelFields(model) : fields;
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> metadata = resolveModelFieldMetadata(model);
        for (int i = 0; i < selectedFields.size(); i++) {
            String fieldName = selectedFields.get(i);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", fieldName);
            item.put("index", Integer.valueOf(i));
            item.put("type", influxTypes ? resolveInfluxColumnType(fieldName, metadata.get(fieldName)) : resolveGenericColumnType(metadata.get(fieldName)));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> resolveFileColumnEntries(DataModelDefinition model,
                                                               List<String> fields,
                                                               List<String> dataTags) {
        List<String> selectedFields = fields == null || fields.isEmpty() ? resolveModelFields(model) : fields;
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> metadata = resolveModelFieldMetadata(model);
        Map<String, Integer> fieldOrder = resolveModelFieldOrder(model);
        Map<String, Integer> tagOrder = resolveTagFieldOrder(dataTags);
        int dataColumnCount = resolveDataColumnCount(model);
        for (int i = 0; i < selectedFields.size(); i++) {
            String fieldName = selectedFields.get(i);
            Map<String, Object> fieldMetadata = metadata.get(fieldName);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", fieldName);
            item.put("index", resolveFileColumnIndex(fieldName, fieldMetadata, fieldOrder, tagOrder, dataColumnCount, Integer.valueOf(i)));
            item.put("type", resolveGenericColumnType(fieldMetadata));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> resolveFileWriterColumnEntries(DataModelDefinition model,
                                                                     List<String> fields) {
        List<String> selectedFields = fields == null || fields.isEmpty() ? resolveModelFields(model) : fields;
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> metadata = resolveModelFieldMetadata(model);
        for (int i = 0; i < selectedFields.size(); i++) {
            String fieldName = selectedFields.get(i);
            Map<String, Object> fieldMetadata = metadata.get(fieldName);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", fieldName);
            item.put("index", Integer.valueOf(i));
            item.put("type", resolveGenericColumnType(fieldMetadata));
            if (isTagFileField(fieldMetadata)) {
                item.put("sourceKind", FILE_FIELD_SOURCE_KIND_TAG);
            }
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> resolveHttpColumnEntries(DataModelDefinition model,
                                                                List<String> fields) {
        List<String> selectedFields = fields == null || fields.isEmpty() ? resolveModelFields(model) : fields;
        if (selectedFields.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP response fields are required");
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> metadata = resolveModelFieldMetadata(model);
        for (String fieldName : selectedFields) {
            Map<String, Object> fieldMetadata = metadata.get(fieldName);
            Object parentNode = fieldMetadata == null ? null : fieldMetadata.get("parentNode");
            if (isBlankValue(parentNode)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP response field parentNode is required for field " + fieldName);
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("parentNode", String.valueOf(parentNode).trim());
            item.put("name", fieldName);
            item.put("type", resolveHttpColumnType(fieldMetadata));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> resolveHttpWriterColumnEntries(DataModelDefinition model,
                                                                      List<String> fields) {
        List<String> selectedFields = fields == null || fields.isEmpty() ? resolveModelFields(model) : fields;
        if (selectedFields.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP request fields are required");
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> metadata = resolveModelFieldMetadata(model);
        for (int i = 0; i < selectedFields.size(); i++) {
            String fieldName = selectedFields.get(i);
            Map<String, Object> fieldMetadata = metadata.get(fieldName);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("index", Integer.valueOf(i));
            item.put("name", fieldName);
            item.put("type", resolveHttpColumnType(fieldMetadata));
            result.add(item);
        }
        return result;
    }

    private String resolveHttpColumnType(Map<String, Object> metadata) {
        Object type = metadata == null ? null : metadata.get("type");
        return isBlankValue(type) ? "STRING" : String.valueOf(type).trim();
    }

    private String resolveHttpUrl(DataSourceDefinition datasource,
                                  DataModelDefinition model,
                                  Map<String, Object> metadata) {
        Object requestPathValue = firstPresent(metadata, "physicalName", "requestPath");
        String requestPath = model == null ? null : model.getPhysicalLocator();
        if (isBlank(requestPath) && !isBlankValue(requestPathValue)) {
            requestPath = String.valueOf(requestPathValue).trim();
        }
        if (!isBlank(requestPath) && isAbsoluteHttpUrl(requestPath)) {
            return requestPath.trim();
        }
        Map<String, Object> connect = buildConnectionConfig(datasource);
        String baseUrl = connect.get("url") == null ? null : String.valueOf(connect.get("url")).trim();
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

    private String resolveHttpMode(Map<String, Object> metadata) {
        Object mode = metadata == null ? null : metadata.get("mode");
        return isBlankValue(mode) ? "GET" : String.valueOf(mode).trim().toUpperCase(Locale.ENGLISH);
    }

    private String resolveHttpWriterMode(Map<String, Object> metadata) {
        Object mode = metadata == null ? null : metadata.get("mode");
        return isBlankValue(mode) ? "POST" : String.valueOf(mode).trim().toUpperCase(Locale.ENGLISH);
    }

    private String resolveHttpResultType(Map<String, Object> metadata) {
        Object resultType = metadata == null ? null : metadata.get("resultType");
        return isBlankValue(resultType) ? "json" : String.valueOf(resultType).trim().toLowerCase(Locale.ENGLISH);
    }

    private Map<String, Object> resolveHttpResponseStatus(Map<String, Object> metadata) {
        Object statusPath = metadata == null ? null : metadata.get("businessStatusPath");
        Object statusCode = metadata == null ? null : metadata.get("businessStatusCode");
        boolean hasPath = !isBlankValue(statusPath);
        boolean hasCode = !isBlankValue(statusCode);
        if (!hasPath && !hasCode) {
            return null;
        }
        if (!hasPath || !hasCode) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "HTTP business status path and code must be configured together");
        }
        Map<String, Object> responseStatus = new LinkedHashMap<String, Object>();
        responseStatus.put("path", String.valueOf(statusPath).trim());
        responseStatus.put("code", String.valueOf(statusCode).trim());
        return responseStatus;
    }

    private boolean hasTagFileField(List<Map<String, Object>> columns) {
        if (columns == null) {
            return false;
        }
        for (Map<String, Object> column : columns) {
            if (isTagFileField(column)) {
                return true;
            }
        }
        return false;
    }

    private Integer resolveFileColumnIndex(String fieldName,
                                           Map<String, Object> metadata,
                                           Map<String, Integer> fieldOrder,
                                           Map<String, Integer> tagOrder,
                                           int dataColumnCount,
                                           Integer fallback) {
        Integer tagIndex = tagOrder.get(fieldName);
        if (tagIndex != null) {
            return Integer.valueOf(dataColumnCount + tagIndex.intValue());
        }
        if (metadata != null) {
            Object index = metadata.get("index");
            if (index instanceof Number) {
                return Integer.valueOf(((Number) index).intValue());
            }
            if (index != null && !String.valueOf(index).trim().isEmpty()) {
                try {
                    return Integer.valueOf(String.valueOf(index).trim());
                } catch (NumberFormatException ignored) {
                    // Fall through to model order.
                }
            }
        }
        Integer modelOrder = fieldOrder.get(fieldName);
        return modelOrder == null ? fallback : modelOrder;
    }

    private List<String> resolveFileDataTags(DataModelDefinition model) {
        List<String> result = new ArrayList<String>();
        for (Map<String, Object> metadata : resolveModelFieldMetadata(model).values()) {
            Object name = metadata.get("name");
            if (name != null && isTagFileField(metadata)) {
                result.add(String.valueOf(name));
            }
        }
        return result;
    }

    private Map<String, Integer> resolveTagFieldOrder(List<String> dataTags) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        if (dataTags == null) {
            return result;
        }
        for (int i = 0; i < dataTags.size(); i++) {
            String tag = dataTags.get(i);
            if (tag != null && !tag.trim().isEmpty()) {
                result.put(tag, Integer.valueOf(i));
            }
        }
        return result;
    }

    private int resolveDataColumnCount(DataModelDefinition model) {
        if (model == null || model.getTechnicalMetadata() == null) {
            return 0;
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        int count = 0;
        if (columns instanceof List<?>) {
            for (Object item : (List<?>) columns) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Map<?, ?> source = (Map<?, ?>) item;
                Object name = source.get("name");
                if (name != null && !String.valueOf(name).trim().isEmpty() && !isTagFileField(source)) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean isTagFileField(Map<?, ?> metadata) {
        Object sourceKind = metadata == null ? null : metadata.get("sourceKind");
        return sourceKind != null && FILE_FIELD_SOURCE_KIND_TAG.equalsIgnoreCase(String.valueOf(sourceKind).trim());
    }

    private Map<String, Object> resolveEFileOptions(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (metadata == null || metadata.isEmpty()) {
            return result;
        }
        Object nested = metadata.get("efile");
        if (nested instanceof Map<?, ?>) {
            Map<?, ?> nestedMap = (Map<?, ?>) nested;
            putIfPresent(result, "entity", nestedMap.get("entity"), null);
            putIfPresent(result, "type", nestedMap.get("type"), null);
            putIfPresent(result, "dataTime", nestedMap.get("dataTime"), null);
            putIfPresent(result, "tableName", nestedMap.get("tableName"), null);
            putIfPresent(result, "tableCode", nestedMap.get("tableCode"), null);
            putIfPresent(result, "planDate", nestedMap.get("planDate"), null);
        }
        putIfPresent(result, "entity", firstPresent(metadata, "efile.entity"), result.get("entity"));
        putIfPresent(result, "type", firstPresent(metadata, "efile.type"), result.get("type"));
        putIfPresent(result, "dataTime", firstPresent(metadata, "efile.dataTime"), result.get("dataTime"));
        putIfPresent(result, "tableName", firstPresent(metadata, "efile.tableName"), result.get("tableName"));
        putIfPresent(result, "tableCode", firstPresent(metadata, "efile.tableCode"), result.get("tableCode"));
        putIfPresent(result, "planDate", firstPresent(metadata, "efile.planDate"), result.get("planDate"));
        return result;
    }

    private Map<String, Integer> resolveModelFieldOrder(DataModelDefinition model) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        if (model == null || model.getTechnicalMetadata() == null) {
            return result;
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        if (columns instanceof List<?>) {
            int index = 0;
            for (Object item : (List<?>) columns) {
                if (item instanceof Map<?, ?>) {
                    Object name = ((Map<?, ?>) item).get("name");
                    if (name != null && !String.valueOf(name).trim().isEmpty()) {
                        result.put(String.valueOf(name), Integer.valueOf(index));
                    }
                }
                index++;
            }
        }
        return result;
    }

    private Map<String, Map<String, Object>> resolveModelFieldMetadata(DataModelDefinition model) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        if (model == null || model.getTechnicalMetadata() == null) {
            return result;
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        if (columns instanceof List<?>) {
            for (Object item : (List<?>) columns) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Map<?, ?> source = (Map<?, ?>) item;
                Object name = source.get("name");
                if (name == null || String.valueOf(name).trim().isEmpty()) {
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (entry.getKey() != null) {
                        metadata.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                result.put(String.valueOf(name), metadata);
            }
        }
        return result;
    }

    private String resolveGenericColumnType(Map<String, Object> metadata) {
        if (metadata == null) {
            return "string";
        }
        Object type = metadata.get("type");
        return type == null || String.valueOf(type).trim().isEmpty() ? "string" : String.valueOf(type);
    }

    private String resolveInfluxColumnType(String fieldName, Map<String, Object> metadata) {
        if ("time".equalsIgnoreCase(fieldName)) {
            return "time";
        }
        if (metadata != null) {
            Object type = metadata.get("type");
            if (type != null) {
                String normalized = String.valueOf(type).trim().toLowerCase();
                if ("time".equals(normalized) || "tag".equals(normalized) || "field".equals(normalized)) {
                    return normalized;
                }
            }
        }
        return "field";
    }

    private List<Map<String, Object>> buildTransformers(List<FieldMappingDefinition> mappings, List<String> targetFields) {
        List<Map<String, Object>> transformers = new ArrayList<Map<String, Object>>();
        if (mappings == null || targetFields == null) {
            return transformers;
        }
        for (FieldMappingDefinition mapping : mappings) {
            if (mapping.getTargetField() == null || mapping.getTransformers() == null || mapping.getTransformers().isEmpty()) {
                continue;
            }
            int columnIndex = targetFields.indexOf(mapping.getTargetField());
            if (columnIndex < 0) {
                continue;
            }
            for (TransformerBinding transformer : mapping.getTransformers()) {
                if (transformer.getTransformerCode() == null || transformer.getTransformerCode().trim().isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("name", transformer.getTransformerCode());
                Map<String, Object> parameters = new LinkedHashMap<String, Object>();
                parameters.put("columnIndex", columnIndex);
                parameters.put("paras", extractRuntimeParas(transformer));
                item.put("parameter", parameters);
                transformers.add(item);
            }
        }
        return transformers;
    }

    private List<Object> extractRuntimeParas(TransformerBinding transformer) {
        if (transformer == null || transformer.getParameters() == null || transformer.getParameters().isEmpty()) {
            return Collections.emptyList();
        }
        Object paras = transformer.getParameters().get("paras");
        if (paras instanceof List<?>) {
            return new ArrayList<Object>((List<?>) paras);
        }
        List<Object> fallback = new ArrayList<Object>();
        for (Map.Entry<String, Object> entry : transformer.getParameters().entrySet()) {
            if ("columnIndex".equals(entry.getKey()) || "paras".equals(entry.getKey())) {
                continue;
            }
            fallback.add(entry.getValue());
        }
        return fallback;
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

    private Object firstPresent(Map<String, Object> metadata, String... keys) {
        if (metadata == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (!isBlankValue(value)) {
                return value;
            }
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value, Object defaultValue) {
        if (!isBlankValue(value)) {
            target.put(key, value);
        } else if (defaultValue != null) {
            target.put(key, defaultValue);
        }
    }

    private List<String> resolveTargetFields(List<FieldMappingDefinition> fieldMappings, DataModelDefinition targetModel) {
        List<String> targetFields = new ArrayList<String>();
        if (fieldMappings != null) {
            for (FieldMappingDefinition mapping : fieldMappings) {
                if (mapping.getTargetField() != null && !mapping.getTargetField().trim().isEmpty()) {
                    targetFields.add(mapping.getTargetField());
                }
            }
        }
        if (!targetFields.isEmpty()) {
            return targetFields;
        }
        return resolveModelFields(targetModel);
    }

    private List<String> resolveSingleSourceFields(List<FieldMappingDefinition> fieldMappings, DataModelDefinition sourceModel) {
        List<String> sourceFields = new ArrayList<String>();
        if (fieldMappings != null) {
            for (FieldMappingDefinition mapping : fieldMappings) {
                if (mapping.getSourceField() != null && !mapping.getSourceField().trim().isEmpty()) {
                    sourceFields.add(mapping.getSourceField());
                }
            }
        }
        if (!sourceFields.isEmpty()) {
            return sourceFields;
        }
        return resolveModelFields(sourceModel);
    }

    private List<String> resolveSourceFieldsByAlias(List<FieldMappingDefinition> fieldMappings,
                                                    String sourceAlias,
                                                    DataModelDefinition sourceModel) {
        Set<String> fields = new LinkedHashSet<String>();
        if (fieldMappings != null) {
            for (FieldMappingDefinition mapping : fieldMappings) {
                if (sourceAlias != null
                        && sourceAlias.equals(mapping.getSourceAlias())
                        && mapping.getSourceField() != null
                        && !mapping.getSourceField().trim().isEmpty()) {
                    fields.add(mapping.getSourceField());
                }
            }
        }
        if (!fields.isEmpty()) {
            return new ArrayList<String>(fields);
        }
        return resolveModelFields(sourceModel);
    }

    private List<String> resolveModelFields(DataModelDefinition model) {
        if (model == null || model.getTechnicalMetadata() == null) {
            return Collections.emptyList();
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        List<String> fields = new ArrayList<String>();
        if (columns instanceof List<?>) {
            for (Object item : (List<?>) columns) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Object name = ((Map<?, ?>) item).get("name");
                if (name != null && !String.valueOf(name).trim().isEmpty()) {
                    fields.add(String.valueOf(name));
                }
            }
        }
        return fields;
    }

    private List<String> resolveJoinKeys(CollectionTaskDefinitionView definition) {
        Map<String, Object> executionOptions = definition.getExecutionOptions() == null
                ? new LinkedHashMap<String, Object>()
                : definition.getExecutionOptions();
        Object keys = executionOptions.get("joinKeys");
        List<String> joinKeys = new ArrayList<String>();
        if (keys instanceof List<?>) {
            for (Object item : (List<?>) keys) {
                if (item != null && !String.valueOf(item).trim().isEmpty()) {
                    joinKeys.add(String.valueOf(item));
                }
            }
        } else if (keys instanceof String && !((String) keys).trim().isEmpty()) {
            String[] items = ((String) keys).split(",");
            for (String item : items) {
                if (!item.trim().isEmpty()) {
                    joinKeys.add(item.trim());
                }
            }
        }
        if (definition.getTaskType() == CollectionTaskType.FUSION && joinKeys.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Fusion task requires join keys");
        }
        return joinKeys;
    }

    private String buildQuerySql(String tableName, List<String> sourceFields) {
        List<String> fields = sourceFields == null || sourceFields.isEmpty() ? Collections.singletonList("*") : sourceFields;
        return "select " + String.join(", ", fields) + " from " + tableName;
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

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return Boolean.TRUE.equals(value);
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String resolveFileType(Object value) {
        return isBlankValue(value) ? "csv" : String.valueOf(value).trim().toLowerCase(Locale.ENGLISH);
    }

    private boolean isEFileType(String fileType) {
        return "efile".equalsIgnoreCase(fileType);
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
