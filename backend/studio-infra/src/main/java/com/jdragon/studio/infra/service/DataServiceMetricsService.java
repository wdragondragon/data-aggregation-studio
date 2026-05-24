package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.dto.model.DataServiceAccessLogView;
import com.jdragon.studio.dto.model.DataServiceApiMetricView;
import com.jdragon.studio.dto.model.DataServiceMetricDashboardView;
import com.jdragon.studio.dto.model.DataServiceMetricDistributionView;
import com.jdragon.studio.dto.model.DataServiceMetricOptionView;
import com.jdragon.studio.dto.model.DataServiceMetricOptionsView;
import com.jdragon.studio.dto.model.DataServiceMetricSummaryView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.RunMetricTrendSeriesView;
import com.jdragon.studio.dto.model.RunMetricTrendView;
import com.jdragon.studio.dto.model.request.DataServiceMetricQueryRequest;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.DataServiceSubscriptionEntity;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataServiceSubscriptionMapper;
import com.jdragon.studio.infra.model.DataServiceAccessCounterSummary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DataServiceMetricsService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_TOP_N = 10;
    private static final DateTimeFormatter REQUEST_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter HOUR_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final DataServiceAccessLogMapper accessLogMapper;
    private final DataServiceAccessCounterMapper accessCounterMapper;
    private final DataServiceDefinitionMapper definitionMapper;
    private final DataServiceSubscriptionMapper subscriptionMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final DataServiceMetricViewSupport metricViewSupport = new DataServiceMetricViewSupport();

    public DataServiceMetricsService(DataServiceAccessLogMapper accessLogMapper,
                                     DataServiceAccessCounterMapper accessCounterMapper,
                                     DataServiceDefinitionMapper definitionMapper,
                                     DataServiceSubscriptionMapper subscriptionMapper,
                                     StudioSecurityService securityService,
                                     ProjectResourceAccessService projectResourceAccessService) {
        this.accessLogMapper = accessLogMapper;
        this.accessCounterMapper = accessCounterMapper;
        this.definitionMapper = definitionMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    public DataServiceMetricOptionsView options() {
        DataServiceMetricOptionsView view = new DataServiceMetricOptionsView();
        Long projectId = projectResourceAccessService.currentProjectId();
        if (projectId == null) {
            return view;
        }
        String tenantId = securityService.currentTenantId();
        List<DataServiceDefinitionEntity> services = definitionMapper.selectList(new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                .eq(DataServiceDefinitionEntity::getTenantId, tenantId)
                .eq(DataServiceDefinitionEntity::getProjectId, projectId)
                .orderByAsc(DataServiceDefinitionEntity::getServiceName)
                .orderByDesc(DataServiceDefinitionEntity::getId));
        List<Long> serviceIds = new ArrayList<Long>();
        for (DataServiceDefinitionEntity service : services) {
            serviceIds.add(service.getId());
            DataServiceMetricOptionView item = new DataServiceMetricOptionView();
            item.setId(service.getId());
            item.setName(service.getServiceName());
            item.setLabel(service.getServiceName() + " / " + service.getServiceCode());
            item.setStatus(service.getStatus());
            view.getServices().add(item);
        }
        if (serviceIds.isEmpty()) {
            return view;
        }
        List<DataServiceSubscriptionEntity> subscriptions = subscriptionMapper.selectList(new LambdaQueryWrapper<DataServiceSubscriptionEntity>()
                .in(DataServiceSubscriptionEntity::getServiceId, serviceIds)
                .orderByAsc(DataServiceSubscriptionEntity::getSubscriptionName)
                .orderByDesc(DataServiceSubscriptionEntity::getId));
        Map<Long, DataServiceDefinitionEntity> serviceMap = metricViewSupport.toServiceMap(services);
        for (DataServiceSubscriptionEntity subscription : subscriptions) {
            DataServiceDefinitionEntity service = serviceMap.get(subscription.getServiceId());
            DataServiceMetricOptionView item = new DataServiceMetricOptionView();
            item.setId(subscription.getId());
            item.setName(subscription.getSubscriptionName());
            item.setLabel(subscription.getSubscriptionName() + (service == null ? "" : " / " + service.getServiceName()));
            item.setStatus(Integer.valueOf(1).equals(subscription.getEnabled()) ? "ENABLED" : "DISABLED");
            item.setServiceId(subscription.getServiceId());
            item.setServiceName(service == null ? null : service.getServiceName());
            view.getSubscriptions().add(item);
        }
        return view;
    }

    public DataServiceMetricDashboardView queryDashboard(DataServiceMetricQueryRequest request) {
        MetricContext context = loadContext(request);
        DataServiceMetricDashboardView view = new DataServiceMetricDashboardView();
        boolean useCounters = shouldUseCounterSummary(context);
        RunMetricTrendView accessTrend = buildAccessTrend(context, useCounters);
        RunMetricTrendView outputRowTrend = buildOutputRowTrend(context, useCounters);
        RunMetricTrendView cacheHitTrend = buildCacheHitTrend(context, useCounters);
        view.setSummary(buildSummary(context.logs, useCounters ? context.counterSummary : null));
        view.setAccessTrend(accessTrend);
        view.setCumulativeAccessTrend(buildCumulativeTrend(accessTrend));
        view.setOutputRowTrend(outputRowTrend);
        view.setCumulativeOutputRowTrend(buildCumulativeTrend(outputRowTrend));
        view.setCacheHitTrend(cacheHitTrend);
        view.setCumulativeCacheHitTrend(buildCumulativeTrend(cacheHitTrend));
        view.setResponseTimeTrend(buildResponseTimeTrend(context));
        view.setSuccessRateTrend(buildSuccessRateTrend(context, useCounters));
        view.setErrorDistribution(buildErrorDistribution(context.logs));
        List<DataServiceApiMetricView> apiMetrics = buildApiMetrics(context.logs, context.serviceMap);
        apiMetrics.sort(Comparator.comparing(DataServiceApiMetricView::getP95ResponseTimeMs, Comparator.nullsFirst(Long::compareTo))
                .thenComparing(DataServiceApiMetricView::getMaxResponseTimeMs, Comparator.nullsFirst(Long::compareTo))
                .reversed());
        view.setTopSlowApis(metricViewSupport.limit(apiMetrics, context.topN));
        List<DataServiceApiMetricView> failedMetrics = new ArrayList<DataServiceApiMetricView>(apiMetrics);
        failedMetrics.sort(Comparator.comparing(DataServiceApiMetricView::getFailureCount, Comparator.nullsFirst(Long::compareTo))
                .thenComparing(DataServiceApiMetricView::getSuccessRate, Comparator.nullsLast(Double::compareTo))
                .reversed());
        view.setTopFailedApis(metricViewSupport.limit(failedMetrics, context.topN));
        view.setSubscriptionRank(metricViewSupport.limit(buildSubscriptionMetrics(context.logs), context.topN));
        return view;
    }

    public PageView<DataServiceApiMetricView> queryApiStats(DataServiceMetricQueryRequest request) {
        MetricContext context = loadContext(request);
        List<DataServiceApiMetricView> metrics = buildApiMetrics(context.logs, context.serviceMap);
        metrics.sort(Comparator.comparing(DataServiceApiMetricView::getAccessCount, Comparator.nullsFirst(Long::compareTo))
                .thenComparing(DataServiceApiMetricView::getLastAccessAt, Comparator.nullsFirst(LocalDateTime::compareTo))
                .reversed());
        return metricViewSupport.pageList(metrics, context.pageNo, context.pageSize);
    }

    public PageView<DataServiceAccessLogView> queryAccessLogs(DataServiceMetricQueryRequest request) {
        MetricContext context = loadContext(request, true);
        if (context.serviceIds.isEmpty()) {
            return PageView.of(context.pageNo, context.pageSize, 0L, new ArrayList<DataServiceAccessLogView>());
        }
        Page<DataServiceAccessLogEntity> page = new Page<DataServiceAccessLogEntity>(context.pageNo, context.pageSize);
        Page<DataServiceAccessLogEntity> entityPage = accessLogMapper.selectPage(page, baseLogQuery(request, context)
                .orderByDesc(DataServiceAccessLogEntity::getOccurredAt)
                .orderByDesc(DataServiceAccessLogEntity::getId));
        List<DataServiceAccessLogView> items = new ArrayList<DataServiceAccessLogView>();
        for (DataServiceAccessLogEntity entity : entityPage.getRecords()) {
            items.add(metricViewSupport.toAccessLogView(entity));
        }
        return PageView.of(context.pageNo, context.pageSize, entityPage.getTotal(), items);
    }

    public int purgeExpiredAccessLogs(int retentionDays) {
        int days = retentionDays <= 0 ? 90 : retentionDays;
        return accessLogMapper.purgeBefore(LocalDateTime.now().minusDays(days));
    }

    private MetricContext loadContext(DataServiceMetricQueryRequest request) {
        return loadContext(request, false);
    }

    private MetricContext loadContext(DataServiceMetricQueryRequest request, boolean skipLogList) {
        DataServiceMetricQueryRequest safeRequest = request == null ? new DataServiceMetricQueryRequest() : request;
        MetricContext context = new MetricContext();
        context.pageNo = normalizePageNo(safeRequest.getPageNo());
        context.pageSize = normalizePageSize(safeRequest.getPageSize());
        context.topN = safeRequest.getTopN() == null || safeRequest.getTopN().intValue() <= 0 ? DEFAULT_TOP_N : safeRequest.getTopN().intValue();
        context.tenantId = securityService.currentTenantId();
        context.projectId = projectResourceAccessService.currentProjectId();
        TimeRange timeRange = resolveTimeRange(safeRequest);
        context.startTime = timeRange.startTime;
        context.endTime = timeRange.endTime;
        context.hourGranularity = resolveHourGranularity(safeRequest, timeRange);
        if (context.projectId == null) {
            return context;
        }
        List<DataServiceDefinitionEntity> services = queryServices(safeRequest, context);
        context.services.addAll(services);
        context.serviceMap.putAll(metricViewSupport.toServiceMap(services));
        for (DataServiceDefinitionEntity service : services) {
            context.serviceIds.add(service.getId());
        }
        if (!context.serviceIds.isEmpty()) {
            Integer successFilter = safeRequest.getSuccess() == null ? null : (Boolean.TRUE.equals(safeRequest.getSuccess()) ? Integer.valueOf(1) : Integer.valueOf(0));
            Integer cacheHitFilter = safeRequest.getCacheHit() == null ? null : (Boolean.TRUE.equals(safeRequest.getCacheHit()) ? Integer.valueOf(1) : Integer.valueOf(0));
            context.counterSummary = accessCounterMapper.selectSummary(context.tenantId, context.projectId, context.serviceIds,
                    safeRequest.getSubscriptionId(), successFilter, cacheHitFilter, context.startTime, context.endTime);
            context.counterBucketSummaries.addAll(accessCounterMapper.selectBucketSummaries(context.tenantId, context.projectId, context.serviceIds,
                    safeRequest.getSubscriptionId(), successFilter, cacheHitFilter, context.startTime, context.endTime));
        }
        if (!skipLogList && !context.serviceIds.isEmpty()) {
            context.logs.addAll(accessLogMapper.selectList(baseLogQuery(safeRequest, context)
                    .orderByAsc(DataServiceAccessLogEntity::getOccurredAt)
                    .orderByAsc(DataServiceAccessLogEntity::getId)));
        }
        return context;
    }

    private List<DataServiceDefinitionEntity> queryServices(DataServiceMetricQueryRequest request, MetricContext context) {
        if (context.projectId == null) {
            return new ArrayList<DataServiceDefinitionEntity>();
        }
        LambdaQueryWrapper<DataServiceDefinitionEntity> queryWrapper = new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                .eq(DataServiceDefinitionEntity::getTenantId, context.tenantId)
                .eq(DataServiceDefinitionEntity::getProjectId, context.projectId)
                .eq(request.getServiceId() != null, DataServiceDefinitionEntity::getId, request.getServiceId())
                .eq(hasText(request.getServiceStatus()), DataServiceDefinitionEntity::getStatus,
                        hasText(request.getServiceStatus()) ? request.getServiceStatus().toUpperCase(Locale.ROOT) : null)
                .orderByAsc(DataServiceDefinitionEntity::getServiceName)
                .orderByDesc(DataServiceDefinitionEntity::getId);
        return definitionMapper.selectList(queryWrapper);
    }

    private LambdaQueryWrapper<DataServiceAccessLogEntity> baseLogQuery(DataServiceMetricQueryRequest request, MetricContext context) {
        return new LambdaQueryWrapper<DataServiceAccessLogEntity>()
                .eq(DataServiceAccessLogEntity::getTenantId, context.tenantId)
                .eq(DataServiceAccessLogEntity::getProjectId, context.projectId)
                .in(!context.serviceIds.isEmpty(), DataServiceAccessLogEntity::getServiceId, context.serviceIds)
                .ge(DataServiceAccessLogEntity::getOccurredAt, context.startTime)
                .le(DataServiceAccessLogEntity::getOccurredAt, context.endTime)
                .eq(request.getSubscriptionId() != null, DataServiceAccessLogEntity::getSubscriptionId, request.getSubscriptionId())
                .eq(request.getSuccess() != null, DataServiceAccessLogEntity::getSuccess, Boolean.TRUE.equals(request.getSuccess()) ? 1 : 0)
                .eq(request.getCacheHit() != null, DataServiceAccessLogEntity::getCacheEnabled, 1)
                .eq(request.getCacheHit() != null, DataServiceAccessLogEntity::getCacheHit, Boolean.TRUE.equals(request.getCacheHit()) ? 1 : 0)
                .and(isFocusedLogQuery(request.getLogFocus()), wrapper -> {
                    String focus = request.getLogFocus().toUpperCase(Locale.ROOT);
                    long minDurationMs = request.getMinDurationMs() == null || request.getMinDurationMs().longValue() < 0L
                            ? 1000L
                            : request.getMinDurationMs().longValue();
                    if ("ERROR".equals(focus)) {
                        wrapper.ne(DataServiceAccessLogEntity::getSuccess, 1);
                    } else if ("SLOW".equals(focus)) {
                        wrapper.ge(DataServiceAccessLogEntity::getDurationMs, minDurationMs);
                    } else if ("ERROR_OR_SLOW".equals(focus)) {
                        wrapper.ne(DataServiceAccessLogEntity::getSuccess, 1)
                                .or()
                                .ge(DataServiceAccessLogEntity::getDurationMs, minDurationMs);
                    }
                });
    }

    private DataServiceMetricSummaryView buildSummary(List<DataServiceAccessLogEntity> logs) {
        return buildSummary(logs, null);
    }

    private DataServiceMetricSummaryView buildSummary(List<DataServiceAccessLogEntity> logs,
                                                      DataServiceAccessCounterSummary counterSummary) {
        if (counterSummary != null && safeLong(counterSummary.getAccessCount()) > 0L) {
            return buildSummaryFromCounter(counterSummary, logs);
        }
        DataServiceMetricSummaryView summary = new DataServiceMetricSummaryView();
        long total = logs.size();
        long success = 0L;
        long cacheEnabled = 0L;
        long cacheHit = 0L;
        long cacheMiss = 0L;
        long cacheDisabled = 0L;
        long sumDuration = 0L;
        Long minDuration = null;
        Long maxDuration = null;
        LocalDateTime lastAccessAt = null;
        List<Long> durations = new ArrayList<Long>();
        for (DataServiceAccessLogEntity log : logs) {
            if (Integer.valueOf(1).equals(log.getSuccess())) {
                success++;
            }
            if (Integer.valueOf(1).equals(log.getCacheEnabled())) {
                cacheEnabled++;
            } else {
                cacheDisabled++;
            }
            if (Integer.valueOf(1).equals(log.getCacheEnabled()) && Integer.valueOf(1).equals(log.getCacheHit())) {
                cacheHit++;
            } else if (Integer.valueOf(1).equals(log.getCacheEnabled())) {
                cacheMiss++;
            }
            long duration = safeLong(log.getDurationMs());
            durations.add(Long.valueOf(duration));
            sumDuration += duration;
            minDuration = minDuration == null ? Long.valueOf(duration) : Long.valueOf(Math.min(minDuration.longValue(), duration));
            maxDuration = maxDuration == null ? Long.valueOf(duration) : Long.valueOf(Math.max(maxDuration.longValue(), duration));
            if (log.getOccurredAt() != null && (lastAccessAt == null || log.getOccurredAt().isAfter(lastAccessAt))) {
                lastAccessAt = log.getOccurredAt();
            }
        }
        summary.setAccessCount(Long.valueOf(total));
        summary.setSuccessCount(Long.valueOf(success));
        summary.setFailureCount(Long.valueOf(total - success));
        summary.setSuccessRate(metricViewSupport.rate(success, total));
        summary.setMinResponseTimeMs(minDuration == null ? Long.valueOf(0L) : minDuration);
        summary.setMaxResponseTimeMs(maxDuration == null ? Long.valueOf(0L) : maxDuration);
        summary.setAvgResponseTimeMs(total == 0 ? Long.valueOf(0L) : Long.valueOf(Math.round(sumDuration * 1.0D / total)));
        summary.setP95ResponseTimeMs(metricViewSupport.percentile(durations, 95));
        summary.setP99ResponseTimeMs(metricViewSupport.percentile(durations, 99));
        summary.setLastAccessAt(lastAccessAt);
        summary.setCacheEnabledCount(Long.valueOf(cacheEnabled));
        summary.setCacheHitCount(Long.valueOf(cacheHit));
        summary.setCacheMissCount(Long.valueOf(cacheMiss));
        summary.setCacheDisabledCount(Long.valueOf(cacheDisabled));
        summary.setCacheHitRate(metricViewSupport.rate(cacheHit, cacheEnabled));
        summary.setCounterBacked(Boolean.FALSE);
        return summary;
    }

    private DataServiceMetricSummaryView buildSummaryFromCounter(DataServiceAccessCounterSummary counterSummary,
                                                                 List<DataServiceAccessLogEntity> logs) {
        DataServiceMetricSummaryView summary = new DataServiceMetricSummaryView();
        DataServiceMetricSummaryView logSummary = logs == null || logs.isEmpty() ? null : buildSummary(logs, null);
        long total = safeLong(counterSummary.getAccessCount());
        long success = safeLong(counterSummary.getSuccessCount());
        long cacheEnabled = safeLong(counterSummary.getCacheEnabledCount());
        long cacheHit = safeLong(counterSummary.getCacheHitCount());
        summary.setAccessCount(Long.valueOf(total));
        summary.setSuccessCount(Long.valueOf(success));
        summary.setFailureCount(Long.valueOf(safeLong(counterSummary.getFailureCount())));
        summary.setSuccessRate(metricViewSupport.rate(success, total));
        summary.setMinResponseTimeMs(logSummary == null ? Long.valueOf(0L) : logSummary.getMinResponseTimeMs());
        summary.setMaxResponseTimeMs(logSummary == null ? Long.valueOf(0L) : logSummary.getMaxResponseTimeMs());
        summary.setAvgResponseTimeMs(logSummary == null ? Long.valueOf(0L) : logSummary.getAvgResponseTimeMs());
        summary.setP95ResponseTimeMs(logSummary == null ? Long.valueOf(0L) : logSummary.getP95ResponseTimeMs());
        summary.setP99ResponseTimeMs(logSummary == null ? Long.valueOf(0L) : logSummary.getP99ResponseTimeMs());
        summary.setLastAccessAt(logSummary == null ? null : logSummary.getLastAccessAt());
        summary.setCacheEnabledCount(Long.valueOf(cacheEnabled));
        summary.setCacheHitCount(Long.valueOf(cacheHit));
        summary.setCacheMissCount(Long.valueOf(safeLong(counterSummary.getCacheMissCount())));
        summary.setCacheDisabledCount(Long.valueOf(safeLong(counterSummary.getCacheDisabledCount())));
        summary.setCacheHitRate(metricViewSupport.rate(cacheHit, cacheEnabled));
        summary.setCounterBacked(Boolean.TRUE);
        return summary;
    }

    private List<DataServiceApiMetricView> buildApiMetrics(List<DataServiceAccessLogEntity> logs,
                                                           Map<Long, DataServiceDefinitionEntity> serviceMap) {
        Map<Long, List<DataServiceAccessLogEntity>> grouped = new LinkedHashMap<Long, List<DataServiceAccessLogEntity>>();
        for (DataServiceAccessLogEntity log : logs) {
            if (log.getServiceId() == null) {
                continue;
            }
            List<DataServiceAccessLogEntity> serviceLogs = grouped.get(log.getServiceId());
            if (serviceLogs == null) {
                serviceLogs = new ArrayList<DataServiceAccessLogEntity>();
                grouped.put(log.getServiceId(), serviceLogs);
            }
            serviceLogs.add(log);
        }
        List<DataServiceApiMetricView> result = new ArrayList<DataServiceApiMetricView>();
        for (Map.Entry<Long, List<DataServiceAccessLogEntity>> entry : grouped.entrySet()) {
            DataServiceDefinitionEntity service = serviceMap.get(entry.getKey());
            DataServiceApiMetricView metric = buildApiMetric(entry.getValue());
            metric.setServiceId(entry.getKey());
            metric.setServiceName(service == null ? metricViewSupport.firstString(entry.getValue(), true) : service.getServiceName());
            metric.setServiceCode(service == null ? metricViewSupport.firstCode(entry.getValue()) : service.getServiceCode());
            metric.setStatus(service == null ? metricViewSupport.firstStatus(entry.getValue()) : service.getStatus());
            result.add(metric);
        }
        return result;
    }

    private List<DataServiceApiMetricView> buildSubscriptionMetrics(List<DataServiceAccessLogEntity> logs) {
        Map<Long, List<DataServiceAccessLogEntity>> grouped = new LinkedHashMap<Long, List<DataServiceAccessLogEntity>>();
        for (DataServiceAccessLogEntity log : logs) {
            if (log.getSubscriptionId() == null) {
                continue;
            }
            List<DataServiceAccessLogEntity> subscriptionLogs = grouped.get(log.getSubscriptionId());
            if (subscriptionLogs == null) {
                subscriptionLogs = new ArrayList<DataServiceAccessLogEntity>();
                grouped.put(log.getSubscriptionId(), subscriptionLogs);
            }
            subscriptionLogs.add(log);
        }
        List<DataServiceApiMetricView> result = new ArrayList<DataServiceApiMetricView>();
        for (Map.Entry<Long, List<DataServiceAccessLogEntity>> entry : grouped.entrySet()) {
            DataServiceApiMetricView metric = buildApiMetric(entry.getValue());
            DataServiceAccessLogEntity first = entry.getValue().isEmpty() ? null : entry.getValue().get(0);
            metric.setSubscriptionId(entry.getKey());
            metric.setSubscriptionName(first == null ? null : first.getSubscriptionNameSnapshot());
            metric.setServiceId(first == null ? null : first.getServiceId());
            metric.setServiceName(first == null ? null : first.getServiceNameSnapshot());
            metric.setServiceCode(first == null ? null : first.getServiceCodeSnapshot());
            metric.setStatus(first == null ? null : first.getServiceStatusSnapshot());
            result.add(metric);
        }
        result.sort(Comparator.comparing(DataServiceApiMetricView::getAccessCount, Comparator.nullsFirst(Long::compareTo))
                .thenComparing(DataServiceApiMetricView::getFailureCount, Comparator.nullsFirst(Long::compareTo))
                .reversed());
        return result;
    }

    private DataServiceApiMetricView buildApiMetric(List<DataServiceAccessLogEntity> logs) {
        DataServiceApiMetricView metric = new DataServiceApiMetricView();
        DataServiceMetricSummaryView summary = buildSummary(logs);
        metric.setAccessCount(summary.getAccessCount());
        metric.setSuccessCount(summary.getSuccessCount());
        metric.setFailureCount(summary.getFailureCount());
        metric.setSuccessRate(summary.getSuccessRate());
        metric.setMinResponseTimeMs(summary.getMinResponseTimeMs());
        metric.setMaxResponseTimeMs(summary.getMaxResponseTimeMs());
        metric.setAvgResponseTimeMs(summary.getAvgResponseTimeMs());
        metric.setP95ResponseTimeMs(summary.getP95ResponseTimeMs());
        metric.setP99ResponseTimeMs(summary.getP99ResponseTimeMs());
        metric.setLastAccessAt(summary.getLastAccessAt());
        metric.setCacheEnabledCount(summary.getCacheEnabledCount());
        metric.setCacheHitCount(summary.getCacheHitCount());
        metric.setCacheMissCount(summary.getCacheMissCount());
        metric.setCacheDisabledCount(summary.getCacheDisabledCount());
        metric.setCacheHitRate(summary.getCacheHitRate());
        return metric;
    }

    private boolean shouldUseCounterSummary(MetricContext context) {
        return context.counterSummary != null
                && safeLong(context.counterSummary.getAccessCount()) > 0L
                && safeLong(context.counterSummary.getAccessCount()) >= (context.logs == null ? 0L : context.logs.size());
    }

    private RunMetricTrendView buildAccessTrend(MetricContext context, boolean useCounters) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView totalSeries = series("total", "访问量");
        RunMetricTrendSeriesView successSeries = series("success", "成功量");
        RunMetricTrendSeriesView failureSeries = series("failure", "失败量");
        for (Bucket bucket : buildBuckets(context)) {
            trend.getXAxis().add(bucket.label);
            long total = 0L;
            long success = 0L;
            long failure = 0L;
            if (useCounters) {
                for (DataServiceAccessCounterSummary summary : context.counterBucketSummaries) {
                    if (inBucket(summary, bucket)) {
                        total += safeLong(summary.getAccessCount());
                        success += safeLong(summary.getSuccessCount());
                        failure += safeLong(summary.getFailureCount());
                    }
                }
            } else {
                for (DataServiceAccessLogEntity log : context.logs) {
                    if (inBucket(log, bucket)) {
                        total++;
                        if (Integer.valueOf(1).equals(log.getSuccess())) {
                            success++;
                        }
                    }
                }
                failure = total - success;
            }
            totalSeries.getData().add(Long.valueOf(total));
            successSeries.getData().add(Long.valueOf(success));
            failureSeries.getData().add(Long.valueOf(failure));
        }
        trend.getSeries().add(totalSeries);
        trend.getSeries().add(successSeries);
        trend.getSeries().add(failureSeries);
        return trend;
    }

    private RunMetricTrendView buildOutputRowTrend(MetricContext context, boolean useCounters) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView rowSeries = series("outputRows", "输出行数");
        for (Bucket bucket : buildBuckets(context)) {
            trend.getXAxis().add(bucket.label);
            long rowCount = 0L;
            if (useCounters) {
                for (DataServiceAccessCounterSummary summary : context.counterBucketSummaries) {
                    if (inBucket(summary, bucket)) {
                        rowCount += safeLong(summary.getRowCount());
                    }
                }
            } else {
                for (DataServiceAccessLogEntity log : context.logs) {
                    if (inBucket(log, bucket)) {
                        rowCount += safeLong(log.getRowCount());
                    }
                }
            }
            rowSeries.getData().add(Long.valueOf(rowCount));
        }
        trend.getSeries().add(rowSeries);
        return trend;
    }

    private RunMetricTrendView buildCacheHitTrend(MetricContext context, boolean useCounters) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView hitSeries = series("cacheHits", "缓存命中次数");
        for (Bucket bucket : buildBuckets(context)) {
            trend.getXAxis().add(bucket.label);
            long cacheHit = 0L;
            if (useCounters) {
                for (DataServiceAccessCounterSummary summary : context.counterBucketSummaries) {
                    if (inBucket(summary, bucket)) {
                        cacheHit += safeLong(summary.getCacheHitCount());
                    }
                }
            } else {
                for (DataServiceAccessLogEntity log : context.logs) {
                    if (inBucket(log, bucket) && Integer.valueOf(1).equals(log.getCacheEnabled()) && Integer.valueOf(1).equals(log.getCacheHit())) {
                        cacheHit++;
                    }
                }
            }
            hitSeries.getData().add(Long.valueOf(cacheHit));
        }
        trend.getSeries().add(hitSeries);
        return trend;
    }

    private RunMetricTrendView buildCumulativeTrend(RunMetricTrendView incrementalTrend) {
        RunMetricTrendView trend = new RunMetricTrendView();
        if (incrementalTrend == null) {
            return trend;
        }
        trend.getXAxis().addAll(incrementalTrend.getXAxis());
        for (RunMetricTrendSeriesView sourceSeries : incrementalTrend.getSeries()) {
            RunMetricTrendSeriesView targetSeries = series(sourceSeries.getKey(), sourceSeries.getName());
            long total = 0L;
            for (Long value : sourceSeries.getData()) {
                total += safeLong(value);
                targetSeries.getData().add(Long.valueOf(total));
            }
            trend.getSeries().add(targetSeries);
        }
        return trend;
    }

    private RunMetricTrendView buildResponseTimeTrend(MetricContext context) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView avgSeries = series("avg", "平均耗时");
        RunMetricTrendSeriesView maxSeries = series("max", "最大耗时");
        RunMetricTrendSeriesView p95Series = series("p95", "P95");
        RunMetricTrendSeriesView p99Series = series("p99", "P99");
        for (Bucket bucket : buildBuckets(context)) {
            trend.getXAxis().add(bucket.label);
            List<Long> durations = new ArrayList<Long>();
            long max = 0L;
            long sum = 0L;
            for (DataServiceAccessLogEntity log : context.logs) {
                if (inBucket(log, bucket)) {
                    long duration = safeLong(log.getDurationMs());
                    durations.add(Long.valueOf(duration));
                    sum += duration;
                    max = Math.max(max, duration);
                }
            }
            avgSeries.getData().add(durations.isEmpty() ? Long.valueOf(0L) : Long.valueOf(Math.round(sum * 1.0D / durations.size())));
            maxSeries.getData().add(Long.valueOf(max));
            p95Series.getData().add(metricViewSupport.percentile(durations, 95));
            p99Series.getData().add(metricViewSupport.percentile(durations, 99));
        }
        trend.getSeries().add(avgSeries);
        trend.getSeries().add(maxSeries);
        trend.getSeries().add(p95Series);
        trend.getSeries().add(p99Series);
        return trend;
    }

    private RunMetricTrendView buildSuccessRateTrend(MetricContext context, boolean useCounters) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView rateSeries = series("successRate", "成功率");
        for (Bucket bucket : buildBuckets(context)) {
            trend.getXAxis().add(bucket.label);
            long total = 0L;
            long success = 0L;
            if (useCounters) {
                for (DataServiceAccessCounterSummary summary : context.counterBucketSummaries) {
                    if (inBucket(summary, bucket)) {
                        total += safeLong(summary.getAccessCount());
                        success += safeLong(summary.getSuccessCount());
                    }
                }
            } else {
                for (DataServiceAccessLogEntity log : context.logs) {
                    if (inBucket(log, bucket)) {
                        total++;
                        if (Integer.valueOf(1).equals(log.getSuccess())) {
                            success++;
                        }
                    }
                }
            }
            rateSeries.getData().add(Long.valueOf(Math.round(metricViewSupport.rate(success, total))));
        }
        trend.getSeries().add(rateSeries);
        return trend;
    }

    private List<DataServiceMetricDistributionView> buildErrorDistribution(List<DataServiceAccessLogEntity> logs) {
        Map<String, Long> grouped = new LinkedHashMap<String, Long>();
        for (DataServiceAccessLogEntity log : logs) {
            if (Integer.valueOf(1).equals(log.getSuccess())) {
                continue;
            }
            String key = hasText(log.getErrorCode()) ? log.getErrorCode() : log.getHttpStatus() == null ? "UNKNOWN" : "HTTP_" + log.getHttpStatus();
            grouped.put(key, Long.valueOf(safeLong(grouped.get(key)) + 1L));
        }
        List<DataServiceMetricDistributionView> result = new ArrayList<DataServiceMetricDistributionView>();
        for (Map.Entry<String, Long> entry : grouped.entrySet()) {
            DataServiceMetricDistributionView item = new DataServiceMetricDistributionView();
            item.setKey(entry.getKey());
            item.setLabel(entry.getKey());
            item.setCount(entry.getValue());
            result.add(item);
        }
        result.sort(Comparator.comparing(DataServiceMetricDistributionView::getCount, Comparator.nullsFirst(Long::compareTo)).reversed());
        return result;
    }

    private List<Bucket> buildBuckets(MetricContext context) {
        List<Bucket> buckets = new ArrayList<Bucket>();
        LocalDateTime cursor = normalizeBucketStart(context.startTime, context.hourGranularity);
        LocalDateTime end = context.endTime == null ? LocalDateTime.now() : context.endTime;
        while (!cursor.isAfter(end)) {
            LocalDateTime next = context.hourGranularity ? cursor.plusHours(1L) : cursor.plusDays(1L);
            Bucket bucket = new Bucket();
            bucket.start = cursor;
            bucket.end = next;
            bucket.label = cursor.format(context.hourGranularity ? HOUR_LABEL_FORMATTER : DAY_LABEL_FORMATTER);
            buckets.add(bucket);
            cursor = next;
        }
        if (buckets.isEmpty()) {
            Bucket bucket = new Bucket();
            bucket.start = normalizeBucketStart(LocalDateTime.now(), context.hourGranularity);
            bucket.end = context.hourGranularity ? bucket.start.plusHours(1L) : bucket.start.plusDays(1L);
            bucket.label = bucket.start.format(context.hourGranularity ? HOUR_LABEL_FORMATTER : DAY_LABEL_FORMATTER);
            buckets.add(bucket);
        }
        return buckets;
    }

    private boolean inBucket(DataServiceAccessLogEntity log, Bucket bucket) {
        if (log.getOccurredAt() == null) {
            return false;
        }
        return !log.getOccurredAt().isBefore(bucket.start) && log.getOccurredAt().isBefore(bucket.end);
    }

    private boolean inBucket(DataServiceAccessCounterSummary summary, Bucket bucket) {
        if (summary.getBucketStart() == null) {
            return false;
        }
        return !summary.getBucketStart().isBefore(bucket.start) && summary.getBucketStart().isBefore(bucket.end);
    }

    private RunMetricTrendSeriesView series(String key, String name) {
        RunMetricTrendSeriesView series = new RunMetricTrendSeriesView();
        series.setKey(key);
        series.setName(name);
        return series;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo.intValue() <= 0 ? DEFAULT_PAGE_NO : pageNo.intValue();
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize.intValue() <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize.intValue(), MAX_PAGE_SIZE);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isFocusedLogQuery(String value) {
        if (!hasText(value) || "ALL".equalsIgnoreCase(value)) {
            return false;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        return "ERROR".equals(normalized) || "SLOW".equals(normalized) || "ERROR_OR_SLOW".equals(normalized);
    }

    private TimeRange resolveTimeRange(DataServiceMetricQueryRequest request) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = parseTime(request.getEndTime(), now);
        LocalDateTime start = parseTime(request.getStartTime(), end.minusDays(7L));
        if (start.isAfter(end)) {
            start = end.minusDays(7L);
        }
        TimeRange range = new TimeRange();
        range.startTime = start;
        range.endTime = end;
        return range;
    }

    private LocalDateTime parseTime(String value, LocalDateTime fallback) {
        if (!hasText(value)) {
            return fallback;
        }
        String trimmed = value.trim();
        try {
            return LocalDateTime.parse(trimmed, REQUEST_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(trimmed);
            } catch (DateTimeParseException ignoredAgain) {
                return fallback;
            }
        }
    }

    private boolean resolveHourGranularity(DataServiceMetricQueryRequest request, TimeRange timeRange) {
        if (hasText(request.getGranularity())) {
            return "HOUR".equalsIgnoreCase(request.getGranularity());
        }
        return timeRange.startTime.plusDays(2L).isAfter(timeRange.endTime);
    }

    private LocalDateTime normalizeBucketStart(LocalDateTime time, boolean hourGranularity) {
        LocalDateTime safeTime = time == null ? LocalDateTime.now() : time;
        return hourGranularity
                ? safeTime.withMinute(0).withSecond(0).withNano(0)
                : safeTime.withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    private static class MetricContext {
        private String tenantId;
        private Long projectId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private boolean hourGranularity;
        private int pageNo;
        private int pageSize;
        private int topN;
        private final List<DataServiceDefinitionEntity> services = new ArrayList<DataServiceDefinitionEntity>();
        private final List<Long> serviceIds = new ArrayList<Long>();
        private final Map<Long, DataServiceDefinitionEntity> serviceMap = new LinkedHashMap<Long, DataServiceDefinitionEntity>();
        private final List<DataServiceAccessLogEntity> logs = new ArrayList<DataServiceAccessLogEntity>();
        private DataServiceAccessCounterSummary counterSummary;
        private final List<DataServiceAccessCounterSummary> counterBucketSummaries = new ArrayList<DataServiceAccessCounterSummary>();
    }

    private static class TimeRange {
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }

    private static class Bucket {
        private LocalDateTime start;
        private LocalDateTime end;
        private String label;
    }
}
