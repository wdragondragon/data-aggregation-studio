package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.dto.model.DataServiceMetricOptionView;
import com.jdragon.studio.dto.model.DataServiceMetricOptionsView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.ProtocolConversionAccessLogView;
import com.jdragon.studio.dto.model.request.ProtocolConversionMetricQueryRequest;
import com.jdragon.studio.infra.entity.ProtocolConversionAccessLogEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionSubscriptionEntity;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessLogMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionSubscriptionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ProtocolConversionMetricsService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 200;
    private static final String DEFAULT_NO_TOKEN_SUBSCRIPTION_NAME = "免 Token 调用";
    private static final DateTimeFormatter REQUEST_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProtocolConversionAccessLogMapper accessLogMapper;
    private final ProtocolConversionServiceMapper serviceMapper;
    private final ProtocolConversionSubscriptionMapper subscriptionMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;

    public ProtocolConversionMetricsService(ProtocolConversionAccessLogMapper accessLogMapper,
                                            ProtocolConversionServiceMapper serviceMapper,
                                            ProtocolConversionSubscriptionMapper subscriptionMapper,
                                            StudioSecurityService securityService,
                                            ProjectResourceAccessService projectResourceAccessService) {
        this.accessLogMapper = accessLogMapper;
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
        List<ProtocolConversionServiceEntity> services = serviceMapper.selectList(new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                .eq(ProtocolConversionServiceEntity::getTenantId, securityService.currentTenantId())
                .eq(ProtocolConversionServiceEntity::getProjectId, projectId)
                .orderByAsc(ProtocolConversionServiceEntity::getServiceName)
                .orderByDesc(ProtocolConversionServiceEntity::getId));
        List<Long> serviceIds = new ArrayList<Long>();
        for (ProtocolConversionServiceEntity service : services) {
            serviceIds.add(service.getId());
            DataServiceMetricOptionView item = new DataServiceMetricOptionView();
            item.setId(service.getId());
            item.setName(service.getServiceName());
            item.setLabel(service.getServiceName() + " / " + service.getServiceCode());
            item.setStatus(service.getStatus());
            view.getServices().add(item);
            if (!isTokenRequired(service)) {
                DataServiceMetricOptionView noToken = new DataServiceMetricOptionView();
                noToken.setId(Long.valueOf(-service.getId().longValue()));
                noToken.setName(defaultSubscriptionName(service));
                noToken.setLabel(defaultSubscriptionName(service) + " / " + service.getServiceName());
                noToken.setStatus("NO_TOKEN");
                noToken.setServiceId(service.getId());
                noToken.setServiceName(service.getServiceName());
                view.getSubscriptions().add(noToken);
            }
        }
        if (serviceIds.isEmpty()) {
            return view;
        }
        List<ProtocolConversionSubscriptionEntity> subscriptions = subscriptionMapper.selectList(new LambdaQueryWrapper<ProtocolConversionSubscriptionEntity>()
                .in(ProtocolConversionSubscriptionEntity::getServiceId, serviceIds)
                .orderByAsc(ProtocolConversionSubscriptionEntity::getSubscriptionName)
                .orderByDesc(ProtocolConversionSubscriptionEntity::getId));
        for (ProtocolConversionSubscriptionEntity subscription : subscriptions) {
            DataServiceMetricOptionView item = new DataServiceMetricOptionView();
            item.setId(subscription.getId());
            item.setName(subscription.getSubscriptionName());
            item.setLabel(subscription.getSubscriptionName());
            item.setStatus(Integer.valueOf(1).equals(subscription.getEnabled()) ? "ENABLED" : "DISABLED");
            item.setServiceId(subscription.getServiceId());
            view.getSubscriptions().add(item);
        }
        return view;
    }

    public PageView<ProtocolConversionAccessLogView> queryAccessLogs(ProtocolConversionMetricQueryRequest request) {
        ProtocolConversionMetricQueryRequest safeRequest = request == null ? new ProtocolConversionMetricQueryRequest() : request;
        int pageNo = normalizePageNo(safeRequest.getPageNo());
        int pageSize = normalizePageSize(safeRequest.getPageSize());
        Long projectId = projectResourceAccessService.currentProjectId();
        if (projectId == null) {
            return PageView.of(pageNo, pageSize, 0L, new ArrayList<ProtocolConversionAccessLogView>());
        }
        TimeRange range = resolveTimeRange(safeRequest);
        Page<ProtocolConversionAccessLogEntity> page = new Page<ProtocolConversionAccessLogEntity>(pageNo, pageSize);
        Page<ProtocolConversionAccessLogEntity> entityPage = accessLogMapper.selectPage(page, baseLogQuery(safeRequest, projectId, range)
                .orderByDesc(ProtocolConversionAccessLogEntity::getOccurredAt)
                .orderByDesc(ProtocolConversionAccessLogEntity::getId));
        List<ProtocolConversionAccessLogView> items = new ArrayList<ProtocolConversionAccessLogView>();
        for (ProtocolConversionAccessLogEntity entity : entityPage.getRecords()) {
            items.add(toAccessLogView(entity));
        }
        return PageView.of(pageNo, pageSize, entityPage.getTotal(), items);
    }

    private LambdaQueryWrapper<ProtocolConversionAccessLogEntity> baseLogQuery(ProtocolConversionMetricQueryRequest request,
                                                                               Long projectId,
                                                                               TimeRange range) {
        return new LambdaQueryWrapper<ProtocolConversionAccessLogEntity>()
                .eq(ProtocolConversionAccessLogEntity::getTenantId, securityService.currentTenantId())
                .eq(ProtocolConversionAccessLogEntity::getProjectId, projectId)
                .eq(request.getServiceId() != null || noTokenServiceId(request.getSubscriptionId()) != null,
                        ProtocolConversionAccessLogEntity::getServiceId,
                        noTokenServiceId(request.getSubscriptionId()) == null ? request.getServiceId() : noTokenServiceId(request.getSubscriptionId()))
                .eq(hasText(request.getServiceStatus()), ProtocolConversionAccessLogEntity::getServiceStatusSnapshot,
                        hasText(request.getServiceStatus()) ? request.getServiceStatus().toUpperCase(Locale.ROOT) : null)
                .eq(request.getSubscriptionId() != null && !isNoTokenSubscriptionFilter(request.getSubscriptionId()), ProtocolConversionAccessLogEntity::getSubscriptionId, request.getSubscriptionId())
                .isNull(isNoTokenSubscriptionFilter(request.getSubscriptionId()), ProtocolConversionAccessLogEntity::getSubscriptionId)
                .eq(request.getSuccess() != null, ProtocolConversionAccessLogEntity::getSuccess, Boolean.TRUE.equals(request.getSuccess()) ? 1 : 0)
                .ge(ProtocolConversionAccessLogEntity::getOccurredAt, range.startTime)
                .le(ProtocolConversionAccessLogEntity::getOccurredAt, range.endTime)
                .and(isFocusedLogQuery(request.getLogFocus()), wrapper -> {
                    String focus = request.getLogFocus().toUpperCase(Locale.ROOT);
                    long minDurationMs = request.getMinDurationMs() == null || request.getMinDurationMs().longValue() < 0L
                            ? 1000L
                            : request.getMinDurationMs().longValue();
                    if ("ERROR".equals(focus)) {
                        wrapper.eq(ProtocolConversionAccessLogEntity::getSuccess, 0);
                    } else if ("SLOW".equals(focus)) {
                        wrapper.eq(ProtocolConversionAccessLogEntity::getSuccess, 1)
                                .ge(ProtocolConversionAccessLogEntity::getDurationMs, minDurationMs);
                    } else {
                        wrapper.eq(ProtocolConversionAccessLogEntity::getSuccess, 0)
                                .or()
                                .ge(ProtocolConversionAccessLogEntity::getDurationMs, minDurationMs);
                    }
                });
    }

    private ProtocolConversionAccessLogView toAccessLogView(ProtocolConversionAccessLogEntity entity) {
        ProtocolConversionAccessLogView view = new ProtocolConversionAccessLogView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(Integer.valueOf(1).equals(entity.getDeleted()));
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setServiceId(entity.getServiceId());
        view.setServiceCode(entity.getServiceCodeSnapshot());
        view.setServiceName(entity.getServiceNameSnapshot());
        view.setServiceStatus(entity.getServiceStatusSnapshot());
        view.setSubscriptionId(entity.getSubscriptionId());
        view.setSubscriptionName(entity.getSubscriptionNameSnapshot());
        view.setRequestId(entity.getRequestId());
        view.setRequestMethod(entity.getRequestMethod());
        view.setSourceProtocol(entity.getSourceProtocolSnapshot());
        view.setTargetProtocol(entity.getTargetProtocolSnapshot());
        view.setOccurredAt(entity.getOccurredAt());
        view.setDurationMs(entity.getDurationMs());
        view.setSuccess(Integer.valueOf(1).equals(entity.getSuccess()));
        view.setHttpStatus(entity.getHttpStatus());
        view.setTargetHttpStatus(entity.getTargetHttpStatus());
        view.setErrorCode(entity.getErrorCode());
        view.setErrorMessage(entity.getErrorMessage());
        view.setSystemLog(entity.getSystemLog());
        view.setClientIp(entity.getClientIp());
        view.setUserAgent(entity.getUserAgent());
        view.setReceivedCount(entity.getReceivedCount());
        view.setSuccessCount(entity.getSuccessCount());
        view.setFailedCount(entity.getFailedCount());
        view.setLogStorageType(entity.getLogStorageType());
        view.setLogObjectBucket(entity.getLogObjectBucket());
        view.setLogObjectKey(entity.getLogObjectKey());
        view.setLogSizeBytes(entity.getLogSizeBytes());
        view.setLogCharset(entity.getLogCharset());
        view.setLogArchiveStatus(entity.getLogArchiveStatus());
        view.setLogArchiveError(entity.getLogArchiveError());
        return view;
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

    private TimeRange resolveTimeRange(ProtocolConversionMetricQueryRequest request) {
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

    private boolean isNoTokenSubscriptionFilter(Long subscriptionId) {
        return subscriptionId != null && subscriptionId.longValue() < 0L;
    }

    private Long noTokenServiceId(Long subscriptionId) {
        return isNoTokenSubscriptionFilter(subscriptionId) ? Long.valueOf(-subscriptionId.longValue()) : null;
    }

    private boolean isTokenRequired(ProtocolConversionServiceEntity service) {
        return service == null || service.getTokenRequired() == null || service.getTokenRequired().intValue() != 0;
    }

    private String defaultSubscriptionName(ProtocolConversionServiceEntity service) {
        if (service != null && hasText(service.getDefaultSubscriptionName())) {
            return service.getDefaultSubscriptionName().trim();
        }
        return DEFAULT_NO_TOKEN_SUBSCRIPTION_NAME;
    }

    private boolean isFocusedLogQuery(String value) {
        if (!hasText(value) || "ALL".equalsIgnoreCase(value)) {
            return false;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        return "ERROR".equals(normalized) || "SLOW".equals(normalized) || "ERROR_OR_SLOW".equals(normalized);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class TimeRange {
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }
}
