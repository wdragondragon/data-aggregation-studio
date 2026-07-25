package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Serializes cluster instance-summary heartbeats across all control-plane and worker processes. */
@Service
public class RuntimeClusterHeartbeatService {

    private static final long LOCK_LEASE_SECONDS = 10L;
    private static final long LOCK_WAIT_MILLIS = 3000L;
    private static final long LOCK_RETRY_MILLIS = 10L;
    private static final DateTimeFormatter HEARTBEAT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RuntimeClusterMapper clusterMapper;
    private final ClusterLockService clusterLockService;

    public RuntimeClusterHeartbeatService(RuntimeClusterMapper clusterMapper,
                                          ClusterLockService clusterLockService) {
        this.clusterMapper = clusterMapper;
        this.clusterLockService = clusterLockService;
    }

    public RuntimeClusterEntity recordByCode(String tenantId,
                                             String clusterCode,
                                             String instanceId,
                                             Map<String, Object> instanceAttributes,
                                             String runtimeVersion,
                                             String summary,
                                             LocalDateTime heartbeatAt) {
        RuntimeClusterEntity cluster = clusterMapper.selectOne(new LambdaQueryWrapper<RuntimeClusterEntity>()
                .eq(RuntimeClusterEntity::getTenantId, requiredText(tenantId, "Runtime tenant id is required"))
                .eq(RuntimeClusterEntity::getCode, requiredText(clusterCode, "Runtime cluster code is required"))
                .last("limit 1"));
        if (cluster == null) {
            throw unavailable();
        }
        return recordById(cluster.getTenantId(), cluster.getId(), instanceId, instanceAttributes,
                runtimeVersion, summary, heartbeatAt);
    }

    public RuntimeClusterEntity recordById(String tenantId,
                                           Long clusterId,
                                           String instanceId,
                                           Map<String, Object> instanceAttributes,
                                           String runtimeVersion,
                                           String summary,
                                           LocalDateTime heartbeatAt) {
        String normalizedTenantId = requiredText(tenantId, "Runtime tenant id is required");
        if (clusterId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Runtime cluster id is required");
        }
        String normalizedInstanceId = requiredText(instanceId, "Runtime instance id is required");
        LocalDateTime effectiveHeartbeatAt = heartbeatAt == null ? LocalDateTime.now() : heartbeatAt;
        String lockName = "runtime-cluster-heartbeat:" + normalizedTenantId + ":" + clusterId;
        acquire(lockName);
        try {
            RuntimeClusterEntity cluster = clusterMapper.selectOne(new LambdaQueryWrapper<RuntimeClusterEntity>()
                    .eq(RuntimeClusterEntity::getTenantId, normalizedTenantId)
                    .eq(RuntimeClusterEntity::getId, clusterId)
                    .last("limit 1"));
            if (cluster == null || !Integer.valueOf(1).equals(cluster.getEnabled())) {
                throw unavailable();
            }
            Map<String, Object> instances = cluster.getInstancesJson() == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(cluster.getInstancesJson());
            Map<String, Object> instance = instanceAttributes == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(instanceAttributes);
            instance.put("instanceId", normalizedInstanceId);
            instance.put("version", optionalText(runtimeVersion));
            instance.put("summary", optionalText(summary));
            instance.put("heartbeatAt", HEARTBEAT_TIME_FORMATTER.format(effectiveHeartbeatAt));
            instances.put(normalizedInstanceId, instance);
            cluster.setInstancesJson(instances);
            cluster.setLastHeartbeatAt(effectiveHeartbeatAt);
            cluster.setStatus("ONLINE");
            if (StringUtils.hasText(runtimeVersion)) {
                cluster.setVersion(runtimeVersion.trim());
            }
            RuntimeClusterEntity update = new RuntimeClusterEntity();
            update.setInstancesJson(instances);
            update.setLastHeartbeatAt(effectiveHeartbeatAt);
            update.setStatus("ONLINE");
            if (StringUtils.hasText(runtimeVersion)) {
                update.setVersion(runtimeVersion.trim());
            }
            int updated = clusterMapper.update(update, new LambdaUpdateWrapper<RuntimeClusterEntity>()
                    .eq(RuntimeClusterEntity::getTenantId, normalizedTenantId)
                    .eq(RuntimeClusterEntity::getId, clusterId)
                    .eq(RuntimeClusterEntity::getEnabled, 1));
            if (updated != 1) {
                throw unavailable();
            }
            return cluster;
        } finally {
            clusterLockService.release(lockName);
        }
    }

    private void acquire(String lockName) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOCK_WAIT_MILLIS);
        do {
            if (clusterLockService.tryAcquireNonReentrant(lockName, LOCK_LEASE_SECONDS)) {
                return;
            }
            try {
                Thread.sleep(LOCK_RETRY_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                        "Runtime cluster heartbeat lock wait was interrupted", ex);
            }
        } while (System.nanoTime() < deadline);
        throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                "Runtime cluster heartbeat is busy; retry later");
    }

    private StudioException unavailable() {
        return new StudioException(StudioErrorCode.NOT_FOUND, "Runtime cluster is not available");
    }

    private String requiredText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String optionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
