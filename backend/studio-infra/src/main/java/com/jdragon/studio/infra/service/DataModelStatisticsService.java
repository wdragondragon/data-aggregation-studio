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

    public static final String SINGLE_ITEM_KEY = "__single__";

    private static final String BUSINESS_SCOPE = "BUSINESS";
    private static final String TECHNICAL_SCOPE = "TECHNICAL";
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
        ResolvedStatisticsData resolved = resolveStatisticsData(request);
        return buildStatistics(resolved.getTargetRows(),
                resolved.getTargetField(),
                resolved.getStatType(),
                request == null ? null : request.getTopN(),
                request == null ? null : request.getBucketConfig());
    }

    public ResolvedStatisticsData resolveStatisticsData(DataModelStatisticsRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Statistics request is required");
        }
        MetadataSchemaDefinition targetSchema = requireTargetSchema(request.getTargetMetaSchemaCode());
        MetadataFieldDefinition targetField = requireTargetField(targetSchema, request.getTargetFieldKey());
        String targetScope = normalizeTargetScope(targetSchema, request.getTargetScope());
        requireSupportedTargetField(targetSchema, targetField, targetScope);
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

        List<DataModelAttrIndexEntity> targetRows = new ArrayList<DataModelAttrIndexEntity>();
        if (!matchedModelIds.isEmpty() && (!hasTargetSchemaGroup || !matchedUnits.isEmpty())) {
            targetRows = loadTargetRows(targetSchema.getSchemaCode(),
                    request.getTargetFieldKey().trim(),
                    targetScope,
                    matchedModelIds);
            if (hasTargetSchemaGroup) {
                targetRows = filterRowsByUnits(targetRows, matchedUnits);
            }
        }
        return new ResolvedStatisticsData(targetSchema, targetField, targetScope, statType, targetRows);
    }

    public DataModelStatisticsView buildAutomaticBucketStatistics(List<DataModelAttrIndexEntity> rows,
                                                                  MetadataFieldDefinition targetField) {
        DataModelStatisticsView view = initializeStatisticsView(rows, targetField);
        NumericBucketResult bucketResult = buildAutomaticNumericBucketResult(rows, targetField);
        view.setBuckets(bucketResult.getBuckets());
        applyEffectiveBucketMetrics(view.getSummaryMetrics(), bucketResult.getPlan());
        return view;
    }

    public DataModelStatisticsView buildStatistics(List<DataModelAttrIndexEntity> rows,
                                                   MetadataFieldDefinition targetField,
                                                   String statType,
                                                   Integer topN,
                                                   DataModelStatisticsBucketConfig bucketConfig) {
        List<DataModelAttrIndexEntity> safeRows = rows == null ? new ArrayList<DataModelAttrIndexEntity>() : rows;
        DataModelStatisticsView view = initializeStatisticsView(safeRows, targetField);

        if (STAT_COUNT_BY_VALUE.equals(statType)) {
            view.setBuckets(buildValueBuckets(safeRows, topN));
        } else if (STAT_COUNT_BY_BUCKET.equals(statType)) {
            NumericBucketResult bucketResult = buildNumericBucketResult(safeRows, bucketConfig);
            view.setBuckets(bucketResult.getBuckets());
            applyEffectiveBucketMetrics(view.getSummaryMetrics(), bucketResult.getPlan());
        } else {
            view.setBuckets(new ArrayList<DataModelStatisticsBucketView>());
        }
        return view;
    }

    public MetadataSchemaDefinition requireTargetSchema(String targetMetaSchemaCode) {
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

    public MetadataFieldDefinition requireTargetField(MetadataSchemaDefinition schema, String targetFieldKey) {
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

    public String normalizeTargetScope(MetadataSchemaDefinition schema, String targetScope) {
        if (targetScope != null && !targetScope.trim().isEmpty()) {
            return targetScope.trim().toUpperCase();
        }
        String schemaDomain = metadataSchemaService.getSchemaDomain(schema);
        return schemaDomain == null ? BUSINESS_SCOPE : schemaDomain.trim().toUpperCase();
    }

    public void requireSupportedTargetField(MetadataSchemaDefinition schema,
                                            MetadataFieldDefinition field,
                                            String targetScope) {
        String normalizedScope = targetScope == null || targetScope.trim().isEmpty()
                ? normalizeTargetScope(schema, null)
                : targetScope.trim().toUpperCase();
        if (!BUSINESS_SCOPE.equals(normalizedScope) && !TECHNICAL_SCOPE.equals(normalizedScope)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported targetScope: " + normalizedScope);
        }
        String schemaDomain = metadataSchemaService.getSchemaDomain(schema);
        if (schemaDomain != null && !normalizedScope.equalsIgnoreCase(schemaDomain)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Target scope does not match schema domain: " + schema.getSchemaCode());
        }
        MetadataScope expectedScope = BUSINESS_SCOPE.equals(normalizedScope) ? MetadataScope.BUSINESS : MetadataScope.TECHNICAL;
        if (field.getScope() != null && expectedScope != field.getScope()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Field scope does not match target scope: " + field.getFieldKey());
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

    public String resolveStatType(MetadataFieldDefinition targetField, String requestStatType) {
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

    public boolean isNumericField(MetadataFieldDefinition field) {
        return field != null && (field.getValueType() == FieldValueType.INTEGER
                || field.getValueType() == FieldValueType.LONG
                || field.getValueType() == FieldValueType.DECIMAL);
    }

    public List<DataModelAttrIndexEntity> loadTargetRows(String targetMetaSchemaCode,
                                                         String targetFieldKey,
                                                         String targetScope,
                                                         Set<Long> matchedModelIds) {
        if (matchedModelIds == null || matchedModelIds.isEmpty()) {
            return new ArrayList<DataModelAttrIndexEntity>();
        }
        return indexMapper.selectList(new LambdaQueryWrapper<DataModelAttrIndexEntity>()
                .eq(DataModelAttrIndexEntity::getTenantId, securityService.currentTenantId())
                .eq(DataModelAttrIndexEntity::getMetaSchemaCode, targetMetaSchemaCode)
                .eq(DataModelAttrIndexEntity::getScope, targetScope)
                .eq(DataModelAttrIndexEntity::getFieldKey, targetFieldKey)
                .in(DataModelAttrIndexEntity::getModelId, matchedModelIds)
                .orderByAsc(DataModelAttrIndexEntity::getModelId)
                .orderByAsc(DataModelAttrIndexEntity::getItemKey));
    }

    public List<DataModelAttrIndexEntity> filterRowsByUnits(List<DataModelAttrIndexEntity> rows,
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
                itemKey = SINGLE_ITEM_KEY;
            }
            if (matchedUnits.contains(new DataModelMatchUnit(row.getModelId(), itemKey))) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private DataModelStatisticsView initializeStatisticsView(List<DataModelAttrIndexEntity> rows,
                                                             MetadataFieldDefinition targetField) {
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
        return view;
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

    private NumericBucketResult buildNumericBucketResult(List<DataModelAttrIndexEntity> rows,
                                                         DataModelStatisticsBucketConfig bucketConfig) {
        NumericBucketPlan bucketPlan = buildManualBucketPlan(rows, bucketConfig);
        return bucketizeRows(rows, bucketPlan);
    }

    private NumericBucketResult buildAutomaticNumericBucketResult(List<DataModelAttrIndexEntity> rows,
                                                                  MetadataFieldDefinition targetField) {
        NumericBucketPlan bucketPlan = buildAutomaticBucketPlan(rows, targetField);
        return bucketizeRows(rows, bucketPlan);
    }

    private NumericBucketResult bucketizeRows(List<DataModelAttrIndexEntity> rows,
                                              NumericBucketPlan bucketPlan) {
        if (bucketPlan == null) {
            return new NumericBucketResult(new ArrayList<DataModelStatisticsBucketView>(), null);
        }
        List<DataModelStatisticsBucketView> buckets = initializeBuckets(bucketPlan);
        if (rows == null || rows.isEmpty()) {
            return new NumericBucketResult(buckets, bucketPlan);
        }
        if (bucketPlan.bucketCount <= 0) {
            return new NumericBucketResult(buckets, bucketPlan);
        }
        for (DataModelAttrIndexEntity row : rows) {
            if (row == null || row.getNumberValue() == null) {
                continue;
            }
            BigDecimal value = row.getNumberValue();
            if (value.compareTo(bucketPlan.lowerBound) < 0 || value.compareTo(bucketPlan.upperBound) > 0) {
                continue;
            }
            int bucketIndex;
            if (bucketPlan.bucketCount == 1 || bucketPlan.lowerBound.compareTo(bucketPlan.upperBound) == 0) {
                bucketIndex = 0;
            } else {
                BigDecimal delta = value.subtract(bucketPlan.lowerBound);
                bucketIndex = delta.divide(bucketPlan.step, 0, RoundingMode.DOWN).intValue();
                if (bucketIndex >= bucketPlan.bucketCount) {
                    bucketIndex = bucketPlan.bucketCount - 1;
                }
            }
            DataModelStatisticsBucketView bucket = buckets.get(bucketIndex);
            long current = bucket.getCount() == null ? 0L : bucket.getCount().longValue();
            bucket.setCount(current + 1L);
        }
        return new NumericBucketResult(buckets, bucketPlan);
    }

    private List<DataModelStatisticsBucketView> initializeBuckets(NumericBucketPlan bucketPlan) {
        List<DataModelStatisticsBucketView> buckets = new ArrayList<DataModelStatisticsBucketView>();
        if (bucketPlan == null || bucketPlan.bucketCount <= 0) {
            return buckets;
        }
        BigDecimal cursor = bucketPlan.lowerBound;
        for (int index = 0; index < bucketPlan.bucketCount; index++) {
            boolean lastBucket = index == bucketPlan.bucketCount - 1;
            BigDecimal upper = lastBucket ? bucketPlan.upperBound : cursor.add(bucketPlan.step);
            if (upper.compareTo(bucketPlan.upperBound) > 0 || lastBucket) {
                upper = bucketPlan.upperBound;
            }
            DataModelStatisticsBucketView bucket = new DataModelStatisticsBucketView();
            bucket.setKey(cursor.toPlainString() + "_" + upper.toPlainString());
            bucket.setLabel(lastBucket
                    ? "[" + cursor.toPlainString() + ", " + upper.toPlainString() + "]"
                    : "[" + cursor.toPlainString() + ", " + upper.toPlainString() + ")");
            bucket.setLowerBound(cursor);
            bucket.setUpperBound(upper);
            bucket.setCount(0L);
            buckets.add(bucket);
            cursor = upper;
        }
        return buckets;
    }

    private NumericBucketPlan buildManualBucketPlan(List<DataModelAttrIndexEntity> rows,
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
        return new NumericBucketPlan(lower, upper, bucketConfig.getStep(), computeBucketCount(lower, upper, bucketConfig.getStep()));
    }

    private NumericBucketPlan buildAutomaticBucketPlan(List<DataModelAttrIndexEntity> rows,
                                                       MetadataFieldDefinition targetField) {
        NumericValueStats stats = collectNumericValueStats(rows);
        if (stats.count <= 0 || stats.min == null || stats.max == null) {
            return null;
        }
        if (stats.distinctCount <= 1 || stats.min.compareTo(stats.max) == 0) {
            return new NumericBucketPlan(stats.min, stats.max, defaultAutomaticStep(targetField), 1);
        }
        BigDecimal range = stats.max.subtract(stats.min);
        BigDecimal rawStep = range.divide(BigDecimal.valueOf(5L), 12, RoundingMode.HALF_UP);
        List<BigDecimal> candidates = automaticStepCandidates(rawStep, targetField);
        NumericBucketPlan bestPlan = null;
        for (BigDecimal candidate : candidates) {
            NumericBucketPlan plan = alignAutomaticPlan(stats.min, stats.max, candidate);
            if (plan == null) {
                continue;
            }
            if (bestPlan == null || automaticPlanScore(plan, range) < automaticPlanScore(bestPlan, range)) {
                bestPlan = plan;
            }
        }
        if (bestPlan != null) {
            return bestPlan;
        }
        BigDecimal fallbackStep = defaultAutomaticStep(targetField);
        return alignAutomaticPlan(stats.min, stats.max, fallbackStep);
    }

    private NumericValueStats collectNumericValueStats(List<DataModelAttrIndexEntity> rows) {
        BigDecimal min = null;
        BigDecimal max = null;
        long count = 0L;
        Set<String> distinctValues = new LinkedHashSet<String>();
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
                count++;
                distinctValues.add(normalizeNumber(value));
            }
        }
        return new NumericValueStats(min, max, count, distinctValues.size());
    }

    private List<BigDecimal> automaticStepCandidates(BigDecimal rawStep,
                                                     MetadataFieldDefinition targetField) {
        BigDecimal safeRawStep = rawStep == null || rawStep.compareTo(BigDecimal.ZERO) <= 0
                ? defaultAutomaticStep(targetField)
                : rawStep;
        double rawStepDouble = safeRawStep.doubleValue();
        if (!(rawStepDouble > 0D)) {
            rawStepDouble = 1D;
        }
        int baseExponent = (int) Math.floor(Math.log10(rawStepDouble));
        Map<String, BigDecimal> candidates = new LinkedHashMap<String, BigDecimal>();
        for (int exponent = baseExponent - 2; exponent <= baseExponent + 2; exponent++) {
            addAutomaticStepCandidate(candidates, createNiceStep(1, exponent), targetField);
            addAutomaticStepCandidate(candidates, createNiceStep(2, exponent), targetField);
            addAutomaticStepCandidate(candidates, createNiceStep(5, exponent), targetField);
        }
        addAutomaticStepCandidate(candidates, defaultAutomaticStep(targetField), targetField);
        List<BigDecimal> result = new ArrayList<BigDecimal>(candidates.values());
        Collections.sort(result);
        return result;
    }

    private void addAutomaticStepCandidate(Map<String, BigDecimal> candidates,
                                           BigDecimal step,
                                           MetadataFieldDefinition targetField) {
        if (step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal candidate = normalizeStepForField(step, targetField);
        if (candidate.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        candidates.put(candidate.stripTrailingZeros().toPlainString(), candidate.stripTrailingZeros());
    }

    private NumericBucketPlan alignAutomaticPlan(BigDecimal min,
                                                 BigDecimal max,
                                                 BigDecimal step) {
        if (min == null || max == null || step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal lower = floorToStep(min, step);
        BigDecimal upper = ceilToStep(max, step);
        if (upper.compareTo(lower) < 0) {
            return null;
        }
        return new NumericBucketPlan(lower, upper, step, computeBucketCount(lower, upper, step));
    }

    private long automaticPlanScore(NumericBucketPlan plan,
                                    BigDecimal range) {
        if (plan == null) {
            return Long.MAX_VALUE;
        }
        int bucketCount = plan.bucketCount;
        long bucketPenalty = (bucketCount >= 4 && bucketCount <= 8)
                ? Math.abs(bucketCount - 5)
                : 100L + Math.abs(bucketCount - 5);
        BigDecimal expandedRange = plan.upperBound.subtract(plan.lowerBound);
        BigDecimal overhead = expandedRange.subtract(range == null ? BigDecimal.ZERO : range).abs();
        BigDecimal normalizedOverhead = overhead.movePointRight(6);
        long overheadPenalty = normalizedOverhead.longValue();
        long stepPenalty = plan.step.movePointRight(6).abs().longValue();
        return bucketPenalty * 1_000_000_000L + overheadPenalty * 1_000L + stepPenalty;
    }

    private BigDecimal createNiceStep(int factor,
                                      int exponent) {
        BigDecimal base = BigDecimal.valueOf(factor);
        if (exponent >= 0) {
            return base.multiply(BigDecimal.TEN.pow(exponent));
        }
        return base.divide(BigDecimal.TEN.pow(-exponent), Math.max(12, -exponent + 2), RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeStepForField(BigDecimal step,
                                             MetadataFieldDefinition targetField) {
        if (step == null) {
            return BigDecimal.ZERO;
        }
        if (targetField != null
                && (targetField.getValueType() == FieldValueType.INTEGER || targetField.getValueType() == FieldValueType.LONG)
                && step.compareTo(BigDecimal.ONE) < 0) {
            return BigDecimal.ONE;
        }
        return step.stripTrailingZeros();
    }

    private BigDecimal defaultAutomaticStep(MetadataFieldDefinition targetField) {
        if (targetField != null
                && (targetField.getValueType() == FieldValueType.INTEGER || targetField.getValueType() == FieldValueType.LONG)) {
            return BigDecimal.ONE;
        }
        return new BigDecimal("0.1");
    }

    private BigDecimal floorToStep(BigDecimal value,
                                   BigDecimal step) {
        BigDecimal quotient = value.divideToIntegralValue(step);
        BigDecimal aligned = quotient.multiply(step);
        if (value.signum() < 0 && aligned.compareTo(value) != 0) {
            aligned = aligned.subtract(step);
        }
        return aligned.stripTrailingZeros();
    }

    private BigDecimal ceilToStep(BigDecimal value,
                                  BigDecimal step) {
        BigDecimal quotient = value.divideToIntegralValue(step);
        BigDecimal aligned = quotient.multiply(step);
        if (value.signum() > 0 && aligned.compareTo(value) != 0) {
            aligned = aligned.add(step);
        }
        return aligned.stripTrailingZeros();
    }

    private int computeBucketCount(BigDecimal lower,
                                   BigDecimal upper,
                                   BigDecimal step) {
        if (lower == null || upper == null || step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        BigDecimal range = upper.subtract(lower);
        if (range.compareTo(BigDecimal.ZERO) <= 0) {
            return 1;
        }
        BigDecimal[] division = range.divideAndRemainder(step);
        int bucketCount = division[0].intValue();
        if (division[1].compareTo(BigDecimal.ZERO) > 0) {
            bucketCount++;
        }
        return bucketCount <= 0 ? 1 : bucketCount;
    }

    private void applyEffectiveBucketMetrics(Map<String, Object> summaryMetrics,
                                             NumericBucketPlan bucketPlan) {
        if (summaryMetrics == null || bucketPlan == null) {
            return;
        }
        summaryMetrics.put("effectiveLowerBound", bucketPlan.lowerBound);
        summaryMetrics.put("effectiveUpperBound", bucketPlan.upperBound);
        summaryMetrics.put("effectiveStep", bucketPlan.step);
        summaryMetrics.put("effectiveBucketCount", (long) bucketPlan.bucketCount);
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
        private final int bucketCount;

        private NumericBucketPlan(BigDecimal lowerBound, BigDecimal upperBound, BigDecimal step, int bucketCount) {
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.step = step;
            this.bucketCount = bucketCount;
        }
    }

    private static class NumericBucketResult {
        private final List<DataModelStatisticsBucketView> buckets;
        private final NumericBucketPlan plan;

        private NumericBucketResult(List<DataModelStatisticsBucketView> buckets, NumericBucketPlan plan) {
            this.buckets = buckets == null ? new ArrayList<DataModelStatisticsBucketView>() : buckets;
            this.plan = plan;
        }

        public List<DataModelStatisticsBucketView> getBuckets() {
            return buckets;
        }

        public NumericBucketPlan getPlan() {
            return plan;
        }
    }

    private static class NumericValueStats {
        private final BigDecimal min;
        private final BigDecimal max;
        private final long count;
        private final int distinctCount;

        private NumericValueStats(BigDecimal min, BigDecimal max, long count, int distinctCount) {
            this.min = min;
            this.max = max;
            this.count = count;
            this.distinctCount = distinctCount;
        }
    }

    public static class ResolvedStatisticsData {
        private final MetadataSchemaDefinition targetSchema;
        private final MetadataFieldDefinition targetField;
        private final String targetScope;
        private final String statType;
        private final List<DataModelAttrIndexEntity> targetRows;

        public ResolvedStatisticsData(MetadataSchemaDefinition targetSchema,
                                      MetadataFieldDefinition targetField,
                                      String targetScope,
                                      String statType,
                                      List<DataModelAttrIndexEntity> targetRows) {
            this.targetSchema = targetSchema;
            this.targetField = targetField;
            this.targetScope = targetScope;
            this.statType = statType;
            this.targetRows = targetRows == null ? new ArrayList<DataModelAttrIndexEntity>() : targetRows;
        }

        public MetadataSchemaDefinition getTargetSchema() {
            return targetSchema;
        }

        public MetadataFieldDefinition getTargetField() {
            return targetField;
        }

        public String getTargetScope() {
            return targetScope;
        }

        public String getStatType() {
            return statType;
        }

        public List<DataModelAttrIndexEntity> getTargetRows() {
            return targetRows;
        }
    }
}
