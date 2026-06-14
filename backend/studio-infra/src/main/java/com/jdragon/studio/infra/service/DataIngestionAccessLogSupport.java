package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.entity.DataIngestionAccessCounterEntity;
import com.jdragon.studio.infra.entity.DataIngestionAccessLogEntity;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataIngestionSubscriptionEntity;
import com.jdragon.studio.infra.mapper.DataIngestionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Locale;

final class DataIngestionAccessLogSupport {

    private static final Logger log = LoggerFactory.getLogger(DataIngestionAccessLogSupport.class);

    private final DataIngestionAccessLogMapper accessLogMapper;
    private final DataIngestionAccessCounterMapper accessCounterMapper;

    DataIngestionAccessLogSupport(DataIngestionAccessLogMapper accessLogMapper,
                                  DataIngestionAccessCounterMapper accessCounterMapper) {
        this.accessLogMapper = accessLogMapper;
        this.accessCounterMapper = accessCounterMapper;
    }

    void recordAccessLog(DataIngestionServiceEntity service,
                         DataIngestionSubscriptionEntity subscription,
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
                         long receivedCount,
                         long successCount,
                         long failedCount,
                         OpenServiceInvocationLogService.ArchiveResult archiveResult) {
        try {
            DataIngestionAccessLogEntity entity = new DataIngestionAccessLogEntity();
            entity.setTenantId(service == null || isBlank(service.getTenantId()) ? StudioConstants.DEFAULT_TENANT_ID : service.getTenantId());
            entity.setProjectId(service == null ? null : service.getProjectId());
            entity.setServiceId(service == null ? null : service.getId());
            entity.setServiceCodeSnapshot(service == null ? null : service.getServiceCode());
            entity.setServiceNameSnapshot(service == null ? null : service.getServiceName());
            entity.setServiceStatusSnapshot(service == null ? null : service.getStatus());
            entity.setSubscriptionId(subscription == null ? null : subscription.getId());
            entity.setSubscriptionNameSnapshot(subscription == null ? defaultSubscriptionName : subscription.getSubscriptionName());
            entity.setRequestId(truncate(requestId, 128));
            entity.setRequestMethod(isBlank(requestMethod) ? null : requestMethod.toUpperCase(Locale.ROOT));
            entity.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
            entity.setDurationMs(Long.valueOf(Math.max(0L, (System.nanoTime() - startedAt) / 1000000L)));
            entity.setSuccess(success ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setHttpStatus(Integer.valueOf(httpStatus));
            entity.setErrorCode(truncate(errorCode, 128));
            entity.setErrorMessage(truncate(errorMessage, 1000));
            entity.setSystemLog(truncate(systemLog, OpenServiceInvocationLogSupport.MAX_LOG_CHARS + 4096));
            entity.setClientIp(truncate(clientIp, 128));
            entity.setUserAgent(truncate(userAgent, 500));
            entity.setReceivedCount(Long.valueOf(Math.max(0L, receivedCount)));
            entity.setSuccessCount(Long.valueOf(Math.max(0L, successCount)));
            entity.setFailedCount(Long.valueOf(Math.max(0L, failedCount)));
            applyArchiveResult(entity, archiveResult);
            accessLogMapper.insert(entity);
            recordAccessCounter(entity);
        } catch (RuntimeException ex) {
            log.warn("Failed to write data ingestion access log", ex);
        }
    }

    private void recordAccessCounter(DataIngestionAccessLogEntity logEntity) {
        if (logEntity == null || logEntity.getProjectId() == null || logEntity.getServiceId() == null) {
            return;
        }
        try {
            DataIngestionAccessCounterEntity counter = new DataIngestionAccessCounterEntity();
            counter.setId(IdWorker.getId());
            counter.setTenantId(logEntity.getTenantId());
            counter.setProjectId(logEntity.getProjectId());
            counter.setServiceId(logEntity.getServiceId());
            counter.setSubscriptionId(logEntity.getSubscriptionId() == null ? Long.valueOf(0L) : logEntity.getSubscriptionId());
            counter.setBucketStart(toHourBucket(logEntity.getOccurredAt()));
            counter.setSuccess(Integer.valueOf(1).equals(logEntity.getSuccess()) ? Integer.valueOf(1) : Integer.valueOf(0));
            counter.setAccessCount(Long.valueOf(1L));
            counter.setReceivedCount(Long.valueOf(safeLong(logEntity.getReceivedCount())));
            counter.setSuccessCount(Long.valueOf(safeLong(logEntity.getSuccessCount())));
            counter.setFailedCount(Long.valueOf(safeLong(logEntity.getFailedCount())));
            incrementOrInsertCounter(counter);
        } catch (RuntimeException ex) {
            log.warn("Failed to update data ingestion access counter", ex);
        }
    }

    private void incrementOrInsertCounter(DataIngestionAccessCounterEntity counter) {
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

    private long safeLong(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void applyArchiveResult(DataIngestionAccessLogEntity entity, OpenServiceInvocationLogService.ArchiveResult archiveResult) {
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
