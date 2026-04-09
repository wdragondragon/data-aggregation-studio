package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.dto.model.DataModelStatisticsBucketView;
import com.jdragon.studio.dto.model.DataModelStatisticsView;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.DataModelQueryCondition;
import com.jdragon.studio.dto.model.request.DataModelQueryGroup;
import com.jdragon.studio.dto.model.request.DataModelQueryRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsBucketConfig;
import com.jdragon.studio.dto.model.request.DataModelStatisticsRequest;
import com.jdragon.studio.infra.entity.DataModelAttrIndexEntity;
import com.jdragon.studio.infra.mapper.DataModelAttrIndexMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DataModelStatisticsService {

    private static final String BUSINESS_SCOPE = "BUSINESS";
    private static final String STAT_COUNT_BY_VALUE = "COUNT_BY_VALUE";
    private static final String STAT_SUMMARY = "SUMMARY";
    private static final String STAT_COUNT_BY_BUCKET = "COUNT_BY_BUCKET";

    private final DataModelAttrIndexMapper indexMapper;
    private final MetadataSchemaService metadataSchemaService;
    private final DataModelSearchIndexService dataModelSearchIndexService;
    private final DataModelAccessScopeService dataModelAccessScopeService;
    private final StudioSecurityService securityService;

    public DataModelStatisticsService(DataModelAttrIndexMapper indexMapper,
                                      MetadataSchemaService metadataSchemaService,
                                      DataModelSearchIndexService dataModelSearchIndexService,
                                      DataModelAccessScopeService dataModelAccessScopeService,
                                      StudioSecurityService securityService) {
        this.indexMapper = indexMapper;
        this.metadataSchemaService = metadataSchemaService;
        this.dataModelSearchIndexService = dataModelSearchIndexService;
        this.dataModelAccessScopeService = dataModelAccessScopeService;
        this.securityService = securityService;
    }

    public DataModelStatisticsView statistics(DataModelStatisticsRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Statistics request is required");
        }
        MetadataSchemaDefinition targetSchema = requireTargetSchema(request.getTargetMetaSchemaCode());
        requireBusinessSchema(targetSchema);
        MetadataFieldDefinition targetField = requireTargetField(targetSchema, request.getTargetFieldKey());
        requireSupportedTargetField(targetField, request.getTargetScope());
        String statType = resolveStatType(targetField, request.getStatType());

        DataModelQueryRequest normalizedRequest = normalizeRequest(request);
        List<DataModelQueryGroup> groups = normalizedRequest.getGroups();

        Set<Long> matchedModelIds;
        Set<DataModelMatchUnit> matchedUnits = new LinkedHashSet<DataModelMatchUnit>();
        boolean hasTargetSchemaGroup = hasTargetSchemaGroup(groups, targetSchema.getSchemaCode());
        if (groups.isEmpty()) {
            matchedModelIds = dataModelAccessScopeService.listAccessibleModelIds(request.getDatasourceId(), request.getModelKind());
        } else {
            matchedModelIds = dataModelSearchIndexService.queryModelIds(normalizedRequest);
            if (hasTargetSchemaGroup) {
                matchedUnits = dataModelSearchIndexService.queryMatchUnits(normalizedRequest, targetSchema.getSchemaCode());
            }
        }
        if (matchedModelIds.isEmpty()) {
            return emptyStatistics(targetField, statType, request.getBucketConfig());
        }
        if (hasTargetSchemaGroup && matchedUnits.isEmpty()) {
            return emptyStatistics(targetField, statType, request.getBucketConfig());
        }

        List<DataModelAttrIndexEntity> targetRows = loadTargetRows(targetSchema.getSchemaCode(),
                request.getTargetFieldKey().trim(),
                matchedModelIds);
        if (hasTargetSchemaGroup) {
            targetRows = filterRowsByUnits(targetRows, matchedUnits);
        }
        return buildStatistics(targetRows, targetField, statType, request.getTopN(), request.getBucketConfig());
    }

    private DataModelStatisticsView buildStatistics(List<DataModelAttrIndexEntity> rows,
                                                    MetadataFieldDefinition targetField,
                                                    String statType,
                                                    Integer topN,
                                                    DataModelStatisticsBucketConfig bucketConfig) {
        DataModelStatisticsView view = new DataModelStatisticsView();
        List<DataModelAttrIndexEntity> safeRows = rows == null ? new ArrayList<DataModelAttrIndexEntity>() : rows;
        Set<Long> modelIds = new LinkedHashSet<Long>();
        for (DataModelAttrIndexEntity row : safeRows) {
            if (row != null && row.getModelId() != null) {
                modelIds.add(row.getModelId());
            }
        }
        view.setMatchedModelCount((long) modelIds.size());
        view.setMatchedItemCount((long) safeRows.size());
        view.setSummaryMetrics(buildSummaryMetrics(safeRows, targetField));

        if (STAT_COUNT_BY_VALUE.equals(statType)) {
            view.setBuckets(buildValueBuckets(safeRows, topN));
        } else if (STAT_COUNT_BY_BUCKET.equals(statType)) {
            view.setBuckets(buildNumericBuckets(safeRows, bucketConfig));
        } else {
            view.setBuckets(new ArrayList<DataModelStatisticsBucketView>());
        }
        return view;
    }

    private DataModelStatisticsView emptyStatistics(MetadataFieldDefinition targetField,
                                                    String statType,
                                                    DataModelStatisticsBucketConfig bucketConfig) {
        return buildStatistics(new ArrayList<DataModelAttrIndexEntity>(), targetField, statType, null, bucketConfig);
    }

    private Map<String, Object> buildSummaryMetrics(List<DataModelAttrIndexEntity> rows,
                                                    MetadataFieldDefinition targetField) {
        Map<String, Object> metrics = new LinkedHashMap<String, Object>();
        metrics.put("count", rows == null ? 0L : (long) rows.size());
        Set<String> distinctValues = new LinkedHashSet<String>();
        if (rows != null) {
            for (DataModelAttrIndexEntity row : rows) {
                String bucketValue = resolveBucketValue(row);
                if (!bucketValue.isEmpty()) {
                    distinctValues.add(bucketValue);
                }
            }
        }
        metrics.put("distinctCount", (long) distinctValues.size());
        if (!isNumericField(targetField)) {
            return metrics;
        }

        List<BigDecimal> values = new ArrayList<BigDecimal>();
        if (rows != null) {
            for (DataModelAttrIndexEntity row : rows) {
                if (row != null && row.getNumberValue() != null) {
                    values.add(row.getNumberValue());
                }
            }
        }
        if (values.isEmpty()) {
            metrics.put("min", null);
            metrics.put("max", null);
            metrics.put("sum", BigDecimal.ZERO);
            metrics.put("avg", null);
            return metrics;
        }
        BigDecimal min = values.get(0);
        BigDecimal max = values.get(0);
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value.compareTo(min) < 0) {
                min = value;
            }
            if (value.compareTo(max) > 0) {
                max = value;
            }
            sum = sum.add(value);
        }
        metrics.put("min", min);
        metrics.put("max", max);
        metrics.put("sum", sum);
        metrics.put("avg", sum.divide(BigDecimal.valueOf(values.size()), 10, RoundingMode.HALF_UP));
        return metrics;
    }

    private List<DataModelStatisticsBucketView> buildValueBuckets(List<DataModelAttrIndexEntity> rows,
                                                                  Integer topN) {
        Map<String, Long> counters = new LinkedHashMap<String, Long>();
        if (rows != null) {
            for (DataModelAttrIndexEntity row : rows) {
                String value = resolveBucketValue(row);
                if (value.isEmpty()) {
                    continue;
                }
                Long current = counters.get(value);
                counters.put(value, current == null ? 1L : current + 1L);
            }
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<Map.Entry<String, Long>>(counters.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> left, Map.Entry<String, Long> right) {
                int countCompare = right.getValue().compareTo(left.getValue());
                if (countCompare != 0) {
                    return countCompare;
                }
                return left.getKey().compareTo(right.getKey());
            }
        });
        int limit = topN == null || topN.intValue() <= 0 ? entries.size() : Math.min(topN.intValue(), entries.size());
        List<DataModelStatisticsBucketView> buckets = new ArrayList<DataModelStatisticsBucketView>();
        for (int index = 0; index < limit; index++) {
            Map.Entry<String, Long> entry = entries.get(index);
            DataModelStatisticsBucketView bucket = new DataModelStatisticsBucketView();
            bucket.setKey(entry.getKey());
            bucket.setLabel(entry.getKey());
            bucket.setValue(entry.getKey());
            bucket.setCount(entry.getValue());
            buckets.add(bucket);
        }
        return buckets;
    }

    private List<DataModelStatisticsBucketView> buildNumericBuckets(List<DataModelAttrIndexEntity> rows,
                                                                    DataModelStatisticsBucketConfig bucketConfig) {
        NumericBucketPlan bucketPlan = buildBucketPlan(rows, bucketConfig);
        if (bucketPlan == null) {
            return new ArrayList<DataModelStatisticsBucketView>();
        }
        List<DataModelStatisticsBucketView> buckets = new ArrayList<DataModelStatisticsBucketView>();
        BigDecimal cursor = bucketPlan.lowerBound;
        while (cursor.compareTo(bucketPlan.upperBound) <= 0) {
            BigDecimal next = cursor.add(bucketPlan.step);
            boolean lastBucket = next.compareTo(bucketPlan.upperBound) >= 0;
            BigDecimal upper = lastBucket ? bucketPlan.upperBound : next;
            long count = countBucket(rows, cursor, upper, lastBucket);
            DataModelStatisticsBucketView bucket = new DataModelStatisticsBucketView();
            bucket.setKey(cursor.toPlainString() + "_" + upper.toPlainString());
            bucket.setLabel(lastBucket
                    ? "[" + cursor.toPlainString() + ", " + upper.toPlainString() + "]"
                    : "[" + cursor.toPlainString() + ", " + upper.toPlainString() + ")");
            bucket.setLowerBound(cursor);
            bucket.setUpperBound(upper);
            bucket.setCount(count);
            buckets.add(bucket);
            if (lastBucket) {
                break;
            }
            cursor = upper;
        }
        return buckets;
    }

    private long countBucket(List<DataModelAttrIndexEntity> rows,
                             BigDecimal lower,
                             BigDecimal upper,
                             boolean lastBucket) {
        long count = 0L;
        if (rows == null) {
            return count;
        }
        for (DataModelAttrIndexEntity row : rows) {
            if (row == null || row.getNumberValue() == null) {
                continue;
            }
            BigDecimal value = row.getNumberValue();
            boolean inRange = value.compareTo(lower) >= 0
                    && (lastBucket ? value.compareTo(upper) <= 0 : value.compareTo(upper) < 0);
            if (inRange) {
                count++;
            }
        }
        return count;
    }

    private NumericBucketPlan buildBucketPlan(List<DataModelAttrIndexEntity> rows,
                                              DataModelStatisticsBucketConfig bucketConfig) {
        if (bucketConfig == null || bucketConfig.getStep() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Bucket step is required for COUNT_BY_BUCKET");
        }
        if (bucketConfig.getStep().compareTo(BigDecimal.ZERO) <= 0) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Bucket step must be greater than 0");
        }
        BigDecimal min = null;
        BigDecimal max = null;
        if (rows != null) {
            for (DataModelAttrIndexEntity row : rows) {
                if (row == null || row.getNumberValue() == null) {
                    continue;
                }
                BigDecimal value = row.getNumberValue();
                if (min == null || value.compareTo(min) < 0) {
                    min = value;
                }
                if (max == null || value.compareTo(max) > 0) {
                    max = value;
                }
            }
        }
        BigDecimal lower = bucketConfig.getLowerBound() == null ? min : bucketConfig.getLowerBound();
        BigDecimal upper = bucketConfig.getUpperBound() == null ? max : bucketConfig.getUpperBound();
        if (lower == null || upper == null) {
            return null;
        }
        if (upper.compareTo(lower) < 0) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Bucket upperBound must be greater than or equal to lowerBound");
        }
        return new NumericBucketPlan(lower, upper, bucketConfig.getStep());
    }

    private List<DataModelAttrIndexEntity> loadTargetRows(String targetMetaSchemaCode,
                                                          String targetFieldKey,
                                                          Set<Long> matchedModelIds) {
        if (matchedModelIds == null || matchedModelIds.isEmpty()) {
            return new ArrayList<DataModelAttrIndexEntity>();
        }
        return indexMapper.selectList(new LambdaQueryWrapper<DataModelAttrIndexEntity>()
                .eq(DataModelAttrIndexEntity::getTenantId, securityService.currentTenantId())
                .eq(DataModelAttrIndexEntity::getMetaSchemaCode, targetMetaSchemaCode)
                .eq(DataModelAttrIndexEntity::getScope, BUSINESS_SCOPE)
                .eq(DataModelAttrIndexEntity::getFieldKey, targetFieldKey)
                .in(DataModelAttrIndexEntity::getModelId, matchedModelIds)
                .orderByAsc(DataModelAttrIndexEntity::getModelId)
                .orderByAsc(DataModelAttrIndexEntity::getItemKey));
    }

    private List<DataModelAttrIndexEntity> filterRowsByUnits(List<DataModelAttrIndexEntity> rows,
                                                             Set<DataModelMatchUnit> matchedUnits) {
        if (rows == null || rows.isEmpty() || matchedUnits == null || matchedUnits.isEmpty()) {
            return new ArrayList<DataModelAttrIndexEntity>();
        }
        List<DataModelAttrIndexEntity> filtered = new ArrayList<DataModelAttrIndexEntity>();
        for (DataModelAttrIndexEntity row : rows) {
            if (row == null || row.getModelId() == null) {
                continue;
            }
            String itemKey = row.getItemKey();
            if (itemKey == null || itemKey.trim().isEmpty()) {
                itemKey = "__single__";
            }
            if (matchedUnits.contains(new DataModelMatchUnit(row.getModelId(), itemKey))) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private DataModelQueryRequest normalizeRequest(DataModelStatisticsRequest request) {
        DataModelQueryRequest normalized = new DataModelQueryRequest();
        normalized.setDatasourceId(request.getDatasourceId());
        normalized.setModelKind(request.getModelKind());
        normalized.setGroups(normalizeQueryGroups(request.getGroups()));
        return normalized;
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

    private boolean hasTargetSchemaGroup(List<DataModelQueryGroup> groups, String targetMetaSchemaCode) {
        if (groups == null || groups.isEmpty() || targetMetaSchemaCode == null || targetMetaSchemaCode.trim().isEmpty()) {
            return false;
        }
        for (DataModelQueryGroup group : groups) {
            if (group != null && targetMetaSchemaCode.equalsIgnoreCase(group.getMetaSchemaCode())) {
                return true;
            }
        }
        return false;
    }

    private MetadataSchemaDefinition requireTargetSchema(String targetMetaSchemaCode) {
        if (targetMetaSchemaCode == null || targetMetaSchemaCode.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "targetMetaSchemaCode is required");
        }
        for (MetadataSchemaDefinition schema : metadataSchemaService.listSchemas()) {
            if (targetMetaSchemaCode.trim().equalsIgnoreCase(schema.getSchemaCode())) {
                return schema;
            }
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Target schema not found: " + targetMetaSchemaCode);
    }

    private void requireBusinessSchema(MetadataSchemaDefinition schema) {
        if (schema == null || !BUSINESS_SCOPE.equalsIgnoreCase(metadataSchemaService.getSchemaDomain(schema))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only BUSINESS metadata schema statistics are supported");
        }
    }

    private MetadataFieldDefinition requireTargetField(MetadataSchemaDefinition schema, String targetFieldKey) {
        if (targetFieldKey == null || targetFieldKey.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "targetFieldKey is required");
        }
        if (schema == null || schema.getFields() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Target field not found: " + targetFieldKey);
        }
        for (MetadataFieldDefinition field : schema.getFields()) {
            if (field != null && targetFieldKey.trim().equalsIgnoreCase(field.getFieldKey())) {
                return field;
            }
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Target field not found: " + targetFieldKey);
    }

    private void requireSupportedTargetField(MetadataFieldDefinition field, String targetScope) {
        String normalizedScope = targetScope == null || targetScope.trim().isEmpty()
                ? BUSINESS_SCOPE
                : targetScope.trim().toUpperCase();
        if (!BUSINESS_SCOPE.equals(normalizedScope)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only BUSINESS targetScope is supported");
        }
        if (field.getScope() != null && MetadataScope.BUSINESS != field.getScope()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only BUSINESS fields can be used for statistics");
        }
        if (!Boolean.TRUE.equals(field.getSearchable())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Target field is not searchable: " + field.getFieldKey());
        }
        if (Boolean.TRUE.equals(field.getSensitive())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Sensitive field statistics are not supported: " + field.getFieldKey());
        }
        if (!isSupportedValueType(field.getValueType())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Unsupported statistics field type: " + (field.getValueType() == null ? "UNKNOWN" : field.getValueType().name()));
        }
    }

    private String resolveStatType(MetadataFieldDefinition targetField, String requestStatType) {
        String statType = requestStatType == null || requestStatType.trim().isEmpty()
                ? defaultStatType(targetField)
                : requestStatType.trim().toUpperCase();
        if (isNumericField(targetField)) {
            if (STAT_SUMMARY.equals(statType) || STAT_COUNT_BY_BUCKET.equals(statType)) {
                return statType;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Numeric fields only support SUMMARY or COUNT_BY_BUCKET statistics");
        }
        if (STAT_COUNT_BY_VALUE.equals(statType)) {
            return statType;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST,
                "STRING/BOOLEAN fields only support COUNT_BY_VALUE statistics");
    }

    private String defaultStatType(MetadataFieldDefinition targetField) {
        return isNumericField(targetField) ? STAT_SUMMARY : STAT_COUNT_BY_VALUE;
    }

    private boolean isSupportedValueType(FieldValueType valueType) {
        return valueType == FieldValueType.STRING
                || valueType == FieldValueType.BOOLEAN
                || valueType == FieldValueType.INTEGER
                || valueType == FieldValueType.LONG
                || valueType == FieldValueType.DECIMAL;
    }

    private boolean isNumericField(MetadataFieldDefinition field) {
        return field != null && (field.getValueType() == FieldValueType.INTEGER
                || field.getValueType() == FieldValueType.LONG
                || field.getValueType() == FieldValueType.DECIMAL);
    }

    private String resolveBucketValue(DataModelAttrIndexEntity row) {
        if (row == null) {
            return "";
        }
        if (row.getNumberValue() != null) {
            return normalizeNumber(row.getNumberValue());
        }
        if (row.getBoolValue() != null) {
            return row.getBoolValue().intValue() == 0 ? "false" : "true";
        }
        if (row.getKeywordValue() != null && !row.getKeywordValue().trim().isEmpty()) {
            return row.getKeywordValue().trim();
        }
        if (row.getRawValue() != null && !row.getRawValue().trim().isEmpty()) {
            return row.getRawValue().trim();
        }
        return "";
    }

    private String normalizeNumber(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0, RoundingMode.UNNECESSARY);
        }
        return normalized.toPlainString();
    }

    private static class NumericBucketPlan {
        private final BigDecimal lowerBound;
        private final BigDecimal upperBound;
        private final BigDecimal step;

        private NumericBucketPlan(BigDecimal lowerBound, BigDecimal upperBound, BigDecimal step) {
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.step = step;
        }
    }
}
