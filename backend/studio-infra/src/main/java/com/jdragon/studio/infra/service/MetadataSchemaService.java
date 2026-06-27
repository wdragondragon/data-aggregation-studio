package com.jdragon.studio.infra.service;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.SchemaStatus;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.MetadataSchemaSaveRequest;
import com.jdragon.studio.core.spi.MetadataSchemaRegistry;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.entity.MetaFieldDefinitionEntity;
import com.jdragon.studio.infra.entity.MetaSchemaEntity;
import com.jdragon.studio.infra.entity.MetaSchemaVersionEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.MetaFieldDefinitionMapper;
import com.jdragon.studio.infra.mapper.MetaSchemaMapper;
import com.jdragon.studio.infra.mapper.MetaSchemaVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MetadataSchemaService implements MetadataSchemaRegistry {

    private final MetaSchemaMapper schemaMapper;
    private final MetaSchemaVersionMapper versionMapper;
    private final MetaFieldDefinitionMapper fieldDefinitionMapper;
    private final DatasourceMapper datasourceMapper;
    private final DataModelMapper dataModelMapper;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final DataModelScopedIndexRefreshService dataModelScopedIndexRefreshService;
    private final TechnicalMetadataFieldBuilder technicalFieldBuilder = new TechnicalMetadataFieldBuilder();

    public MetadataSchemaService(MetaSchemaMapper schemaMapper,
                                 MetaSchemaVersionMapper versionMapper,
                                 MetaFieldDefinitionMapper fieldDefinitionMapper,
                                 DatasourceMapper datasourceMapper,
                                 DataModelMapper dataModelMapper,
                                 DatasourceTypeCapabilityService datasourceTypeCapabilityService,
                                 DataModelScopedIndexRefreshService dataModelScopedIndexRefreshService) {
        this.schemaMapper = schemaMapper;
        this.versionMapper = versionMapper;
        this.fieldDefinitionMapper = fieldDefinitionMapper;
        this.datasourceMapper = datasourceMapper;
        this.dataModelMapper = dataModelMapper;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.dataModelScopedIndexRefreshService = dataModelScopedIndexRefreshService;
    }

    @Override
    public List<MetadataSchemaDefinition> listSchemas() {
        return listSchemas(true);
    }

    public List<MetadataSchemaDefinition> listSchemas(boolean includeFields) {
        List<MetaSchemaEntity> schemas = schemaMapper.selectList(new LambdaQueryWrapper<MetaSchemaEntity>()
                .orderByAsc(MetaSchemaEntity::getSchemaCode));
        return toDefinitions(schemas, includeFields);
    }

    public MetadataSchemaDefinition getSchema(Long schemaId) {
        MetaSchemaEntity schema = schemaMapper.selectById(schemaId);
        if (schema == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Schema not found: " + schemaId);
        }
        return toDefinition(schema);
    }

    public List<MetadataSchemaDefinition> listSchemasWithFieldsByIds(Collection<Long> schemaIds) {
        if (schemaIds == null || schemaIds.isEmpty()) {
            return new ArrayList<MetadataSchemaDefinition>();
        }
        List<MetaSchemaEntity> schemas = schemaMapper.selectList(new LambdaQueryWrapper<MetaSchemaEntity>()
                .in(MetaSchemaEntity::getId, schemaIds)
                .orderByAsc(MetaSchemaEntity::getSchemaCode));
        return toDefinitions(schemas, true);
    }

    @Override
    @Transactional
    public MetadataSchemaDefinition saveDraft(MetadataSchemaSaveRequest request) {
        MetaSchemaEntity schema = request.getSchemaId() == null ? new MetaSchemaEntity() : schemaMapper.selectById(request.getSchemaId());
        MetadataSchemaDefinition previousDefinition = schema == null || schema.getId() == null ? null : toDefinition(schema);
        if (schema == null) {
            schema = new MetaSchemaEntity();
        }
        schema.setSchemaCode(request.getSchemaCode());
        schema.setSchemaName(request.getSchemaName());
        schema.setObjectType(request.getObjectType());
        schema.setTypeCode(request.getTypeCode());
        schema.setDescription(request.getDescription());
        schema.setStatus(SchemaStatus.DRAFT.name());
        if (schema.getId() == null) {
            schemaMapper.insert(schema);
        } else {
            schemaMapper.updateById(schema);
        }

        int nextVersion = nextVersion(schema.getId());
        MetaSchemaVersionEntity version = new MetaSchemaVersionEntity();
        version.setSchemaId(schema.getId());
        version.setVersionNumber(nextVersion);
        version.setStatus(SchemaStatus.DRAFT.name());
        version.setDescription(request.getDescription());
        versionMapper.insert(version);

        for (MetadataFieldDefinition field : request.getFields()) {
            MetaFieldDefinitionEntity entity = new MetaFieldDefinitionEntity();
            entity.setSchemaVersionId(version.getId());
            entity.setFieldKey(field.getFieldKey());
            entity.setFieldName(field.getFieldName());
            entity.setDescription(field.getDescription());
            entity.setScope(field.getScope() == null ? null : field.getScope().name());
            entity.setValueType(field.getValueType() == null ? null : field.getValueType().name());
            entity.setComponentType(field.getComponentType() == null ? null : field.getComponentType().name());
            entity.setRequiredFlag(Boolean.TRUE.equals(field.getRequired()) ? 1 : 0);
            entity.setSensitiveFlag(Boolean.TRUE.equals(field.getSensitive()) ? 1 : 0);
            entity.setSortOrder(field.getSortOrder());
            entity.setValidationRule(field.getValidationRule());
            entity.setPlaceholder(field.getPlaceholder());
            entity.setDefaultValue(field.getDefaultValue());
            entity.setSearchableFlag(Boolean.TRUE.equals(field.getSearchable()) ? 1 : 0);
            entity.setSortableFlag(Boolean.TRUE.equals(field.getSortable()) ? 1 : 0);
            entity.setQueryOperators(field.getQueryOperators() == null ? new ArrayList<String>() : field.getQueryOperators());
            entity.setQueryDefaultOperator(field.getQueryDefaultOperator());
            entity.setOptions(field.getOptions() == null ? new ArrayList<String>() : field.getOptions());
            fieldDefinitionMapper.insert(entity);
        }

        schema.setCurrentVersionId(version.getId());
        schemaMapper.updateById(schema);
        MetadataSchemaDefinition currentDefinition = toDefinition(schema);
        dataModelScopedIndexRefreshService.scheduleScopedRebuild(previousDefinition, currentDefinition);
        return currentDefinition;
    }

    @Override
    @Transactional
    public MetadataSchemaDefinition publish(Long schemaId) {
        MetaSchemaEntity schema = schemaMapper.selectById(schemaId);
        if (schema == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Schema not found: " + schemaId);
        }
        MetaSchemaVersionEntity version = versionMapper.selectById(schema.getCurrentVersionId());
        if (version == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Current draft version not found");
        }
        version.setStatus(SchemaStatus.PUBLISHED.name());
        versionMapper.updateById(version);
        schema.setStatus(SchemaStatus.PUBLISHED.name());
        schemaMapper.updateById(schema);
        return toDefinition(schema);
    }

    @Transactional
    public void delete(Long schemaId) {
        MetaSchemaEntity schema = schemaMapper.selectById(schemaId);
        if (schema == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Schema not found: " + schemaId);
        }
        List<MetaSchemaVersionEntity> versions = versionMapper.selectList(new LambdaQueryWrapper<MetaSchemaVersionEntity>()
                .eq(MetaSchemaVersionEntity::getSchemaId, schemaId));
        List<Long> versionIds = new ArrayList<Long>();
        for (MetaSchemaVersionEntity version : versions) {
            versionIds.add(version.getId());
        }
        if (!versionIds.isEmpty()) {
            Long datasourceReferences = datasourceMapper.selectCount(new LambdaQueryWrapper<DatasourceEntity>()
                    .in(DatasourceEntity::getSchemaVersionId, versionIds));
            Long modelReferences = dataModelMapper.selectCount(new LambdaQueryWrapper<DataModelEntity>()
                    .in(DataModelEntity::getSchemaVersionId, versionIds));
            if ((datasourceReferences != null && datasourceReferences > 0)
                    || (modelReferences != null && modelReferences > 0)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Schema is referenced by datasource or model instances");
            }
            fieldDefinitionMapper.delete(new LambdaQueryWrapper<MetaFieldDefinitionEntity>()
                    .in(MetaFieldDefinitionEntity::getSchemaVersionId, versionIds));
        }
        versionMapper.delete(new LambdaQueryWrapper<MetaSchemaVersionEntity>()
                .eq(MetaSchemaVersionEntity::getSchemaId, schemaId));
        schemaMapper.deleteById(schemaId);
    }

    @Transactional
    public List<MetadataSchemaDefinition> syncAllTechnicalMetaModels() {
        List<MetadataSchemaDefinition> result = new ArrayList<MetadataSchemaDefinition>();
        for (String typeCode : datasourceTypeCapabilityService.sourceTypes()) {
            result.addAll(syncTechnicalMetaModels(typeCode));
        }
        return result;
    }

    @Transactional
    public List<MetadataSchemaDefinition> syncTechnicalMetaModels(String typeCode) {
        if (typeCode == null || typeCode.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource type is required");
        }
        List<MetadataSchemaDefinition> synced = new ArrayList<MetadataSchemaDefinition>();
        synced.add(ensureTechnicalMetaModel(typeCode, "source"));
        synced.add(ensureTechnicalMetaModel(typeCode, "table"));
        synced.add(ensureTechnicalMetaModel(typeCode, "field"));
        return synced;
    }

    public MetadataSchemaDefinition findSchemaByVersionId(Long schemaVersionId) {
        if (schemaVersionId == null) {
            return null;
        }
        MetaSchemaVersionEntity version = versionMapper.selectById(schemaVersionId);
        if (version != null && version.getSchemaId() != null) {
            MetaSchemaEntity schema = schemaMapper.selectById(version.getSchemaId());
            if (schema != null) {
                return toDefinition(schema);
            }
        }
        for (MetadataSchemaDefinition schema : listSchemas()) {
            if (schemaVersionId.equals(schema.getCurrentVersionId()) || schemaVersionId.equals(schema.getId())) {
                return schema;
            }
        }
        return null;
    }

    public MetadataSchemaDefinition findTechnicalMetaModel(String datasourceType, String metaModelCode) {
        if (datasourceType == null || datasourceType.trim().isEmpty()
                || metaModelCode == null || metaModelCode.trim().isEmpty()) {
            return null;
        }
        String expectedSchemaCode = "technical:" + datasourceType.trim() + ":" + metaModelCode.trim();
        for (MetadataSchemaDefinition schema : listSchemas()) {
            JSONObject config = extractMetaModelConfig(schema);
            if (config == null) {
                continue;
            }
            if (!"TECHNICAL".equalsIgnoreCase(config.getString("domain"))) {
                continue;
            }
            if (!normalize(datasourceType).equals(normalize(config.getString("datasourceType")))) {
                continue;
            }
            if (!normalize(metaModelCode).equals(normalize(config.getString("metaModelCode")))) {
                continue;
            }
            if (expectedSchemaCode.equalsIgnoreCase(schema.getSchemaCode())) {
                return schema;
            }
        }
        return null;
    }

    public MetadataSchemaDefinition findCurrentSchema(String objectType, String typeCode) {
        if (objectType == null || typeCode == null) {
            return null;
        }
        for (MetadataSchemaDefinition schema : listSchemas()) {
            if (!objectType.equalsIgnoreCase(schema.getObjectType())) {
                continue;
            }
            if (typeCode.equalsIgnoreCase(schema.getTypeCode())) {
                return schema;
            }
        }
        return null;
    }

    public MetadataSchemaDefinition findRuntimeOptionSchema(String role, String pluginType) {
        String normalizedRole = normalize(role);
        String normalizedPluginType = normalize(pluginType);
        if (normalizedRole.isEmpty() || normalizedPluginType.isEmpty()) {
            return null;
        }
        String expectedTypeCode = normalizedRole + ":" + normalizedPluginType;
        String expectedSchemaCode = "runtime:" + normalizedRole + ":" + normalizedPluginType;
        for (MetadataSchemaDefinition schema : listSchemas()) {
            if (schema == null) {
                continue;
            }
            String schemaCode = normalize(schema.getSchemaCode());
            String objectType = normalize(schema.getObjectType());
            String typeCode = normalize(schema.getTypeCode());
            if (expectedSchemaCode.equals(schemaCode)) {
                return schema;
            }
            if ("collection-runtime-option".equals(objectType) && expectedTypeCode.equals(typeCode)) {
                return schema;
            }
            JSONObject config = extractMetaModelConfig(schema);
            if (config == null || !"RUNTIME".equalsIgnoreCase(config.getString("domain"))) {
                continue;
            }
            if (normalizedRole.equals(normalize(config.getString("role")))
                    && normalizedPluginType.equals(normalize(config.getString("pluginType")))) {
                return schema;
            }
        }
        return null;
    }

    private int nextVersion(Long schemaId) {
        List<MetaSchemaVersionEntity> versions = versionMapper.selectList(new LambdaQueryWrapper<MetaSchemaVersionEntity>()
                .eq(MetaSchemaVersionEntity::getSchemaId, schemaId));
        int max = 0;
        for (MetaSchemaVersionEntity version : versions) {
            if (version.getVersionNumber() != null && version.getVersionNumber() > max) {
                max = version.getVersionNumber();
            }
        }
        return max + 1;
    }

    private MetadataSchemaDefinition ensureTechnicalMetaModel(String datasourceType, String metaModelCode) {
        MetadataSchemaDefinition existing = findTechnicalMetaModel(datasourceType, metaModelCode);
        if (existing != null && !needsTechnicalMetaModelRefresh(existing, datasourceType, metaModelCode)) {
            return existing;
        }
        return saveDraft(buildTechnicalMetaModelDraft(datasourceType, metaModelCode));
    }

    private boolean needsTechnicalMetaModelRefresh(MetadataSchemaDefinition existing,
                                                   String datasourceType,
                                                   String metaModelCode) {
        if (existing == null || existing.getFields() == null || existing.getFields().isEmpty()) {
            return true;
        }
        List<MetadataFieldDefinition> expectedFields = technicalFieldBuilder.buildTechnicalFields(datasourceType, metaModelCode);
        if (existing.getFields().size() != expectedFields.size()) {
            return true;
        }
        for (int index = 0; index < expectedFields.size(); index++) {
            if (!sameFieldDefinition(existing.getFields().get(index), expectedFields.get(index))) {
                return true;
            }
        }
        return false;
    }

    private MetadataSchemaSaveRequest buildTechnicalMetaModelDraft(String datasourceType, String metaModelCode) {
        String normalizedType = datasourceType.trim();
        MetadataSchemaDefinition existing = findExistingTechnicalSchema(normalizedType, metaModelCode);
        MetadataSchemaSaveRequest request = new MetadataSchemaSaveRequest();
        request.setSchemaId(existing == null ? null : existing.getId());
        request.setSchemaCode("technical:" + normalizedType + ":" + metaModelCode);
        request.setSchemaName(buildTechnicalSchemaName(normalizedType, metaModelCode));
        request.setObjectType("source".equalsIgnoreCase(metaModelCode) ? "datasource" : "model");
        request.setTypeCode("source".equalsIgnoreCase(metaModelCode) ? normalizedType : normalizedType + "." + metaModelCode);
        request.setDescription(encodeMetaModelDescription(baseTechnicalDescription(normalizedType, metaModelCode),
                "TECHNICAL",
                normalizedType,
                null,
                null,
                metaModelCode,
                toMetaModelName(metaModelCode),
                "field".equalsIgnoreCase(metaModelCode) ? "MULTIPLE" : "SINGLE",
                true,
                resolveSyncStrategy(metaModelCode)));
        request.setFields(technicalFieldBuilder.buildTechnicalFields(normalizedType, metaModelCode));
        return request;
    }

    private MetadataSchemaDefinition findExistingTechnicalSchema(String datasourceType, String metaModelCode) {
        return findTechnicalMetaModel(datasourceType, metaModelCode);
    }

    private String buildTechnicalSchemaName(String datasourceType, String metaModelCode) {
        return datasourceType.toUpperCase() + " " + toMetaModelName(metaModelCode);
    }

    private String toMetaModelName(String metaModelCode) {
        if ("source".equalsIgnoreCase(metaModelCode)) {
            return "数据源信息";
        }
        if ("table".equalsIgnoreCase(metaModelCode)) {
            return "表信息";
        }
        if ("field".equalsIgnoreCase(metaModelCode)) {
            return "字段信息";
        }
        if (metaModelCode == null || metaModelCode.trim().isEmpty()) {
            return "元模型";
        }
        return metaModelCode.endsWith("信息") ? metaModelCode : metaModelCode + "信息";
    }

    private String resolveSyncStrategy(String metaModelCode) {
        if ("source".equalsIgnoreCase(metaModelCode)) {
            return "DATASOURCE_CONNECTION";
        }
        if ("field".equalsIgnoreCase(metaModelCode)) {
            return "COLUMN_DISCOVERY";
        }
        return "OBJECT_DISCOVERY";
    }

    private String baseTechnicalDescription(String datasourceType, String metaModelCode) {
        return "用于采集 " + datasourceType.toUpperCase() + " " + toMetaModelName(metaModelCode) + " 的技术元模型定义。";
    }

    private String encodeMetaModelDescription(String plainDescription,
                                              String domain,
                                              String datasourceType,
                                              String directoryCode,
                                              String directoryName,
                                              String metaModelCode,
                                              String metaModelName,
                                              String displayMode,
                                              boolean required,
                                              String syncStrategy) {
        JSONObject config = new JSONObject(true);
        config.put("domain", domain);
        config.put("datasourceType", datasourceType);
        config.put("directoryCode", directoryCode);
        config.put("directoryName", directoryName);
        config.put("metaModelCode", metaModelCode);
        config.put("metaModelName", metaModelName);
        config.put("displayMode", displayMode);
        config.put("required", required);
        config.put("syncStrategy", syncStrategy);
        return MetaModelConfigDescriptions.encode(config, plainDescription);
    }

    private boolean sameFieldDefinition(MetadataFieldDefinition actual, MetadataFieldDefinition expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        return Objects.equals(actual.getFieldKey(), expected.getFieldKey())
                && Objects.equals(actual.getFieldName(), expected.getFieldName())
                && Objects.equals(actual.getDescription(), expected.getDescription())
                && Objects.equals(actual.getScope(), expected.getScope())
                && Objects.equals(actual.getValueType(), expected.getValueType())
                && Objects.equals(actual.getComponentType(), expected.getComponentType())
                && Objects.equals(actual.getRequired(), expected.getRequired())
                && Objects.equals(actual.getSensitive(), expected.getSensitive())
                && Objects.equals(actual.getSortOrder(), expected.getSortOrder())
                && Objects.equals(actual.getValidationRule(), expected.getValidationRule())
                && Objects.equals(actual.getPlaceholder(), expected.getPlaceholder())
                && Objects.equals(actual.getDefaultValue(), expected.getDefaultValue())
                && Objects.equals(actual.getSearchable(), expected.getSearchable())
                && Objects.equals(actual.getSortable(), expected.getSortable())
                && sameList(actual.getQueryOperators(), expected.getQueryOperators())
                && Objects.equals(actual.getQueryDefaultOperator(), expected.getQueryDefaultOperator())
                && sameList(actual.getOptions(), expected.getOptions());
    }

    private boolean sameList(List<String> left, List<String> right) {
        if (left == null || left.isEmpty()) {
            return right == null || right.isEmpty();
        }
        if (right == null || right.isEmpty()) {
            return false;
        }
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!Objects.equals(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String resolveSchemaDomain(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("domain") != null) {
            return config.getString("domain");
        }
        if ("business".equalsIgnoreCase(schema.getObjectType())
                || (schema.getSchemaCode() != null && schema.getSchemaCode().toLowerCase().startsWith("business:"))) {
            return "BUSINESS";
        }
        return "TECHNICAL";
    }

    private String resolveSchemaDatasourceType(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("datasourceType") != null) {
            return config.getString("datasourceType");
        }
        if (schema.getSchemaCode() != null && schema.getSchemaCode().toLowerCase().startsWith("technical:")) {
            String[] parts = schema.getSchemaCode().split(":");
            if (parts.length > 1) {
                return parts[1];
            }
        }
        if ("datasource".equalsIgnoreCase(schema.getObjectType())) {
            return schema.getTypeCode();
        }
        if ("model".equalsIgnoreCase(schema.getObjectType()) && schema.getTypeCode() != null) {
            String[] parts = schema.getTypeCode().split("\\.");
            if (parts.length > 0) {
                return parts[0];
            }
        }
        return null;
    }

    private String resolveSchemaMetaModelCode(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("metaModelCode") != null) {
            return config.getString("metaModelCode");
        }
        if (schema.getSchemaCode() != null) {
            String[] parts = schema.getSchemaCode().split(":");
            if (parts.length > 2) {
                return parts[2];
            }
        }
        if ("datasource".equalsIgnoreCase(schema.getObjectType())) {
            return "source";
        }
        if ("model".equalsIgnoreCase(schema.getObjectType()) && schema.getTypeCode() != null) {
            String[] parts = schema.getTypeCode().split("\\.");
            if (parts.length > 1 && parts[1] != null && !parts[1].trim().isEmpty()) {
                return parts[1];
            }
            return "table";
        }
        return null;
    }

    public String getSchemaDomain(MetadataSchemaDefinition schema) {
        return resolveSchemaDomain(schema);
    }

    public String getSchemaDatasourceType(MetadataSchemaDefinition schema) {
        return resolveSchemaDatasourceType(schema);
    }

    public String getSchemaMetaModelCode(MetadataSchemaDefinition schema) {
        return resolveSchemaMetaModelCode(schema);
    }

    public String getSchemaDirectoryCode(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("directoryCode") != null) {
            return config.getString("directoryCode");
        }
        if (schema != null && schema.getSchemaCode() != null && schema.getSchemaCode().toLowerCase().startsWith("business:")) {
            String[] parts = schema.getSchemaCode().split(":");
            if (parts.length > 1) {
                return parts[1];
            }
        }
        return null;
    }

    public String getSchemaDirectoryName(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("directoryName") != null) {
            return config.getString("directoryName");
        }
        return getSchemaDirectoryCode(schema);
    }

    public String getSchemaDisplayMode(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("displayMode") != null) {
            return config.getString("displayMode");
        }
        return "field".equalsIgnoreCase(resolveSchemaMetaModelCode(schema)) ? "MULTIPLE" : "SINGLE";
    }

    public String getSchemaCollectionKey(MetadataSchemaDefinition schema) {
        String metaModelCode = resolveSchemaMetaModelCode(schema);
        if ("field".equalsIgnoreCase(metaModelCode)) {
            return "columns";
        }
        if (metaModelCode == null || metaModelCode.trim().isEmpty()) {
            return "items";
        }
        return metaModelCode.endsWith("s") ? metaModelCode : metaModelCode + "s";
    }

    private JSONObject extractMetaModelConfig(MetadataSchemaDefinition schema) {
        if (schema == null || schema.getDescription() == null) {
            return null;
        }
        return MetaModelConfigDescriptions.decode(schema.getDescription());
    }

    private List<MetadataSchemaDefinition> toDefinitions(List<MetaSchemaEntity> schemas, boolean includeFields) {
        if (schemas == null || schemas.isEmpty()) {
            return new ArrayList<MetadataSchemaDefinition>();
        }
        List<Long> versionIds = new ArrayList<Long>();
        for (MetaSchemaEntity schema : schemas) {
            if (schema.getCurrentVersionId() != null) {
                versionIds.add(schema.getCurrentVersionId());
            }
        }
        Map<Long, MetaSchemaVersionEntity> versionMap = new HashMap<Long, MetaSchemaVersionEntity>();
        if (!versionIds.isEmpty()) {
            List<MetaSchemaVersionEntity> versions = versionMapper.selectList(new LambdaQueryWrapper<MetaSchemaVersionEntity>()
                    .in(MetaSchemaVersionEntity::getId, versionIds));
            for (MetaSchemaVersionEntity version : versions) {
                versionMap.put(version.getId(), version);
            }
        }
        Map<Long, List<MetaFieldDefinitionEntity>> fieldsByVersionId = new HashMap<Long, List<MetaFieldDefinitionEntity>>();
        if (includeFields && !versionIds.isEmpty()) {
            List<MetaFieldDefinitionEntity> fields = fieldDefinitionMapper.selectList(new LambdaQueryWrapper<MetaFieldDefinitionEntity>()
                    .in(MetaFieldDefinitionEntity::getSchemaVersionId, versionIds)
                    .orderByAsc(MetaFieldDefinitionEntity::getSchemaVersionId)
                    .orderByAsc(MetaFieldDefinitionEntity::getSortOrder));
            for (MetaFieldDefinitionEntity field : fields) {
                List<MetaFieldDefinitionEntity> versionFields = fieldsByVersionId.get(field.getSchemaVersionId());
                if (versionFields == null) {
                    versionFields = new ArrayList<MetaFieldDefinitionEntity>();
                    fieldsByVersionId.put(field.getSchemaVersionId(), versionFields);
                }
                versionFields.add(field);
            }
        }
        List<MetadataSchemaDefinition> result = new ArrayList<MetadataSchemaDefinition>();
        for (MetaSchemaEntity schema : schemas) {
            MetaSchemaVersionEntity version = schema.getCurrentVersionId() == null ? null : versionMap.get(schema.getCurrentVersionId());
            List<MetaFieldDefinitionEntity> fields = version == null || !includeFields
                    ? Collections.emptyList()
                    : fieldsByVersionId.get(version.getId());
            result.add(toDefinition(schema, version, fields, includeFields));
        }
        return result;
    }

    private MetadataSchemaDefinition toDefinition(MetaSchemaEntity schema) {
        List<MetadataSchemaDefinition> definitions = toDefinitions(Collections.singletonList(schema), true);
        return definitions.isEmpty() ? null : definitions.get(0);
    }

    private MetadataSchemaDefinition toDefinition(MetaSchemaEntity schema,
                                                  MetaSchemaVersionEntity version,
                                                  List<MetaFieldDefinitionEntity> fields,
                                                  boolean includeFields) {
        MetadataSchemaDefinition definition = new MetadataSchemaDefinition();
        definition.setId(schema.getId());
        definition.setSchemaCode(schema.getSchemaCode());
        definition.setSchemaName(schema.getSchemaName());
        definition.setObjectType(schema.getObjectType());
        definition.setTypeCode(schema.getTypeCode());
        definition.setCurrentVersionId(schema.getCurrentVersionId());
        definition.setStatus(schema.getStatus() == null ? null : SchemaStatus.valueOf(schema.getStatus()));
        definition.setDescription(schema.getDescription());
        if (version != null) {
            definition.setVersionNumber(version.getVersionNumber());
        }
        if (includeFields && version != null) {
            List<MetadataFieldDefinition> fieldDefinitions = new ArrayList<MetadataFieldDefinition>();
            for (MetaFieldDefinitionEntity field : fields == null ? Collections.<MetaFieldDefinitionEntity>emptyList() : fields) {
                MetadataFieldDefinition fieldDefinition = new MetadataFieldDefinition();
                fieldDefinition.setFieldKey(field.getFieldKey());
                fieldDefinition.setFieldName(field.getFieldName());
                fieldDefinition.setDescription(field.getDescription());
                fieldDefinition.setRequired(field.getRequiredFlag() != null && field.getRequiredFlag() == 1);
                fieldDefinition.setSensitive(field.getSensitiveFlag() != null && field.getSensitiveFlag() == 1);
                fieldDefinition.setSortOrder(field.getSortOrder());
                fieldDefinition.setValidationRule(field.getValidationRule());
                fieldDefinition.setPlaceholder(field.getPlaceholder());
                fieldDefinition.setDefaultValue(field.getDefaultValue());
                fieldDefinition.setSearchable(field.getSearchableFlag() != null && field.getSearchableFlag() == 1);
                fieldDefinition.setSortable(field.getSortableFlag() != null && field.getSortableFlag() == 1);
                fieldDefinition.setQueryOperators(field.getQueryOperators() == null ? new ArrayList<String>() : field.getQueryOperators());
                fieldDefinition.setQueryDefaultOperator(field.getQueryDefaultOperator());
                fieldDefinition.setOptions(field.getOptions() == null ? new ArrayList<String>() : field.getOptions());
                if (field.getScope() != null) {
                    fieldDefinition.setScope(com.jdragon.studio.dto.enums.MetadataScope.valueOf(field.getScope()));
                }
                if (field.getValueType() != null) {
                    fieldDefinition.setValueType(com.jdragon.studio.dto.enums.FieldValueType.valueOf(field.getValueType()));
                }
                if (field.getComponentType() != null) {
                    fieldDefinition.setComponentType(com.jdragon.studio.dto.enums.FieldComponentType.valueOf(field.getComponentType()));
                }
                fieldDefinitions.add(fieldDefinition);
            }
            definition.setFields(fieldDefinitions);
        }
        return definition;
    }
}

