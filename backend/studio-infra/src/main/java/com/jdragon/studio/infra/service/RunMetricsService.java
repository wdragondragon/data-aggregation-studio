package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.RunMetricDashboardView;
import com.jdragon.studio.dto.model.RunMetricFilterOptionView;
import com.jdragon.studio.dto.model.RunMetricOptionsView;
import com.jdragon.studio.dto.model.RunMetricSummaryView;
import com.jdragon.studio.dto.model.RunMetricTopNItemView;
import com.jdragon.studio.dto.model.RunMetricTrendSeriesView;
import com.jdragon.studio.dto.model.RunMetricTrendView;
import com.jdragon.studio.dto.model.request.RunMetricDashboardQueryRequest;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RunMetricsService {

    private static final String GRANULARITY_DAY = "DAY";
    private static final String GRANULARITY_WEEK = "WEEK";
    private static final String GRANULARITY_MONTH = "MONTH";
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter REQUEST_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> TREND_METRIC_KEYS = Arrays.asList(
            "collectedRecords",
            "successRecords",
            "failedRecords",
            "transformerTotalRecords",
            "transformerSuccessRecords",
            "transformerFailedRecords",
            "transformerFilterRecords"
    );

    private final CollectionTaskService collectionTaskService;
    private final RunRecordMapper runRecordMapper;
    private final StudioSecurityService securityService;
    private final RunMetricSummaryMapper runMetricSummaryMapper;

    public RunMetricsService(CollectionTaskService collectionTaskService,
                             RunRecordMapper runRecordMapper,
                             StudioSecurityService securityService,
                             RunMetricSummaryMapper runMetricSummaryMapper) {
        this.collectionTaskService = collectionTaskService;
        this.runRecordMapper = runRecordMapper;
        this.securityService = securityService;
        this.runMetricSummaryMapper = runMetricSummaryMapper;
    }

    public RunMetricOptionsView options() {
        List<CollectionTaskDefinitionView> tasks = collectionTaskService.list(null, null, null);
        RunMetricOptionsView view = new RunMetricOptionsView();
        Map<Long, RunMetricFilterOptionView> datasourceOptions = new LinkedHashMap<Long, RunMetricFilterOptionView>();
        Map<Long, RunMetricFilterOptionView> sourceModelOptions = new LinkedHashMap<Long, RunMetricFilterOptionView>();
        Map<Long, RunMetricFilterOptionView> targetModelOptions = new LinkedHashMap<Long, RunMetricFilterOptionView>();
        for (CollectionTaskDefinitionView task : tasks) {
            if (task == null) {
                continue;
            }
            for (CollectionTaskSourceBinding sourceBinding : emptySourceBindings(task)) {
                registerOption(datasourceOptions,
                        asLong(sourceBinding.getDatasourceId()),
                        sourceBinding.getDatasourceName(),
                        buildDatasourceLabel(sourceBinding.getDatasourceName(), sourceBinding.getDatasourceTypeCode()),
                        sourceBinding.getDatasourceTypeCode());
                registerOption(sourceModelOptions,
                        asLong(sourceBinding.getModelId()),
                        sourceBinding.getModelName(),
                        buildModelLabel(sourceBinding.getModelName(), sourceBinding.getModelPhysicalLocator()),
                        sourceBinding.getDatasourceTypeCode());
            }
            CollectionTaskTargetBinding targetBinding = task.getTargetBinding();
            if (targetBinding == null) {
                continue;
            }
            registerOption(datasourceOptions,
                    asLong(targetBinding.getDatasourceId()),
                    targetBinding.getDatasourceName(),
                    buildDatasourceLabel(targetBinding.getDatasourceName(), targetBinding.getDatasourceTypeCode()),
                    targetBinding.getDatasourceTypeCode());
            registerOption(targetModelOptions,
                    asLong(targetBinding.getModelId()),
                    targetBinding.getModelName(),
                    buildModelLabel(targetBinding.getModelName(), targetBinding.getModelPhysicalLocator()),
                    targetBinding.getDatasourceTypeCode());
        }
        view.setDatasources(sortOptions(datasourceOptions));
        view.setSourceModels(sortOptions(sourceModelOptions));
        view.setTargetModels(sortOptions(targetModelOptions));
        return view;
    }

    public RunMetricDashboardView query(RunMetricDashboardQueryRequest request) {
        TimeWindow timeWindow = resolveTimeWindow(request);
        int topN = normalizeTopN(request == null ? null : request.getTopN());
        String granularity = normalizeGranularity(request == null ? null : request.getGranularity());
        List<CollectionTaskDefinitionView> matchedTasks = filterTasks(collectionTaskService.list(null, null, null), request);
        RunMetricDashboardView view = new RunMetricDashboardView();
        view.setTrend(createEmptyTrend(timeWindow, granularity));
        view.setLegacyRunCount(Long.valueOf(0L));
        if (matchedTasks.isEmpty()) {
            return view;
        }

        Set<Long> taskIds = new LinkedHashSet<Long>();
        Map<Long, CollectionTaskDefinitionView> taskById = new LinkedHashMap<Long, CollectionTaskDefinitionView>();
        for (CollectionTaskDefinitionView task : matchedTasks) {
            if (task.getId() != null) {
                taskIds.add(task.getId());
                taskById.put(task.getId(), task);
            }
        }
        if (taskIds.isEmpty()) {
            return view;
        }

        List<RunRecordEntity> records = runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getTenantId, securityService.currentTenantId())
                .eq(securityService.currentProjectId() != null, RunRecordEntity::getProjectId, securityService.currentProjectId())
                .in(RunRecordEntity::getCollectionTaskId, taskIds)
                .in(RunRecordEntity::getStatus, Arrays.asList("SUCCESS", "FAILED"))
                .isNotNull(RunRecordEntity::getEndedAt)
                .ge(RunRecordEntity::getEndedAt, timeWindow.getStart())
                .le(RunRecordEntity::getEndedAt, timeWindow.getEnd())
                .orderByAsc(RunRecordEntity::getEndedAt)
                .orderByAsc(RunRecordEntity::getId));

        List<Bucket> buckets = buildBuckets(timeWindow, granularity);
        Map<String, List<Long>> trendValues = initializeTrendValues(buckets.size());
        Map<Long, RunMetricTopNItemView> sourceDatasourceTopN = new LinkedHashMap<Long, RunMetricTopNItemView>();
        Map<Long, RunMetricTopNItemView> targetDatasourceTopN = new LinkedHashMap<Long, RunMetricTopNItemView>();
        Map<Long, RunMetricTopNItemView> sourceModelTopN = new LinkedHashMap<Long, RunMetricTopNItemView>();
        Map<Long, RunMetricTopNItemView> targetModelTopN = new LinkedHashMap<Long, RunMetricTopNItemView>();

        long legacyCount = 0L;
        for (RunRecordEntity record : records) {
            if (!runMetricSummaryMapper.hasPreciseMetrics(record)) {
                legacyCount++;
                continue;
            }
            int bucketIndex = resolveBucketIndex(record.getEndedAt(), buckets);
            if (bucketIndex < 0) {
                continue;
            }
            RunMetricSummaryView summary = runMetricSummaryMapper.fromEntity(record);
            mergeTrendValues(trendValues, bucketIndex, summary);
            mergeTopN(sourceDatasourceTopN, sourceModelTopN, targetDatasourceTopN, targetModelTopN, taskById.get(record.getCollectionTaskId()), summary);
        }

        view.setTrend(toTrendView(buckets, trendValues));
        view.setSourceDatasourceTopN(toTopNView(sourceDatasourceTopN, topN));
        view.setTargetDatasourceTopN(toTopNView(targetDatasourceTopN, topN));
        view.setSourceModelTopN(toTopNView(sourceModelTopN, topN));
        view.setTargetModelTopN(toTopNView(targetModelTopN, topN));
        view.setLegacyRunCount(Long.valueOf(legacyCount));
        return view;
    }

    private void mergeTopN(Map<Long, RunMetricTopNItemView> sourceDatasourceTopN,
                           Map<Long, RunMetricTopNItemView> sourceModelTopN,
                           Map<Long, RunMetricTopNItemView> targetDatasourceTopN,
                           Map<Long, RunMetricTopNItemView> targetModelTopN,
                           CollectionTaskDefinitionView task,
                           RunMetricSummaryView summary) {
        if (task == null || summary == null) {
            return;
        }
        long sourceCount = safeValue(summary.getReadSucceedRecords());
        long targetCount = safeValue(summary.getSuccessRecords());
        for (CollectionTaskSourceBinding sourceBinding : emptySourceBindings(task)) {
            accumulate(sourceDatasourceTopN,
                    asLong(sourceBinding.getDatasourceId()),
                    sourceBinding.getDatasourceName(),
                    buildDatasourceLabel(sourceBinding.getDatasourceName(), sourceBinding.getDatasourceTypeCode()),
                    sourceBinding.getDatasourceTypeCode(),
                    sourceCount);
            accumulate(sourceModelTopN,
                    asLong(sourceBinding.getModelId()),
                    sourceBinding.getModelName(),
                    buildModelLabel(sourceBinding.getModelName(), sourceBinding.getModelPhysicalLocator()),
                    sourceBinding.getDatasourceTypeCode(),
                    sourceCount);
        }
        CollectionTaskTargetBinding targetBinding = task.getTargetBinding();
        if (targetBinding == null) {
            return;
        }
        accumulate(targetDatasourceTopN,
                asLong(targetBinding.getDatasourceId()),
                targetBinding.getDatasourceName(),
                buildDatasourceLabel(targetBinding.getDatasourceName(), targetBinding.getDatasourceTypeCode()),
                targetBinding.getDatasourceTypeCode(),
                targetCount);
        accumulate(targetModelTopN,
                asLong(targetBinding.getModelId()),
                targetBinding.getModelName(),
                buildModelLabel(targetBinding.getModelName(), targetBinding.getModelPhysicalLocator()),
                targetBinding.getDatasourceTypeCode(),
                targetCount);
    }

    private void mergeTrendValues(Map<String, List<Long>> trendValues,
                                  int bucketIndex,
                                  RunMetricSummaryView summary) {
        addMetric(trendValues, "collectedRecords", bucketIndex, summary.getCollectedRecords());
        addMetric(trendValues, "successRecords", bucketIndex, summary.getSuccessRecords());
        addMetric(trendValues, "failedRecords", bucketIndex, summary.getFailedRecords());
        addMetric(trendValues, "transformerTotalRecords", bucketIndex, summary.getTransformerTotalRecords());
        addMetric(trendValues, "transformerSuccessRecords", bucketIndex, summary.getTransformerSuccessRecords());
        addMetric(trendValues, "transformerFailedRecords", bucketIndex, summary.getTransformerFailedRecords());
        addMetric(trendValues, "transformerFilterRecords", bucketIndex, summary.getTransformerFilterRecords());
    }

    private void addMetric(Map<String, List<Long>> trendValues, String key, int bucketIndex, Long value) {
        List<Long> values = trendValues.get(key);
        if (values == null || bucketIndex < 0 || bucketIndex >= values.size()) {
            return;
        }
        values.set(bucketIndex, Long.valueOf(values.get(bucketIndex).longValue() + safeValue(value)));
    }

    private RunMetricTrendView toTrendView(List<Bucket> buckets, Map<String, List<Long>> trendValues) {
        RunMetricTrendView trend = new RunMetricTrendView();
        List<String> xAxis = new ArrayList<String>();
        for (Bucket bucket : buckets) {
            xAxis.add(bucket.getLabel());
        }
        trend.setXAxis(xAxis);
        List<RunMetricTrendSeriesView> seriesViews = new ArrayList<RunMetricTrendSeriesView>();
        for (String key : TREND_METRIC_KEYS) {
            RunMetricTrendSeriesView series = new RunMetricTrendSeriesView();
            series.setKey(key);
            series.setName(key);
            series.setData(new ArrayList<Long>(trendValues.getOrDefault(key, Collections.<Long>emptyList())));
            seriesViews.add(series);
        }
        trend.setSeries(seriesViews);
        return trend;
    }

    private Map<String, List<Long>> initializeTrendValues(int bucketSize) {
        Map<String, List<Long>> trendValues = new LinkedHashMap<String, List<Long>>();
        for (String key : TREND_METRIC_KEYS) {
            List<Long> values = new ArrayList<Long>();
            for (int index = 0; index < bucketSize; index++) {
                values.add(Long.valueOf(0L));
            }
            trendValues.put(key, values);
        }
        return trendValues;
    }

    private RunMetricTrendView createEmptyTrend(TimeWindow timeWindow, String granularity) {
        return toTrendView(buildBuckets(timeWindow, granularity), initializeTrendValues(buildBuckets(timeWindow, granularity).size()));
    }

    private List<Bucket> buildBuckets(TimeWindow timeWindow, String granularity) {
        List<Bucket> buckets = new ArrayList<Bucket>();
        LocalDate cursor = alignDate(timeWindow.getStart().toLocalDate(), granularity);
        LocalDate end = alignDate(timeWindow.getEnd().toLocalDate(), granularity);
        while (!cursor.isAfter(end)) {
            buckets.add(new Bucket(cursor, formatBucketLabel(cursor, granularity)));
            cursor = stepDate(cursor, granularity);
        }
        return buckets;
    }

    private int resolveBucketIndex(LocalDateTime endedAt, List<Bucket> buckets) {
        if (endedAt == null || buckets.isEmpty()) {
            return -1;
        }
        LocalDate bucketDate = alignDate(endedAt.toLocalDate(), guessGranularity(buckets));
        for (int index = 0; index < buckets.size(); index++) {
            if (buckets.get(index).getDate().equals(bucketDate)) {
                return index;
            }
        }
        return -1;
    }

    private String guessGranularity(List<Bucket> buckets) {
        if (buckets.size() >= 2) {
            LocalDate first = buckets.get(0).getDate();
            LocalDate second = buckets.get(1).getDate();
            int diff = (int) java.time.temporal.ChronoUnit.DAYS.between(first, second);
            if (diff >= 27) {
                return GRANULARITY_MONTH;
            }
            if (diff >= 6) {
                return GRANULARITY_WEEK;
            }
        }
        return GRANULARITY_DAY;
    }

    private LocalDate alignDate(LocalDate date, String granularity) {
        if (GRANULARITY_MONTH.equals(granularity)) {
            return date.withDayOfMonth(1);
        }
        if (GRANULARITY_WEEK.equals(granularity)) {
            return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }
        return date;
    }

    private LocalDate stepDate(LocalDate date, String granularity) {
        if (GRANULARITY_MONTH.equals(granularity)) {
            return date.plusMonths(1L);
        }
        if (GRANULARITY_WEEK.equals(granularity)) {
            return date.plusWeeks(1L);
        }
        return date.plusDays(1L);
    }

    private String formatBucketLabel(LocalDate date, String granularity) {
        if (GRANULARITY_MONTH.equals(granularity)) {
            return MONTH_FORMATTER.format(date);
        }
        return DAY_FORMATTER.format(date);
    }

    private List<CollectionTaskDefinitionView> filterTasks(List<CollectionTaskDefinitionView> tasks,
                                                           RunMetricDashboardQueryRequest request) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        Long datasourceId = request == null ? null : request.getDatasourceId();
        Long sourceModelId = request == null ? null : request.getSourceModelId();
        Long targetModelId = request == null ? null : request.getTargetModelId();
        List<CollectionTaskDefinitionView> result = new ArrayList<CollectionTaskDefinitionView>();
        for (CollectionTaskDefinitionView task : tasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            if (datasourceId != null && !matchesDatasource(task, datasourceId)) {
                continue;
            }
            if (sourceModelId != null && !matchesSourceModel(task, sourceModelId)) {
                continue;
            }
            if (targetModelId != null && !matchesTargetModel(task, targetModelId)) {
                continue;
            }
            result.add(task);
        }
        return result;
    }

    private boolean matchesDatasource(CollectionTaskDefinitionView task, Long datasourceId) {
        for (CollectionTaskSourceBinding sourceBinding : emptySourceBindings(task)) {
            if (datasourceId.equals(asLong(sourceBinding.getDatasourceId()))) {
                return true;
            }
        }
        CollectionTaskTargetBinding targetBinding = task.getTargetBinding();
        return targetBinding != null && datasourceId.equals(asLong(targetBinding.getDatasourceId()));
    }

    private boolean matchesSourceModel(CollectionTaskDefinitionView task, Long sourceModelId) {
        for (CollectionTaskSourceBinding sourceBinding : emptySourceBindings(task)) {
            if (sourceModelId.equals(asLong(sourceBinding.getModelId()))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesTargetModel(CollectionTaskDefinitionView task, Long targetModelId) {
        CollectionTaskTargetBinding targetBinding = task.getTargetBinding();
        return targetBinding != null && targetModelId.equals(asLong(targetBinding.getModelId()));
    }

    private TimeWindow resolveTimeWindow(RunMetricDashboardQueryRequest request) {
        LocalDateTime end = request != null && request.getEndTime() != null
                ? parseRequestTime(request.getEndTime())
                : LocalDateTime.now();
        LocalDateTime start = request != null && request.getStartTime() != null
                ? parseRequestTime(request.getStartTime())
                : end.minusDays(29L);
        if (start.isAfter(end)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Start time must be earlier than end time");
        }
        return new TimeWindow(start, end);
    }

    private LocalDateTime parseRequestTime(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Time value must not be empty");
        }
        String text = value.trim();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text, REQUEST_TIME_FORMATTER);
            } catch (DateTimeParseException ex) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid time format: " + text);
            }
        }
    }

    private int normalizeTopN(Integer topN) {
        if (topN == null || topN.intValue() <= 0) {
            return 10;
        }
        return Math.min(topN.intValue(), 100);
    }

    private String normalizeGranularity(String granularity) {
        String normalized = granularity == null ? GRANULARITY_DAY : granularity.trim().toUpperCase(Locale.ROOT);
        if (!GRANULARITY_DAY.equals(normalized)
                && !GRANULARITY_WEEK.equals(normalized)
                && !GRANULARITY_MONTH.equals(normalized)) {
            return GRANULARITY_DAY;
        }
        return normalized;
    }

    private List<CollectionTaskSourceBinding> emptySourceBindings(CollectionTaskDefinitionView task) {
        return task == null || task.getSourceBindings() == null
                ? Collections.<CollectionTaskSourceBinding>emptyList()
                : task.getSourceBindings();
    }

    private void registerOption(Map<Long, RunMetricFilterOptionView> options,
                                Long id,
                                String name,
                                String label,
                                String typeCode) {
        if (id == null || options.containsKey(id)) {
            return;
        }
        RunMetricFilterOptionView option = new RunMetricFilterOptionView();
        option.setId(id);
        option.setName(name);
        option.setLabel(label);
        option.setTypeCode(typeCode);
        options.put(id, option);
    }

    private List<RunMetricFilterOptionView> sortOptions(Map<Long, RunMetricFilterOptionView> options) {
        List<RunMetricFilterOptionView> result = new ArrayList<RunMetricFilterOptionView>(options.values());
        result.sort((left, right) -> normalizeSortValue(left.getLabel()).compareToIgnoreCase(normalizeSortValue(right.getLabel())));
        return result;
    }

    private String normalizeSortValue(String value) {
        return value == null ? "" : value;
    }

    private String buildDatasourceLabel(String name, String typeCode) {
        if (typeCode == null || typeCode.trim().isEmpty()) {
            return name;
        }
        return String.format("%s (%s)", name, typeCode);
    }

    private String buildModelLabel(String name, String locator) {
        if (locator == null || locator.trim().isEmpty() || locator.equals(name)) {
            return name;
        }
        return String.format("%s (%s)", name, locator);
    }

    private Long asLong(Object value) {
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (!text.isEmpty()) {
                try {
                    return Long.valueOf(text);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private void accumulate(Map<Long, RunMetricTopNItemView> target,
                            Long id,
                            String name,
                            String label,
                            String typeCode,
                            long delta) {
        if (id == null) {
            return;
        }
        RunMetricTopNItemView item = target.get(id);
        if (item == null) {
            item = new RunMetricTopNItemView();
            item.setId(id);
            item.setName(name);
            item.setLabel(label);
            item.setTypeCode(typeCode);
            item.setCount(Long.valueOf(0L));
            target.put(id, item);
        }
        item.setCount(Long.valueOf(safeValue(item.getCount()) + delta));
    }

    private List<RunMetricTopNItemView> toTopNView(Map<Long, RunMetricTopNItemView> values, int topN) {
        List<RunMetricTopNItemView> result = new ArrayList<RunMetricTopNItemView>(values.values());
        result.sort((left, right) -> {
            int compare = Long.compare(safeValue(right.getCount()), safeValue(left.getCount()));
            if (compare != 0) {
                return compare;
            }
            return normalizeSortValue(left.getLabel()).compareToIgnoreCase(normalizeSortValue(right.getLabel()));
        });
        if (result.size() <= topN) {
            return result;
        }
        return new ArrayList<RunMetricTopNItemView>(result.subList(0, topN));
    }

    private long safeValue(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private static final class Bucket {
        private final LocalDate date;
        private final String label;

        private Bucket(LocalDate date, String label) {
            this.date = date;
            this.label = label;
        }

        public LocalDate getDate() {
            return date;
        }

        public String getLabel() {
            return label;
        }
    }

    private static final class TimeWindow {
        private final LocalDateTime start;
        private final LocalDateTime end;

        private TimeWindow(LocalDateTime start, LocalDateTime end) {
            this.start = start;
            this.end = end;
        }

        public LocalDateTime getStart() {
            return start;
        }

        public LocalDateTime getEnd() {
            return end;
        }
    }
}
