package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.DataModelQueryCondition;
import com.jdragon.studio.dto.model.request.DataModelQueryGroup;
import com.jdragon.studio.dto.model.request.DataModelQueryRequest;
import com.jdragon.studio.infra.entity.DataModelAttrIndexEntity;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataModelAttrIndexMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DataModelSearchIndexService {

    private static final String SINGLE_ITEM_KEY = "__single__";

    private final DataModelAttrIndexMapper indexMapper;
    private final MetadataSchemaService metadataSchemaService;
    private final BusinessMetaModelMetadataService businessMetaModelMetadataService;

    public DataModelSearchIndexService(DataModelAttrIndexMapper indexMapper,
                                       MetadataSchemaService metadataSchemaService,
                                       BusinessMetaModelMetadataService businessMetaModelMetadataService) {
        this.indexMapper = indexMapper;
        this.metadataSchemaService = metadataSchemaService;
        this.businessMetaModelMetadataService = businessMetaModelMetadataService;
    }

    @Transactional
    public void rebuildModelIndex(DataModelEntity model, DataSourceDefinition datasource) {
        if (model == null || model.getId() == null) {
            return;
        }
        deleteByModelId(model.getId());
        List<DataModelAttrIndexEntity> entries = buildIndexEntries(model, datasource);
        for (DataModelAttrIndexEntity entry : entries) {
            indexMapper.insert(entry);
        }
    }

    @Transactional
    public void deleteByModelId(Long modelId) {
        if (modelId == null) {
            return;
        }
        indexMapper.delete(new LambdaQueryWrapper<DataModelAttrIndexEntity>()
                .eq(DataModelAttrIndexEntity::getModelId, modelId));
    }

    @Transactional
    public void deleteByDatasourceId(Long datasourceId) {
        if (datasourceId == null) {
            return;
        }
        indexMapper.delete(new LambdaQueryWrapper<DataModelAttrIndexEntity>()
                .eq(DataModelAttrIndexEntity::getDatasourceId, datasourceId));
    }

    public Set<Long> queryModelIds(DataModelQueryRequest request) {
        Set<Long> matchedModelIds = null;
        if (request == null || request.getGroups() == null) {
            return new LinkedHashSet<Long>();
        }
        for (DataModelQueryGroup group : request.getGroups()) {
            Set<Long> groupMatched = matchGroup(group, request.getDatasourceId(), matchedModelIds);
            if (matchedModelIds == null) {
                matchedModelIds = groupMatched;
            } else {
                matchedModelIds.retainAll(groupMatched);
            }
            if (matchedModelIds == null || matchedModelIds.isEmpty()) {
                return new LinkedHashSet<Long>();
            }
        }
        return matchedModelIds == null ? new LinkedHashSet<Long>() : matchedModelIds;
    }

    private Set<Long> matchGroup(DataModelQueryGroup group,
                                 Long datasourceId,
                                 Set<Long> candidateModelIds) {
        MetadataSchemaDefinition schema = findSchemaByCode(group == null ? null : group.getMetaSchemaCode());
        if (schema == null) {
            return new LinkedHashSet<Long>();
        }
        List<MetadataFieldDefinition> searchableFields = searchableFields(schema);
        if (searchableFields.isEmpty()) {
            return new LinkedHashSet<Long>();
        }
        Map<String, MetadataFieldDefinition> fieldMap = new LinkedHashMap<String, MetadataFieldDefinition>();
        for (MetadataFieldDefinition field : searchableFields) {
            fieldMap.put(field.getFieldKey(), field);
        }
        List<DataModelQueryCondition> conditions = normalizeConditions(group, fieldMap);
        if (conditions.isEmpty()) {
            return new LinkedHashSet<Long>();
        }
        List<DataModelAttrIndexEntity> rows = loadGroupRows(group, schema, datasourceId, candidateModelIds, fieldMap.keySet());
        String displayMode = metadataSchemaService.getSchemaDisplayMode(schema);
        if ("MULTIPLE".equalsIgnoreCase(displayMode)
                && "SAME_ITEM".equalsIgnoreCase(group == null ? null : group.getRowMatchMode())) {
            return matchSameItem(rows, conditions, fieldMap);
        }
        return matchAnyItem(rows, conditions, fieldMap);
    }

    private List<DataModelAttrIndexEntity> loadGroupRows(DataModelQueryGroup group,
                                                         MetadataSchemaDefinition schema,
                                                         Long datasourceId,
                                                         Set<Long> candidateModelIds,
                                                         Set<String> fieldKeys) {
        LambdaQueryWrapper<DataModelAttrIndexEntity> queryWrapper = new LambdaQueryWrapper<DataModelAttrIndexEntity>()
                .eq(DataModelAttrIndexEntity::getMetaSchemaCode, schema.getSchemaCode())
                .eq(DataModelAttrIndexEntity::getScope, normalize(group == null || group.getScope() == null
                        ? metadataSchemaService.getSchemaDomain(schema)
                        : group.getScope()).toUpperCase())
                .in(DataModelAttrIndexEntity::getFieldKey, fieldKeys)
                .orderByAsc(DataModelAttrIndexEntity::getModelId)
                .orderByAsc(DataModelAttrIndexEntity::getItemKey);
        if (datasourceId != null) {
            queryWrapper.eq(DataModelAttrIndexEntity::getDatasourceId, datasourceId);
        }
        if (candidateModelIds != null) {
            if (candidateModelIds.isEmpty()) {
                return new ArrayList<DataModelAttrIndexEntity>();
            }
            queryWrapper.in(DataModelAttrIndexEntity::getModelId, candidateModelIds);
        }
        return indexMapper.selectList(queryWrapper);
    }

    private Set<Long> matchAnyItem(List<DataModelAttrIndexEntity> rows,
                                   List<DataModelQueryCondition> conditions,
                                   Map<String, MetadataFieldDefinition> fieldMap) {
        Set<Long> matchedModelIds = null;
        for (DataModelQueryCondition condition : conditions) {
            Set<Long> conditionMatched = new LinkedHashSet<Long>();
            for (DataModelAttrIndexEntity row : rows) {
                if (!condition.getFieldKey().equals(row.getFieldKey())) {
                    continue;
                }
                if (matches(row, condition, fieldMap.get(condition.getFieldKey()))) {
                    conditionMatched.add(row.getModelId());
                }
            }
            if (matchedModelIds == null) {
                matchedModelIds = conditionMatched;
            } else {
                matchedModelIds.retainAll(conditionMatched);
            }
            if (matchedModelIds == null || matchedModelIds.isEmpty()) {
                return new LinkedHashSet<Long>();
            }
        }
        return matchedModelIds == null ? new LinkedHashSet<Long>() : matchedModelIds;
    }

    private Set<Long> matchSameItem(List<DataModelAttrIndexEntity> rows,
                                    List<DataModelQueryCondition> conditions,
                                    Map<String, MetadataFieldDefinition> fieldMap) {
        Map<Long, Set<String>> matchedItems = null;
        for (DataModelQueryCondition condition : conditions) {
            Map<Long, Set<String>> conditionMatched = new LinkedHashMap<Long, Set<String>>();
            for (DataModelAttrIndexEntity row : rows) {
                if (!condition.getFieldKey().equals(row.getFieldKey())) {
                    continue;
                }
                if (!matches(row, condition, fieldMap.get(condition.getFieldKey()))) {
                    continue;
                }
                Set<String> itemKeys = conditionMatched.get(row.getModelId());
                if (itemKeys == null) {
                    itemKeys = new LinkedHashSet<String>();
                    conditionMatched.put(row.getModelId(), itemKeys);
                }
                itemKeys.add(row.getItemKey());
            }
            if (matchedItems == null) {
                matchedItems = conditionMatched;
            } else {
                Map<Long, Set<String>> next = new LinkedHashMap<Long, Set<String>>();
                for (Map.Entry<Long, Set<String>> entry : matchedItems.entrySet()) {
                    Set<String> currentKeys = entry.getValue();
                    Set<String> conditionKeys = conditionMatched.get(entry.getKey());
                    if (conditionKeys == null || conditionKeys.isEmpty()) {
                        continue;
                    }
                    Set<String> intersection = new LinkedHashSet<String>(currentKeys);
                    intersection.retainAll(conditionKeys);
                    if (!intersection.isEmpty()) {
                        next.put(entry.getKey(), intersection);
                    }
                }
                matchedItems = next;
            }
            if (matchedItems == null || matchedItems.isEmpty()) {
                return new LinkedHashSet<Long>();
            }
        }
        return matchedItems == null ? new LinkedHashSet<Long>() : new LinkedHashSet<Long>(matchedItems.keySet());
    }

    private boolean matches(DataModelAttrIndexEntity row,
                            DataModelQueryCondition condition,
                            MetadataFieldDefinition fieldDefinition) {
        String operator = normalizeOperator(condition, fieldDefinition);
        if ("LIKE".equals(operator)) {
            String expected = normalize(stringValue(condition.getValue()));
            return !expected.isEmpty() && row.getTextValue() != null && row.getTextValue().contains(expected);
        }
        if ("IN".equals(operator)) {
            for (Object value : normalizeValues(condition)) {
                if (matchesEquals(row, value, fieldDefinition)) {
                    return true;
                }
            }
            return false;
        }
        if ("BETWEEN".equals(operator)) {
            List<Object> values = normalizeValues(condition);
            if (values.size() < 2 || row.getNumberValue() == null) {
                return false;
            }
            BigDecimal lower = toBigDecimal(values.get(0));
            BigDecimal upper = toBigDecimal(values.get(1));
            if (lower == null || upper == null) {
                return false;
            }
            return row.getNumberValue().compareTo(lower) >= 0 && row.getNumberValue().compareTo(upper) <= 0;
        }
        if ("GT".equals(operator) || "GE".equals(operator) || "LT".equals(operator) || "LE".equals(operator)) {
            if (row.getNumberValue() == null) {
                return false;
            }
            BigDecimal expected = toBigDecimal(condition.getValue());
            if (expected == null) {
                return false;
            }
            int comparison = row.getNumberValue().compareTo(expected);
            if ("GT".equals(operator)) {
                return comparison > 0;
            }
            if ("GE".equals(operator)) {
                return comparison >= 0;
            }
            if ("LT".equals(operator)) {
                return comparison < 0;
            }
            return comparison <= 0;
        }
        return matchesEquals(row, condition.getValue(), fieldDefinition);
    }

    private boolean matchesEquals(DataModelAttrIndexEntity row,
                                  Object expectedValue,
                                  MetadataFieldDefinition fieldDefinition) {
        String valueType = normalize(row.getValueType());
        if ("boolean".equals(valueType)) {
            Boolean expected = toBoolean(expectedValue);
            return expected != null && row.getBoolValue() != null && row.getBoolValue().intValue() == (expected ? 1 : 0);
        }
        if ("integer".equals(valueType) || "long".equals(valueType) || "decimal".equals(valueType)) {
            BigDecimal expected = toBigDecimal(expectedValue);
            return expected != null && row.getNumberValue() != null && row.getNumberValue().compareTo(expected) == 0;
        }
        if (fieldDefinition != null && fieldDefinition.getValueType() != null) {
            switch (fieldDefinition.getValueType()) {
                case BOOLEAN:
                    Boolean expected = toBoolean(expectedValue);
                    return expected != null && row.getBoolValue() != null && row.getBoolValue().intValue() == (expected ? 1 : 0);
                case INTEGER:
                case LONG:
                case DECIMAL:
                    BigDecimal number = toBigDecimal(expectedValue);
                    return number != null && row.getNumberValue() != null && row.getNumberValue().compareTo(number) == 0;
                default:
                    break;
            }
        }
        return normalize(row.getKeywordValue()).equals(normalize(stringValue(expectedValue)));
    }

    private String normalizeOperator(DataModelQueryCondition condition, MetadataFieldDefinition fieldDefinition) {
        String operator = normalize(condition == null ? null : condition.getOperator()).toUpperCase();
        if (!operator.isEmpty()) {
            return operator;
        }
        if (fieldDefinition != null && fieldDefinition.getQueryDefaultOperator() != null) {
            return fieldDefinition.getQueryDefaultOperator().trim().toUpperCase();
        }
        return "EQ";
    }

    private List<Object> normalizeValues(DataModelQueryCondition condition) {
        List<Object> values = new ArrayList<Object>();
        if (condition == null) {
            return values;
        }
        if (condition.getValues() != null && !condition.getValues().isEmpty()) {
            values.addAll(condition.getValues());
            return values;
        }
        if (condition.getValue() != null) {
            values.add(condition.getValue());
        }
        return values;
    }

    private List<DataModelQueryCondition> normalizeConditions(DataModelQueryGroup group,
                                                              Map<String, MetadataFieldDefinition> fieldMap) {
        List<DataModelQueryCondition> conditions = new ArrayList<DataModelQueryCondition>();
        if (group == null || group.getConditions() == null) {
            return conditions;
        }
        for (DataModelQueryCondition condition : group.getConditions()) {
            if (condition == null || condition.getFieldKey() == null || condition.getFieldKey().trim().isEmpty()) {
                continue;
            }
            if (!fieldMap.containsKey(condition.getFieldKey())) {
                continue;
            }
            conditions.add(condition);
        }
        return conditions;
    }

    private List<DataModelAttrIndexEntity> buildIndexEntries(DataModelEntity model, DataSourceDefinition datasource) {
        List<DataModelAttrIndexEntity> entries = new ArrayList<DataModelAttrIndexEntity>();
        for (MetadataSchemaDefinition schema : resolveTechnicalSchemas(model, datasource)) {
            indexMetadata(entries, model, schema, model.getTechnicalMetadata());
        }
        for (BusinessMetaModelMetadataService.ResolvedBusinessMetaModelEntry entry
                : businessMetaModelMetadataService.resolveEntries(model.getBusinessMetadata())) {
            MetadataSchemaDefinition schema = entry.getSchema();
            if (entry.isMultiple()) {
                Map<String, Object> metadata = new LinkedHashMap<String, Object>();
                metadata.put(metadataSchemaService.getSchemaCollectionKey(schema), entry.getRows());
                indexMetadata(entries, model, schema, metadata);
            } else {
                indexMetadata(entries, model, schema, entry.getValues());
            }
        }
        return entries;
    }

    private List<MetadataSchemaDefinition> resolveTechnicalSchemas(DataModelEntity model, DataSourceDefinition datasource) {
        List<MetadataSchemaDefinition> result = new ArrayList<MetadataSchemaDefinition>();
        MetadataSchemaDefinition activeSchema = metadataSchemaService.findSchemaByVersionId(model.getSchemaVersionId());
        if (activeSchema != null
                && "TECHNICAL".equalsIgnoreCase(metadataSchemaService.getSchemaDomain(activeSchema))
                && "model".equalsIgnoreCase(activeSchema.getObjectType())
                && !"source".equalsIgnoreCase(metadataSchemaService.getSchemaMetaModelCode(activeSchema))) {
            result.add(activeSchema);
        }
        String datasourceType = datasource == null ? null : datasource.getTypeCode();
        if (datasourceType == null || datasourceType.trim().isEmpty()) {
            return result;
        }
        for (MetadataSchemaDefinition schema : metadataSchemaService.listSchemas()) {
            if (!"model".equalsIgnoreCase(schema.getObjectType())) {
                continue;
            }
            if (!"TECHNICAL".equalsIgnoreCase(metadataSchemaService.getSchemaDomain(schema))) {
                continue;
            }
            if (!normalize(datasourceType).equals(normalize(metadataSchemaService.getSchemaDatasourceType(schema)))) {
                continue;
            }
            if ("source".equalsIgnoreCase(metadataSchemaService.getSchemaMetaModelCode(schema))) {
                continue;
            }
            boolean isActiveSchema = sameId(schema.getId(), activeSchema == null ? null : activeSchema.getId())
                    || sameId(schema.getCurrentVersionId(), activeSchema == null ? null : activeSchema.getCurrentVersionId());
            boolean isMultipleSchema = "MULTIPLE".equalsIgnoreCase(metadataSchemaService.getSchemaDisplayMode(schema));
            if ((isActiveSchema || isMultipleSchema) && !containsSchema(result, schema)) {
                result.add(schema);
            }
        }
        return result;
    }

    private boolean containsSchema(List<MetadataSchemaDefinition> schemas, MetadataSchemaDefinition candidate) {
        for (MetadataSchemaDefinition schema : schemas) {
            if (sameId(schema.getId(), candidate.getId())
                    || sameId(schema.getCurrentVersionId(), candidate.getCurrentVersionId())
                    || normalize(schema.getSchemaCode()).equals(normalize(candidate.getSchemaCode()))) {
                return true;
            }
        }
        return false;
    }

    private void indexMetadata(List<DataModelAttrIndexEntity> entries,
                               DataModelEntity model,
                               MetadataSchemaDefinition schema,
                               Map<String, Object> metadata) {
        if (schema == null || metadata == null) {
            return;
        }
        List<MetadataFieldDefinition> searchableFields = searchableFields(schema);
        if (searchableFields.isEmpty()) {
            return;
        }
        String scope = metadataSchemaService.getSchemaDomain(schema);
        String metaModelCode = metadataSchemaService.getSchemaMetaModelCode(schema);
        String displayMode = metadataSchemaService.getSchemaDisplayMode(schema);
        if ("MULTIPLE".equalsIgnoreCase(displayMode)) {
            String collectionKey = metadataSchemaService.getSchemaCollectionKey(schema);
            List<Map<String, Object>> rows = parseRows(metadata.get(collectionKey));
            for (int index = 0; index < rows.size(); index++) {
                Map<String, Object> row = rows.get(index);
                String itemKey = collectionKey + ":" + index;
                for (MetadataFieldDefinition field : searchableFields) {
                    appendEntry(entries, model, schema, scope, metaModelCode, itemKey, field, row.get(field.getFieldKey()));
                }
            }
            return;
        }
        for (MetadataFieldDefinition field : searchableFields) {
            appendEntry(entries, model, schema, scope, metaModelCode, SINGLE_ITEM_KEY, field, metadata.get(field.getFieldKey()));
        }
    }

    private void appendEntry(List<DataModelAttrIndexEntity> entries,
                             DataModelEntity model,
                             MetadataSchemaDefinition schema,
                             String scope,
                             String metaModelCode,
                             String itemKey,
                             MetadataFieldDefinition field,
                             Object value) {
        if (field == null || value == null) {
            return;
        }
        String rawValue = stringValue(value);
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return;
        }
        DataModelAttrIndexEntity entry = new DataModelAttrIndexEntity();
        Boolean booleanValue = toBoolean(value);
        entry.setModelId(model.getId());
        entry.setDatasourceId(model.getDatasourceId());
        entry.setMetaSchemaVersionId(schema.getCurrentVersionId());
        entry.setMetaSchemaCode(schema.getSchemaCode());
        entry.setScope(scope == null ? null : scope.toUpperCase());
        entry.setMetaModelCode(metaModelCode);
        entry.setItemKey(itemKey);
        entry.setFieldKey(field.getFieldKey());
        entry.setValueType(field.getValueType() == null ? null : field.getValueType().name());
        entry.setKeywordValue(rawValue);
        entry.setTextValue(normalize(rawValue));
        entry.setNumberValue(toBigDecimal(value));
        entry.setBoolValue(booleanValue == null ? null : (booleanValue ? 1 : 0));
        entry.setRawValue(rawValue);
        entries.add(entry);
    }

    private List<Map<String, Object>> parseRows(Object value) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (!(value instanceof List)) {
            return rows;
        }
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                rows.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
            }
        }
        return rows;
    }

    private List<MetadataFieldDefinition> searchableFields(MetadataSchemaDefinition schema) {
        List<MetadataFieldDefinition> result = new ArrayList<MetadataFieldDefinition>();
        if (schema == null || schema.getFields() == null) {
            return result;
        }
        for (MetadataFieldDefinition field : schema.getFields()) {
            if (Boolean.TRUE.equals(field.getSearchable())) {
                result.add(field);
            }
        }
        return result;
    }

    private MetadataSchemaDefinition findSchemaByCode(String schemaCode) {
        if (schemaCode == null || schemaCode.trim().isEmpty()) {
            return null;
        }
        for (MetadataSchemaDefinition schema : metadataSchemaService.listSchemas()) {
            if (schemaCode.equalsIgnoreCase(schema.getSchemaCode())) {
                return schema;
            }
        }
        return null;
    }

    private boolean sameId(Long left, Long right) {
        if (left == null || right == null) {
            return false;
        }
        return left.longValue() == right.longValue();
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

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(String.valueOf(value));
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(text);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String text = normalize((String) value);
            if ("true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text)) {
                return true;
            }
            if ("false".equals(text) || "0".equals(text) || "no".equals(text) || "n".equals(text)) {
                return false;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
