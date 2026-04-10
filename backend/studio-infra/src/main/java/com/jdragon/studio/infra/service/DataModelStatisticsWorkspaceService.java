package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.dto.model.DataModelStatisticsBucketView;
import com.jdragon.studio.dto.model.DataModelStatisticsChartSeriesView;
import com.jdragon.studio.dto.model.DataModelStatisticsChartTableRowView;
import com.jdragon.studio.dto.model.DataModelStatisticsChartView;
import com.jdragon.studio.dto.model.DataModelStatisticsFieldOptionView;
import com.jdragon.studio.dto.model.DataModelStatisticsOptionsView;
import com.jdragon.studio.dto.model.DataModelStatisticsSchemaOptionView;
import com.jdragon.studio.dto.model.DataModelStatisticsView;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.DataModelStatisticsChartRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsOptionsRequest;
import com.jdragon.studio.dto.model.request.DataModelStatisticsRequest;
import com.jdragon.studio.infra.entity.DataModelAttrIndexEntity;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DataModelStatisticsWorkspaceService {

    private static final String CHART_TREND = "TREND";
    private static final String CHART_BAR = "BAR";
    private static final String CHART_PIE = "PIE";
    private static final String CHART_TOPN = "TOPN";

    private static final String STAT_COUNT_BY_VALUE = "COUNT_BY_VALUE";
    private static final String STAT_COUNT_BY_BUCKET = "COUNT_BY_BUCKET";

    private final MetadataSchemaService metadataSchemaService;
    private final DataSourceService dataSourceService;
    private final DataModelStatisticsService dataModelStatisticsService;
    private final DataModelMapper dataModelMapper;
    private final StudioSecurityService securityService;

    public DataModelStatisticsWorkspaceService(MetadataSchemaService metadataSchemaService,
                                               DataSourceService dataSourceService,
                                               DataModelStatisticsService dataModelStatisticsService,
                                               DataModelMapper dataModelMapper,
                                               StudioSecurityService securityService) {
        this.metadataSchemaService = metadataSchemaService;
        this.dataSourceService = dataSourceService;
        this.dataModelStatisticsService = dataModelStatisticsService;
        this.dataModelMapper = dataModelMapper;
        this.securityService = securityService;
    }

    public DataModelStatisticsOptionsView options(DataModelStatisticsOptionsRequest request) {
        MetadataScope targetScope = resolveOptionsTargetScope(request);
        String datasourceType = resolveDatasourceType(request);
        Set<String> availableMetaModelCodes = resolveAvailableModelMetaModelCodes(datasourceType);

        DataModelStatisticsOptionsView view = new DataModelStatisticsOptionsView();
        view.setDatasourceType(datasourceType);

        List<DataModelStatisticsSchemaOptionView> querySchemas = new ArrayList<DataModelStatisticsSchemaOptionView>();
        List<DataModelStatisticsSchemaOptionView> targetSchemas = new ArrayList<DataModelStatisticsSchemaOptionView>();
        for (MetadataSchemaDefinition schema : metadataSchemaService.listSchemas()) {
            List<DataModelStatisticsFieldOptionView> queryFields = buildQueryFields(schema);
            if (!queryFields.isEmpty() && isQuerySchemaRelevant(schema, targetScope, datasourceType, availableMetaModelCodes)) {
                querySchemas.add(toSchemaOption(schema, queryFields));
            }
            List<DataModelStatisticsFieldOptionView> targetFields = buildTargetFields(schema);
            if (!targetFields.isEmpty() && isTargetSchemaRelevant(schema, targetScope, datasourceType, availableMetaModelCodes)) {
                targetSchemas.add(toSchemaOption(schema, targetFields));
            }
        }
        Collections.sort(querySchemas, schemaOptionComparator());
        Collections.sort(targetSchemas, schemaOptionComparator());
        view.setQuerySchemas(querySchemas);
        view.setTargetSchemas(targetSchemas);
        return view;
    }

    public DataModelStatisticsChartView queryChart(DataModelStatisticsChartRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Chart request is required");
        }
        String chartType = normalize(request.getChartType()).toUpperCase(Locale.ENGLISH);
        if (chartType.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "chartType is required");
        }
        if (CHART_TREND.equals(chartType)) {
            return queryTrend(request);
        }
        if (CHART_BAR.equals(chartType)) {
            return queryBar(request);
        }
        if (CHART_PIE.equals(chartType)) {
            return queryPie(request);
        }
        if (CHART_TOPN.equals(chartType)) {
            return queryTopN(request);
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported chartType: " + chartType);
    }

    private DataModelStatisticsChartView queryBar(DataModelStatisticsChartRequest request) {
        MetadataSchemaDefinition targetSchema = dataModelStatisticsService.requireTargetSchema(request.getTargetMetaSchemaCode());
        MetadataFieldDefinition targetField = dataModelStatisticsService.requireTargetField(targetSchema, request.getTargetFieldKey());
        String targetScope = dataModelStatisticsService.normalizeTargetScope(targetSchema, request.getTargetScope());
        dataModelStatisticsService.requireSupportedTargetField(targetSchema, targetField, targetScope);

        DataModelStatisticsRequest prepared = copyStatisticsRequest(request);
        prepared.setStatType(null);
        prepared.setBucketConfig(null);
        prepared.setTopN(null);
        DataModelStatisticsService.ResolvedStatisticsData resolved = dataModelStatisticsService.resolveStatisticsData(prepared);
        DataModelStatisticsView raw;
        if (dataModelStatisticsService.isNumericField(targetField)) {
            raw = dataModelStatisticsService.buildAutomaticBucketStatistics(resolved.getTargetRows(), resolved.getTargetField());
        } else {
            prepared.setStatType(STAT_COUNT_BY_VALUE);
            raw = dataModelStatisticsService.buildStatistics(resolved.getTargetRows(),
                    resolved.getTargetField(),
                    prepared.getStatType(),
                    null,
                    null);
        }
        return toBarChart(raw, chartSummaryMetrics(raw));
    }

    private DataModelStatisticsChartView queryPie(DataModelStatisticsChartRequest request) {
        MetadataSchemaDefinition targetSchema = dataModelStatisticsService.requireTargetSchema(request.getTargetMetaSchemaCode());
        MetadataFieldDefinition targetField = dataModelStatisticsService.requireTargetField(targetSchema, request.getTargetFieldKey());
        String targetScope = dataModelStatisticsService.normalizeTargetScope(targetSchema, request.getTargetScope());
        dataModelStatisticsService.requireSupportedTargetField(targetSchema, targetField, targetScope);
        if (dataModelStatisticsService.isNumericField(targetField)) {
            return disabledChart(CHART_PIE, "当前数值字段仅支持分桶柱状图和摘要统计");
        }
        DataModelStatisticsRequest prepared = copyStatisticsRequest(request);
        prepared.setStatType(STAT_COUNT_BY_VALUE);
        prepared.setTopN(null);
        DataModelStatisticsView raw = dataModelStatisticsService.statistics(prepared);
        return toPieChart(raw, chartSummaryMetrics(raw));
    }

    private DataModelStatisticsChartView queryTopN(DataModelStatisticsChartRequest request) {
        MetadataSchemaDefinition targetSchema = dataModelStatisticsService.requireTargetSchema(request.getTargetMetaSchemaCode());
        MetadataFieldDefinition targetField = dataModelStatisticsService.requireTargetField(targetSchema, request.getTargetFieldKey());
        String targetScope = dataModelStatisticsService.normalizeTargetScope(targetSchema, request.getTargetScope());
        dataModelStatisticsService.requireSupportedTargetField(targetSchema, targetField, targetScope);
        if (dataModelStatisticsService.isNumericField(targetField)) {
            return disabledChart(CHART_TOPN, "当前数值字段在 V1 不支持 TopN 排行");
        }
        DataModelStatisticsRequest prepared = copyStatisticsRequest(request);
        prepared.setStatType(STAT_COUNT_BY_VALUE);
        prepared.setTopN(resolveTopN(request));
        DataModelStatisticsView raw = dataModelStatisticsService.statistics(prepared);
        return toTopNChart(raw, chartSummaryMetrics(raw));
    }

    private DataModelStatisticsChartView queryTrend(DataModelStatisticsChartRequest request) {
        DataModelStatisticsRequest prepared = copyStatisticsRequest(request);
        prepared.setStatType(null);
        DataModelStatisticsService.ResolvedStatisticsData resolved = dataModelStatisticsService.resolveStatisticsData(prepared);
        if ("MULTIPLE".equalsIgnoreCase(metadataSchemaService.getSchemaDisplayMode(resolved.getTargetSchema()))) {
            return disabledChart(CHART_TREND, "当前仅支持单值元模型趋势");
        }

        int days = resolveDays(request);
        Set<Long> modelIds = new LinkedHashSet<Long>();
        for (DataModelAttrIndexEntity row : resolved.getTargetRows()) {
            if (row != null && row.getModelId() != null) {
                modelIds.add(row.getModelId());
            }
        }

        DataModelStatisticsChartView chart = new DataModelStatisticsChartView();
        chart.setChartType(CHART_TREND);
        Map<String, Object> summaryMetrics = new LinkedHashMap<String, Object>();
        summaryMetrics.put("matchedModelCount", (long) modelIds.size());
        summaryMetrics.put("matchedItemCount", (long) resolved.getTargetRows().size());
        summaryMetrics.put("days", days);
        chart.setSummaryMetrics(summaryMetrics);

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days - 1L);
        Map<LocalDate, Long> counters = new LinkedHashMap<LocalDate, Long>();
        List<String> xAxis = new ArrayList<String>();
        for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
            counters.put(cursor, 0L);
            xAxis.add(cursor.toString());
        }
        if (!modelIds.isEmpty()) {
            LocalDateTime startDateTime = start.atStartOfDay();
            List<DataModelEntity> entities = dataModelMapper.selectList(new LambdaQueryWrapper<DataModelEntity>()
                    .eq(DataModelEntity::getTenantId, securityService.currentTenantId())
                    .in(DataModelEntity::getId, modelIds)
                    .ge(DataModelEntity::getCreatedAt, startDateTime)
                    .orderByAsc(DataModelEntity::getCreatedAt));
            for (DataModelEntity entity : entities) {
                if (entity == null || entity.getCreatedAt() == null) {
                    continue;
                }
                LocalDate createdDate = entity.getCreatedAt().toLocalDate();
                if (!counters.containsKey(createdDate)) {
                    continue;
                }
                Long current = counters.get(createdDate);
                counters.put(createdDate, current == null ? 1L : current + 1L);
            }
        }
        chart.setXAxis(xAxis);
        DataModelStatisticsChartSeriesView series = new DataModelStatisticsChartSeriesView();
        series.setName("新增数量");
        series.setType("line");
        long totalCount = 0L;
        for (String axis : xAxis) {
            long count = counters.get(LocalDate.parse(axis));
            series.getData().add(count);
            totalCount += count;

            DataModelStatisticsChartTableRowView row = new DataModelStatisticsChartTableRowView();
            row.setDate(axis);
            row.setLabel(axis);
            row.setCount(count);
            chart.getTableRows().add(row);
        }
        chart.getSeries().add(series);
        summaryMetrics.put("count", totalCount);
        return chart;
    }

    private DataModelStatisticsSchemaOptionView toSchemaOption(MetadataSchemaDefinition schema,
                                                               List<DataModelStatisticsFieldOptionView> fields) {
        DataModelStatisticsSchemaOptionView view = new DataModelStatisticsSchemaOptionView();
        view.setSchemaCode(schema.getSchemaCode());
        view.setSchemaName(schema.getSchemaName());
        view.setScope(toScope(metadataSchemaService.getSchemaDomain(schema)));
        view.setDatasourceType(metadataSchemaService.getSchemaDatasourceType(schema));
        view.setMetaModelCode(metadataSchemaService.getSchemaMetaModelCode(schema));
        view.setDisplayMode(metadataSchemaService.getSchemaDisplayMode(schema));
        view.setFields(fields);
        return view;
    }

    private List<DataModelStatisticsFieldOptionView> buildQueryFields(MetadataSchemaDefinition schema) {
        List<DataModelStatisticsFieldOptionView> result = new ArrayList<DataModelStatisticsFieldOptionView>();
        if (schema == null || schema.getFields() == null) {
            return result;
        }
        for (MetadataFieldDefinition field : schema.getFields()) {
            if (!Boolean.TRUE.equals(field.getSearchable()) || Boolean.TRUE.equals(field.getSensitive())) {
                continue;
            }
            DataModelStatisticsFieldOptionView item = new DataModelStatisticsFieldOptionView();
            item.setFieldKey(field.getFieldKey());
            item.setFieldName(field.getFieldName());
            item.setScope(field.getScope());
            item.setValueType(field.getValueType());
            item.setQueryOperators(copyList(field.getQueryOperators()));
            item.setQueryDefaultOperator(field.getQueryDefaultOperator());
            result.add(item);
        }
        Collections.sort(result, fieldOptionComparator());
        return result;
    }

    private List<DataModelStatisticsFieldOptionView> buildTargetFields(MetadataSchemaDefinition schema) {
        List<DataModelStatisticsFieldOptionView> result = new ArrayList<DataModelStatisticsFieldOptionView>();
        MetadataScope schemaScope = toScope(metadataSchemaService.getSchemaDomain(schema));
        if (schema == null || schema.getFields() == null || schemaScope == null) {
            return result;
        }
        for (MetadataFieldDefinition field : schema.getFields()) {
            if (!isEligibleTargetField(field, schemaScope)) {
                continue;
            }
            DataModelStatisticsFieldOptionView item = new DataModelStatisticsFieldOptionView();
            item.setFieldKey(field.getFieldKey());
            item.setFieldName(field.getFieldName());
            item.setScope(field.getScope());
            item.setValueType(field.getValueType());
            item.setQueryOperators(copyList(field.getQueryOperators()));
            item.setQueryDefaultOperator(field.getQueryDefaultOperator());
            item.setSupportedChartTypes(resolveSupportedChartTypes(field, metadataSchemaService.getSchemaDisplayMode(schema)));
            result.add(item);
        }
        Collections.sort(result, fieldOptionComparator());
        return result;
    }

    private boolean isEligibleTargetField(MetadataFieldDefinition field, MetadataScope schemaScope) {
        if (field == null || field.getScope() != schemaScope) {
            return false;
        }
        if (!Boolean.TRUE.equals(field.getSearchable()) || Boolean.TRUE.equals(field.getSensitive())) {
            return false;
        }
        return field.getValueType() == FieldValueType.STRING
                || field.getValueType() == FieldValueType.BOOLEAN
                || field.getValueType() == FieldValueType.INTEGER
                || field.getValueType() == FieldValueType.LONG
                || field.getValueType() == FieldValueType.DECIMAL;
    }

    private List<String> resolveSupportedChartTypes(MetadataFieldDefinition field, String displayMode) {
        List<String> chartTypes = new ArrayList<String>();
        if (field == null || field.getValueType() == null) {
            return chartTypes;
        }
        if (field.getValueType() == FieldValueType.STRING || field.getValueType() == FieldValueType.BOOLEAN) {
            chartTypes.add(CHART_BAR);
            chartTypes.add(CHART_PIE);
            chartTypes.add(CHART_TOPN);
        } else if (field.getValueType() == FieldValueType.INTEGER
                || field.getValueType() == FieldValueType.LONG
                || field.getValueType() == FieldValueType.DECIMAL) {
            chartTypes.add(CHART_BAR);
        }
        if (!"MULTIPLE".equalsIgnoreCase(displayMode)) {
            chartTypes.add(0, CHART_TREND);
        }
        return chartTypes;
    }

    private boolean isQuerySchemaRelevant(MetadataSchemaDefinition schema,
                                          MetadataScope targetScope,
                                          String datasourceType,
                                          Set<String> availableMetaModelCodes) {
        if (targetScope == MetadataScope.TECHNICAL) {
            if (!hasDatasourceType(datasourceType)) {
                return false;
            }
            return isTechnicalTargetSchemaRelevant(schema, datasourceType)
                    || isBusinessSchemaRelevant(schema, datasourceType, availableMetaModelCodes);
        }
        return isBusinessSchemaRelevant(schema, datasourceType, availableMetaModelCodes);
    }

    private boolean isTargetSchemaRelevant(MetadataSchemaDefinition schema,
                                           MetadataScope targetScope,
                                           String datasourceType,
                                           Set<String> availableMetaModelCodes) {
        if (targetScope == MetadataScope.TECHNICAL) {
            return isTechnicalTargetSchemaRelevant(schema, datasourceType);
        }
        return isBusinessSchemaRelevant(schema, datasourceType, availableMetaModelCodes);
    }

    private boolean isBusinessSchemaRelevant(MetadataSchemaDefinition schema,
                                             String datasourceType,
                                             Set<String> availableMetaModelCodes) {
        if (schema == null) {
            return false;
        }
        String metaModelCode = normalize(metadataSchemaService.getSchemaMetaModelCode(schema));
        if ("source".equals(metaModelCode)) {
            return false;
        }
        if (toScope(metadataSchemaService.getSchemaDomain(schema)) != MetadataScope.BUSINESS) {
            return false;
        }
        if (!hasDatasourceType(datasourceType)) {
            return true;
        }
        return availableMetaModelCodes.contains(metaModelCode);
    }

    private boolean isTechnicalTargetSchemaRelevant(MetadataSchemaDefinition schema,
                                                    String datasourceType) {
        if (schema == null || !hasDatasourceType(datasourceType)) {
            return false;
        }
        if (toScope(metadataSchemaService.getSchemaDomain(schema)) != MetadataScope.TECHNICAL) {
            return false;
        }
        if (!"model".equalsIgnoreCase(schema.getObjectType())) {
            return false;
        }
        String metaModelCode = normalize(metadataSchemaService.getSchemaMetaModelCode(schema));
        if (metaModelCode.isEmpty() || "source".equals(metaModelCode)) {
            return false;
        }
        return normalize(datasourceType).equals(normalize(metadataSchemaService.getSchemaDatasourceType(schema)));
    }

    private Set<String> resolveAvailableModelMetaModelCodes(String datasourceType) {
        Set<String> result = new LinkedHashSet<String>();
        for (MetadataSchemaDefinition schema : metadataSchemaService.listSchemas()) {
            if (schema == null || !"model".equalsIgnoreCase(schema.getObjectType())) {
                continue;
            }
            if (!"TECHNICAL".equalsIgnoreCase(metadataSchemaService.getSchemaDomain(schema))) {
                continue;
            }
            String metaModelCode = normalize(metadataSchemaService.getSchemaMetaModelCode(schema));
            if (metaModelCode.isEmpty() || "source".equals(metaModelCode)) {
                continue;
            }
            if (datasourceType != null && !datasourceType.trim().isEmpty()
                    && !normalize(datasourceType).equals(normalize(metadataSchemaService.getSchemaDatasourceType(schema)))) {
                continue;
            }
            result.add(metaModelCode);
        }
        return result;
    }

    private MetadataScope resolveOptionsTargetScope(DataModelStatisticsOptionsRequest request) {
        MetadataScope targetScope = toScope(request == null ? null : request.getTargetScope());
        return targetScope == null ? MetadataScope.BUSINESS : targetScope;
    }

    private String resolveDatasourceType(DataModelStatisticsOptionsRequest request) {
        if (request != null && request.getDatasourceId() != null) {
            DataSourceDefinition datasource = dataSourceService.get(request.getDatasourceId());
            if (datasource == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource not found: " + request.getDatasourceId());
            }
            return datasource.getTypeCode();
        }
        return request == null ? null : request.getDatasourceType();
    }

    private boolean hasDatasourceType(String datasourceType) {
        return datasourceType != null && !datasourceType.trim().isEmpty();
    }

    private DataModelStatisticsChartView toBarChart(DataModelStatisticsView raw,
                                                    Map<String, Object> summaryMetrics) {
        DataModelStatisticsChartView chart = new DataModelStatisticsChartView();
        chart.setChartType(CHART_BAR);
        chart.setSummaryMetrics(summaryMetrics);
        DataModelStatisticsChartSeriesView series = new DataModelStatisticsChartSeriesView();
        series.setName("数量");
        series.setType("bar");
        long total = raw.getMatchedItemCount() == null ? 0L : raw.getMatchedItemCount().longValue();
        for (DataModelStatisticsBucketView bucket : raw.getBuckets()) {
            String label = bucketLabel(bucket);
            long count = bucket.getCount() == null ? 0L : bucket.getCount().longValue();
            chart.getXAxis().add(label);
            series.getData().add(count);
            chart.getTableRows().add(toTableRow(bucket, count, total, chart.getTableRows().size() + 1));
        }
        chart.getSeries().add(series);
        return chart;
    }

    private DataModelStatisticsChartView toPieChart(DataModelStatisticsView raw,
                                                    Map<String, Object> summaryMetrics) {
        DataModelStatisticsChartView chart = new DataModelStatisticsChartView();
        chart.setChartType(CHART_PIE);
        chart.setSummaryMetrics(summaryMetrics);
        DataModelStatisticsChartSeriesView series = new DataModelStatisticsChartSeriesView();
        series.setName("数量");
        series.setType("pie");
        long total = raw.getMatchedItemCount() == null ? 0L : raw.getMatchedItemCount().longValue();
        for (DataModelStatisticsBucketView bucket : raw.getBuckets()) {
            String label = bucketLabel(bucket);
            long count = bucket.getCount() == null ? 0L : bucket.getCount().longValue();
            chart.getXAxis().add(label);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", label);
            item.put("value", count);
            series.getData().add(item);
            chart.getTableRows().add(toTableRow(bucket, count, total, chart.getTableRows().size() + 1));
        }
        chart.getSeries().add(series);
        return chart;
    }

    private DataModelStatisticsChartView toTopNChart(DataModelStatisticsView raw,
                                                     Map<String, Object> summaryMetrics) {
        DataModelStatisticsChartView chart = new DataModelStatisticsChartView();
        chart.setChartType(CHART_TOPN);
        chart.setSummaryMetrics(summaryMetrics);
        long total = raw.getMatchedItemCount() == null ? 0L : raw.getMatchedItemCount().longValue();
        List<DataModelStatisticsBucketView> buckets = new ArrayList<DataModelStatisticsBucketView>(raw.getBuckets());
        Collections.sort(buckets, new Comparator<DataModelStatisticsBucketView>() {
            @Override
            public int compare(DataModelStatisticsBucketView left, DataModelStatisticsBucketView right) {
                long leftCount = left == null || left.getCount() == null ? 0L : left.getCount().longValue();
                long rightCount = right == null || right.getCount() == null ? 0L : right.getCount().longValue();
                return Long.compare(rightCount, leftCount);
            }
        });
        DataModelStatisticsChartSeriesView series = new DataModelStatisticsChartSeriesView();
        series.setName("数量");
        series.setType("bar");
        int rank = 1;
        for (DataModelStatisticsBucketView bucket : buckets) {
            String label = bucketLabel(bucket);
            long count = bucket.getCount() == null ? 0L : bucket.getCount().longValue();
            chart.getXAxis().add(label);
            series.getData().add(count);
            chart.getTableRows().add(toTableRow(bucket, count, total, rank));
            rank++;
        }
        chart.getSeries().add(series);
        return chart;
    }

    private DataModelStatisticsChartTableRowView toTableRow(DataModelStatisticsBucketView bucket,
                                                            long count,
                                                            long total,
                                                            int rank) {
        DataModelStatisticsChartTableRowView row = new DataModelStatisticsChartTableRowView();
        row.setKey(bucket.getKey());
        row.setLabel(bucketLabel(bucket));
        row.setCategory(bucketLabel(bucket));
        row.setRank(rank);
        row.setCount(count);
        row.setValue(bucket.getValue());
        row.setRatio(total <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(count).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP));
        if (bucket.getLowerBound() != null) {
            row.setLowerBound(toBigDecimal(bucket.getLowerBound()));
        }
        if (bucket.getUpperBound() != null) {
            row.setUpperBound(toBigDecimal(bucket.getUpperBound()));
        }
        return row;
    }

    private Map<String, Object> chartSummaryMetrics(DataModelStatisticsView raw) {
        Map<String, Object> metrics = new LinkedHashMap<String, Object>();
        if (raw != null && raw.getSummaryMetrics() != null) {
            metrics.putAll(raw.getSummaryMetrics());
        }
        if (raw != null) {
            metrics.put("matchedModelCount", raw.getMatchedModelCount());
            metrics.put("matchedItemCount", raw.getMatchedItemCount());
        }
        return metrics;
    }

    private DataModelStatisticsChartView disabledChart(String chartType, String reason) {
        DataModelStatisticsChartView chart = new DataModelStatisticsChartView();
        chart.setChartType(chartType);
        chart.setDisabledReason(reason);
        return chart;
    }

    private DataModelStatisticsRequest copyStatisticsRequest(DataModelStatisticsRequest source) {
        DataModelStatisticsRequest request = new DataModelStatisticsRequest();
        request.setDatasourceId(source.getDatasourceId());
        request.setModelKind(source.getModelKind());
        request.setGroups(source.getGroups());
        request.setTargetMetaSchemaCode(source.getTargetMetaSchemaCode());
        request.setTargetFieldKey(source.getTargetFieldKey());
        request.setTargetScope(source.getTargetScope());
        request.setStatType(source.getStatType());
        request.setTopN(source.getTopN());
        request.setBucketConfig(source.getBucketConfig());
        return request;
    }

    private int resolveDays(DataModelStatisticsChartRequest request) {
        if (request == null || request.getDays() == null || request.getDays().intValue() <= 0) {
            return 30;
        }
        return request.getDays().intValue();
    }

    private int resolveTopN(DataModelStatisticsChartRequest request) {
        if (request == null || request.getTopN() == null || request.getTopN().intValue() <= 0) {
            return 10;
        }
        return request.getTopN().intValue();
    }

    private MetadataScope toScope(String scope) {
        String normalized = normalize(scope).toUpperCase(Locale.ENGLISH);
        if ("BUSINESS".equals(normalized)) {
            return MetadataScope.BUSINESS;
        }
        if ("TECHNICAL".equals(normalized)) {
            return MetadataScope.TECHNICAL;
        }
        return null;
    }

    private List<String> copyList(List<String> values) {
        return values == null ? new ArrayList<String>() : new ArrayList<String>(values);
    }

    private Comparator<DataModelStatisticsSchemaOptionView> schemaOptionComparator() {
        return new Comparator<DataModelStatisticsSchemaOptionView>() {
            @Override
            public int compare(DataModelStatisticsSchemaOptionView left, DataModelStatisticsSchemaOptionView right) {
                String leftLabel = (left.getScope() == null ? "" : left.getScope().name()) + ":" + left.getSchemaName();
                String rightLabel = (right.getScope() == null ? "" : right.getScope().name()) + ":" + right.getSchemaName();
                return leftLabel.compareToIgnoreCase(rightLabel);
            }
        };
    }

    private Comparator<DataModelStatisticsFieldOptionView> fieldOptionComparator() {
        return new Comparator<DataModelStatisticsFieldOptionView>() {
            @Override
            public int compare(DataModelStatisticsFieldOptionView left, DataModelStatisticsFieldOptionView right) {
                return left.getFieldName().compareToIgnoreCase(right.getFieldName());
            }
        };
    }

    private String bucketLabel(DataModelStatisticsBucketView bucket) {
        if (bucket == null) {
            return "-";
        }
        if (bucket.getLabel() != null && !bucket.getLabel().trim().isEmpty()) {
            return bucket.getLabel();
        }
        if (bucket.getKey() != null && !bucket.getKey().trim().isEmpty()) {
            return bucket.getKey();
        }
        if (bucket.getValue() != null && !bucket.getValue().trim().isEmpty()) {
            return bucket.getValue();
        }
        if (bucket.getLowerBound() != null || bucket.getUpperBound() != null) {
            return String.valueOf(bucket.getLowerBound()) + " ~ " + String.valueOf(bucket.getUpperBound());
        }
        return "-";
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
            return new BigDecimal(text);
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
    }
}
