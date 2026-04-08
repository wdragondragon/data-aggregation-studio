package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
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

    private final DatasourceMapper datasourceMapper;
    private final DataModelMapper dataModelMapper;
    private final EncryptionService encryptionService;
    private final AggregationSourceCapabilityProvider capabilityProvider;
    private final MetadataSchemaService metadataSchemaService;
    private final DataModelSearchIndexService dataModelSearchIndexService;
    private final BusinessMetaModelMetadataService businessMetaModelMetadataService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;

    public DataSourceService(DatasourceMapper datasourceMapper,
                             DataModelMapper dataModelMapper,
                             EncryptionService encryptionService,
                             AggregationSourceCapabilityProvider capabilityProvider,
                             MetadataSchemaService metadataSchemaService,
                             DataModelSearchIndexService dataModelSearchIndexService,
                             BusinessMetaModelMetadataService businessMetaModelMetadataService,
                             StudioSecurityService securityService,
                             ProjectResourceAccessService projectResourceAccessService) {
        this.datasourceMapper = datasourceMapper;
        this.dataModelMapper = dataModelMapper;
        this.encryptionService = encryptionService;
        this.capabilityProvider = capabilityProvider;
        this.metadataSchemaService = metadataSchemaService;
        this.dataModelSearchIndexService = dataModelSearchIndexService;
        this.businessMetaModelMetadataService = businessMetaModelMetadataService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    public List<DataSourceDefinition> list() {
        List<DatasourceEntity> entities = datasourceMapper.selectList(buildAccessibleQuery()
                .orderByAsc(DatasourceEntity::getProjectId)
                .orderByAsc(DatasourceEntity::getName));
        List<DataSourceDefinition> result = new ArrayList<DataSourceDefinition>();
        for (DatasourceEntity entity : entities) {
            result.add(toDefinition(entity, true));
        }
        return result;
    }

    public DataSourceDefinition get(Long id) {
        DatasourceEntity entity = findAccessibleEntity(id);
        return entity == null ? null : toDefinition(entity, true);
    }

    public DataSourceDefinition getInternal(Long id) {
        DatasourceEntity entity = findAccessibleEntity(id);
        return entity == null ? null : toDefinition(entity, false);
    }

    @Transactional
    public DataSourceDefinition save(DataSourceSaveRequest request) {
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        String currentTenantId = securityService.currentTenantId();
        DatasourceEntity entity = request.getId() == null ? new DatasourceEntity() : requireWritableEntity(request.getId());
        if (entity == null) {
            entity = new DatasourceEntity();
        }
        MetadataSchemaDefinition schema = findDatasourceSchema(request.getSchemaVersionId(), request.getTypeCode());
        Map<String, Object> technicalMetadata = applyDefaults(request.getTechnicalMetadata(), schema, MetadataScope.TECHNICAL);
        technicalMetadata = preserveSensitiveValues(entity.getTechnicalMetadata(), technicalMetadata);
        ensureUniqueName(currentProjectId, request.getName(), entity.getId());
        entity.setTenantId(currentTenantId);
        entity.setProjectId(currentProjectId);
        entity.setName(request.getName());
        entity.setTypeCode(request.getTypeCode());
        entity.setSchemaVersionId(resolveSchemaVersionId(request, schema));
        entity.setEnabled(Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);
        entity.setExecutable(Boolean.TRUE.equals(request.getExecutable()) ? 1 : 0);
        entity.setTechnicalMetadata(encryptSensitive(technicalMetadata));
        entity.setBusinessMetadata(businessMetaModelMetadataService.normalizeForDatasource(request.getBusinessMetadata()));
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

    public ConnectionTestResult testConnection(DataSourceSaveRequest request) {
        return capabilityProvider.testConnection(buildDefinitionForTest(request));
    }

    public ModelDiscoveryResult discoverModels(Long id) {
        DataSourceDefinition definition = getInternal(id);
        return capabilityProvider.discoverModels(definition);
    }

    @Transactional
    public void delete(Long id) {
        requireWritableEntity(id);
        dataModelSearchIndexService.deleteByDatasourceId(id);
        dataModelMapper.delete(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getDatasourceId, id));
        datasourceMapper.deleteById(id);
    }

    private DataSourceDefinition toDefinition(DatasourceEntity entity, boolean maskSensitive) {
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(entity.getId());
        definition.setTenantId(entity.getTenantId());
        definition.setProjectId(entity.getProjectId());
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

    private DataSourceDefinition buildDefinitionForTest(DataSourceSaveRequest request) {
        DatasourceEntity entity = request.getId() == null ? null : datasourceMapper.selectById(request.getId());
        MetadataSchemaDefinition schema = findDatasourceSchema(request.getSchemaVersionId(), request.getTypeCode());
        Map<String, Object> technicalMetadata = applyDefaults(request.getTechnicalMetadata(), schema, MetadataScope.TECHNICAL);
        technicalMetadata = preserveSensitiveValues(entity == null ? null : entity.getTechnicalMetadata(), technicalMetadata);

        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(request.getId());
        definition.setName(request.getName());
        definition.setTypeCode(request.getTypeCode());
        definition.setSchemaVersionId(resolveSchemaVersionId(request, schema));
        definition.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        definition.setExecutable(Boolean.TRUE.equals(request.getExecutable()));
        definition.setTechnicalMetadata(technicalMetadata);
        definition.setBusinessMetadata(businessMetaModelMetadataService.normalizeForDatasource(request.getBusinessMetadata()));
        return definition;
    }

    private LambdaQueryWrapper<DatasourceEntity> buildAccessibleQuery() {
        LambdaQueryWrapper<DatasourceEntity> queryWrapper = new LambdaQueryWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getTenantId, securityService.currentTenantId());
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return queryWrapper;
        }
        List<Long> sharedIds = projectResourceAccessService.sharedResourceIdList(StudioConstants.RESOURCE_TYPE_DATASOURCE);
        if (sharedIds.isEmpty()) {
            queryWrapper.eq(DatasourceEntity::getProjectId, currentProjectId);
            return queryWrapper;
        }
        queryWrapper.and(wrapper -> wrapper.eq(DatasourceEntity::getProjectId, currentProjectId)
                .or()
                .in(DatasourceEntity::getId, sharedIds));
        return queryWrapper;
    }

    private DatasourceEntity findAccessibleEntity(Long id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        if (entity == null) {
            return null;
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATASOURCE,
                entity.getProjectId(), entity.getId(), "Datasource not found: " + id);
        return entity;
    }

    private DatasourceEntity requireWritableEntity(Long id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + id);
        }
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private void ensureUniqueName(Long projectId, String name, Long selfId) {
        if (projectId == null || name == null || name.trim().isEmpty()) {
            return;
        }
        List<DatasourceEntity> duplicates = datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getProjectId, projectId)
                .eq(DatasourceEntity::getName, name.trim()));
        for (DatasourceEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource name already exists in the current project");
        }
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

