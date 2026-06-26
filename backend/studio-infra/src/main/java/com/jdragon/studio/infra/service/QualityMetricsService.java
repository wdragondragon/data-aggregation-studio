package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.QualityAssetDetailView;
import com.jdragon.studio.dto.model.QualityAssetRiskView;
import com.jdragon.studio.dto.model.QualityIssueView;
import com.jdragon.studio.dto.model.QualityMetricDashboardView;
import com.jdragon.studio.dto.model.QualityMetricOptionsView;
import com.jdragon.studio.dto.model.QualityTaskListView;
import com.jdragon.studio.dto.model.QualityScoreTrendPoint;
import com.jdragon.studio.dto.model.RunMetricFilterOptionView;
import com.jdragon.studio.dto.model.request.QualityAssetQueryRequest;
import com.jdragon.studio.dto.model.request.QualityMetricDashboardQueryRequest;
import com.jdragon.studio.infra.entity.QualityIssueEntity;
import com.jdragon.studio.infra.entity.QualityIssueEventEntity;
import com.jdragon.studio.infra.entity.QualityMetricSnapshotEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.QualityMetricSnapshotMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class QualityMetricsService {

    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final QualityTaskService qualityTaskService;
    private final QualityIssueService qualityIssueService;
    private final RunRecordMapper runRecordMapper;
    private final QualityMetricSnapshotMapper snapshotMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final QualityMetricCalculationSupport calculationSupport = new QualityMetricCalculationSupport();

    public QualityMetricsService(DataSourceService dataSourceService,
                                 DataModelService dataModelService,
                                 QualityTaskService qualityTaskService,
                                 QualityIssueService qualityIssueService,
                                 RunRecordMapper runRecordMapper,
                                 QualityMetricSnapshotMapper snapshotMapper,
                                 StudioSecurityService securityService,
                                 ProjectResourceAccessService projectResourceAccessService) {
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.qualityTaskService = qualityTaskService;
        this.qualityIssueService = qualityIssueService;
        this.runRecordMapper = runRecordMapper;
        this.snapshotMapper = snapshotMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    public QualityMetricOptionsView options() {
        QualityMetricOptionsView view = new QualityMetricOptionsView();
        for (DataSourceDefinition datasource : dataSourceService.list()) {
            RunMetricFilterOptionView option = new RunMetricFilterOptionView();
            option.setId(datasource.getId());
            option.setName(datasource.getName());
            option.setLabel(datasource.getName() + " / " + datasource.getTypeCode());
            option.setTypeCode(datasource.getTypeCode());
            view.getDatasources().add(option);
        }
        for (DataModelDefinition model : dataModelService.list()) {
            RunMetricFilterOptionView option = new RunMetricFilterOptionView();
            option.setId(model.getId());
            option.setName(model.getName());
            option.setLabel(model.getName() + " / " + model.getPhysicalLocator());
            option.setTypeCode(null);
            view.getModels().add(option);
        }
        return view;
    }

    public QualityMetricDashboardView queryDashboard(QualityMetricDashboardQueryRequest request) {
        MetricsContext context = buildContext(request == null ? null : request.getDatasourceId(),
                request == null ? null : request.getModelId(),
                request == null ? null : request.getRuleDimension(),
                request == null ? null : request.getGranularity(),
                request == null ? null : request.getTaskStatus(),
                request == null ? null : request.getStartTime(),
                request == null ? null : request.getEndTime());
        QualityMetricDashboardView view = new QualityMetricDashboardView();
        if (!context.available) {
            return view;
        }
        if (context.coveredAssetKeys.isEmpty()) {
            return view;
        }
        List<QualityAssetRiskView> assets = buildAssetViews(context, null, null);
        long executionHealth = calculationSupport.executionHealth(context.runRecords, context.activeIssues);
        long governanceRisk = calculationSupport.governanceRisk(context.activeIssues);
        long overdueCount = calculationSupport.overdueCount(context.activeIssues);
        long affectedAssetCount = calculationSupport.affectedAssetCount(context.activeIssues);
        view.getSummaryMetrics().put("executionHealthScore", Long.valueOf(executionHealth));
        view.getSummaryMetrics().put("governanceRiskScore", Long.valueOf(governanceRisk));
        view.getSummaryMetrics().put("activeIssueCount", Long.valueOf(context.activeIssues.size()));
        view.getSummaryMetrics().put("overdueIssueCount", Long.valueOf(overdueCount));
        view.getSummaryMetrics().put("affectedAssetCount", Long.valueOf(affectedAssetCount));
        view.getSummaryMetrics().put("managedAssetCount", Long.valueOf(assets.size()));
        view.setScoreTrend(buildScoreTrend(context));
        view.setIssueTrend(buildIssueTrend(context));
        view.setDimensionDistribution(buildDimensionDistribution(context));
        view.setCoverageMatrix(buildCoverageMatrix(context));
        view.setRiskyAssets(limitAssets(assets, calculationSupport.normalizeTopN(request == null ? null : request.getTopN())));
        view.setNoisyTargets(buildNoisyTargets(context, calculationSupport.normalizeTopN(request == null ? null : request.getTopN())));
        persistSnapshots(context, assets, view);
        return view;
    }

    public List<QualityAssetRiskView> queryAssets(QualityAssetQueryRequest request) {
        MetricsContext context = buildContext(request == null ? null : request.getDatasourceId(),
                request == null ? null : request.getModelId(),
                request == null ? null : request.getRuleDimension(),
                request == null ? null : request.getGranularity(),
                request == null ? null : request.getTaskStatus(),
                request == null ? null : request.getStartTime(),
                request == null ? null : request.getEndTime());
        if (!context.available) {
            return new ArrayList<QualityAssetRiskView>();
        }
        return buildAssetViews(context,
                request == null ? null : request.getOnlyProblemAssets(),
                request == null ? null : request.getOnlyLowCoverageAssets());
    }

    public QualityAssetDetailView getAssetDetail(String assetId, LocalDateTime startTime, LocalDateTime endTime) {
        Long datasourceId = calculationSupport.parseAssetPart(assetId, 0);
        Long modelId = calculationSupport.parseAssetPart(assetId, 1);
        MetricsContext context = buildContext(datasourceId, modelId, null, null, null, startTime, endTime);
        QualityAssetDetailView detail = new QualityAssetDetailView();
        if (!context.available) {
            return detail;
        }
        List<QualityAssetRiskView> assets = buildAssetViews(context, null, null);
        if (!assets.isEmpty()) {
            copyAsset(detail, assets.get(0));
        }
        detail.getSummaryMetrics().put("coverageRate", Long.valueOf(detail.getCoverageRate() == null ? 0L : detail.getCoverageRate()));
        detail.getSummaryMetrics().put("activeIssueCount", Long.valueOf(detail.getActiveIssueCount() == null ? 0L : detail.getActiveIssueCount()));
        detail.getSummaryMetrics().put("overdueIssueCount", Long.valueOf(detail.getOverdueIssueCount() == null ? 0L : detail.getOverdueIssueCount()));
        detail.setScoreTrend(buildScoreTrend(context));
        detail.setActiveIssues(sortedIssueViews(context.activeIssues));
        detail.setRelatedTasks(buildRelatedTasks(context));
        detail.setLatestFailures(buildLatestFailures(context));
        detail.setFieldIssueGroups(buildFieldIssueGroups(context));
        return detail;
    }

    private MetricsContext buildContext(Long datasourceId,
                                        Long modelId,
                                        String ruleDimension,
                                        String granularity,
                                        String taskStatus,
                                        LocalDateTime startTime,
                                        LocalDateTime endTime) {
        MetricsContext context = new MetricsContext();
        context.available = projectResourceAccessService.currentProjectId() != null;
        if (!context.available) {
            return context;
        }
        context.startTime = startTime == null ? LocalDateTime.now().minusDays(7) : startTime;
        context.endTime = endTime == null ? LocalDateTime.now() : endTime;
        for (DataSourceDefinition datasource : dataSourceService.list()) {
            if (datasource.getId() != null) {
                context.datasourceById.put(datasource.getId(), datasource);
            }
        }
        for (DataModelDefinition model : dataModelService.list()) {
            if (datasourceId != null && !datasourceId.equals(model.getDatasourceId())) {
                continue;
            }
            if (modelId != null && !modelId.equals(model.getId())) {
                continue;
            }
            context.models.add(model);
            if (model.getId() != null) {
                context.modelById.put(model.getId(), model);
            }
        }
        for (QualityTaskListView task : qualityTaskService.list(null, taskStatus, ruleDimension, granularity)) {
            if (datasourceId != null && !datasourceId.equals(task.getDatasourceId())) {
                continue;
            }
            if (modelId != null && !modelId.equals(task.getModelId())) {
                continue;
            }
            context.tasks.add(task);
            if (task.getId() != null) {
                context.taskById.put(task.getId(), task);
                context.taskIds.add(task.getId());
            }
            context.coveredAssetKeys.add(calculationSupport.assetKey(task.getDatasourceId(), task.getModelId()));
        }
        if (!context.taskIds.isEmpty()) {
            context.runRecords = runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                    .eq(RunRecordEntity::getTenantId, securityService.currentTenantId())
                    .eq(projectResourceAccessService.currentProjectId() != null, RunRecordEntity::getProjectId, projectResourceAccessService.currentProjectId())
                    .in(RunRecordEntity::getQualityTaskId, context.taskIds)
                    .in(RunRecordEntity::getStatus, Arrays.asList("SUCCESS", "FAILED"))
                    .ge(RunRecordEntity::getEndedAt, context.startTime)
                    .le(RunRecordEntity::getEndedAt, context.endTime)
                    .orderByDesc(RunRecordEntity::getEndedAt)
                    .orderByDesc(RunRecordEntity::getId));
        }
        for (QualityIssueEntity issue : qualityIssueService.listProjectIssues()) {
            if (!calculationSupport.matchesIssue(issue, datasourceId, modelId, ruleDimension, granularity, context.taskIds)) {
                continue;
            }
            context.issues.add(issue);
            if (calculationSupport.isActive(issue)) {
                context.activeIssues.add(issue);
            }
        }
        for (QualityIssueEventEntity event : qualityIssueService.listProjectIssueEvents(context.startTime, context.endTime)) {
            QualityIssueEntity issue = calculationSupport.findIssue(context.issues, event.getIssueId());
            if (issue != null) {
                context.issueEvents.add(event);
            }
        }
        return context;
    }

    private List<QualityAssetRiskView> buildAssetViews(MetricsContext context, Boolean onlyProblems, Boolean onlyLowCoverage) {
        Map<String, AssetState> assetStates = new LinkedHashMap<String, AssetState>();
        for (QualityTaskListView task : context.tasks) {
            AssetState state = ensureAssetState(assetStates, task.getDatasourceId(), task.getModelId());
            state.datasource = context.datasourceById.get(task.getDatasourceId());
            if (state.model == null && task.getModelId() != null) {
                state.model = context.modelById.get(task.getModelId());
            }
            state.tasks.add(task);
            if (task.getRuleDimension() != null) {
                state.coverageDimensions.add(task.getRuleDimension().name());
            }
        }
        for (RunRecordEntity record : context.runRecords) {
            QualityTaskListView task = context.taskById.get(record.getQualityTaskId());
            if (task == null) {
                continue;
            }
            AssetState state = ensureAssetState(assetStates, task.getDatasourceId(), task.getModelId());
            state.runRecords.add(record);
        }
        for (QualityIssueEntity issue : context.issues) {
            AssetState state = ensureAssetState(assetStates, issue.getDatasourceId(), issue.getModelId());
            state.issues.add(issue);
            if (calculationSupport.isActive(issue)) {
                state.activeIssues.add(issue);
            }
            if (calculationSupport.hasText(issue.getRuleDimension())) {
                state.riskDimensions.add(issue.getRuleDimension());
            }
        }
        List<QualityAssetRiskView> views = new ArrayList<QualityAssetRiskView>();
        for (AssetState state : assetStates.values()) {
            QualityAssetRiskView view = toAssetView(state);
            if (Boolean.TRUE.equals(onlyProblems) && (view.getActiveIssueCount() == null || view.getActiveIssueCount() <= 0)) {
                continue;
            }
            if (Boolean.TRUE.equals(onlyLowCoverage) && (view.getCoverageRate() == null || view.getCoverageRate() >= 50L)) {
                continue;
            }
            views.add(view);
        }
        Collections.sort(views, new Comparator<QualityAssetRiskView>() {
            @Override
            public int compare(QualityAssetRiskView left, QualityAssetRiskView right) {
                int riskCompare = Long.compare(calculationSupport.safeLong(right.getGovernanceRiskScore()), calculationSupport.safeLong(left.getGovernanceRiskScore()));
                if (riskCompare != 0) {
                    return riskCompare;
                }
                int issueCompare = Long.compare(calculationSupport.safeLong(right.getActiveIssueCount()), calculationSupport.safeLong(left.getActiveIssueCount()));
                if (issueCompare != 0) {
                    return issueCompare;
                }
                return calculationSupport.compareTimeDesc(left.getLatestRunAt(), right.getLatestRunAt());
            }
        });
        return views;
    }

    private AssetState ensureAssetState(Map<String, AssetState> states, Long datasourceId, Long modelId) {
        String key = calculationSupport.assetKey(datasourceId, modelId);
        AssetState state = states.get(key);
        if (state != null) {
            return state;
        }
        state = new AssetState();
        state.assetId = key;
        state.datasourceId = datasourceId;
        state.modelId = modelId;
        states.put(key, state);
        return state;
    }

    private QualityAssetRiskView toAssetView(AssetState state) {
        QualityAssetRiskView view = new QualityAssetRiskView();
        view.setAssetId(state.assetId);
        view.setDatasourceId(state.datasourceId);
        view.setDatasourceName(state.datasource != null ? state.datasource.getName() : (state.tasks.isEmpty() ? null : state.tasks.get(0).getDatasourceName()));
        view.setDatasourceTypeCode(state.datasource != null ? state.datasource.getTypeCode() : (state.tasks.isEmpty() ? null : state.tasks.get(0).getDatasourceTypeCode()));
        view.setModelId(state.modelId);
        view.setModelName(state.model != null ? state.model.getName() : (state.tasks.isEmpty() ? null : state.tasks.get(0).getModelName()));
        view.setModelPhysicalLocator(state.model != null ? state.model.getPhysicalLocator() : (state.tasks.isEmpty() ? null : state.tasks.get(0).getModelPhysicalLocator()));
        long coverageRate = calculationSupport.coverageRate(state.coverageDimensions.size(), 6);
        view.setCoverageRate(Long.valueOf(coverageRate));
        view.setCoverageDimensions(new ArrayList<String>(state.coverageDimensions));
        view.setRiskDimensions(new ArrayList<String>(state.riskDimensions));
        view.setActiveIssueCount(Long.valueOf(state.activeIssues.size()));
        view.setOverdueIssueCount(Long.valueOf(calculationSupport.overdueCount(state.activeIssues)));
        view.setExecutionHealthScore(Long.valueOf(calculationSupport.executionHealth(state.runRecords, state.activeIssues)));
        view.setGovernanceRiskScore(Long.valueOf(calculationSupport.governanceRisk(state.activeIssues)));
        RunRecordEntity latest = calculationSupport.latestRecord(state.runRecords);
        if (latest != null) {
            view.setLatestRunAt(latest.getEndedAt());
            view.setLatestRunStatus(calculationSupport.isFailureOrAlert(latest) ? "FAILED" : latest.getStatus());
            view.setLatestEvidence(latest.getMessage());
        } else if (!state.activeIssues.isEmpty()) {
            QualityIssueEntity latestIssue = calculationSupport.latestIssue(state.activeIssues);
            view.setLatestEvidence(latestIssue == null ? null : latestIssue.getLatestMessage());
        }
        return view;
    }

    private List<QualityScoreTrendPoint> buildScoreTrend(MetricsContext context) {
        Map<LocalDate, List<RunRecordEntity>> runsByDay = new LinkedHashMap<LocalDate, List<RunRecordEntity>>();
        for (LocalDate day = context.startTime.toLocalDate(); !day.isAfter(context.endTime.toLocalDate()); day = day.plusDays(1)) {
            runsByDay.put(day, new ArrayList<RunRecordEntity>());
        }
        for (RunRecordEntity record : context.runRecords) {
            if (record.getEndedAt() == null) {
                continue;
            }
            LocalDate day = record.getEndedAt().toLocalDate();
            if (runsByDay.containsKey(day)) {
                runsByDay.get(day).add(record);
            }
        }
        List<QualityScoreTrendPoint> trend = new ArrayList<QualityScoreTrendPoint>();
        for (Map.Entry<LocalDate, List<RunRecordEntity>> entry : runsByDay.entrySet()) {
            QualityScoreTrendPoint point = new QualityScoreTrendPoint();
            point.setDateLabel(entry.getKey().toString());
            point.setExecutionHealthScore(Long.valueOf(calculationSupport.executionHealth(entry.getValue(), context.activeIssues)));
            point.setGovernanceRiskScore(Long.valueOf(calculationSupport.governanceRisk(context.activeIssues)));
            trend.add(point);
        }
        return trend;
    }

    private List<Map<String, Object>> buildIssueTrend(MetricsContext context) {
        Map<String, Map<String, Long>> bucket = new LinkedHashMap<String, Map<String, Long>>();
        for (QualityIssueEventEntity event : context.issueEvents) {
            LocalDateTime createdAt = event.getCreatedAt();
            if (createdAt == null) {
                continue;
            }
            QualityIssueEntity issue = calculationSupport.findIssue(context.issues, event.getIssueId());
            String date = createdAt.toLocalDate().toString();
            Map<String, Long> metrics = bucket.containsKey(date) ? bucket.get(date) : new LinkedHashMap<String, Long>();
            if (!bucket.containsKey(date)) {
                bucket.put(date, metrics);
                metrics.put("newLow", Long.valueOf(0L));
                metrics.put("newMedium", Long.valueOf(0L));
                metrics.put("newHigh", Long.valueOf(0L));
                metrics.put("newCritical", Long.valueOf(0L));
                metrics.put("resolvedLow", Long.valueOf(0L));
                metrics.put("resolvedMedium", Long.valueOf(0L));
                metrics.put("resolvedHigh", Long.valueOf(0L));
                metrics.put("resolvedCritical", Long.valueOf(0L));
            }
            String severityKey = calculationSupport.severityKey(issue == null ? null : issue.getSeverity());
            if ("CREATED".equalsIgnoreCase(event.getEventType()) || "REOPENED".equalsIgnoreCase(event.getEventType()) || "DETECTED".equalsIgnoreCase(event.getEventType())) {
                metrics.put("new" + severityKey, Long.valueOf(calculationSupport.safeLong(metrics.get("new" + severityKey)) + 1L));
            }
            if ("STATUS_CHANGED".equalsIgnoreCase(event.getEventType())
                    && event.getMetadataJson() != null
                    && ("RESOLVED".equalsIgnoreCase(String.valueOf(event.getMetadataJson().get("currentStatus")))
                    || "FALSE_POSITIVE".equalsIgnoreCase(String.valueOf(event.getMetadataJson().get("currentStatus"))))) {
                metrics.put("resolved" + severityKey, Long.valueOf(calculationSupport.safeLong(metrics.get("resolved" + severityKey)) + 1L));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Map<String, Long>> entry : bucket.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("dateLabel", entry.getKey());
            row.putAll(entry.getValue());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildDimensionDistribution(MetricsContext context) {
        Map<String, long[]> metrics = new LinkedHashMap<String, long[]>();
        metrics.put("CONSISTENCY", new long[]{0L, 0L});
        metrics.put("ACCURACY", new long[]{0L, 0L});
        metrics.put("UNIQUENESS", new long[]{0L, 0L});
        metrics.put("TIMELINESS", new long[]{0L, 0L});
        metrics.put("COMPLETENESS", new long[]{0L, 0L});
        metrics.put("VALIDITY", new long[]{0L, 0L});
        for (RunRecordEntity record : context.runRecords) {
            QualityTaskListView task = context.taskById.get(record.getQualityTaskId());
            String dimension = task == null || task.getRuleDimension() == null ? null : task.getRuleDimension().name();
            if (!metrics.containsKey(dimension)) {
                continue;
            }
            metrics.get(dimension)[0]++;
            if (calculationSupport.isFailureOrAlert(record)) {
                metrics.get(dimension)[1]++;
            }
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, long[]> entry : metrics.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("ruleDimension", entry.getKey());
            row.put("totalRuns", Long.valueOf(entry.getValue()[0]));
            row.put("failedRuns", Long.valueOf(entry.getValue()[1]));
            row.put("failureRatio", Long.valueOf(entry.getValue()[0] <= 0L ? 0L : Math.round((entry.getValue()[1] * 100.0d) / entry.getValue()[0])));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildCoverageMatrix(MetricsContext context) {
        Map<String, Set<Long>> matrix = new LinkedHashMap<String, Set<Long>>();
        for (QualityTaskListView task : context.tasks) {
            String key = calculationSupport.safeText(task.getDatasourceTypeCode(), "UNKNOWN") + "|" + calculationSupport.safeText(task.getRuleDimension() == null ? null : task.getRuleDimension().name(), "UNKNOWN");
            Set<Long> modelIds = matrix.containsKey(key) ? matrix.get(key) : new LinkedHashSet<Long>();
            if (!matrix.containsKey(key)) {
                matrix.put(key, modelIds);
            }
            if (task.getModelId() != null) {
                modelIds.add(task.getModelId());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Set<Long>> entry : matrix.entrySet()) {
            String[] parts = entry.getKey().split("\\|", -1);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("datasourceTypeCode", parts.length > 0 ? parts[0] : "UNKNOWN");
            row.put("ruleDimension", parts.length > 1 ? parts[1] : "UNKNOWN");
            row.put("value", Long.valueOf(entry.getValue().size()));
            rows.add(row);
        }
        return rows;
    }

    private List<QualityAssetRiskView> limitAssets(List<QualityAssetRiskView> assets, int topN) {
        if (assets.size() <= topN) {
            return assets;
        }
        return new ArrayList<QualityAssetRiskView>(assets.subList(0, topN));
    }

    private List<Map<String, Object>> buildNoisyTargets(MetricsContext context, int topN) {
        Map<String, Map<String, Object>> noisy = new LinkedHashMap<String, Map<String, Object>>();
        for (RunRecordEntity record : context.runRecords) {
            if (!calculationSupport.isFailureOrAlert(record)) {
                continue;
            }
            QualityTaskListView task = context.taskById.get(record.getQualityTaskId());
            if (task != null && task.getId() != null) {
                accumulateNoisy(noisy, "TASK", task.getId(), task.getTaskName(), task.getRuleDimension() == null ? null : task.getRuleDimension().name(), record.getEndedAt());
            }
            if (task != null && task.getRuleId() != null) {
                accumulateNoisy(noisy, "RULE", task.getRuleId(), task.getRuleName(), task.getRuleDimension() == null ? null : task.getRuleDimension().name(), record.getEndedAt());
            }
        }
        for (QualityIssueEntity issue : context.activeIssues) {
            if (issue.getQualityTaskId() != null) {
                bumpReopen(noisy, "TASK", issue.getQualityTaskId(), issue.getReopenCount());
            }
            if (issue.getRuleId() != null) {
                bumpReopen(noisy, "RULE", issue.getRuleId(), issue.getReopenCount());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(noisy.values());
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                int failCompare = Long.compare(calculationSupport.safeLong(right.get("failureCount")), calculationSupport.safeLong(left.get("failureCount")));
                if (failCompare != 0) {
                    return failCompare;
                }
                return Long.compare(calculationSupport.safeLong(right.get("reopenCount")), calculationSupport.safeLong(left.get("reopenCount")));
            }
        });
        if (rows.size() > topN) {
            return new ArrayList<Map<String, Object>>(rows.subList(0, topN));
        }
        return rows;
    }

    private void accumulateNoisy(Map<String, Map<String, Object>> noisy,
                                 String type,
                                 Long id,
                                 String name,
                                 String dimension,
                                 LocalDateTime time) {
        String key = type + ":" + id;
        Map<String, Object> row = noisy.containsKey(key) ? noisy.get(key) : new LinkedHashMap<String, Object>();
        if (!noisy.containsKey(key)) {
            noisy.put(key, row);
            row.put("targetType", type);
            row.put("id", id);
            row.put("name", name);
            row.put("ruleDimension", dimension);
            row.put("failureCount", Long.valueOf(0L));
            row.put("reopenCount", Long.valueOf(0L));
        }
        row.put("failureCount", Long.valueOf(calculationSupport.safeLong(row.get("failureCount")) + 1L));
        row.put("lastSeenAt", time);
    }

    private void bumpReopen(Map<String, Map<String, Object>> noisy, String type, Long id, Integer reopenCount) {
        String key = type + ":" + id;
        if (!noisy.containsKey(key)) {
            return;
        }
        noisy.get(key).put("reopenCount", Long.valueOf(calculationSupport.safeLong(noisy.get(key).get("reopenCount")) + calculationSupport.safeLong(reopenCount)));
    }

    private List<Map<String, Object>> buildRelatedTasks(MetricsContext context) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (QualityTaskListView task : context.tasks) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("taskId", task.getId());
            row.put("taskName", task.getTaskName());
            row.put("ruleId", task.getRuleId());
            row.put("ruleName", task.getRuleName());
            row.put("ruleDimension", task.getRuleDimension() == null ? null : task.getRuleDimension().name());
            row.put("granularity", task.getGranularity() == null ? null : task.getGranularity().name());
            row.put("status", task.getStatus() == null ? null : task.getStatus().name());
            RunRecordEntity latest = calculationSupport.latestTaskRecord(context.runRecords, task.getId());
            row.put("latestRunStatus", latest == null ? null : (calculationSupport.isFailureOrAlert(latest) ? "FAILED" : latest.getStatus()));
            row.put("latestRunAt", latest == null ? null : latest.getEndedAt());
            row.put("latestRunRecordId", latest == null ? null : latest.getId());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildLatestFailures(MetricsContext context) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (RunRecordEntity record : context.runRecords) {
            if (!calculationSupport.isFailureOrAlert(record)) {
                continue;
            }
            QualityTaskListView task = context.taskById.get(record.getQualityTaskId());
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("runRecordId", record.getId());
            row.put("qualityTaskId", task == null ? null : task.getId());
            row.put("taskName", task == null ? null : task.getTaskName());
            row.put("ruleName", task == null ? null : task.getRuleName());
            row.put("message", record.getMessage());
            row.put("endedAt", record.getEndedAt());
            row.put("resolvedSql", record.getResultJson() == null ? null : record.getResultJson().get("resolvedSql"));
            row.put("alertCount", record.getResultJson() == null ? null : record.getResultJson().get("alertCount"));
            rows.add(row);
            if (rows.size() >= 5) {
                break;
            }
        }
        return rows;
    }

    private List<Map<String, Object>> buildFieldIssueGroups(MetricsContext context) {
        Map<String, List<QualityIssueEntity>> grouped = new LinkedHashMap<String, List<QualityIssueEntity>>();
        for (QualityIssueEntity issue : context.activeIssues) {
            if (!calculationSupport.hasText(issue.getColumnName())) {
                continue;
            }
            if (!grouped.containsKey(issue.getColumnName())) {
                grouped.put(issue.getColumnName(), new ArrayList<QualityIssueEntity>());
            }
            grouped.get(issue.getColumnName()).add(issue);
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, List<QualityIssueEntity>> entry : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("columnName", entry.getKey());
            row.put("issueCount", Integer.valueOf(entry.getValue().size()));
            row.put("issues", sortedIssueViews(entry.getValue()));
            rows.add(row);
        }
        return rows;
    }

    private List<QualityIssueView> sortedIssueViews(List<QualityIssueEntity> issues) {
        List<QualityIssueView> rows = new ArrayList<QualityIssueView>();
        for (QualityIssueEntity issue : issues) {
            rows.add(qualityIssueService.get(issue.getId()));
        }
        return rows;
    }

    private void copyAsset(QualityAssetDetailView target, QualityAssetRiskView source) {
        target.setAssetId(source.getAssetId());
        target.setDatasourceId(source.getDatasourceId());
        target.setDatasourceName(source.getDatasourceName());
        target.setDatasourceTypeCode(source.getDatasourceTypeCode());
        target.setModelId(source.getModelId());
        target.setModelName(source.getModelName());
        target.setModelPhysicalLocator(source.getModelPhysicalLocator());
        target.setExecutionHealthScore(source.getExecutionHealthScore());
        target.setGovernanceRiskScore(source.getGovernanceRiskScore());
        target.setActiveIssueCount(source.getActiveIssueCount());
        target.setOverdueIssueCount(source.getOverdueIssueCount());
        target.setCoverageRate(source.getCoverageRate());
        target.setCoverageDimensions(source.getCoverageDimensions());
        target.setRiskDimensions(source.getRiskDimensions());
        target.setLatestRunStatus(source.getLatestRunStatus());
        target.setLatestRunAt(source.getLatestRunAt());
        target.setLatestEvidence(source.getLatestEvidence());
    }

    private void persistSnapshots(MetricsContext context, List<QualityAssetRiskView> assets, QualityMetricDashboardView dashboard) {
        LocalDate snapshotDate = context.endTime.toLocalDate();
        snapshotMapper.delete(new LambdaQueryWrapper<QualityMetricSnapshotEntity>()
                .eq(QualityMetricSnapshotEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityMetricSnapshotEntity::getProjectId, projectResourceAccessService.currentProjectId())
                .eq(QualityMetricSnapshotEntity::getSnapshotDate, snapshotDate));
        QualityMetricSnapshotEntity projectSnapshot = new QualityMetricSnapshotEntity();
        projectSnapshot.setTenantId(securityService.currentTenantId());
        projectSnapshot.setProjectId(projectResourceAccessService.currentProjectId());
        projectSnapshot.setSnapshotDate(snapshotDate);
        projectSnapshot.setExecutionHealthScore(dashboard.getSummaryMetrics().get("executionHealthScore"));
        projectSnapshot.setGovernanceRiskScore(dashboard.getSummaryMetrics().get("governanceRiskScore"));
        projectSnapshot.setActiveIssueCount(dashboard.getSummaryMetrics().get("activeIssueCount"));
        projectSnapshot.setOverdueIssueCount(dashboard.getSummaryMetrics().get("overdueIssueCount"));
        projectSnapshot.setCoverageRate(dashboard.getSummaryMetrics().get("coverageRate"));
        projectSnapshot.setAffectedAssetCount(dashboard.getSummaryMetrics().get("affectedAssetCount"));
        snapshotMapper.insert(projectSnapshot);
        for (QualityAssetRiskView asset : assets) {
            QualityMetricSnapshotEntity assetSnapshot = new QualityMetricSnapshotEntity();
            assetSnapshot.setTenantId(securityService.currentTenantId());
            assetSnapshot.setProjectId(projectResourceAccessService.currentProjectId());
            assetSnapshot.setSnapshotDate(snapshotDate);
            assetSnapshot.setDatasourceId(asset.getDatasourceId());
            assetSnapshot.setDatasourceNameSnapshot(asset.getDatasourceName());
            assetSnapshot.setDatasourceTypeCode(asset.getDatasourceTypeCode());
            assetSnapshot.setModelId(asset.getModelId());
            assetSnapshot.setModelNameSnapshot(asset.getModelName());
            assetSnapshot.setModelPhysicalLocator(asset.getModelPhysicalLocator());
            assetSnapshot.setExecutionHealthScore(asset.getExecutionHealthScore());
            assetSnapshot.setGovernanceRiskScore(asset.getGovernanceRiskScore());
            assetSnapshot.setActiveIssueCount(asset.getActiveIssueCount());
            assetSnapshot.setOverdueIssueCount(asset.getOverdueIssueCount());
            assetSnapshot.setCoverageRate(asset.getCoverageRate());
            snapshotMapper.insert(assetSnapshot);
        }
    }

    private static class MetricsContext {
        private boolean available;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private final List<DataModelDefinition> models = new ArrayList<DataModelDefinition>();
        private final Map<Long, DataModelDefinition> modelById = new LinkedHashMap<Long, DataModelDefinition>();
        private final Map<Long, DataSourceDefinition> datasourceById = new LinkedHashMap<Long, DataSourceDefinition>();
        private final List<QualityTaskListView> tasks = new ArrayList<QualityTaskListView>();
        private final Map<Long, QualityTaskListView> taskById = new LinkedHashMap<Long, QualityTaskListView>();
        private final Set<Long> taskIds = new LinkedHashSet<Long>();
        private final Set<String> coveredAssetKeys = new LinkedHashSet<String>();
        private List<RunRecordEntity> runRecords = new ArrayList<RunRecordEntity>();
        private final List<QualityIssueEntity> issues = new ArrayList<QualityIssueEntity>();
        private final List<QualityIssueEntity> activeIssues = new ArrayList<QualityIssueEntity>();
        private final List<QualityIssueEventEntity> issueEvents = new ArrayList<QualityIssueEventEntity>();
    }

    private static class AssetState {
        private String assetId;
        private Long datasourceId;
        private Long modelId;
        private DataSourceDefinition datasource;
        private DataModelDefinition model;
        private final List<QualityTaskListView> tasks = new ArrayList<QualityTaskListView>();
        private final List<RunRecordEntity> runRecords = new ArrayList<RunRecordEntity>();
        private final List<QualityIssueEntity> issues = new ArrayList<QualityIssueEntity>();
        private final List<QualityIssueEntity> activeIssues = new ArrayList<QualityIssueEntity>();
        private final Set<String> coverageDimensions = new LinkedHashSet<String>();
        private final Set<String> riskDimensions = new LinkedHashSet<String>();
    }
}
