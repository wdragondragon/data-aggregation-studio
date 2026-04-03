package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataSourceService {

    private static final String BUSINESS_SCHEMA_VERSION_KEY = "__businessSchemaVersionId";

    private final DatasourceMapper datasourceMapper;
    private final DataModelMapper dataModelMapper;
    private final EncryptionService encryptionService;
    private final AggregationSourceCapabilityProvider capabilityProvider;
    private final MetadataSchemaService metadataSchemaService;

    public DataSourceService(DatasourceMapper datasourceMapper,
                             DataModelMapper dataModelMapper,
                             EncryptionService encryptionService,
                             AggregationSourceCapabilityProvider capabilityProvider,
                             MetadataSchemaService metadataSchemaService) {
        this.datasourceMapper = datasourceMapper;
        this.dataModelMapper = dataModelMapper;
        this.encryptionService = encryptionService;
        this.capabilityProvider = capabilityProvider;
        this.metadataSchemaService = metadataSchemaService;
    }

    public List<DataSourceDefinition> list() {
        List<DatasourceEntity> entities = datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                .orderByAsc(DatasourceEntity::getName));
        List<DataSourceDefinition> result = new ArrayList<DataSourceDefinition>();
        for (DatasourceEntity entity : entities) {
            result.add(toDefinition(entity, true));
        }
        return result;
    }

    public DataSourceDefinition get(Long id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        return entity == null ? null : toDefinition(entity, true);
    }

    public DataSourceDefinition getInternal(Long id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        return entity == null ? null : toDefinition(entity, false);
    }

    @Transactional
    public DataSourceDefinition save(DataSourceSaveRequest request) {
        DatasourceEntity entity = request.getId() == null ? new DatasourceEntity() : datasourceMapper.selectById(request.getId());
        if (entity == null) {
            entity = new DatasourceEntity();
        }
        MetadataSchemaDefinition schema = findDatasourceSchema(request.getSchemaVersionId(), request.getTypeCode());
        Map<String, Object> technicalMetadata = applyDefaults(request.getTechnicalMetadata(), schema, MetadataScope.TECHNICAL);
        technicalMetadata = preserveSensitiveValues(entity.getTechnicalMetadata(), technicalMetadata);
        Map<String, Object> businessMetadata = new LinkedHashMap<String, Object>();
        if (request.getBusinessMetadata() != null) {
            businessMetadata.putAll(request.getBusinessMetadata());
        }
        MetadataSchemaDefinition businessSchema = resolveBusinessSchema(businessMetadata);
        entity.setName(request.getName());
        entity.setTypeCode(request.getTypeCode());
        entity.setSchemaVersionId(resolveSchemaVersionId(request, schema));
        entity.setEnabled(Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);
        entity.setExecutable(Boolean.TRUE.equals(request.getExecutable()) ? 1 : 0);
        entity.setTechnicalMetadata(encryptSensitive(technicalMetadata));
        entity.setBusinessMetadata(applyDefaults(businessMetadata, businessSchema == null ? schema : businessSchema, MetadataScope.BUSINESS));
        if (entity.getId() == null) {
            datasourceMapper.insert(entity);
        } else {
            datasourceMapper.updateById(entity);
        }
        return toDefinition(entity, true);
    }

    public ConnectionTestResult testConnection(Long id) {
        DataSourceDefinition definition = getInternal(id);
        return capabilityProvider.testConnection(definition);
    }

    public ModelDiscoveryResult discoverModels(Long id) {
        DataSourceDefinition definition = getInternal(id);
        return capabilityProvider.discoverModels(definition);
    }

    @Transactional
    public void delete(Long id) {
        dataModelMapper.delete(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getDatasourceId, id));
        datasourceMapper.deleteById(id);
    }

    private DataSourceDefinition toDefinition(DatasourceEntity entity, boolean maskSensitive) {
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(entity.getId());
        definition.setTenantId(entity.getTenantId());
        definition.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        definition.setCreatedAt(entity.getCreatedAt());
        definition.setUpdatedAt(entity.getUpdatedAt());
        definition.setName(entity.getName());
        definition.setTypeCode(entity.getTypeCode());
        definition.setSchemaVersionId(resolveReadableSchemaVersionId(entity));
        definition.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
        definition.setExecutable(entity.getExecutable() != null && entity.getExecutable() == 1);
        definition.setTechnicalMetadata(maskSensitive ? maskSensitive(entity.getTechnicalMetadata()) : entity.getTechnicalMetadata());
        definition.setBusinessMetadata(entity.getBusinessMetadata());
        return definition;
    }

    private Map<String, Object> encryptSensitive(Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (input == null) {
            return output;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && isSensitive(entry.getKey()) && !String.valueOf(value).startsWith("ENC(")) {
                output.put(entry.getKey(), "ENC(" + encryptionService.encrypt(String.valueOf(value)) + ")");
            } else {
                output.put(entry.getKey(), value);
            }
        }
        return output;
    }

    private Map<String, Object> maskSensitive(Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (input == null) {
            return output;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && isSensitive(entry.getKey()) && String.valueOf(value).startsWith("ENC(") && String.valueOf(value).endsWith(")")) {
                String cipher = String.valueOf(value).substring(4, String.valueOf(value).length() - 1);
                output.put(entry.getKey(), encryptionService.mask(encryptionService.decrypt(cipher)));
            } else {
                output.put(entry.getKey(), value);
            }
        }
        return output;
    }

    private Map<String, Object> preserveSensitiveValues(Map<String, Object> existing,
                                                        Map<String, Object> incoming) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (incoming != null) {
            output.putAll(incoming);
        }
        if (existing == null || existing.isEmpty()) {
            return output;
        }
        Map<String, Object> maskedExisting = maskSensitive(existing);
        for (Map.Entry<String, Object> entry : existing.entrySet()) {
            String key = entry.getKey();
            if (!isSensitive(key)) {
                continue;
            }
            if (!output.containsKey(key)) {
                output.put(key, entry.getValue());
                continue;
            }
            Object incomingValue = output.get(key);
            Object maskedValue = maskedExisting.get(key);
            if (incomingValue instanceof String
                    && maskedValue instanceof String
                    && String.valueOf(incomingValue).equals(maskedValue)) {
                output.put(key, entry.getValue());
            }
        }
        return output;
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("accesskey");
    }

    private Long resolveSchemaVersionId(DataSourceSaveRequest request, MetadataSchemaDefinition schema) {
        if (request.getSchemaVersionId() != null) {
            return request.getSchemaVersionId();
        }
        return schema == null ? null : schema.getCurrentVersionId();
    }

    private Long resolveReadableSchemaVersionId(DatasourceEntity entity) {
        if (entity.getSchemaVersionId() != null
                && metadataSchemaService.findSchemaByVersionId(entity.getSchemaVersionId()) != null) {
            return entity.getSchemaVersionId();
        }
        MetadataSchemaDefinition schema = findDatasourceSchema(null, entity.getTypeCode());
        return schema == null ? entity.getSchemaVersionId() : schema.getCurrentVersionId();
    }

    private MetadataSchemaDefinition findDatasourceSchema(Long schemaVersionId, String typeCode) {
        if (schemaVersionId != null) {
            MetadataSchemaDefinition schema = metadataSchemaService.findSchemaByVersionId(schemaVersionId);
            if (schema != null && "datasource".equalsIgnoreCase(schema.getObjectType())) {
                return schema;
            }
        }
        MetadataSchemaDefinition preferred = metadataSchemaService.findTechnicalMetaModel(typeCode, "source");
        if (preferred != null) {
            return preferred;
        }
        for (MetadataSchemaDefinition schema : metadataSchemaService.listSchemas()) {
            if ("datasource".equalsIgnoreCase(schema.getObjectType())
                    && typeCode != null
                    && typeCode.equalsIgnoreCase(schema.getTypeCode())
                    && schema.getCurrentVersionId() != null) {
                return schema;
            }
        }
        return null;
    }

    private MetadataSchemaDefinition resolveBusinessSchema(Map<String, Object> businessMetadata) {
        Long schemaVersionId = parseSchemaVersionId(businessMetadata == null ? null : businessMetadata.get(BUSINESS_SCHEMA_VERSION_KEY));
        if (schemaVersionId == null) {
            return null;
        }
        MetadataSchemaDefinition schema = metadataSchemaService.findSchemaByVersionId(schemaVersionId);
        if (schema == null || !"business".equalsIgnoreCase(schema.getObjectType())) {
            return null;
        }
        return schema;
    }

    private Long parseSchemaVersionId(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> applyDefaults(Map<String, Object> input,
                                              MetadataSchemaDefinition schema,
                                              MetadataScope scope) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (input != null) {
            output.putAll(input);
        }
        if (schema == null || schema.getFields() == null) {
            return output;
        }
        for (MetadataFieldDefinition field : schema.getFields()) {
            if (field.getScope() != scope) {
                continue;
            }
            if (output.containsKey(field.getFieldKey())) {
                continue;
            }
            Object defaultValue = parseDefaultValue(field);
            if (defaultValue != null) {
                output.put(field.getFieldKey(), defaultValue);
            }
        }
        return output;
    }

    private Object parseDefaultValue(MetadataFieldDefinition field) {
        String defaultValue = field.getDefaultValue();
        if (defaultValue == null || defaultValue.trim().isEmpty()) {
            return null;
        }
        FieldValueType valueType = field.getValueType();
        if (valueType == null) {
            return defaultValue;
        }
        try {
            switch (valueType) {
                case BOOLEAN:
                    return Boolean.parseBoolean(defaultValue);
                case INTEGER:
                    return Integer.parseInt(defaultValue);
                case LONG:
                    return Long.parseLong(defaultValue);
                case DECIMAL:
                    return new BigDecimal(defaultValue);
                default:
                    return defaultValue;
            }
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}

