package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.dto.model.DataIngestionAccessLogView;
import com.jdragon.studio.dto.model.DataIngestionApiMetricView;
import com.jdragon.studio.dto.model.DataIngestionMetricDashboardView;
import com.jdragon.studio.dto.model.DataIngestionMetricSummaryView;
import com.jdragon.studio.dto.model.DataServiceMetricDistributionView;
import com.jdragon.studio.dto.model.DataServiceMetricOptionView;
import com.jdragon.studio.dto.model.DataServiceMetricOptionsView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.RunMetricTrendSeriesView;
import com.jdragon.studio.dto.model.RunMetricTrendView;
import com.jdragon.studio.dto.model.request.DataIngestionMetricQueryRequest;
import com.jdragon.studio.infra.entity.DataIngestionAccessLogEntity;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataIngestionSubscriptionEntity;
import com.jdragon.studio.infra.mapper.DataIngestionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataIngestionSubscriptionMapper;
import com.jdragon.studio.infra.model.DataIngestionAccessCounterSummary;
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
public class DataIngestionMetricsService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_TOP_N = 10;
    private static final String DEFAULT_NO_TOKEN_SUBSCRIPTION_NAME = "免 Token 调用";
    private static final DateTimeFormatter REQUEST_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter HOUR_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final DataIngestionAccessLogMapper accessLogMapper;
    private final DataIngestionAccessCounterMapper accessCounterMapper;
    private final DataIngestionServiceMapper serviceMapper;
    private final DataIngestionSubscriptionMapper subscriptionMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final DataIngestionMetricViewSupport metricViewSupport = new DataIngestionMetricViewSupport();

    public DataIngestionMetricsService(DataIngestionAccessLogMapper accessLogMapper,
                                       DataIngestionAccessCounterMapper accessCounterMapper,
                                       DataIngestionServiceMapper serviceMapper,
                                       DataIngestionSubscriptionMapper subscriptionMapper,
                                       StudioSecurityService securityService,
                                       ProjectResourceAccessService projectResourceAccessService) {
        this.accessLogMapper = accessLogMapper;
        this.accessCounterMapper = accessCounterMapper;
        this.serviceMapper = serviceMapper;
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
        List<DataIngestionServiceEntity> services = serviceMapper.selectList(new LambdaQueryWrapper<DataIngestionServiceEntity>()
                .eq(DataIngestionServiceEntity::getTenantId, tenantId)
                .eq(DataIngestionServiceEntity::getProjectId, projectId)
                .orderByAsc(DataIngestionServiceEntity::getServiceName)
                .orderByDesc(DataIngestionServiceEntity::getId));
        List<Long> serviceIds = new ArrayList<Long>();
        for (DataIngestionServiceEntity service : services) {
            serviceIds.add(service.getId());
            DataServiceMetricOptionView item = new DataServiceMetricOptionView();
            item.setId(service.getId());
            item.setName(service.getServiceName());
            item.setLabel(service.getServiceName() + " / " + service.getServiceCode());
            item.setStatus(service.getStatus());
            view.getServices().add(item);
            if (!isTokenRequired(service)) {
                DataServiceMetricOptionView noTokenItem = new DataServiceMetricOptionView();
                noTokenItem.setId(Long.valueOf(-service.getId().longValue()));
                noTokenItem.setName(defaultSubscriptionName(service));
                noTokenItem.setLabel(defaultSubscriptionName(service) + " / " + service.getServiceName());
                noTokenItem.setStatus("NO_TOKEN");
                noTokenItem.setServiceId(service.getId());
                noTokenItem.setServiceName(service.getServiceName());
                view.getSubscriptions().add(noTokenItem);
            }
        }
        if (serviceIds.isEmpty()) {
            return view;
        }
        List<DataIngestionSubscriptionEntity> subscriptions = subscriptionMapper.selectList(new LambdaQueryWrapper<DataIngestionSubscriptionEntity>()
                .in(DataIngestionSubscriptionEntity::getServiceId, serviceIds)
                .orderByAsc(DataIngestionSubscriptionEntity::getSubscriptionName)
                .orderByDesc(DataIngestionSubscriptionEntity::getId));
        Map<Long, DataIngestionServiceEntity> serviceMap = metricViewSupport.toServiceMap(services);
        for (DataIngestionSubscriptionEntity subscription : subscriptions) {
            DataIngestionServiceEntity service = serviceMap.get(subscription.getServiceId());
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

    public DataIngestionMetricDashboardView queryDashboard(DataIngestionMetricQueryRequest request) {
        MetricContext context = loadContext(request);
        DataIngestionMetricDashboardView view = new DataIngestionMetricDashboardView();
        boolean useCounters = shouldUseCounterSummary(context);
        RunMetricTrendView accessTrend = buildAccessTrend(context, useCounters);
        RunMetricTrendView receivedTrend = buildRowTrend(context, useCounters, "received", "接收行数");
        RunMetricTrendView writtenTrend = buildRowTrend(context, useCounters, "written", "写入成功行数");
        view.setSummary(buildSummary(context.logs, useCounters ? context.counterSummary : null));
        view.setAccessTrend(accessTrend);
        view.setCumulativeAccessTrend(buildCumulativeTrend(accessTrend));
        view.setReceivedTrend(receivedTrend);
        view.setCumulativeReceivedTrend(buildCumulativeTrend(receivedTrend));
        view.setWrittenTrend(writtenTrend);
        view.setCumulativeWrittenTrend(buildCumulativeTrend(writtenTrend));
        view.setResponseTimeTrend(buildResponseTimeTrend(context));
        view.setSuccessRateTrend(buildSuccessRateTrend(context, useCounters));
        view.setErrorDistribution(buildErrorDistribution(context.logs));
        List<DataIngestionApiMetricView> serviceMetrics = buildServiceMetrics(context.logs, context.serviceMap);
        serviceMetrics.sort(Comparator.comparing(DataIngestionApiMetricView::getP95ResponseTimeMs, Comparator.nullsFirst(Long::compareTo))
                .thenComparing(DataIngestionApiMetricView::getMaxResponseTimeMs, Comparator.nullsFirst(Long::compareTo))
                .reversed());
        view.setTopSlowServices(metricViewSupport.limit(serviceMetrics, context.topN));
        List<DataIngestionApiMetricView> failedMetrics = new ArrayList<DataIngestionApiMetricView>(serviceMetrics);
        failedMetrics.sort(Comparator.comparing(DataIngestionApiMetricView::getFailureCount, Comparator.nullsFirst(Long::compareTo))
                .thenComparing(DataIngestionApiMetricView::getSuccessRate, Comparator.nullsLast(Double::compareTo))
                .reversed());
        view.setTopFailedServices(metricViewSupport.limit(failedMetrics, context.topN));
        view.setSubscriptionRank(metricViewSupport.limit(buildSubscriptionMetrics(context.logs), context.topN));
        return view;
    }

    public PageView<DataIngestionApiMetricView> queryApiStats(DataIngestionMetricQueryRequest request) {
        MetricContext context = loadContext(request);
        List<DataIngestionApiMetricView> metrics = buildServiceMetrics(context.logs, context.serviceMap);
        metrics.sort(Comparator.comparing(DataIngestionApiMetricView::getAccessCount, Comparator.nullsFirst(Long::compareTo))
                .thenComparing(DataIngestionApiMetricView::getLastAccessAt, Comparator.nullsFirst(LocalDateTime::compareTo))
                .reversed());
        return metricViewSupport.pageList(metrics, context.pageNo, context.pageSize);
    }

    public PageView<DataIngestionAccessLogView> queryAccessLogs(DataIngestionMetricQueryRequest request) {
        DataIngestionMetricQueryRequest safeRequest = request == null ? new DataIngestionMetricQueryRequest() : request;
        MetricContext context = loadContext(safeRequest, true);
        if (context.serviceIds.isEmpty()) {
            return PageView.of(context.pageNo, context.pageSize, 0L, new ArrayList<DataIngestionAccessLogView>());
        }
        Page<DataIngestionAccessLogEntity> page = new Page<DataIngestionAccessLogEntity>(context.pageNo, context.pageSize);
        Page<DataIngestionAccessLogEntity> entityPage = accessLogMapper.selectPage(page, baseLogQuery(safeRequest, context)
                .orderByDesc(DataIngestionAccessLogEntity::getOccurredAt)
                .orderByDesc(DataIngestionAccessLogEntity::getId));
        List<DataIngestionAccessLogView> items = new ArrayList<DataIngestionAccessLogView>();
        for (DataIngestionAccessLogEntity entity : entityPage.getRecords()) {
            items.add(metricViewSupport.toAccessLogView(entity));
        }
        return PageView.of(context.pageNo, context.pageSize, entityPage.getTotal(), items);
    }

    private MetricContext loadContext(DataIngestionMetricQueryRequest request) {
        return loadContext(request, false);
    }

    private MetricContext loadContext(DataIngestionMetricQueryRequest request, boolean skipLogList) {
        DataIngestionMetricQueryRequest safeRequest = request == null ? new DataIngestionMetricQueryRequest() : request;
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
        List<DataIngestionServiceEntity> services = queryServices(safeRequest, context);
        context.services.addAll(services);
        context.serviceMap.putAll(metricViewSupport.toServiceMap(services));
        for (DataIngestionServiceEntity service : services) {
            context.serviceIds.add(service.getId());
        }
        if (!context.serviceIds.isEmpty()) {
            Integer successFilter = safeRequest.getSuccess() == null ? null : (Boolean.TRUE.equals(safeRequest.getSuccess()) ? Integer.valueOf(1) : Integer.valueOf(0));
            Long subscriptionId = counterSubscriptionId(safeRequest.getSubscriptionId());
            context.counterSummary = accessCounterMapper.selectSummary(context.tenantId, context.projectId, context.serviceIds,
                    subscriptionId, successFilter, context.startTime, context.endTime);
            context.counterBucketSummaries.addAll(accessCounterMapper.selectBucketSummaries(context.tenantId, context.projectId, context.serviceIds,
                    subscriptionId, successFilter, context.startTime, context.endTime));
        }
        if (!skipLogList && !context.serviceIds.isEmpty()) {
            context.logs.addAll(accessLogMapper.selectList(baseLogQuery(safeRequest, context)
                    .orderByAsc(DataIngestionAccessLogEntity::getOccurredAt)
                    .orderByAsc(DataIngestionAccessLogEntity::getId)));
        }
        return context;
    }

    private List<DataIngestionServiceEntity> queryServices(DataIngestionMetricQueryRequest request, MetricContext context) {
        if (context.projectId == null) {
            return new ArrayList<DataIngestionServiceEntity>();
        }
        return serviceMapper.selectList(new LambdaQueryWrapper<DataIngestionServiceEntity>()
                .eq(DataIngestionServiceEntity::getTenantId, context.tenantId)
                .eq(DataIngestionServiceEntity::getProjectId, context.projectId)
                .eq(request.getServiceId() != null, DataIngestionServiceEntity::getId, request.getServiceId())
                .eq(noTokenServiceId(request.getSubscriptionId()) != null, DataIngestionServiceEntity::getId, noTokenServiceId(request.getSubscriptionId()))
                .eq(hasText(request.getServiceStatus()), DataIngestionServiceEntity::getStatus,
                        hasText(request.getServiceStatus()) ? request.getServiceStatus().toUpperCase(Locale.ROOT) : null)
                .orderByAsc(DataIngestionServiceEntity::getServiceName)
                .orderByDesc(DataIngestionServiceEntity::getId));
    }

    private LambdaQueryWrapper<DataIngestionAccessLogEntity> baseLogQuery(DataIngestionMetricQueryRequest request, MetricContext context) {
        return new LambdaQueryWrapper<DataIngestionAccessLogEntity>()
                .eq(DataIngestionAccessLogEntity::getTenantId, context.tenantId)
                .eq(DataIngestionAccessLogEntity::getProjectId, context.projectId)
                .in(!context.serviceIds.isEmpty(), DataIngestionAccessLogEntity::getServiceId, context.serviceIds)
                .ge(DataIngestionAccessLogEntity::getOccurredAt, context.startTime)
                .le(DataIngestionAccessLogEntity::getOccurredAt, context.endTime)
                .eq(request.getSubscriptionId() != null && !isNoTokenSubscriptionFilter(request.getSubscriptionId()), DataIngestionAccessLogEntity::getSubscriptionId, request.getSubscriptionId())
                .isNull(isNoTokenSubscriptionFilter(request.getSubscriptionId()), DataIngestionAccessLogEntity::getSubscriptionId)
                .eq(request.getSuccess() != null, DataIngestionAccessLogEntity::getSuccess, Boolean.TRUE.equals(request.getSuccess()) ? 1 : 0)
                .and(isFocusedLogQuery(request.getLogFocus()), wrapper -> {
                    String focus = request.getLogFocus().toUpperCase(Locale.ROOT);
                    long minDurationMs = request.getMinDurationMs() == null || request.getMinDurationMs().longValue() < 0L
                            ? 1000L
                            : request.getMinDurationMs().longValue();
                    if ("ERROR".equals(focus)) {
                        wrapper.ne(DataIngestionAccessLogEntity::getSuccess, 1);
                    } else if ("SLOW".equals(focus)) {
                        wrapper.ge(DataIngestionAccessLogEntity::getDurationMs, minDurationMs);
                    } else if ("ERROR_OR_SLOW".equals(focus)) {
                        wrapper.ne(DataIngestionAccessLogEntity::getSuccess, 1)
                                .or()
                                .ge(DataIngestionAccessLogEntity::getDurationMs, minDurationMs);
                    }
                });
    }

    private DataIngestionMetricSummaryView buildSummary(List<DataIngestionAccessLogEntity> logs,
                                                        DataIngestionAccessCounterSummary counterSummary) {
        if (counterSummary != null && safeLong(counterSummary.getAccessCount()) > 0L) {
            return buildSummaryFromCounter(counterSummary, logs);
        }
        DataIngestionMetricSummaryView summary = new DataIngestionMetricSummaryView();
        long total = logs == null ? 0L : logs.size();
        long success = 0L;
        long received = 0L;
        long written = 0L;
        long failed = 0L;
        long sumDuration = 0L;
        Long minDuration = null;
        Long maxDuration = null;
        LocalDateTime lastAccessAt = null;
        List<Long> durations = new ArrayList<Long>();
        if (logs != null) {
            for (DataIngestionAccessLogEntity log : logs) {
                if (Integer.valueOf(1).equals(log.getSuccess())) {
                    success++;
                }
                long duration = safeLong(log.getDurationMs());
                durations.add(Long.valueOf(duration));
                sumDuration += duration;
                minDuration = minDuration == null ? Long.valueOf(duration) : Long.valueOf(Math.min(minDuration.longValue(), duration));
                maxDuration = maxDuration == null ? Long.valueOf(duration) : Long.valueOf(Math.max(maxDuration.longValue(), duration));
                received += safeLong(log.getReceivedCount());
                written += safeLong(log.getSuccessCount());
                failed += safeLong(log.getFailedCount());
                if (log.getOccurredAt() != null && (lastAccessAt == null || log.getOccurredAt().isAfter(lastAccessAt))) {
                    lastAccessAt = log.getOccurredAt();
                }
            }
        }
        summary.setAccessCount(Long.valueOf(total));
        summary.setSuccessCount(Long.valueOf(success));
        summary.setFailureCount(Long.valueOf(total - success));
        summary.setSuccessRate(metricViewSupport.rate(success, total));
        summary.setReceivedCount(Long.valueOf(received));
        summary.setWrittenCount(Long.valueOf(written));
        summary.setFailedCount(Long.valueOf(failed));
        summary.setMinResponseTimeMs(minDuration == null ? Long.valueOf(0L) : minDuration);
        summary.setMaxResponseTimeMs(maxDuration == null ? Long.valueOf(0L) : maxDuration);
        summary.setAvgResponseTimeMs(total == 0L ? Long.valueOf(0L) : Long.valueOf(Math.round(sumDuration * 1.0D / total)));
        summary.setP95ResponseTimeMs(metricViewSupport.percentile(durations, 95));
        summary.setP99ResponseTimeMs(metricViewSupport.percentile(durations, 99));
        summary.setLastAccessAt(lastAccessAt);
        summary.setCounterBacked(Boolean.FALSE);
        return summary;
    }

    private DataIngestionMetricSummaryView buildSummaryFromCounter(DataIngestionAccessCounterSummary counterSummary,
                                                                   List<DataIngestionAccessLogEntity> logs) {
        DataIngestionMetricSummaryView summary = new DataIngestionMetricSummaryView();
        DataIngestionMetricSummaryView logSummary = logs == null || logs.isEmpty() ? null : buildSummary(logs, null);
        long total = safeLong(counterSummary.getAccessCount());
        long success = safeLong(counterSummary.getSuccessCount());
        summary.setAccessCount(Long.valueOf(total));
        summary.setSuccessCount(Long.valueOf(success));
        summary.setFailureCount(Long.valueOf(safeLong(counterSummary.getFailureCount())));
        summary.setSuccessRate(metricViewSupport.rate(success, total));
        summary.setReceivedCount(Long.valueOf(safeLong(counterSummary.getReceivedCount())));
        summary.setWrittenCount(Long.valueOf(safeLong(counterSummary.getWrittenCount())));
        summary.setFailedCount(Long.valueOf(safeLong(counterSummary.getFailedCount())));
        summary.setMinResponseTimeMs(logSummary == null ? Long.valueOf(0L) : logSummary.getMinResponseTimeMs());
        summary.setMaxResponseTimeMs(logSummary == null ? Long.valueOf(0L) : logSummary.getMaxResponseTimeMs());
        summary.setAvgResponseTimeMs(logSummary == null ? Long.valueOf(0L) : logSummary.getAvgResponseTimeMs());
        summary.setP95ResponseTimeMs(logSummary == null ? Long.valueOf(0L) : logSummary.getP95ResponseTimeMs());
        summary.setP99ResponseTimeMs(logSummary == null ? Long.valueOf(0L) : logSummary.getP99ResponseTimeMs());
        summary.setLastAccessAt(logSummary == null ? null : logSummary.getLastAccessAt());
        summary.setCounterBacked(Boolean.TRUE);
        return summary;
    }

    private List<DataIngestionApiMetricView> buildServiceMetrics(List<DataIngestionAccessLogEntity> logs,
                                                                 Map<Long, DataIngestionServiceEntity> serviceMap) {
        Map<Long, List<DataIngestionAccessLogEntity>> grouped = new LinkedHashMap<Long, List<DataIngestionAccessLogEntity>>();
        for (DataIngestionAccessLogEntity log : logs) {
            if (log.getServiceId() == null) {
                continue;
            }
            List<DataIngestionAccessLogEntity> serviceLogs = grouped.get(log.getServiceId());
            if (serviceLogs == null) {
                serviceLogs = new ArrayList<DataIngestionAccessLogEntity>();
                grouped.put(log.getServiceId(), serviceLogs);
            }
            serviceLogs.add(log);
        }
        List<DataIngestionApiMetricView> result = new ArrayList<DataIngestionApiMetricView>();
        for (Map.Entry<Long, List<DataIngestionAccessLogEntity>> entry : grouped.entrySet()) {
            DataIngestionServiceEntity service = serviceMap.get(entry.getKey());
            DataIngestionApiMetricView metric = buildApiMetric(entry.getValue());
            metric.setServiceId(entry.getKey());
            metric.setServiceName(service == null ? metricViewSupport.firstServiceName(entry.getValue()) : service.getServiceName());
            metric.setServiceCode(service == null ? metricViewSupport.firstServiceCode(entry.getValue()) : service.getServiceCode());
            metric.setStatus(service == null ? metricViewSupport.firstServiceStatus(entry.getValue()) : service.getStatus());
            result.add(metric);
        }
        return result;
    }

    private List<DataIngestionApiMetricView> buildSubscriptionMetrics(List<DataIngestionAccessLogEntity> logs) {
        Map<Long, List<DataIngestionAccessLogEntity>> grouped = new LinkedHashMap<Long, List<DataIngestionAccessLogEntity>>();
        for (DataIngestionAccessLogEntity log : logs) {
            if (log.getSubscriptionId() == null) {
                continue;
            }
            List<DataIngestionAccessLogEntity> subscriptionLogs = grouped.get(log.getSubscriptionId());
            if (subscriptionLogs == null) {
                subscriptionLogs = new ArrayList<DataIngestionAccessLogEntity>();
                grouped.put(log.getSubscriptionId(), subscriptionLogs);
            }
            subscriptionLogs.add(log);
        }
        List<DataIngestionApiMetricView> result = new ArrayList<DataIngestionApiMetricView>();
        for (Map.Entry<Long, List<DataIngestionAccessLogEntity>> entry : grouped.entrySet()) {
            DataIngestionApiMetricView metric = buildApiMetric(entry.getValue());
            DataIngestionAccessLogEntity first = entry.getValue().isEmpty() ? null : entry.getValue().get(0);
            metric.setSubscriptionId(entry.getKey());
            metric.setSubscriptionName(first == null ? null : first.getSubscriptionNameSnapshot());
            metric.setServiceId(first == null ? null : first.getServiceId());
            metric.setServiceName(first == null ? null : first.getServiceNameSnapshot());
            metric.setServiceCode(first == null ? null : first.getServiceCodeSnapshot());
            metric.setStatus(first == null ? null : first.getServiceStatusSnapshot());
            result.add(metric);
        }
        result.sort(Comparator.comparing(DataIngestionApiMetricView::getAccessCount, Comparator.nullsFirst(Long::compareTo))
                .thenComparing(DataIngestionApiMetricView::getFailureCount, Comparator.nullsFirst(Long::compareTo))
                .reversed());
        return result;
    }

    private DataIngestionApiMetricView buildApiMetric(List<DataIngestionAccessLogEntity> logs) {
        DataIngestionApiMetricView metric = new DataIngestionApiMetricView();
        DataIngestionMetricSummaryView summary = buildSummary(logs, null);
        metric.setAccessCount(summary.getAccessCount());
        metric.setSuccessCount(summary.getSuccessCount());
        metric.setFailureCount(summary.getFailureCount());
        metric.setSuccessRate(summary.getSuccessRate());
        metric.setReceivedCount(summary.getReceivedCount());
        metric.setWrittenCount(summary.getWrittenCount());
        metric.setFailedCount(summary.getFailedCount());
        metric.setMinResponseTimeMs(summary.getMinResponseTimeMs());
        metric.setMaxResponseTimeMs(summary.getMaxResponseTimeMs());
        metric.setAvgResponseTimeMs(summary.getAvgResponseTimeMs());
        metric.setP95ResponseTimeMs(summary.getP95ResponseTimeMs());
        metric.setP99ResponseTimeMs(summary.getP99ResponseTimeMs());
        metric.setLastAccessAt(summary.getLastAccessAt());
        return metric;
    }

    private boolean shouldUseCounterSummary(MetricContext context) {
        return context.counterSummary != null
                && safeLong(context.counterSummary.getAccessCount()) > 0L
                && safeLong(context.counterSummary.getAccessCount()) >= (context.logs == null ? 0L : context.logs.size());
    }

    private RunMetricTrendView buildAccessTrend(MetricContext context, boolean useCounters) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView totalSeries = series("total", "调用量");
        RunMetricTrendSeriesView successSeries = series("success", "成功调用");
        RunMetricTrendSeriesView failureSeries = series("failure", "失败调用");
        for (Bucket bucket : buildBuckets(context)) {
            trend.getXAxis().add(bucket.label);
            long total = 0L;
            long success = 0L;
            long failure = 0L;
            if (useCounters) {
                for (DataIngestionAccessCounterSummary summary : context.counterBucketSummaries) {
                    if (inBucket(summary, bucket)) {
                        total += safeLong(summary.getAccessCount());
                        success += safeLong(summary.getSuccessCount());
                        failure += safeLong(summary.getFailureCount());
                    }
                }
            } else {
                for (DataIngestionAccessLogEntity log : context.logs) {
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

    private RunMetricTrendView buildRowTrend(MetricContext context, boolean useCounters, String key, String label) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView rowSeries = series(key, label);
        for (Bucket bucket : buildBuckets(context)) {
            trend.getXAxis().add(bucket.label);
            long rowCount = 0L;
            if (useCounters) {
                for (DataIngestionAccessCounterSummary summary : context.counterBucketSummaries) {
                    if (inBucket(summary, bucket)) {
                        rowCount += "written".equals(key) ? safeLong(summary.getWrittenCount()) : safeLong(summary.getReceivedCount());
                    }
                }
            } else {
                for (DataIngestionAccessLogEntity log : context.logs) {
                    if (inBucket(log, bucket)) {
                        rowCount += "written".equals(key) ? safeLong(log.getSuccessCount()) : safeLong(log.getReceivedCount());
                    }
                }
            }
            rowSeries.getData().add(Long.valueOf(rowCount));
        }
        trend.getSeries().add(rowSeries);
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
            for (DataIngestionAccessLogEntity log : context.logs) {
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
                for (DataIngestionAccessCounterSummary summary : context.counterBucketSummaries) {
                    if (inBucket(summary, bucket)) {
                        total += safeLong(summary.getAccessCount());
                        success += safeLong(summary.getSuccessCount());
                    }
                }
            } else {
                for (DataIngestionAccessLogEntity log : context.logs) {
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

    private List<DataServiceMetricDistributionView> buildErrorDistribution(List<DataIngestionAccessLogEntity> logs) {
        Map<String, Long> grouped = new LinkedHashMap<String, Long>();
        for (DataIngestionAccessLogEntity log : logs) {
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

    private boolean inBucket(DataIngestionAccessLogEntity log, Bucket bucket) {
        if (log.getOccurredAt() == null) {
            return false;
        }
        return !log.getOccurredAt().isBefore(bucket.start) && log.getOccurredAt().isBefore(bucket.end);
    }

    private boolean inBucket(DataIngestionAccessCounterSummary summary, Bucket bucket) {
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

    private boolean isNoTokenSubscriptionFilter(Long subscriptionId) {
        return subscriptionId != null && subscriptionId.longValue() < 0L;
    }

    private Long noTokenServiceId(Long subscriptionId) {
        if (!isNoTokenSubscriptionFilter(subscriptionId)) {
            return null;
        }
        return Long.valueOf(-subscriptionId.longValue());
    }

    private Long counterSubscriptionId(Long subscriptionId) {
        if (subscriptionId == null) {
            return null;
        }
        return isNoTokenSubscriptionFilter(subscriptionId) ? Long.valueOf(0L) : subscriptionId;
    }

    private boolean isTokenRequired(DataIngestionServiceEntity service) {
        return service == null || service.getTokenRequired() == null || service.getTokenRequired().intValue() != 0;
    }

    private String defaultSubscriptionName(DataIngestionServiceEntity service) {
        if (service != null && hasText(service.getDefaultSubscriptionName())) {
            return service.getDefaultSubscriptionName().trim();
        }
        return DEFAULT_NO_TOKEN_SUBSCRIPTION_NAME;
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

    private TimeRange resolveTimeRange(DataIngestionMetricQueryRequest request) {
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

    private boolean resolveHourGranularity(DataIngestionMetricQueryRequest request, TimeRange timeRange) {
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
        private final List<DataIngestionServiceEntity> services = new ArrayList<DataIngestionServiceEntity>();
        private final List<Long> serviceIds = new ArrayList<Long>();
        private final Map<Long, DataIngestionServiceEntity> serviceMap = new LinkedHashMap<Long, DataIngestionServiceEntity>();
        private final List<DataIngestionAccessLogEntity> logs = new ArrayList<DataIngestionAccessLogEntity>();
        private DataIngestionAccessCounterSummary counterSummary;
        private final List<DataIngestionAccessCounterSummary> counterBucketSummaries = new ArrayList<DataIngestionAccessCounterSummary>();
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
