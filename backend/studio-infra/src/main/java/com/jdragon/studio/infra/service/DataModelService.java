package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.ModelKind;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.DataModelQueryCondition;
import com.jdragon.studio.dto.model.request.DataModelQueryGroup;
import com.jdragon.studio.dto.model.request.DataModelQueryRequest;
import com.jdragon.studio.dto.model.request.DataModelSaveRequest;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DataModelService {

    private final DataModelMapper dataModelMapper;
    private final DataSourceService dataSourceService;
    private final AggregationSourceCapabilityProvider modelDiscoveryProvider;
    private final MetadataSchemaService metadataSchemaService;
    private final DataModelSearchIndexService dataModelSearchIndexService;
    private final BusinessMetaModelMetadataService businessMetaModelMetadataService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final DataModelAccessScopeService dataModelAccessScopeService;

    public DataModelService(DataModelMapper dataModelMapper,
                            DataSourceService dataSourceService,
                            AggregationSourceCapabilityProvider modelDiscoveryProvider,
                            MetadataSchemaService metadataSchemaService,
                            DataModelSearchIndexService dataModelSearchIndexService,
                            BusinessMetaModelMetadataService businessMetaModelMetadataService,
                            StudioSecurityService securityService,
                            ProjectResourceAccessService projectResourceAccessService,
                            DataModelAccessScopeService dataModelAccessScopeService) {
        this.dataModelMapper = dataModelMapper;
        this.dataSourceService = dataSourceService;
        this.modelDiscoveryProvider = modelDiscoveryProvider;
        this.metadataSchemaService = metadataSchemaService;
        this.dataModelSearchIndexService = dataModelSearchIndexService;
        this.businessMetaModelMetadataService = businessMetaModelMetadataService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.dataModelAccessScopeService = dataModelAccessScopeService;
    }

    public List<DataModelDefinition> list() {
        List<DataModelEntity> entities = dataModelMapper.selectList(buildAccessibleQuery()
                .orderByAsc(DataModelEntity::getProjectId)
                .orderByAsc(DataModelEntity::getName));
        List<DataModelDefinition> result = new ArrayList<DataModelDefinition>();
        for (DataModelEntity entity : entities) {
            result.add(toDefinition(entity));
        }
        return result;
    }

    public List<DataModelDefinition> listByDatasource(Long datasourceId) {
        dataSourceService.get(datasourceId);
        List<DataModelEntity> entities = dataModelMapper.selectList(buildAccessibleQuery()
                .eq(DataModelEntity::getDatasourceId, datasourceId)
                .orderByAsc(DataModelEntity::getName));
        List<DataModelDefinition> result = new ArrayList<DataModelDefinition>();
        for (DataModelEntity entity : entities) {
            result.add(toDefinition(entity));
        }
        return result;
    }

    public DataModelDefinition get(Long modelId) {
        DataModelEntity entity = findAccessibleEntity(modelId);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Model not found: " + modelId);
        }
        return toDefinition(entity);
    }

    public List<DataModelDefinition> query(DataModelQueryRequest request) {
        if (request == null) {
            return list();
        }
        List<DataModelQueryGroup> groups = normalizeQueryGroups(request.getGroups());
        if (groups.isEmpty()) {
            LambdaQueryWrapper<DataModelEntity> queryWrapper = buildAccessibleQuery()
                    .orderByAsc(DataModelEntity::getName);
            if (request.getDatasourceId() != null) {
                queryWrapper.eq(DataModelEntity::getDatasourceId, request.getDatasourceId());
            }
            if (request.getModelKind() != null && !request.getModelKind().trim().isEmpty()) {
                queryWrapper.eq(DataModelEntity::getModelKind, request.getModelKind().trim().toUpperCase());
            }
            return toDefinitions(dataModelMapper.selectList(queryWrapper));
        }
        DataModelQueryRequest normalizedRequest = new DataModelQueryRequest();
        normalizedRequest.setDatasourceId(request.getDatasourceId());
        normalizedRequest.setModelKind(request.getModelKind());
        normalizedRequest.setGroups(groups);
        Set<Long> matchedIds = dataModelSearchIndexService.queryModelIds(normalizedRequest);
        if (matchedIds.isEmpty()) {
            return new ArrayList<DataModelDefinition>();
        }
        LambdaQueryWrapper<DataModelEntity> queryWrapper = buildAccessibleQuery()
                .in(DataModelEntity::getId, matchedIds)
                .orderByAsc(DataModelEntity::getName);
        if (request.getDatasourceId() != null) {
            queryWrapper.eq(DataModelEntity::getDatasourceId, request.getDatasourceId());
        }
        if (request.getModelKind() != null && !request.getModelKind().trim().isEmpty()) {
            queryWrapper.eq(DataModelEntity::getModelKind, request.getModelKind().trim().toUpperCase());
        }
        return toDefinitions(dataModelMapper.selectList(queryWrapper));
    }

    @Transactional
    public List<DataModelDefinition> syncFromDatasource(Long datasourceId) {
        return syncFromDatasource(datasourceId, null);
    }

    @Transactional
    public List<DataModelDefinition> syncFromDatasource(Long datasourceId, List<String> physicalLocators) {
        DataSourceDefinition datasource = dataSourceService.getInternal(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        String currentTenantId = securityService.currentTenantId();
        List<DataModelDefinition> discovered = modelDiscoveryProvider.discoverModels(datasource).getModels();
        Set<String> selectedLocators = normalizeLocators(physicalLocators);
        for (DataModelDefinition definition : discovered) {
            if (!selectedLocators.isEmpty()
                    && !selectedLocators.contains(definition.getPhysicalLocator())
                    && !selectedLocators.contains(definition.getName())) {
                continue;
            }
            DataModelEntity existing = dataModelMapper.selectOne(new LambdaQueryWrapper<DataModelEntity>()
                    .eq(DataModelEntity::getDatasourceId, datasourceId)
                    .eq(DataModelEntity::getProjectId, currentProjectId)
                    .eq(DataModelEntity::getPhysicalLocator, definition.getPhysicalLocator())
                    .last("limit 1"));
            DataModelEntity entity = existing == null ? new DataModelEntity() : existing;
            entity.setTenantId(currentTenantId);
            entity.setProjectId(currentProjectId);
            entity.setDatasourceId(datasourceId);
            entity.setName(definition.getName());
            entity.setModelKind(definition.getModelKind() == null ? ModelKind.DATASET.name() : definition.getModelKind().name());
            entity.setPhysicalLocator(definition.getPhysicalLocator());
            Long schemaVersionId = resolveSchemaVersionId(definition, datasource.getTypeCode(), existing);
            entity.setSchemaVersionId(schemaVersionId);
            Map<String, Object> technicalMetadata = mergeTechnicalMetadata(existing == null ? null : existing.getTechnicalMetadata(),
                    definition.getTechnicalMetadata());
            entity.setTechnicalMetadata(normalizeTechnicalMetadata(technicalMetadata, datasource.getTypeCode(), schemaVersionId));
            entity.setBusinessMetadata(businessMetaModelMetadataService.normalizeForModel(
                    existing == null ? null : existing.getBusinessMetadata(),
                    resolveAllowedBusinessMetaModelCodes(datasource.getTypeCode(), schemaVersionId)));
            if (entity.getId() == null) {
                dataModelMapper.insert(entity);
            } else {
                dataModelMapper.updateById(entity);
            }
            dataModelSearchIndexService.rebuildModelIndex(entity, datasource);
        }
        return listByDatasource(datasourceId);
    }

    @Transactional
    public DataModelDefinition save(DataModelSaveRequest request) {
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        DataModelEntity entity = request.getId() == null ? new DataModelEntity() : requireWritableEntity(request.getId());
        if (entity == null) {
            entity = new DataModelEntity();
        }
        DataSourceDefinition datasource = dataSourceService.getInternal(request.getDatasourceId());
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + request.getDatasourceId());
        }
        ensureUniqueName(currentProjectId, request.getName(), entity.getId());
        entity.setTenantId(securityService.currentTenantId());
        entity.setProjectId(currentProjectId);
        entity.setDatasourceId(request.getDatasourceId());
        entity.setName(request.getName());
        entity.setPhysicalLocator(request.getPhysicalLocator());
        entity.setModelKind(resolveModelKind(request, entity));
        Long schemaVersionId = resolveSchemaVersionId(request, datasource, entity);
        entity.setSchemaVersionId(schemaVersionId);
        MetadataSchemaDefinition technicalSchema = metadataSchemaService.findSchemaByVersionId(schemaVersionId);
        Map<String, Object> technicalMetadata = enrichTechnicalMetadata(entity.getTechnicalMetadata(), request, datasource);
        entity.setTechnicalMetadata(normalizeTechnicalMetadata(
                applyDefaults(technicalMetadata, technicalSchema, MetadataScope.TECHNICAL),
                datasource == null ? null : datasource.getTypeCode(),
                schemaVersionId));
        Map<String, Object> businessMetadata = request.getBusinessMetadata() == null
                ? entity.getBusinessMetadata()
                : request.getBusinessMetadata();
        entity.setBusinessMetadata(businessMetaModelMetadataService.normalizeForModel(
                businessMetadata,
                resolveAllowedBusinessMetaModelCodes(datasource == null ? null : datasource.getTypeCode(), schemaVersionId)));
        if (entity.getId() == null) {
            dataModelMapper.insert(entity);
        } else {
            dataModelMapper.updateById(entity);
        }
        dataModelSearchIndexService.rebuildModelIndex(entity, datasource);
        return toDefinition(entity);
    }

    public List<Map<String, Object>> preview(Long modelId, int limit) {
        DataModelEntity model = findAccessibleEntity(modelId);
        if (model == null) {
            return new ArrayList<Map<String, Object>>();
        }
        DataSourceDefinition datasource = dataSourceService.getInternal(model.getDatasourceId());
        return modelDiscoveryProvider.preview(datasource, toDefinition(model), limit);
    }

    @Transactional
    public void delete(Long modelId) {
        requireWritableEntity(modelId);
        dataModelSearchIndexService.deleteByModelId(modelId);
        dataModelMapper.deleteById(modelId);
    }

    @Transactional
    public int rebuildSearchIndex(Long datasourceId) {
        LambdaQueryWrapper<DataModelEntity> queryWrapper = buildAccessibleQuery()
                .orderByAsc(DataModelEntity::getId);
        if (datasourceId != null) {
            queryWrapper.eq(DataModelEntity::getDatasourceId, datasourceId);
        }
        List<DataModelEntity> entities = dataModelMapper.selectList(queryWrapper);
        Map<Long, DataSourceDefinition> datasourceCache = new LinkedHashMap<Long, DataSourceDefinition>();
        for (DataModelEntity entity : entities) {
            if (entity.getDatasourceId() == null) {
                continue;
            }
            DataSourceDefinition datasource = datasourceCache.get(entity.getDatasourceId());
            if (datasource == null) {
                datasource = dataSourceService.getInternal(entity.getDatasourceId());
                datasourceCache.put(entity.getDatasourceId(), datasource);
            }
            dataModelSearchIndexService.rebuildModelIndex(entity, datasource);
        }
        return entities.size();
    }

    private DataModelDefinition toDefinition(DataModelEntity entity) {
        DataModelDefinition definition = new DataModelDefinition();
        definition.setId(entity.getId());
        definition.setTenantId(entity.getTenantId());
        definition.setProjectId(entity.getProjectId());
        definition.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        definition.setCreatedAt(entity.getCreatedAt());
        definition.setUpdatedAt(entity.getUpdatedAt());
        definition.setDatasourceId(entity.getDatasourceId());
        definition.setName(entity.getName());
        definition.setPhysicalLocator(entity.getPhysicalLocator());
        definition.setSchemaVersionId(entity.getSchemaVersionId());
        definition.setTechnicalMetadata(entity.getTechnicalMetadata() == null ? new LinkedHashMap<String, Object>() : entity.getTechnicalMetadata());
        definition.setBusinessMetadata(entity.getBusinessMetadata() == null ? new LinkedHashMap<String, Object>() : entity.getBusinessMetadata());
        if (entity.getModelKind() != null) {
            definition.setModelKind(ModelKind.valueOf(entity.getModelKind()));
        }
        return definition;
    }

    private LambdaQueryWrapper<DataModelEntity> buildAccessibleQuery() {
        return dataModelAccessScopeService.buildAccessibleQuery();
    }

    private DataModelEntity findAccessibleEntity(Long modelId) {
        DataModelEntity entity = dataModelMapper.selectById(modelId);
        if (entity == null) {
            return null;
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATA_MODEL,
                entity.getProjectId(), entity.getId(), "Model not found: " + modelId);
        return entity;
    }

    private DataModelEntity requireWritableEntity(Long modelId) {
        DataModelEntity entity = dataModelMapper.selectById(modelId);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Model not found: " + modelId);
        }
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private void ensureUniqueName(Long projectId, String name, Long selfId) {
        if (projectId == null || name == null || name.trim().isEmpty()) {
            return;
        }
        List<DataModelEntity> duplicates = dataModelMapper.selectList(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getProjectId, projectId)
                .eq(DataModelEntity::getName, name.trim()));
        for (DataModelEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model name already exists in the current project");
        }
    }

    private List<DataModelDefinition> toDefinitions(List<DataModelEntity> entities) {
        List<DataModelDefinition> result = new ArrayList<DataModelDefinition>();
        for (DataModelEntity entity : entities) {
            result.add(toDefinition(entity));
        }
        return result;
    }

    private List<DataModelQueryGroup> normalizeQueryGroups(List<DataModelQueryGroup> groups) {
        List<DataModelQueryGroup> normalized = new ArrayList<DataModelQueryGroup>();
        if (groups == null) {
            return normalized;
        }
        for (DataModelQueryGroup group : groups) {
            if (group == null || group.getMetaSchemaCode() == null || group.getMetaSchemaCode().trim().isEmpty()) {
                continue;
            }
            DataModelQueryGroup copied = new DataModelQueryGroup();
            copied.setScope(group.getScope());
            copied.setMetaSchemaCode(group.getMetaSchemaCode().trim());
            copied.setRowMatchMode(group.getRowMatchMode());
            List<DataModelQueryCondition> conditions = new ArrayList<DataModelQueryCondition>();
            if (group.getConditions() != null) {
                for (DataModelQueryCondition condition : group.getConditions()) {
                    if (condition == null || condition.getFieldKey() == null || condition.getFieldKey().trim().isEmpty()) {
                        continue;
                    }
                    if ((condition.getValue() == null || String.valueOf(condition.getValue()).trim().isEmpty())
                            && (condition.getValues() == null || condition.getValues().isEmpty())) {
                        continue;
                    }
                    DataModelQueryCondition copiedCondition = new DataModelQueryCondition();
                    copiedCondition.setFieldKey(condition.getFieldKey().trim());
                    copiedCondition.setOperator(condition.getOperator());
                    copiedCondition.setValue(condition.getValue());
                    copiedCondition.setValues(condition.getValues());
                    conditions.add(copiedCondition);
                }
            }
            if (!conditions.isEmpty()) {
                copied.setConditions(conditions);
                normalized.add(copied);
            }
        }
        return normalized;
    }

    private Map<String, Object> mergeTechnicalMetadata(Map<String, Object> existing, Map<String, Object> discovered) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>();
        if (existing != null) {
            merged.putAll(existing);
        }
        if (discovered != null) {
            merged.putAll(discovered);
        }
        return merged;
    }

    private Set<String> normalizeLocators(List<String> physicalLocators) {
        Set<String> selected = new HashSet<String>();
        if (physicalLocators == null) {
            return selected;
        }
        for (String physicalLocator : physicalLocators) {
            if (physicalLocator != null && !physicalLocator.trim().isEmpty()) {
                selected.add(physicalLocator.trim());
            }
        }
        return selected;
    }

    private String resolveModelKind(DataModelSaveRequest request, DataModelEntity existing) {
        if (request.getModelKind() != null) {
            return request.getModelKind().name();
        }
        if (existing != null && existing.getModelKind() != null) {
            return existing.getModelKind();
        }
        return ModelKind.DATASET.name();
    }

    private Long resolveSchemaVersionId(DataModelSaveRequest request,
                                        DataSourceDefinition datasource,
                                        DataModelEntity existing) {
        if (request.getSchemaVersionId() != null) {
            return request.getSchemaVersionId();
        }
        DataModelDefinition temp = new DataModelDefinition();
        if (request.getModelKind() != null) {
            temp.setModelKind(request.getModelKind());
        } else if (existing != null && existing.getModelKind() != null) {
            temp.setModelKind(ModelKind.valueOf(existing.getModelKind()));
        }
        return resolveSchemaVersionId(temp, datasource == null ? null : datasource.getTypeCode(), existing);
    }

    private Map<String, Object> enrichTechnicalMetadata(Map<String, Object> existing,
                                                        DataModelSaveRequest request,
                                                        DataSourceDefinition datasource) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        if (existing != null) {
            metadata.putAll(existing);
        }
        if (request.getTechnicalMetadata() != null) {
            metadata.putAll(request.getTechnicalMetadata());
        }
        putIfAbsent(metadata, "sourceType", datasource == null ? null : datasource.getTypeCode());
        putIfAbsent(metadata, "discoveryMode", "MANUAL");
        putIfAbsent(metadata, "physicalName", request.getPhysicalLocator());
        if (request.getModelKind() != null) {
            putIfAbsent(metadata, "tableType", request.getModelKind().name());
        }
        return metadata;
    }

    private void putIfAbsent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (target.containsKey(key)) {
            Object existing = target.get(key);
            if (existing != null && (!(existing instanceof String) || !((String) existing).trim().isEmpty())) {
                return;
            }
        }
        target.put(key, value);
    }

    private Long resolveSchemaVersionId(DataModelDefinition definition, String datasourceTypeCode, DataModelEntity existing) {
        if (definition.getSchemaVersionId() != null) {
            return definition.getSchemaVersionId();
        }
        if (existing != null && existing.getSchemaVersionId() != null) {
            return existing.getSchemaVersionId();
        }
        if (datasourceTypeCode == null || datasourceTypeCode.trim().isEmpty()) {
            return null;
        }
        String modelKind = definition.getModelKind() == null ? null : definition.getModelKind().name().toLowerCase();
        if (modelKind != null) {
            MetadataSchemaDefinition exactSchema = metadataSchemaService.findTechnicalMetaModel(datasourceTypeCode, modelKind);
            if (exactSchema != null && exactSchema.getCurrentVersionId() != null) {
                return exactSchema.getCurrentVersionId();
            }
        }
        MetadataSchemaDefinition tableSchema = metadataSchemaService.findTechnicalMetaModel(datasourceTypeCode, "table");
        if (tableSchema != null && tableSchema.getCurrentVersionId() != null) {
            return tableSchema.getCurrentVersionId();
        }
        List<MetadataSchemaDefinition> schemas = metadataSchemaService.listSchemas();
        for (MetadataSchemaDefinition schema : schemas) {
            if (!"model".equalsIgnoreCase(schema.getObjectType())) {
                continue;
            }
            if (schema.getCurrentVersionId() == null) {
                continue;
            }
            String typeCode = schema.getTypeCode();
            if (typeCode == null) {
                continue;
            }
            if (typeCode.equalsIgnoreCase(datasourceTypeCode + "." + modelKind)
                    || typeCode.equalsIgnoreCase(datasourceTypeCode)
                    || (modelKind != null && typeCode.equalsIgnoreCase(modelKind))) {
                return schema.getCurrentVersionId();
            }
        }
        return null;
    }

    private Map<String, Object> normalizeTechnicalMetadata(Map<String, Object> metadata,
                                                           String datasourceTypeCode,
                                                           Long schemaVersionId) {
        MetadataSchemaDefinition tableSchema = metadataSchemaService.findSchemaByVersionId(schemaVersionId);
        Map<String, Object> normalized = applyDefaults(metadata, tableSchema, MetadataScope.TECHNICAL);
        MetadataSchemaDefinition fieldSchema = metadataSchemaService.findTechnicalMetaModel(datasourceTypeCode, "field");
        Object columns = normalized.get("columns");
        if (columns instanceof List) {
            normalized.put("columns", normalizeColumnMetadata((List<?>) columns, fieldSchema));
        }
        return normalized;
    }

    private List<Map<String, Object>> normalizeColumnMetadata(List<?> columns,
                                                              MetadataSchemaDefinition fieldSchema) {
        List<Map<String, Object>> normalized = new ArrayList<Map<String, Object>>();
        if (columns == null) {
            return normalized;
        }
        for (Object column : columns) {
            if (!(column instanceof Map)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.putAll((Map<String, Object>) column);
            normalized.add(applyDefaults(item, fieldSchema, MetadataScope.TECHNICAL));
        }
        return normalized;
    }

    private Set<String> resolveAllowedBusinessMetaModelCodes(String datasourceTypeCode,
                                                             Long activeSchemaVersionId) {
        Set<String> allowedCodes = new HashSet<String>();
        String normalizedDatasourceType = datasourceTypeCode == null ? "" : datasourceTypeCode.trim().toLowerCase();
        if (normalizedDatasourceType.isEmpty()) {
            return allowedCodes;
        }
        for (MetadataSchemaDefinition schema : metadataSchemaService.listSchemas()) {
            if (!"model".equalsIgnoreCase(schema.getObjectType())) {
                continue;
            }
            if (!"TECHNICAL".equalsIgnoreCase(metadataSchemaService.getSchemaDomain(schema))) {
                continue;
            }
            if (!normalizedDatasourceType.equals(normalize(metadataSchemaService.getSchemaDatasourceType(schema)))) {
                continue;
            }
            String metaModelCode = metadataSchemaService.getSchemaMetaModelCode(schema);
            if ("source".equalsIgnoreCase(metaModelCode)) {
                continue;
            }
            boolean activeSchema = sameId(schema.getId(), activeSchemaVersionId)
                    || sameId(schema.getCurrentVersionId(), activeSchemaVersionId);
            boolean multipleSchema = "MULTIPLE".equalsIgnoreCase(metadataSchemaService.getSchemaDisplayMode(schema));
            if (activeSchema || multipleSchema || (activeSchemaVersionId == null && "table".equalsIgnoreCase(metaModelCode))) {
                allowedCodes.add(metaModelCode);
            }
        }
        return allowedCodes;
    }

    private boolean sameId(Long left, Long right) {
        if (left == null || right == null) {
            return false;
        }
        return left.longValue() == right.longValue();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
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

