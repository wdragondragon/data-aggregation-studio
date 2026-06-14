package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.DataServiceAccessLogView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DataServiceMetricViewSupport {

    DataServiceAccessLogView toAccessLogView(DataServiceAccessLogEntity entity) {
        DataServiceAccessLogView view = new DataServiceAccessLogView();
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
        view.setOccurredAt(entity.getOccurredAt());
        view.setDurationMs(entity.getDurationMs());
        view.setSuccess(Integer.valueOf(1).equals(entity.getSuccess()));
        view.setHttpStatus(entity.getHttpStatus());
        view.setErrorCode(entity.getErrorCode());
        view.setErrorMessage(entity.getErrorMessage());
        view.setSystemLog(entity.getSystemLog());
        view.setClientIp(entity.getClientIp());
        view.setUserAgent(entity.getUserAgent());
        view.setCacheEnabled(Integer.valueOf(1).equals(entity.getCacheEnabled()));
        view.setCacheHit(Integer.valueOf(1).equals(entity.getCacheHit()));
        view.setRowCount(entity.getRowCount());
        view.setLogStorageType(entity.getLogStorageType());
        view.setLogObjectBucket(entity.getLogObjectBucket());
        view.setLogObjectKey(entity.getLogObjectKey());
        view.setLogSizeBytes(entity.getLogSizeBytes());
        view.setLogCharset(entity.getLogCharset());
        view.setLogArchiveStatus(entity.getLogArchiveStatus());
        view.setLogArchiveError(entity.getLogArchiveError());
        return view;
    }

    <T> PageView<T> pageList(List<T> items, int pageNo, int pageSize) {
        int total = items == null ? 0 : items.size();
        int start = Math.max(0, (pageNo - 1) * pageSize);
        int end = Math.min(total, start + pageSize);
        List<T> pageItems = start >= total ? new ArrayList<T>() : new ArrayList<T>(items.subList(start, end));
        return PageView.of(pageNo, pageSize, total, pageItems);
    }

    <T> List<T> limit(List<T> items, int limit) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<T>();
        }
        return new ArrayList<T>(items.subList(0, Math.min(items.size(), Math.max(1, limit))));
    }

    Long percentile(List<Long> values, int percentile) {
        if (values == null || values.isEmpty()) {
            return Long.valueOf(0L);
        }
        List<Long> sorted = new ArrayList<Long>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0D * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }

    Double rate(long numerator, long denominator) {
        if (denominator <= 0L) {
            return Double.valueOf(0D);
        }
        return Double.valueOf(Math.round(numerator * 10000.0D / denominator) / 100.0D);
    }

    Map<Long, DataServiceDefinitionEntity> toServiceMap(List<DataServiceDefinitionEntity> services) {
        Map<Long, DataServiceDefinitionEntity> map = new LinkedHashMap<Long, DataServiceDefinitionEntity>();
        for (DataServiceDefinitionEntity service : services) {
            if (service.getId() != null) {
                map.put(service.getId(), service);
            }
        }
        return map;
    }

    String firstString(List<DataServiceAccessLogEntity> logs, boolean serviceName) {
        if (logs == null) {
            return null;
        }
        for (DataServiceAccessLogEntity log : logs) {
            String value = serviceName ? log.getServiceNameSnapshot() : log.getSubscriptionNameSnapshot();
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    String firstCode(List<DataServiceAccessLogEntity> logs) {
        if (logs == null) {
            return null;
        }
        for (DataServiceAccessLogEntity log : logs) {
            if (hasText(log.getServiceCodeSnapshot())) {
                return log.getServiceCodeSnapshot();
            }
        }
        return null;
    }

    String firstStatus(List<DataServiceAccessLogEntity> logs) {
        if (logs == null) {
            return null;
        }
        for (DataServiceAccessLogEntity log : logs) {
            if (hasText(log.getServiceStatusSnapshot())) {
                return log.getServiceStatusSnapshot();
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
