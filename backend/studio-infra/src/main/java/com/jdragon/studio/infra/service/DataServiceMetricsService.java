package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.dto.model.DataServiceAccessLogListView;
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
import com.jdragon.studio.infra.model.OpenServiceDashboardBucketSummary;
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
    private static final String DEFAULT_NO_TOKEN_SUBSCRIPTION_NAME = "免 Token 调用";
    private static final DateTimeFormatter REQUEST_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter HOUR_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter DAY_BUCKET_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HOUR_BUCKET_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH");

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
                .select(DataServiceDefinitionEntity::getId,
                        DataServiceDefinitionEntity::getServiceCode,
                        DataServiceDefinitionEntity::getServiceName,
                        DataServiceDefinitionEntity::getStatus,
                        DataServiceDefinitionEntity::getTokenRequired,
                        DataServiceDefinitionEntity::getDefaultSubscriptionName)
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
        List<DataServiceSubscriptionEntity> subscriptions = subscriptionMapper.selectList(new LambdaQueryWrapper<DataServiceSubscriptionEntity>()
                .select(DataServiceSubscriptionEntity::getId,
                        DataServiceSubscriptionEntity::getServiceId,
                        DataServiceSubscriptionEntity::getSubscriptionName,
                        DataServiceSubscriptionEntity::getEnabled)
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
        DataServiceMetricQueryRequest safeRequest = request == null ? new DataServiceMetricQueryRequest() : request;
        MetricContext context = loadContext(safeRequest, true);
        DataServiceMetricDashboardView view = new DataServiceMetricDashboardView();
        if (context.serviceIds.isEmpty()) {
            Map<String, OpenServiceDashboardBucketSummary> emptyBucketMap = new LinkedHashMap<String, OpenServiceDashboardBucketSummary>();
            RunMetricTrendView accessTrend = buildAccessTrend(context, emptyBucketMap);
            RunMetricTrendView outputRowTrend = buildOutputRowTrend(context, emptyBucketMap);
            RunMetricTrendView cacheHitTrend = buildCacheHitTrend(context, emptyBucketMap);
            view.setSummary(toSummary(null));
            view.setAccessTrend(accessTrend);
            view.setCumulativeAccessTrend(buildCumulativeTrend(accessTrend));
            view.setOutputRowTrend(outputRowTrend);
            view.setCumulativeOutputRowTrend(buildCumulativeTrend(outputRowTrend));
            view.setCacheHitTrend(cacheHitTrend);
            view.setCumulativeCacheHitTrend(buildCumulativeTrend(cacheHitTrend));
            view.setResponseTimeTrend(buildResponseTimeTrend(context, emptyBucketMap));
            view.setSuccessRateTrend(buildSuccessRateTrend(context, emptyBucketMap));
            return view;
        }
        Integer successFilter = safeRequest.getSuccess() == null ? null : (Boolean.TRUE.equals(safeRequest.getSuccess()) ? Integer.valueOf(1) : Integer.valueOf(0));
        Integer cacheHitFilter = safeRequest.getCacheHit() == null ? null : (Boolean.TRUE.equals(safeRequest.getCacheHit()) ? Integer.valueOf(1) : Integer.valueOf(0));
        Long subscriptionId = isNoTokenSubscriptionFilter(safeRequest.getSubscriptionId()) ? null : safeRequest.getSubscriptionId();
        boolean noTokenSubscription = isNoTokenSubscriptionFilter(safeRequest.getSubscriptionId());
        String logFocus = normalizedLogFocus(safeRequest.getLogFocus());
        Long minDurationMs = normalizeMinDurationMs(safeRequest.getMinDurationMs());
        DataServiceApiMetricView summary = accessLogMapper.selectDashboardSummary(context.tenantId, context.projectId, context.serviceIds,
                safeRequest.getRequestedClusterId(), safeRequest.getActualClusterId(),
                subscriptionId, noTokenSubscription, successFilter, cacheHitFilter, logFocus, minDurationMs,
                context.startTime, context.endTime, context.hourGranularity);
        List<OpenServiceDashboardBucketSummary> bucketSummaries = accessLogMapper.selectDashboardBuckets(context.tenantId, context.projectId, context.serviceIds,
                safeRequest.getRequestedClusterId(), safeRequest.getActualClusterId(),
                subscriptionId, noTokenSubscription, successFilter, cacheHitFilter, logFocus, minDurationMs,
                context.startTime, context.endTime, context.hourGranularity);
        Map<String, OpenServiceDashboardBucketSummary> bucketMap = bucketSummaryMap(bucketSummaries);
        RunMetricTrendView accessTrend = buildAccessTrend(context, bucketMap);
        RunMetricTrendView outputRowTrend = buildOutputRowTrend(context, bucketMap);
        RunMetricTrendView cacheHitTrend = buildCacheHitTrend(context, bucketMap);
        view.setSummary(toSummary(summary));
        view.setAccessTrend(accessTrend);
        view.setCumulativeAccessTrend(buildCumulativeTrend(accessTrend));
        view.setOutputRowTrend(outputRowTrend);
        view.setCumulativeOutputRowTrend(buildCumulativeTrend(outputRowTrend));
        view.setCacheHitTrend(cacheHitTrend);
        view.setCumulativeCacheHitTrend(buildCumulativeTrend(cacheHitTrend));
        view.setResponseTimeTrend(buildResponseTimeTrend(context, bucketMap));
        view.setSuccessRateTrend(buildSuccessRateTrend(context, bucketMap));
        view.setErrorDistribution(normalizeErrorDistribution(accessLogMapper.selectDashboardErrorDistribution(context.tenantId, context.projectId, context.serviceIds,
                safeRequest.getRequestedClusterId(), safeRequest.getActualClusterId(),
                subscriptionId, noTokenSubscription, successFilter, cacheHitFilter, logFocus, minDurationMs,
                context.startTime, context.endTime, context.hourGranularity, context.topN)));
        List<DataServiceApiMetricView> topSlowApis = accessLogMapper.selectDashboardApiStats(context.tenantId, context.projectId, context.serviceIds,
                safeRequest.getRequestedClusterId(), safeRequest.getActualClusterId(),
                subscriptionId, noTokenSubscription, successFilter, cacheHitFilter, logFocus, minDurationMs,
                context.startTime, context.endTime, context.hourGranularity, "SLOW", context.topN);
        hydrateApiMetrics(topSlowApis, context.serviceMap);
        view.setTopSlowApis(topSlowApis);
        List<DataServiceApiMetricView> topFailedApis = accessLogMapper.selectDashboardApiStats(context.tenantId, context.projectId, context.serviceIds,
                safeRequest.getRequestedClusterId(), safeRequest.getActualClusterId(),
                subscriptionId, noTokenSubscription, successFilter, cacheHitFilter, logFocus, minDurationMs,
                context.startTime, context.endTime, context.hourGranularity, "FAILED", context.topN);
        hydrateApiMetrics(topFailedApis, context.serviceMap);
        view.setTopFailedApis(topFailedApis);
        List<DataServiceApiMetricView> subscriptionRank = accessLogMapper.selectDashboardSubscriptionRank(context.tenantId, context.projectId, context.serviceIds,
                safeRequest.getRequestedClusterId(), safeRequest.getActualClusterId(),
                subscriptionId, noTokenSubscription, successFilter, cacheHitFilter, logFocus, minDurationMs,
                context.startTime, context.endTime, context.hourGranularity, context.topN);
        hydrateApiMetrics(subscriptionRank, context.serviceMap);
        view.setSubscriptionRank(subscriptionRank);
        return view;
    }

    public PageView<DataServiceApiMetricView> queryApiStats(DataServiceMetricQueryRequest request) {
        DataServiceMetricQueryRequest safeRequest = request == null ? new DataServiceMetricQueryRequest() : request;
        MetricContext context = loadContext(safeRequest, true);
        if (context.serviceIds.isEmpty()) {
            return PageView.of(context.pageNo, context.pageSize, 0L, new ArrayList<DataServiceApiMetricView>());
        }
        Integer successFilter = safeRequest.getSuccess() == null ? null : (Boolean.TRUE.equals(safeRequest.getSuccess()) ? Integer.valueOf(1) : Integer.valueOf(0));
        Integer cacheHitFilter = safeRequest.getCacheHit() == null ? null : (Boolean.TRUE.equals(safeRequest.getCacheHit()) ? Integer.valueOf(1) : Integer.valueOf(0));
        Long subscriptionId = isNoTokenSubscriptionFilter(safeRequest.getSubscriptionId()) ? null : safeRequest.getSubscriptionId();
        boolean noTokenSubscription = isNoTokenSubscriptionFilter(safeRequest.getSubscriptionId());
        String logFocus = normalizedLogFocus(safeRequest.getLogFocus());
        Long minDurationMs = normalizeMinDurationMs(safeRequest.getMinDurationMs());
        long total = accessLogMapper.countApiStats(context.tenantId, context.projectId, context.serviceIds,
                safeRequest.getRequestedClusterId(), safeRequest.getActualClusterId(),
                subscriptionId, noTokenSubscription, successFilter, cacheHitFilter, logFocus, minDurationMs,
                context.startTime, context.endTime);
        List<DataServiceApiMetricView> metrics = total <= 0L
                ? new ArrayList<DataServiceApiMetricView>()
                : accessLogMapper.selectApiStatsPage(context.tenantId, context.projectId, context.serviceIds,
                        safeRequest.getRequestedClusterId(), safeRequest.getActualClusterId(),
                        subscriptionId, noTokenSubscription, successFilter, cacheHitFilter, logFocus, minDurationMs,
                        context.startTime, context.endTime, context.pageSize, offset(context));
        hydrateApiMetrics(metrics, context.serviceMap);
        return PageView.of(context.pageNo, context.pageSize, total, metrics);
    }

    public PageView<DataServiceAccessLogListView> queryAccessLogs(DataServiceMetricQueryRequest request) {
        DataServiceMetricQueryRequest safeRequest = request == null ? new DataServiceMetricQueryRequest() : request;
        MetricContext context = loadContext(safeRequest, true);
        if (context.serviceIds.isEmpty()) {
            return PageView.of(context.pageNo, context.pageSize, 0L, new ArrayList<DataServiceAccessLogListView>());
        }
        Page<DataServiceAccessLogEntity> page = new Page<DataServiceAccessLogEntity>(context.pageNo, context.pageSize);
        Page<DataServiceAccessLogEntity> entityPage = accessLogMapper.selectPage(page, accessLogListQuery(safeRequest, context)
                .orderByDesc(DataServiceAccessLogEntity::getOccurredAt)
                .orderByDesc(DataServiceAccessLogEntity::getId));
        List<DataServiceAccessLogListView> items = new ArrayList<DataServiceAccessLogListView>();
        for (DataServiceAccessLogEntity entity : entityPage.getRecords()) {
            items.add(metricViewSupport.toAccessLogListView(entity));
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
        if (!skipLogList && !context.serviceIds.isEmpty()) {
            Integer successFilter = safeRequest.getSuccess() == null ? null : (Boolean.TRUE.equals(safeRequest.getSuccess()) ? Integer.valueOf(1) : Integer.valueOf(0));
            Integer cacheHitFilter = safeRequest.getCacheHit() == null ? null : (Boolean.TRUE.equals(safeRequest.getCacheHit()) ? Integer.valueOf(1) : Integer.valueOf(0));
            Long counterSubscriptionId = counterSubscriptionId(safeRequest.getSubscriptionId());
            context.counterSummary = accessCounterMapper.selectSummary(context.tenantId, context.projectId, context.serviceIds,
                    counterSubscriptionId, successFilter, cacheHitFilter, context.startTime, context.endTime);
            context.counterBucketSummaries.addAll(accessCounterMapper.selectBucketSummaries(context.tenantId, context.projectId, context.serviceIds,
                    counterSubscriptionId, successFilter, cacheHitFilter, context.startTime, context.endTime));
        }
        if (!skipLogList && !context.serviceIds.isEmpty()) {
            context.logs.addAll(accessLogMapper.selectList(metricLogQuery(safeRequest, context)
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
                .select(DataServiceDefinitionEntity::getId,
                        DataServiceDefinitionEntity::getServiceCode,
                        DataServiceDefinitionEntity::getServiceName,
                        DataServiceDefinitionEntity::getStatus)
                .eq(DataServiceDefinitionEntity::getTenantId, context.tenantId)
                .eq(DataServiceDefinitionEntity::getProjectId, context.projectId)
                .eq(request.getServiceId() != null, DataServiceDefinitionEntity::getId, request.getServiceId())
                .eq(noTokenServiceId(request.getSubscriptionId()) != null, DataServiceDefinitionEntity::getId, noTokenServiceId(request.getSubscriptionId()))
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
                .eq(request.getRequestedClusterId() != null, DataServiceAccessLogEntity::getRequestedClusterId, request.getRequestedClusterId())
                .eq(request.getActualClusterId() != null, DataServiceAccessLogEntity::getActualClusterId, request.getActualClusterId())
                .ge(DataServiceAccessLogEntity::getOccurredAt, context.startTime)
                .le(DataServiceAccessLogEntity::getOccurredAt, context.endTime)
                .eq(request.getSubscriptionId() != null && !isNoTokenSubscriptionFilter(request.getSubscriptionId()), DataServiceAccessLogEntity::getSubscriptionId, request.getSubscriptionId())
                .isNull(isNoTokenSubscriptionFilter(request.getSubscriptionId()), DataServiceAccessLogEntity::getSubscriptionId)
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

    private LambdaQueryWrapper<DataServiceAccessLogEntity> accessLogListQuery(DataServiceMetricQueryRequest request,
                                                                              MetricContext context) {
        return baseLogQuery(request, context)
                .select(DataServiceAccessLogEntity::getId,
                        DataServiceAccessLogEntity::getServiceId,
                        DataServiceAccessLogEntity::getRequestedClusterId,
                        DataServiceAccessLogEntity::getActualClusterId,
                        DataServiceAccessLogEntity::getServiceCodeSnapshot,
                        DataServiceAccessLogEntity::getServiceNameSnapshot,
                        DataServiceAccessLogEntity::getServiceStatusSnapshot,
                        DataServiceAccessLogEntity::getSubscriptionId,
                        DataServiceAccessLogEntity::getSubscriptionNameSnapshot,
                        DataServiceAccessLogEntity::getRequestId,
                        DataServiceAccessLogEntity::getRequestMethod,
                        DataServiceAccessLogEntity::getOccurredAt,
                        DataServiceAccessLogEntity::getDurationMs,
                        DataServiceAccessLogEntity::getSuccess,
                        DataServiceAccessLogEntity::getHttpStatus,
                        DataServiceAccessLogEntity::getErrorCode,
                        DataServiceAccessLogEntity::getErrorMessage,
                        DataServiceAccessLogEntity::getClientIp,
                        DataServiceAccessLogEntity::getUserAgent,
                        DataServiceAccessLogEntity::getCacheEnabled,
                        DataServiceAccessLogEntity::getCacheHit,
                        DataServiceAccessLogEntity::getRowCount);
    }

    private LambdaQueryWrapper<DataServiceAccessLogEntity> metricLogQuery(DataServiceMetricQueryRequest request,
                                                                          MetricContext context) {
        return baseLogQuery(request, context)
                .select(DataServiceAccessLogEntity::getId,
                        DataServiceAccessLogEntity::getServiceId,
                        DataServiceAccessLogEntity::getServiceCodeSnapshot,
                        DataServiceAccessLogEntity::getServiceNameSnapshot,
                        DataServiceAccessLogEntity::getServiceStatusSnapshot,
                        DataServiceAccessLogEntity::getSubscriptionId,
                        DataServiceAccessLogEntity::getSubscriptionNameSnapshot,
                        DataServiceAccessLogEntity::getOccurredAt,
                        DataServiceAccessLogEntity::getDurationMs,
                        DataServiceAccessLogEntity::getSuccess,
                        DataServiceAccessLogEntity::getHttpStatus,
                        DataServiceAccessLogEntity::getErrorCode,
                        DataServiceAccessLogEntity::getCacheEnabled,
                        DataServiceAccessLogEntity::getCacheHit,
                        DataServiceAccessLogEntity::getRowCount);
    }

    private DataServiceMetricSummaryView toSummary(DataServiceApiMetricView metric) {
        DataServiceMetricSummaryView summary = new DataServiceMetricSummaryView();
        long total = metric == null ? 0L : safeLong(metric.getAccessCount());
        long success = metric == null ? 0L : safeLong(metric.getSuccessCount());
        long cacheEnabled = metric == null ? 0L : safeLong(metric.getCacheEnabledCount());
        long cacheHit = metric == null ? 0L : safeLong(metric.getCacheHitCount());
        summary.setAccessCount(Long.valueOf(total));
        summary.setSuccessCount(Long.valueOf(success));
        summary.setFailureCount(Long.valueOf(metric == null ? 0L : safeLong(metric.getFailureCount())));
        summary.setSuccessRate(metricViewSupport.rate(success, total));
        summary.setMinResponseTimeMs(Long.valueOf(metric == null ? 0L : safeLong(metric.getMinResponseTimeMs())));
        summary.setMaxResponseTimeMs(Long.valueOf(metric == null ? 0L : safeLong(metric.getMaxResponseTimeMs())));
        summary.setAvgResponseTimeMs(Long.valueOf(metric == null ? 0L : safeLong(metric.getAvgResponseTimeMs())));
        summary.setP95ResponseTimeMs(Long.valueOf(metric == null ? 0L : safeLong(metric.getP95ResponseTimeMs())));
        summary.setP99ResponseTimeMs(Long.valueOf(metric == null ? 0L : safeLong(metric.getP99ResponseTimeMs())));
        summary.setLastAccessAt(metric == null ? null : metric.getLastAccessAt());
        summary.setCacheEnabledCount(Long.valueOf(cacheEnabled));
        summary.setCacheHitCount(Long.valueOf(cacheHit));
        summary.setCacheMissCount(Long.valueOf(metric == null ? 0L : safeLong(metric.getCacheMissCount())));
        summary.setCacheDisabledCount(Long.valueOf(metric == null ? 0L : safeLong(metric.getCacheDisabledCount())));
        summary.setCacheHitRate(metricViewSupport.rate(cacheHit, cacheEnabled));
        summary.setCounterBacked(Boolean.FALSE);
        return summary;
    }

    private Map<String, OpenServiceDashboardBucketSummary> bucketSummaryMap(List<OpenServiceDashboardBucketSummary> summaries) {
        Map<String, OpenServiceDashboardBucketSummary> result = new LinkedHashMap<String, OpenServiceDashboardBucketSummary>();
        if (summaries == null) {
            return result;
        }
        for (OpenServiceDashboardBucketSummary summary : summaries) {
            if (summary != null && hasText(summary.getBucketKey())) {
                result.put(summary.getBucketKey(), summary);
            }
        }
        return result;
    }

    private RunMetricTrendView buildAccessTrend(MetricContext context,
                                                Map<String, OpenServiceDashboardBucketSummary> bucketMap) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView totalSeries = series("total", "访问量");
        RunMetricTrendSeriesView successSeries = series("success", "成功量");
        RunMetricTrendSeriesView failureSeries = series("failure", "失败量");
        for (Bucket bucket : buildBuckets(context)) {
            OpenServiceDashboardBucketSummary summary = bucketMap.get(bucketKey(bucket, context.hourGranularity));
            trend.getXAxis().add(bucket.label);
            totalSeries.getData().add(Long.valueOf(summary == null ? 0L : safeLong(summary.getAccessCount())));
            successSeries.getData().add(Long.valueOf(summary == null ? 0L : safeLong(summary.getSuccessCount())));
            failureSeries.getData().add(Long.valueOf(summary == null ? 0L : safeLong(summary.getFailureCount())));
        }
        trend.getSeries().add(totalSeries);
        trend.getSeries().add(successSeries);
        trend.getSeries().add(failureSeries);
        return trend;
    }

    private RunMetricTrendView buildOutputRowTrend(MetricContext context,
                                                   Map<String, OpenServiceDashboardBucketSummary> bucketMap) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView rowSeries = series("outputRows", "输出行数");
        for (Bucket bucket : buildBuckets(context)) {
            OpenServiceDashboardBucketSummary summary = bucketMap.get(bucketKey(bucket, context.hourGranularity));
            trend.getXAxis().add(bucket.label);
            rowSeries.getData().add(Long.valueOf(summary == null ? 0L : safeLong(summary.getRowCount())));
        }
        trend.getSeries().add(rowSeries);
        return trend;
    }

    private RunMetricTrendView buildCacheHitTrend(MetricContext context,
                                                  Map<String, OpenServiceDashboardBucketSummary> bucketMap) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView hitSeries = series("cacheHits", "缓存命中次数");
        for (Bucket bucket : buildBuckets(context)) {
            OpenServiceDashboardBucketSummary summary = bucketMap.get(bucketKey(bucket, context.hourGranularity));
            trend.getXAxis().add(bucket.label);
            hitSeries.getData().add(Long.valueOf(summary == null ? 0L : safeLong(summary.getCacheHitCount())));
        }
        trend.getSeries().add(hitSeries);
        return trend;
    }

    private RunMetricTrendView buildResponseTimeTrend(MetricContext context,
                                                      Map<String, OpenServiceDashboardBucketSummary> bucketMap) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView avgSeries = series("avg", "平均耗时");
        RunMetricTrendSeriesView maxSeries = series("max", "最大耗时");
        RunMetricTrendSeriesView p95Series = series("p95", "P95");
        RunMetricTrendSeriesView p99Series = series("p99", "P99");
        for (Bucket bucket : buildBuckets(context)) {
            OpenServiceDashboardBucketSummary summary = bucketMap.get(bucketKey(bucket, context.hourGranularity));
            trend.getXAxis().add(bucket.label);
            avgSeries.getData().add(Long.valueOf(summary == null ? 0L : safeLong(summary.getAvgResponseTimeMs())));
            maxSeries.getData().add(Long.valueOf(summary == null ? 0L : safeLong(summary.getMaxResponseTimeMs())));
            p95Series.getData().add(Long.valueOf(summary == null ? 0L : safeLong(summary.getP95ResponseTimeMs())));
            p99Series.getData().add(Long.valueOf(summary == null ? 0L : safeLong(summary.getP99ResponseTimeMs())));
        }
        trend.getSeries().add(avgSeries);
        trend.getSeries().add(maxSeries);
        trend.getSeries().add(p95Series);
        trend.getSeries().add(p99Series);
        return trend;
    }

    private RunMetricTrendView buildSuccessRateTrend(MetricContext context,
                                                     Map<String, OpenServiceDashboardBucketSummary> bucketMap) {
        RunMetricTrendView trend = new RunMetricTrendView();
        RunMetricTrendSeriesView rateSeries = series("successRate", "成功率");
        for (Bucket bucket : buildBuckets(context)) {
            OpenServiceDashboardBucketSummary summary = bucketMap.get(bucketKey(bucket, context.hourGranularity));
            long total = summary == null ? 0L : safeLong(summary.getAccessCount());
            long success = summary == null ? 0L : safeLong(summary.getSuccessCount());
            trend.getXAxis().add(bucket.label);
            rateSeries.getData().add(Long.valueOf(Math.round(metricViewSupport.rate(success, total))));
        }
        trend.getSeries().add(rateSeries);
        return trend;
    }

    private List<DataServiceMetricDistributionView> normalizeErrorDistribution(List<DataServiceMetricDistributionView> items) {
        List<DataServiceMetricDistributionView> result = new ArrayList<DataServiceMetricDistributionView>();
        if (items == null) {
            return result;
        }
        for (DataServiceMetricDistributionView item : items) {
            if (item == null) {
                continue;
            }
            String key = item.getKey();
            String normalizedKey = isNumeric(key) ? "HTTP_" + key : hasText(key) ? key : "UNKNOWN";
            item.setKey(normalizedKey);
            item.setLabel(hasText(item.getLabel()) && !isNumeric(item.getLabel()) ? item.getLabel() : normalizedKey);
            result.add(item);
        }
        return result;
    }

    private String bucketKey(Bucket bucket, boolean hourGranularity) {
        return bucket.start.format(hourGranularity ? HOUR_BUCKET_KEY_FORMATTER : DAY_BUCKET_KEY_FORMATTER);
    }

    private boolean isNumeric(String value) {
        if (!hasText(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
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

    private void hydrateApiMetrics(List<DataServiceApiMetricView> metrics,
                                   Map<Long, DataServiceDefinitionEntity> serviceMap) {
        if (metrics == null) {
            return;
        }
        for (DataServiceApiMetricView metric : metrics) {
            DataServiceDefinitionEntity service = serviceMap.get(metric.getServiceId());
            if (service != null) {
                metric.setServiceName(service.getServiceName());
                metric.setServiceCode(service.getServiceCode());
                metric.setStatus(service.getStatus());
            }
            metric.setSuccessRate(metricViewSupport.rate(safeLong(metric.getSuccessCount()), safeLong(metric.getAccessCount())));
            metric.setCacheHitRate(metricViewSupport.rate(safeLong(metric.getCacheHitCount()), safeLong(metric.getCacheEnabledCount())));
        }
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

    private int offset(MetricContext context) {
        return Math.max(0, (context.pageNo - 1) * context.pageSize);
    }

    private Long normalizeMinDurationMs(Long minDurationMs) {
        return minDurationMs == null || minDurationMs.longValue() < 0L ? Long.valueOf(1000L) : minDurationMs;
    }

    private String normalizedLogFocus(String value) {
        if (!isFocusedLogQuery(value)) {
            return null;
        }
        return value.toUpperCase(Locale.ROOT);
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

    private boolean isTokenRequired(DataServiceDefinitionEntity service) {
        return service == null || service.getTokenRequired() == null || service.getTokenRequired().intValue() != 0;
    }

    private String defaultSubscriptionName(DataServiceDefinitionEntity service) {
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
