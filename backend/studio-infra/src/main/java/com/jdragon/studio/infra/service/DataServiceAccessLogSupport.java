package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.entity.DataServiceAccessCounterEntity;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.DataServiceSubscriptionEntity;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.model.AlertSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

final class DataServiceAccessLogSupport {

    private static final Logger log = LoggerFactory.getLogger(DataServiceAccessLogSupport.class);

    private final DataServiceAccessLogMapper accessLogMapper;
    private final DataServiceAccessCounterMapper accessCounterMapper;
    private final DataServiceInvocationSupport invocationSupport;
    private AlertSignalPublisher alertSignalPublisher;

    DataServiceAccessLogSupport(DataServiceAccessLogMapper accessLogMapper,
                                DataServiceAccessCounterMapper accessCounterMapper,
                                DataServiceInvocationSupport invocationSupport) {
        this.accessLogMapper = accessLogMapper;
        this.accessCounterMapper = accessCounterMapper;
        this.invocationSupport = invocationSupport;
    }

    void recordAccessLog(DataServiceDefinitionEntity service,
                         DataServiceSubscriptionEntity subscription,
                         String defaultSubscriptionName,
                         String requestId,
                         String requestMethod,
                         LocalDateTime occurredAt,
                         long startedAt,
                         boolean success,
                         int httpStatus,
                         String errorCode,
                         String errorMessage,
                         String systemLog,
                         String clientIp,
                         String userAgent,
                         boolean cacheEnabled,
                         boolean cacheHit,
                         long rowCount,
                         OpenServiceInvocationLogService.ArchiveResult archiveResult) {
        try {
            DataServiceAccessLogEntity entity = new DataServiceAccessLogEntity();
            entity.setTenantId(service == null || !invocationSupport.hasText(service.getTenantId()) ? StudioConstants.DEFAULT_TENANT_ID : service.getTenantId());
            entity.setProjectId(service == null ? null : service.getProjectId());
            entity.setServiceId(service == null ? null : service.getId());
            entity.setRequestedClusterId(service == null ? null : service.getRuntimeClusterId());
            entity.setActualClusterId(service == null ? null : service.getRuntimeClusterId());
            entity.setServiceCodeSnapshot(service == null ? null : service.getServiceCode());
            entity.setServiceNameSnapshot(service == null ? null : service.getServiceName());
            entity.setServiceStatusSnapshot(service == null ? null : service.getStatus());
            entity.setSubscriptionId(subscription == null ? null : subscription.getId());
            entity.setSubscriptionNameSnapshot(subscription == null ? defaultSubscriptionName : subscription.getSubscriptionName());
            entity.setRequestId(truncate(requestId, 128));
            entity.setRequestMethod(invocationSupport.hasText(requestMethod) ? requestMethod.toUpperCase(Locale.ROOT) : null);
            entity.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
            entity.setDurationMs(Long.valueOf(Math.max(0L, (System.nanoTime() - startedAt) / 1000000L)));
            entity.setSuccess(success ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setHttpStatus(Integer.valueOf(httpStatus));
            entity.setErrorCode(truncate(errorCode, 128));
            entity.setErrorMessage(truncate(errorMessage, 1000));
            entity.setSystemLog(truncate(systemLog, 20000));
            entity.setClientIp(truncate(clientIp, 128));
            entity.setUserAgent(truncate(userAgent, 500));
            entity.setCacheEnabled(cacheEnabled ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setCacheHit(cacheHit ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setRowCount(Long.valueOf(Math.max(0L, rowCount)));
            applyArchiveResult(entity, archiveResult);
            accessLogMapper.insert(entity);
            recordAccessCounter(entity);
            publishSignals(service, entity);
        } catch (RuntimeException ex) {
            log.warn("Failed to write data service access log", ex);
        }
    }

    void setAlertSignalPublisher(AlertSignalPublisher alertSignalPublisher) {
        this.alertSignalPublisher = alertSignalPublisher;
    }

    private void publishSignals(DataServiceDefinitionEntity service, DataServiceAccessLogEntity entity) {
        if (alertSignalPublisher == null || service == null || entity.getProjectId() == null) {
            return;
        }
        publishLogSignal(entity, "DATA_SERVICE_LOG", "数据服务调用日志");
    }

    private void publishLogSignal(DataServiceAccessLogEntity entity, String domain, String name) {
        if (!"FAILED".equalsIgnoreCase(entity.getLogArchiveStatus()) && !"AVAILABLE".equalsIgnoreCase(entity.getLogArchiveStatus())) {
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("accessLogId", entity.getId());
        evidence.put("logArchiveStatus", entity.getLogArchiveStatus());
        evidence.put("logArchiveError", entity.getLogArchiveError());
        evidence.put("targetClusterId", entity.getRequestedClusterId());
        evidence.put("actualClusterId", entity.getActualClusterId());
        alertSignalPublisher.publish(new AlertSignal()
                .setTenantId(entity.getTenantId()).setProjectId(entity.getProjectId())
                .setSignalType("LOG_ARCHIVE").setSubjectType("LOG_STORAGE")
                .setSubjectKey(domain).setSubjectName(name).setStatus(entity.getLogArchiveStatus())
                .setSuccess("AVAILABLE".equalsIgnoreCase(entity.getLogArchiveStatus()))
                .setSourceId(String.valueOf(entity.getId())).setSourceEventKey("data-service-log:" + entity.getId() + ":" + entity.getLogArchiveStatus())
                .setTargetPath(AlertIncidentService.targetPath("LOG_STORAGE", null, null))
                .setOccurredAt(entity.getOccurredAt()).setEvidence(evidence));
    }

    private void recordAccessCounter(DataServiceAccessLogEntity logEntity) {
        if (logEntity == null || logEntity.getProjectId() == null || logEntity.getServiceId() == null) {
            return;
        }
        try {
            DataServiceAccessCounterEntity counter = new DataServiceAccessCounterEntity();
            counter.setId(IdWorker.getId());
            counter.setTenantId(logEntity.getTenantId());
            counter.setProjectId(logEntity.getProjectId());
            counter.setServiceId(logEntity.getServiceId());
            counter.setSubscriptionId(logEntity.getSubscriptionId() == null ? Long.valueOf(0L) : logEntity.getSubscriptionId());
            counter.setBucketStart(toHourBucket(logEntity.getOccurredAt()));
            counter.setSuccess(Integer.valueOf(1).equals(logEntity.getSuccess()) ? Integer.valueOf(1) : Integer.valueOf(0));
            counter.setCacheEnabled(Integer.valueOf(1).equals(logEntity.getCacheEnabled()) ? Integer.valueOf(1) : Integer.valueOf(0));
            counter.setCacheHit(Integer.valueOf(1).equals(logEntity.getCacheEnabled()) && Integer.valueOf(1).equals(logEntity.getCacheHit())
                    ? Integer.valueOf(1)
                    : Integer.valueOf(0));
            counter.setAccessCount(Long.valueOf(1L));
            counter.setRowCount(Long.valueOf(Math.max(0L, invocationSupport.safeLong(logEntity.getRowCount()))));
            incrementOrInsertCounter(counter);
        } catch (RuntimeException ex) {
            log.warn("Failed to update data service access counter", ex);
        }
    }

    private void incrementOrInsertCounter(DataServiceAccessCounterEntity counter) {
        int updated = accessCounterMapper.increment(counter);
        if (updated > 0) {
            return;
        }
        try {
            accessCounterMapper.insert(counter);
        } catch (RuntimeException ex) {
            accessCounterMapper.increment(counter);
        }
    }

    private LocalDateTime toHourBucket(LocalDateTime time) {
        LocalDateTime safeTime = time == null ? LocalDateTime.now() : time;
        return safeTime.withMinute(0).withSecond(0).withNano(0);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void applyArchiveResult(DataServiceAccessLogEntity entity, OpenServiceInvocationLogService.ArchiveResult archiveResult) {
        if (entity == null || archiveResult == null) {
            return;
        }
        entity.setLogStorageType(archiveResult.getLogStorageType());
        entity.setLogObjectBucket(archiveResult.getLogObjectBucket());
        entity.setLogObjectKey(archiveResult.getLogObjectKey());
        entity.setLogSizeBytes(archiveResult.getLogSizeBytes());
        entity.setLogCharset(archiveResult.getLogCharset());
        entity.setLogArchiveStatus(archiveResult.getLogArchiveStatus());
        entity.setLogArchiveError(truncate(archiveResult.getLogArchiveError(), 1000));
    }
}
