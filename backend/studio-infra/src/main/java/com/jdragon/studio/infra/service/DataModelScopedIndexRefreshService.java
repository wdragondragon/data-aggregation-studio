package com.jdragon.studio.infra.service;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.entity.MetaSchemaVersionEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.MetaSchemaVersionMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DataModelScopedIndexRefreshService {

    private static final String META_MODEL_CONFIG_PREFIX = "META_MODEL_CONFIG:";
    private static final String META_MODELS_KEY = "__metaModels";

    private final DataModelMapper dataModelMapper;
    private final DatasourceMapper datasourceMapper;
    private final MetaSchemaVersionMapper versionMapper;
    private final DataModelSearchIndexService dataModelSearchIndexService;
    private final StudioSecurityService securityService;

    public DataModelScopedIndexRefreshService(DataModelMapper dataModelMapper,
                                              DatasourceMapper datasourceMapper,
                                              MetaSchemaVersionMapper versionMapper,
                                              @Lazy DataModelSearchIndexService dataModelSearchIndexService,
                                              StudioSecurityService securityService) {
        this.dataModelMapper = dataModelMapper;
        this.datasourceMapper = datasourceMapper;
        this.versionMapper = versionMapper;
        this.dataModelSearchIndexService = dataModelSearchIndexService;
        this.securityService = securityService;
    }

    public void scheduleScopedRebuild(MetadataSchemaDefinition previousSchema,
                                      MetadataSchemaDefinition currentSchema) {
        if (!requiresRebuild(previousSchema, currentSchema)) {
            return;
        }
        final String tenantId = securityService.currentTenantId();
        Runnable task = () -> rebuildImpactedIndexes(tenantId, previousSchema, currentSchema);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    private boolean requiresRebuild(MetadataSchemaDefinition previousSchema,
                                    MetadataSchemaDefinition currentSchema) {
        if (!isIndexRelevant(previousSchema) && !isIndexRelevant(currentSchema)) {
            return false;
        }
        if (!hasSearchableFields(previousSchema) && !hasSearchableFields(currentSchema)) {
            return false;
        }
        return !Objects.equals(buildIndexSignature(previousSchema), buildIndexSignature(currentSchema));
    }

    private void rebuildImpactedIndexes(String tenantId,
                                        MetadataSchemaDefinition previousSchema,
                                        MetadataSchemaDefinition currentSchema) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return;
        }
        Set<Long> impactedModelIds = new LinkedHashSet<Long>();
        impactedModelIds.addAll(resolveImpactedModelIds(tenantId, previousSchema));
        impactedModelIds.addAll(resolveImpactedModelIds(tenantId, currentSchema));
        if (impactedModelIds.isEmpty()) {
            return;
        }

        Map<Long, DatasourceEntity> datasourceMap = new LinkedHashMap<Long, DatasourceEntity>();
        List<DatasourceEntity> datasources = datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getTenantId, tenantId));
        for (DatasourceEntity datasource : datasources) {
            datasourceMap.put(datasource.getId(), datasource);
        }

        List<DataModelEntity> models = dataModelMapper.selectList(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getTenantId, tenantId)
                .in(DataModelEntity::getId, impactedModelIds)
                .orderByAsc(DataModelEntity::getId));
        for (DataModelEntity model : models) {
            DatasourceEntity datasource = model.getDatasourceId() == null ? null : datasourceMap.get(model.getDatasourceId());
            dataModelSearchIndexService.rebuildModelIndex(model, toDatasourceDefinition(datasource));
        }
    }

    private Set<Long> resolveImpactedModelIds(String tenantId,
                                              MetadataSchemaDefinition schema) {
        if (!isIndexRelevant(schema)) {
            return new LinkedHashSet<Long>();
        }
        String domain = resolveSchemaDomain(schema);
        if ("TECHNICAL".equalsIgnoreCase(domain)) {
            return resolveTechnicalModelIds(tenantId, schema);
        }
        return resolveBusinessModelIds(tenantId, schema);
    }

    private Set<Long> resolveTechnicalModelIds(String tenantId,
                                               MetadataSchemaDefinition schema) {
        if (!"model".equalsIgnoreCase(schema.getObjectType())) {
            return new LinkedHashSet<Long>();
        }
        String displayMode = resolveSchemaDisplayMode(schema);
        if ("MULTIPLE".equalsIgnoreCase(displayMode)) {
            return resolveModelIdsByDatasourceType(tenantId, resolveSchemaDatasourceType(schema));
        }
        Set<Long> versionIds = listSchemaVersionIds(schema.getId());
        if (schema.getCurrentVersionId() != null) {
            versionIds.add(schema.getCurrentVersionId());
        }
        return resolveModelIdsBySchemaVersions(tenantId, versionIds);
    }

    private Set<Long> resolveBusinessModelIds(String tenantId,
                                              MetadataSchemaDefinition schema) {
        Set<Long> versionIds = listSchemaVersionIds(schema.getId());
        if (schema.getCurrentVersionId() != null) {
            versionIds.add(schema.getCurrentVersionId());
        }
        String schemaCode = normalize(schema.getSchemaCode());
        List<DataModelEntity> models = dataModelMapper.selectList(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getTenantId, tenantId)
                .orderByAsc(DataModelEntity::getId));
        Set<Long> result = new LinkedHashSet<Long>();
        for (DataModelEntity model : models) {
            if (referencesBusinessSchema(model.getBusinessMetadata(), versionIds, schemaCode)) {
                result.add(model.getId());
            }
        }
        return result;
    }

    private Set<Long> resolveModelIdsBySchemaVersions(String tenantId,
                                                      Collection<Long> versionIds) {
        Set<Long> normalizedVersionIds = new LinkedHashSet<Long>();
        for (Long versionId : versionIds) {
            if (versionId != null) {
                normalizedVersionIds.add(versionId);
            }
        }
        if (normalizedVersionIds.isEmpty()) {
            return new LinkedHashSet<Long>();
        }
        List<DataModelEntity> models = dataModelMapper.selectList(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getTenantId, tenantId)
                .in(DataModelEntity::getSchemaVersionId, normalizedVersionIds)
                .orderByAsc(DataModelEntity::getId));
        Set<Long> result = new LinkedHashSet<Long>();
        for (DataModelEntity model : models) {
            result.add(model.getId());
        }
        return result;
    }

    private Set<Long> resolveModelIdsByDatasourceType(String tenantId,
                                                      String datasourceType) {
        String normalizedDatasourceType = normalize(datasourceType);
        if (normalizedDatasourceType.isEmpty()) {
            return new LinkedHashSet<Long>();
        }
        List<DatasourceEntity> datasources = datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getTenantId, tenantId)
                .orderByAsc(DatasourceEntity::getId));
        Set<Long> datasourceIds = new LinkedHashSet<Long>();
        for (DatasourceEntity datasource : datasources) {
            if (normalizedDatasourceType.equals(normalize(datasource.getTypeCode()))) {
                datasourceIds.add(datasource.getId());
            }
        }
        if (datasourceIds.isEmpty()) {
            return new LinkedHashSet<Long>();
        }
        List<DataModelEntity> models = dataModelMapper.selectList(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getTenantId, tenantId)
                .in(DataModelEntity::getDatasourceId, datasourceIds)
                .orderByAsc(DataModelEntity::getId));
        Set<Long> result = new LinkedHashSet<Long>();
        for (DataModelEntity model : models) {
            result.add(model.getId());
        }
        return result;
    }

    private boolean referencesBusinessSchema(Map<String, Object> metadata,
                                             Set<Long> versionIds,
                                             String schemaCode) {
        if (metadata == null) {
            return false;
        }
        Object rawEntries = metadata.get(META_MODELS_KEY);
        if (!(rawEntries instanceof List)) {
            return false;
        }
        for (Object candidate : (List<?>) rawEntries) {
            if (!(candidate instanceof Map)) {
                continue;
            }
            Map<?, ?> entry = (Map<?, ?>) candidate;
            Long schemaVersionId = parseLong(entry.get("schemaVersionId"));
            if (schemaVersionId != null && versionIds.contains(schemaVersionId)) {
                return true;
            }
            String entrySchemaCode = normalize(stringValue(entry.get("schemaCode")));
            if (!schemaCode.isEmpty() && schemaCode.equals(entrySchemaCode)) {
                return true;
            }
        }
        return false;
    }

    private Set<Long> listSchemaVersionIds(Long schemaId) {
        Set<Long> result = new LinkedHashSet<Long>();
        if (schemaId == null) {
            return result;
        }
        List<MetaSchemaVersionEntity> versions = versionMapper.selectList(new LambdaQueryWrapper<MetaSchemaVersionEntity>()
                .eq(MetaSchemaVersionEntity::getSchemaId, schemaId));
        for (MetaSchemaVersionEntity version : versions) {
            if (version.getId() != null) {
                result.add(version.getId());
            }
        }
        return result;
    }

    private boolean isIndexRelevant(MetadataSchemaDefinition schema) {
        if (schema == null) {
            return false;
        }
        String metaModelCode = resolveSchemaMetaModelCode(schema);
        if ("source".equalsIgnoreCase(metaModelCode)) {
            return false;
        }
        String domain = resolveSchemaDomain(schema);
        if ("TECHNICAL".equalsIgnoreCase(domain)) {
            return "model".equalsIgnoreCase(schema.getObjectType());
        }
        return "BUSINESS".equalsIgnoreCase(domain);
    }

    private boolean hasSearchableFields(MetadataSchemaDefinition schema) {
        if (schema == null || schema.getFields() == null) {
            return false;
        }
        for (MetadataFieldDefinition field : schema.getFields()) {
            if (Boolean.TRUE.equals(field.getSearchable())) {
                return true;
            }
        }
        return false;
    }

    private String buildIndexSignature(MetadataSchemaDefinition schema) {
        if (!isIndexRelevant(schema)) {
            return "";
        }
        List<String> fieldSignatures = new ArrayList<String>();
        if (schema.getFields() != null) {
            for (MetadataFieldDefinition field : schema.getFields()) {
                if (!Boolean.TRUE.equals(field.getSearchable())) {
                    continue;
                }
                fieldSignatures.add(normalize(field.getFieldKey()) + "|"
                        + normalize(field.getScope() == null ? null : field.getScope().name()) + "|"
                        + normalize(field.getValueType() == null ? null : field.getValueType().name()));
            }
        }
        java.util.Collections.sort(fieldSignatures);
        return normalize(schema.getSchemaCode()) + "#"
                + normalize(schema.getObjectType()) + "#"
                + normalize(resolveSchemaDomain(schema)) + "#"
                + normalize(resolveSchemaDatasourceType(schema)) + "#"
                + normalize(resolveSchemaMetaModelCode(schema)) + "#"
                + normalize(resolveSchemaDisplayMode(schema)) + "#"
                + String.join(",", fieldSignatures);
    }

    private DataSourceDefinition toDatasourceDefinition(DatasourceEntity datasource) {
        if (datasource == null) {
            return null;
        }
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(datasource.getId());
        definition.setTenantId(datasource.getTenantId());
        definition.setProjectId(datasource.getProjectId());
        definition.setName(datasource.getName());
        definition.setTypeCode(datasource.getTypeCode());
        definition.setSchemaVersionId(datasource.getSchemaVersionId());
        definition.setTechnicalMetadata(datasource.getTechnicalMetadata());
        definition.setBusinessMetadata(datasource.getBusinessMetadata());
        return definition;
    }

    private JSONObject extractMetaModelConfig(MetadataSchemaDefinition schema) {
        if (schema == null || schema.getDescription() == null) {
            return null;
        }
        String[] lines = schema.getDescription().split("\\r?\\n", 2);
        if (lines.length == 0 || !lines[0].startsWith(META_MODEL_CONFIG_PREFIX)) {
            return null;
        }
        try {
            return JSONObject.parseObject(lines[0].substring(META_MODEL_CONFIG_PREFIX.length()).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveSchemaDomain(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("domain") != null) {
            return config.getString("domain");
        }
        if ("business".equalsIgnoreCase(schema == null ? null : schema.getObjectType())
                || normalize(schema == null ? null : schema.getSchemaCode()).startsWith("business:")) {
            return "BUSINESS";
        }
        return "TECHNICAL";
    }

    private String resolveSchemaDatasourceType(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("datasourceType") != null) {
            return config.getString("datasourceType");
        }
        if (schema != null && schema.getSchemaCode() != null && schema.getSchemaCode().toLowerCase().startsWith("technical:")) {
            String[] parts = schema.getSchemaCode().split(":");
            if (parts.length > 1) {
                return parts[1];
            }
        }
        if (schema != null && "model".equalsIgnoreCase(schema.getObjectType()) && schema.getTypeCode() != null) {
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
        if (schema != null && schema.getSchemaCode() != null) {
            String[] parts = schema.getSchemaCode().split(":");
            if (parts.length > 2) {
                return parts[2];
            }
        }
        if (schema != null && "model".equalsIgnoreCase(schema.getObjectType()) && schema.getTypeCode() != null) {
            String[] parts = schema.getTypeCode().split("\\.");
            if (parts.length > 1) {
                return parts[1];
            }
        }
        return null;
    }

    private String resolveSchemaDisplayMode(MetadataSchemaDefinition schema) {
        JSONObject config = extractMetaModelConfig(schema);
        if (config != null && config.getString("displayMode") != null) {
            return config.getString("displayMode");
        }
        return "field".equalsIgnoreCase(resolveSchemaMetaModelCode(schema)) ? "MULTIPLE" : "SINGLE";
    }

    private Long parseLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
