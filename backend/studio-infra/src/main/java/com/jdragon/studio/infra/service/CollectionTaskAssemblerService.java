package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskType;
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
import java.util.Map;
import java.util.Set;

@Service
public class CollectionTaskAssemblerService {

    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final EncryptionService encryptionService;

    public CollectionTaskAssemblerService(DataSourceService dataSourceService,
                                          DataModelService dataModelService,
                                          EncryptionService encryptionService) {
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.encryptionService = encryptionService;
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
        config.put("reader", buildStandardReader(sourceDatasource, sourceModel, sourceFields));
        List<Map<String, Object>> transformers = buildTransformers(definition.getFieldMappings(), targetFields);
        if (!transformers.isEmpty()) {
            config.put("transformer", transformers);
        }
        config.put("writer", buildStandardWriter(targetDatasource, targetModel, targetFields, definition.getExecutionOptions()));
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
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", sourceBinding.getSourceAlias());
            item.put("type", sourceDatasource.getTypeCode());
            item.put("config", buildConnectionConfig(sourceDatasource));
            item.put("querySql", buildQuerySql(sourceModel.getPhysicalLocator(), sourceFields));
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
        reader.put("config", readerConfig);

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("reader", reader);
        List<Map<String, Object>> transformers = buildTransformers(definition.getFieldMappings(), targetFields);
        if (!transformers.isEmpty()) {
            config.put("transformer", transformers);
        }
        config.put("writer", buildStandardWriter(targetDatasource, targetModel, targetFields, executionOptions));
        return config;
    }

    private Map<String, Object> buildStandardReader(DataSourceDefinition datasource,
                                                    DataModelDefinition model,
                                                    List<String> sourceFields) {
        Map<String, Object> reader = new LinkedHashMap<String, Object>();
        reader.put("type", datasource.getTypeCode());
        Map<String, Object> readerConfig = new LinkedHashMap<String, Object>();
        readerConfig.put("connect", buildConnectionConfig(datasource));
        readerConfig.put("table", model.getPhysicalLocator());
        readerConfig.put("columns", sourceFields);
        reader.put("config", readerConfig);
        return reader;
    }

    private Map<String, Object> buildStandardWriter(DataSourceDefinition datasource,
                                                    DataModelDefinition model,
                                                    List<String> targetFields,
                                                    Map<String, Object> executionOptions) {
        Map<String, Object> writer = new LinkedHashMap<String, Object>();
        writer.put("type", datasource.getTypeCode());
        Map<String, Object> writerConfig = new LinkedHashMap<String, Object>();
        writerConfig.put("connect", buildConnectionConfig(datasource));
        writerConfig.put("table", model.getPhysicalLocator());
        writerConfig.put("columns", targetFields);
        writerConfig.put("writeMode", executionOptions == null ? "insert" : String.valueOf(executionOptions.getOrDefault("writeMode", "insert")));
        writer.put("config", writerConfig);
        return writer;
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
                if (transformer.getParameters() != null) {
                    parameters.putAll(transformer.getParameters());
                }
                parameters.put("columnIndex", columnIndex);
                item.put("parameter", parameters);
                transformers.add(item);
            }
        }
        return transformers;
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

    @SuppressWarnings("unchecked")
    private List<String> resolveModelFields(DataModelDefinition model) {
        if (model == null || model.getTechnicalMetadata() == null) {
            return Collections.emptyList();
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        List<String> fields = new ArrayList<String>();
        if (columns instanceof List) {
            for (Object item : (List<Object>) columns) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Object name = ((Map<String, Object>) item).get("name");
                if (name != null && !String.valueOf(name).trim().isEmpty()) {
                    fields.add(String.valueOf(name));
                }
            }
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveJoinKeys(CollectionTaskDefinitionView definition) {
        Map<String, Object> executionOptions = definition.getExecutionOptions() == null
                ? new LinkedHashMap<String, Object>()
                : definition.getExecutionOptions();
        Object keys = executionOptions.get("joinKeys");
        List<String> joinKeys = new ArrayList<String>();
        if (keys instanceof List) {
            for (Object item : (List<Object>) keys) {
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
