package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataSourceListView;
import com.jdragon.studio.dto.model.DatasourceClusterHealthView;
import com.jdragon.studio.dto.model.RuntimeClusterView;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.DatasourceConnectionTestRecordView;
import com.jdragon.studio.dto.model.dto.DatasourceConnectionTrendPointView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DatasourceConnectionHealthEntity;
import com.jdragon.studio.infra.entity.DatasourceConnectionTestRecordEntity;
import com.jdragon.studio.infra.mapper.DatasourceConnectionHealthMapper;
import com.jdragon.studio.infra.mapper.DatasourceConnectionTestRecordMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class DatasourceConnectionHealthService {
    private static final String PROBE_STATE_IDLE = "IDLE";
    private static final String PROBE_STATE_RUNNING = "RUNNING";
    private static final String PROBE_MODE_MANUAL = "MANUAL";
    private static final String PROBE_MODE_SCHEDULED = "SCHEDULED";
    private static final int MAX_CONNECTION_TEST_MESSAGE_LENGTH = 1000;

    private final DatasourceConnectionHealthMapper healthMapper;
    private final DatasourceConnectionTestRecordMapper recordMapper;
    private final StudioPlatformProperties properties;
    private final ClusterInstanceIdentity identity;
    private final ThreadPoolTaskExecutor manualProbeExecutor;
    private final ThreadPoolTaskExecutor scheduledProbeExecutor;
    private final Semaphore manualSemaphore;
    private final Semaphore scheduledSemaphore;
    private final Semaphore globalSemaphore;

    public DatasourceConnectionHealthService(DatasourceConnectionHealthMapper healthMapper,
                                             DatasourceConnectionTestRecordMapper recordMapper,
                                             StudioPlatformProperties properties,
                                             ClusterInstanceIdentity identity,
                                             @Qualifier("datasourceManualProbeExecutor") ThreadPoolTaskExecutor manualProbeExecutor,
                                             @Qualifier("datasourceScheduledProbeExecutor") ThreadPoolTaskExecutor scheduledProbeExecutor) {
        this.healthMapper = healthMapper;
        this.recordMapper = recordMapper;
        this.properties = properties;
        this.identity = identity;
        this.manualProbeExecutor = manualProbeExecutor;
        this.scheduledProbeExecutor = scheduledProbeExecutor;
        this.manualSemaphore = new Semaphore(resolveManualConcurrency());
        this.scheduledSemaphore = new Semaphore(resolveScheduledConcurrency());
        this.globalSemaphore = new Semaphore(resolveGlobalConcurrency());
    }

    public boolean enabled() {
        return datasourceHealthProperties().isEnabled();
    }

    public int recentLimit() {
        Integer configured = historyProperties().getRecentLimit();
        return Math.max(1, configured == null ? 10 : configured.intValue());
    }

    public int batchSize() {
        Integer configured = datasourceHealthProperties().getBatchSize();
        return Math.max(1, configured == null ? 100 : configured.intValue());
    }

    public int roundBudgetSeconds() {
        Integer configured = datasourceHealthProperties().getRoundBudgetSeconds();
        return Math.max(1, configured == null ? 120 : configured.intValue());
    }

    public int effectiveManualTimeout(DataSourceDefinition definition) {
        return effectiveTimeout(definition == null ? null : definition.getManualConnectionTestTimeoutSeconds(),
                definition == null ? null : definition.getTypeCode(),
                true);
    }

    public int effectiveScheduledTimeout(DataSourceDefinition definition) {
        return effectiveTimeout(definition == null ? null : definition.getScheduledConnectionTestTimeoutSeconds(),
                definition == null ? null : definition.getTypeCode(),
                false);
    }

    public int effectiveManualTimeout(Integer datasourceOverride, String typeCode) {
        return effectiveTimeout(datasourceOverride, typeCode, true);
    }

    public int effectiveScheduledTimeout(Integer datasourceOverride, String typeCode) {
        return effectiveTimeout(datasourceOverride, typeCode, false);
    }

    @Transactional
    public void ensureHealthRow(String tenantId, String fingerprint) {
        ensureHealthRow(tenantId, null, fingerprint);
    }
    @Transactional
    public void ensureHealthRow(String tenantId, Long runtimeClusterId, String fingerprint) {
        if (!hasText(tenantId) || !hasText(fingerprint) || selectHealth(tenantId, runtimeClusterId, fingerprint) != null) {
            return;
        }
        DatasourceConnectionHealthEntity entity = new DatasourceConnectionHealthEntity();
        entity.setTenantId(tenantId);
        entity.setRuntimeClusterId(runtimeClusterId);
        entity.setConnectionFingerprint(fingerprint);
        entity.setConnectionStatus(DataSourceConnectionStatus.UNKNOWN.name());
        entity.setProbeState(PROBE_STATE_IDLE);
        entity.setFailureCount(0);
        entity.setNextProbeAt(LocalDateTime.now());
        try {
            healthMapper.insert(entity);
        } catch (DuplicateKeyException ignored) {
            // Created concurrently by another request.
        }
    }

    public void hydrateDefinitions(List<DataSourceDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return;
        }
        Map<String, Set<String>> fingerprintsByTenant = new LinkedHashMap<String, Set<String>>();
        for (DataSourceDefinition definition : definitions) {
            if (definition == null || !hasText(definition.getTenantId()) || !hasText(definition.getConnectionFingerprint())) {
                continue;
            }
            Set<String> fingerprints = fingerprintsByTenant.get(definition.getTenantId());
            if (fingerprints == null) {
                fingerprints = new LinkedHashSet<String>();
                fingerprintsByTenant.put(definition.getTenantId(), fingerprints);
            }
            fingerprints.add(definition.getConnectionFingerprint());
        }
        if (fingerprintsByTenant.isEmpty()) {
            return;
        }
        Map<String, DatasourceConnectionHealthEntity> healthByKey = new LinkedHashMap<String, DatasourceConnectionHealthEntity>();
        Map<String, List<DatasourceConnectionTestRecordView>> recordsByKey = new LinkedHashMap<String, List<DatasourceConnectionTestRecordView>>();
        int recentLimit = recentLimit();
        for (Map.Entry<String, Set<String>> entry : fingerprintsByTenant.entrySet()) {
            List<DatasourceConnectionHealthEntity> healthItems = healthMapper.selectList(new LambdaQueryWrapper<DatasourceConnectionHealthEntity>()
                    .eq(DatasourceConnectionHealthEntity::getTenantId, entry.getKey())
                    .isNull(DatasourceConnectionHealthEntity::getRuntimeClusterId)
                    .in(DatasourceConnectionHealthEntity::getConnectionFingerprint, entry.getValue()));
            for (DatasourceConnectionHealthEntity health : healthItems) {
                healthByKey.put(healthKey(health.getTenantId(), health.getConnectionFingerprint()), health);
            }
            List<DatasourceConnectionTestRecordEntity> recordItems = recordMapper.selectRecentByFingerprints(entry.getKey(), entry.getValue(), recentLimit);
            for (DatasourceConnectionTestRecordEntity record : recordItems) {
                String key = healthKey(record.getTenantId(), record.getConnectionFingerprint());
                List<DatasourceConnectionTestRecordView> records = recordsByKey.get(key);
                if (records == null) {
                    records = new ArrayList<DatasourceConnectionTestRecordView>();
                    recordsByKey.put(key, records);
                }
                records.add(toView(record));
            }
        }
        for (DataSourceDefinition definition : definitions) {
            String key = healthKey(definition.getTenantId(), definition.getConnectionFingerprint());
            DatasourceConnectionHealthEntity health = healthByKey.get(key);
            if (health != null) {
                applyHealth(definition, health);
            }
            List<DatasourceConnectionTestRecordView> records = recordsByKey.get(key);
            definition.setRecentConnectionTests(records == null ? new ArrayList<DatasourceConnectionTestRecordView>() : records);
        }
    }

    public void hydrateListViews(List<DataSourceListView> views) {
        if (views == null || views.isEmpty()) {
            return;
        }
        Map<String, Set<String>> fingerprintsByTenant = new LinkedHashMap<String, Set<String>>();
        for (DataSourceListView view : views) {
            if (view == null || !hasText(view.getTenantId()) || !hasText(view.getConnectionFingerprint())) {
                continue;
            }
            Set<String> fingerprints = fingerprintsByTenant.get(view.getTenantId());
            if (fingerprints == null) {
                fingerprints = new LinkedHashSet<String>();
                fingerprintsByTenant.put(view.getTenantId(), fingerprints);
            }
            fingerprints.add(view.getConnectionFingerprint());
        }
        if (fingerprintsByTenant.isEmpty()) {
            return;
        }
        Map<String, DatasourceConnectionHealthEntity> healthByKey = new LinkedHashMap<String, DatasourceConnectionHealthEntity>();
        Map<String, List<DatasourceConnectionTrendPointView>> recordsByKey = new LinkedHashMap<String, List<DatasourceConnectionTrendPointView>>();
        int recentLimit = recentLimit();
        for (Map.Entry<String, Set<String>> entry : fingerprintsByTenant.entrySet()) {
            List<DatasourceConnectionHealthEntity> healthItems = healthMapper.selectList(new LambdaQueryWrapper<DatasourceConnectionHealthEntity>()
                    .select(DatasourceConnectionHealthEntity::getTenantId,
                            DatasourceConnectionHealthEntity::getConnectionFingerprint,
                            DatasourceConnectionHealthEntity::getConnectionStatus,
                            DatasourceConnectionHealthEntity::getLastConnectionTestAt,
                            DatasourceConnectionHealthEntity::getLastConnectionTestMessage,
                            DatasourceConnectionHealthEntity::getLastConnectionTestDurationMs,
                            DatasourceConnectionHealthEntity::getProbeState,
                            DatasourceConnectionHealthEntity::getProbeLeaseUntil,
                            DatasourceConnectionHealthEntity::getNextProbeAt)
                    .eq(DatasourceConnectionHealthEntity::getTenantId, entry.getKey())
                    .isNull(DatasourceConnectionHealthEntity::getRuntimeClusterId)
                    .in(DatasourceConnectionHealthEntity::getConnectionFingerprint, entry.getValue()));
            for (DatasourceConnectionHealthEntity health : healthItems) {
                healthByKey.put(healthKey(health.getTenantId(), health.getConnectionFingerprint()), health);
            }
            List<DatasourceConnectionTestRecordEntity> recordItems = recordMapper.selectRecentTrendByFingerprints(entry.getKey(), entry.getValue(), recentLimit);
            for (DatasourceConnectionTestRecordEntity record : recordItems) {
                String key = healthKey(record.getTenantId(), record.getConnectionFingerprint());
                List<DatasourceConnectionTrendPointView> records = recordsByKey.get(key);
                if (records == null) {
                    records = new ArrayList<DatasourceConnectionTrendPointView>();
                    recordsByKey.put(key, records);
                }
                records.add(toTrendPointView(record));
            }
        }
        for (DataSourceListView view : views) {
            String key = healthKey(view.getTenantId(), view.getConnectionFingerprint());
            DatasourceConnectionHealthEntity health = healthByKey.get(key);
            if (health != null) {
                applyHealth(view, health);
            }
            List<DatasourceConnectionTrendPointView> records = recordsByKey.get(key);
            view.setRecentConnectionTests(records == null ? new ArrayList<DatasourceConnectionTrendPointView>() : records);
        }
    }

    public void hydrateClusterHealth(List<DataSourceListView> views) {
        if (views == null || views.isEmpty()) {
            return;
        }
        Map<String, Set<String>> fingerprintsByTenant = new LinkedHashMap<String, Set<String>>();
        Set<Long> clusterIds = new LinkedHashSet<Long>();
        for (DataSourceListView view : views) {
            if (view == null || !hasText(view.getTenantId()) || !hasText(view.getConnectionFingerprint())) {
                continue;
            }
            Set<String> fingerprints = fingerprintsByTenant.get(view.getTenantId());
            if (fingerprints == null) {
                fingerprints = new LinkedHashSet<String>();
                fingerprintsByTenant.put(view.getTenantId(), fingerprints);
            }
            fingerprints.add(view.getConnectionFingerprint());
            for (RuntimeClusterView cluster : view.getApplicableClusters()) {
                if (cluster != null && cluster.getId() != null) {
                    clusterIds.add(cluster.getId());
                }
            }
        }
        Map<String, DatasourceConnectionHealthEntity> healthByKey = new LinkedHashMap<String, DatasourceConnectionHealthEntity>();
        if (!clusterIds.isEmpty()) {
            for (Map.Entry<String, Set<String>> entry : fingerprintsByTenant.entrySet()) {
                List<DatasourceConnectionHealthEntity> healthItems = healthMapper.selectList(
                        new LambdaQueryWrapper<DatasourceConnectionHealthEntity>()
                                .eq(DatasourceConnectionHealthEntity::getTenantId, entry.getKey())
                                .in(DatasourceConnectionHealthEntity::getRuntimeClusterId, clusterIds)
                                .in(DatasourceConnectionHealthEntity::getConnectionFingerprint, entry.getValue()));
                for (DatasourceConnectionHealthEntity health : healthItems) {
                    healthByKey.put(clusterHealthKey(health.getTenantId(), health.getRuntimeClusterId(),
                            health.getConnectionFingerprint()), health);
                }
            }
        }
        for (DataSourceListView view : views) {
            List<DatasourceClusterHealthView> items = new ArrayList<DatasourceClusterHealthView>();
            for (RuntimeClusterView cluster : view.getApplicableClusters()) {
                if (cluster == null || cluster.getId() == null) {
                    continue;
                }
                DatasourceClusterHealthView item = new DatasourceClusterHealthView();
                item.setRuntimeClusterId(cluster.getId());
                item.setRuntimeClusterCode(cluster.getCode());
                item.setRuntimeClusterName(cluster.getName());
                DatasourceConnectionHealthEntity health = healthByKey.get(clusterHealthKey(
                        view.getTenantId(), cluster.getId(), view.getConnectionFingerprint()));
                if (health != null) {
                    item.setConnectionStatus(parseStatus(health.getConnectionStatus()));
                    item.setLastConnectionTestAt(health.getLastConnectionTestAt());
                    item.setLastConnectionTestMessage(safeMessage(health.getLastConnectionTestMessage()));
                    item.setLastConnectionTestDurationMs(health.getLastConnectionTestDurationMs());
                    item.setConnectionTesting(isRunning(health, LocalDateTime.now()));
                    item.setConnectionStale(isStale(health));
                    item.setNextConnectionProbeAt(health.getNextProbeAt());
                }
                items.add(item);
            }
            view.setClusterHealth(items);
        }
    }

    public ConnectionTestResult runManualProbe(DataSourceDefinition definition,
                                               Callable<ConnectionTestResult> probe,
                                               int timeoutSeconds) {
        return runManualProbe(definition, null, probe, timeoutSeconds);
    }

    /** Health rows are isolated by runtime cluster; null is readable only for historical migration records. */
    public ConnectionTestResult runManualProbe(DataSourceDefinition definition, Long runtimeClusterId,
                                               Callable<ConnectionTestResult> probe, int timeoutSeconds) {
        String tenantId = definition.getTenantId();
        String fingerprint = definition.getConnectionFingerprint();
        ensureHealthRow(tenantId, runtimeClusterId, fingerprint);
        String runId = IdWorker.getIdStr();
        if (!claimProbe(tenantId, runtimeClusterId, fingerprint, runId, timeoutSeconds, PROBE_MODE_MANUAL)) {
            return waitForRunningProbe(tenantId, runtimeClusterId, fingerprint);
        }
        boolean capacityAcquired = false;
        try {
            if (!tryAcquireCapacity(PROBE_MODE_MANUAL)) {
                releaseProbeLease(tenantId, runtimeClusterId, fingerprint, runId);
                return busyResult(tenantId, runtimeClusterId, fingerprint, "Manual connection test is busy, please retry later");
            }
            capacityAcquired = true;
            ProbeExecution execution = executeWithTimeout(manualProbeExecutor, probe, timeoutSeconds);
            ConnectionTestResult result = finishProbe(definition, runtimeClusterId, runId, PROBE_MODE_MANUAL, timeoutSeconds, execution, execution.timedOut);
            if (execution.timedOut) {
                releaseProbeAndCapacityWhenTaskCompletes(execution.future, tenantId, runtimeClusterId, fingerprint, runId, PROBE_MODE_MANUAL, timeoutSeconds);
                capacityAcquired = false;
            }
            return result;
        } catch (TaskRejectedException e) {
            releaseProbeLease(tenantId, runtimeClusterId, fingerprint, runId);
            return busyResult(tenantId, runtimeClusterId, fingerprint, "Manual connection test is busy, please retry later");
        } finally {
            if (capacityAcquired) {
                releaseCapacity(PROBE_MODE_MANUAL);
            }
        }
    }

    public ConnectionTestResult runCurrentFormProbe(Callable<ConnectionTestResult> probe, int timeoutSeconds) {
        if (!tryAcquireCapacity(PROBE_MODE_MANUAL)) {
            ConnectionTestResult result = new ConnectionTestResult();
            result.setSuccess(false);
            result.setStatus(DataSourceConnectionStatus.UNKNOWN);
            result.setBusy(true);
            result.setMessage("Manual connection test is busy, please retry later");
            result.setTimeoutSeconds(timeoutSeconds);
            return result;
        }
        boolean capacityAcquired = true;
        try {
            ProbeExecution execution = executeWithTimeout(manualProbeExecutor, probe, timeoutSeconds);
            ConnectionTestResult result = execution.result;
            result.setLastTestAt(execution.endedAt);
            result.setTimeoutSeconds(timeoutSeconds);
            result.setBusy(false);
            result.setTesting(false);
            if (execution.timedOut) {
                releaseProbeAndCapacityWhenTaskCompletes(execution.future, null, null, null, PROBE_MODE_MANUAL, timeoutSeconds);
                capacityAcquired = false;
            }
            return result;
        } catch (TaskRejectedException e) {
            ConnectionTestResult result = new ConnectionTestResult();
            result.setSuccess(false);
            result.setStatus(DataSourceConnectionStatus.UNKNOWN);
            result.setBusy(true);
            result.setMessage("Manual connection test is busy, please retry later");
            result.setTimeoutSeconds(timeoutSeconds);
            return result;
        } finally {
            if (capacityAcquired) {
                releaseCapacity(PROBE_MODE_MANUAL);
            }
        }
    }

    public void submitScheduledProbe(final DataSourceDefinition definition,
                                     final Callable<ConnectionTestResult> probe,
                                     final int timeoutSeconds) {
        submitScheduledProbe(definition, null, probe, timeoutSeconds);
    }
    public void submitScheduledProbe(final DataSourceDefinition definition, final Long runtimeClusterId,
                                     final Callable<ConnectionTestResult> probe, final int timeoutSeconds) {
        final String tenantId = definition.getTenantId();
        final String fingerprint = definition.getConnectionFingerprint();
        ensureHealthRow(tenantId, runtimeClusterId, fingerprint);
        if (!tryAcquireCapacity(PROBE_MODE_SCHEDULED)) {
            return;
        }
        final String runId = IdWorker.getIdStr();
        if (!claimProbe(tenantId, runtimeClusterId, fingerprint, runId, timeoutSeconds, PROBE_MODE_SCHEDULED)) {
            releaseCapacity(PROBE_MODE_SCHEDULED);
            return;
        }
        try {
            final LocalDateTime startedAt = LocalDateTime.now();
            final long startedNanos = System.nanoTime();
            final Future<ConnectionTestResult> future = scheduledProbeExecutor.submit(probe);
            Thread watcher = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ProbeExecution execution = waitForFuture(future, startedAt, startedNanos, timeoutSeconds);
                        finishProbe(definition, runtimeClusterId, runId, PROBE_MODE_SCHEDULED, timeoutSeconds, execution, execution.timedOut);
                        if (execution.timedOut) {
                            waitForTaskCompletionAndExtendLease(future, tenantId, runtimeClusterId,
                                    fingerprint, runId, timeoutSeconds);
                            releaseProbeLease(tenantId, runtimeClusterId, fingerprint, runId);
                        }
                    } finally {
                        releaseCapacity(PROBE_MODE_SCHEDULED);
                    }
                }
            }, "datasource-scheduled-probe-watch-" + runId);
            watcher.setDaemon(true);
            watcher.start();
        } catch (TaskRejectedException e) {
            releaseProbeLease(tenantId, runtimeClusterId, fingerprint, runId);
            releaseCapacity(PROBE_MODE_SCHEDULED);
        }
    }

    public boolean scheduledDue(String tenantId, String fingerprint) {
        return scheduledDue(tenantId, null, fingerprint);
    }
    public boolean scheduledDue(String tenantId, Long runtimeClusterId, String fingerprint) {
        DatasourceConnectionHealthEntity health = selectHealth(tenantId, runtimeClusterId, fingerprint);
        if (health == null) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        if (isRunning(health, now)) {
            return false;
        }
        return health.getNextProbeAt() == null || !health.getNextProbeAt().isAfter(now);
    }

    public List<DatasourceConnectionTestRecordView> history(String tenantId,
                                                            String fingerprint,
                                                            Integer days,
                                                            Integer limit) {
        return history(tenantId, null, fingerprint, days, limit);
    }
    public List<DatasourceConnectionTestRecordView> history(String tenantId, Long runtimeClusterId,
                                                            String fingerprint, Integer days, Integer limit) {
        if (!hasText(tenantId) || !hasText(fingerprint)) {
            return new ArrayList<DatasourceConnectionTestRecordView>();
        }
        int safeDays = Math.max(1, Math.min(days == null ? historyProperties().getRetentionDays() : days.intValue(), 7));
        int safeLimit = safeHistoryLimit(limit);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(safeDays);
        List<DatasourceConnectionTestRecordEntity> records = recordMapper.selectList(new LambdaQueryWrapper<DatasourceConnectionTestRecordEntity>()
                .eq(DatasourceConnectionTestRecordEntity::getTenantId, tenantId)
                .eq(runtimeClusterId != null, DatasourceConnectionTestRecordEntity::getRuntimeClusterId, runtimeClusterId)
                .isNull(runtimeClusterId == null, DatasourceConnectionTestRecordEntity::getRuntimeClusterId)
                .eq(DatasourceConnectionTestRecordEntity::getConnectionFingerprint, fingerprint)
                .ge(DatasourceConnectionTestRecordEntity::getEndedAt, cutoff)
                .orderByDesc(DatasourceConnectionTestRecordEntity::getEndedAt)
                .last("limit " + safeLimit));
        List<DatasourceConnectionTestRecordView> result = new ArrayList<DatasourceConnectionTestRecordView>();
        for (DatasourceConnectionTestRecordEntity record : records) {
            result.add(toView(record));
        }
        return result;
    }

    public void cleanupExpiredHistory() {
        Integer configuredDays = historyProperties().getRetentionDays();
        int retentionDays = Math.max(1, configuredDays == null ? 7 : configuredDays.intValue());
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        recordMapper.deleteExpiredPhysically(cutoff);
    }

    private ProbeExecution executeWithTimeout(ThreadPoolTaskExecutor executor,
                                              Callable<ConnectionTestResult> probe,
                                              int timeoutSeconds) {
        LocalDateTime startedAt = LocalDateTime.now();
        long startedNanos = System.nanoTime();
        Future<ConnectionTestResult> future = executor.submit(probe);
        return waitForFuture(future, startedAt, startedNanos, timeoutSeconds);
    }

    private ProbeExecution waitForFuture(Future<ConnectionTestResult> future,
                                         LocalDateTime startedAt,
                                         long startedNanos,
                                         int timeoutSeconds) {
        try {
            ConnectionTestResult result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return new ProbeExecution(startedAt, LocalDateTime.now(), normalizeResult(result, startedNanos, false, timeoutSeconds), future, false);
        } catch (TimeoutException e) {
            return new ProbeExecution(startedAt, LocalDateTime.now(), timeoutResult(startedNanos, timeoutSeconds), future, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeExecution(startedAt, LocalDateTime.now(), failureResult(startedNanos, e), future, true);
        } catch (Exception e) {
            return new ProbeExecution(startedAt, LocalDateTime.now(), failureResult(startedNanos, e), future, false);
        }
    }

    @Transactional
    protected ConnectionTestResult finishProbe(DataSourceDefinition definition, String runId, String probeMode,
                                               int timeoutSeconds, ProbeExecution execution, boolean keepProbeRunning) {
        return finishProbe(definition, null, runId, probeMode, timeoutSeconds, execution, keepProbeRunning);
    }
    @Transactional
    protected ConnectionTestResult finishProbe(DataSourceDefinition definition, Long runtimeClusterId,
                                               String runId,
                                               String probeMode,
                                               int timeoutSeconds,
                                               ProbeExecution execution,
                                               boolean keepProbeRunning) {
        ConnectionTestResult result = execution.result;
        DataSourceConnectionStatus status = resolveStatus(result);
        DatasourceConnectionHealthEntity current = selectHealth(definition.getTenantId(), runtimeClusterId, definition.getConnectionFingerprint());
        int nextFailureCount = status == DataSourceConnectionStatus.AVAILABLE
                ? 0
                : (current == null || current.getFailureCount() == null ? 0 : current.getFailureCount().intValue()) + 1;
        LocalDateTime nextProbeAt = nextProbeAt(status, nextFailureCount, execution.endedAt);
        LambdaUpdateWrapper<DatasourceConnectionHealthEntity> updateWrapper = new LambdaUpdateWrapper<DatasourceConnectionHealthEntity>()
                .eq(DatasourceConnectionHealthEntity::getTenantId, definition.getTenantId())
                .eq(runtimeClusterId != null, DatasourceConnectionHealthEntity::getRuntimeClusterId, runtimeClusterId)
                .isNull(runtimeClusterId == null, DatasourceConnectionHealthEntity::getRuntimeClusterId)
                .eq(DatasourceConnectionHealthEntity::getConnectionFingerprint, definition.getConnectionFingerprint())
                .eq(DatasourceConnectionHealthEntity::getProbeRunId, runId)
                .set(DatasourceConnectionHealthEntity::getConnectionStatus, status.name())
                .set(DatasourceConnectionHealthEntity::getLastConnectionTestAt, execution.endedAt)
                .set(DatasourceConnectionHealthEntity::getLastConnectionTestMessage, safeMessage(result.getMessage()))
                .set(DatasourceConnectionHealthEntity::getLastConnectionTestDurationMs, result.getDurationMs())
                .set(DatasourceConnectionHealthEntity::getFailureCount, nextFailureCount)
                .set(DatasourceConnectionHealthEntity::getNextProbeAt, nextProbeAt);
        if (keepProbeRunning) {
            updateWrapper.set(DatasourceConnectionHealthEntity::getProbeLeaseUntil, nextLeaseUntil(timeoutSeconds));
        } else {
            updateWrapper.set(DatasourceConnectionHealthEntity::getProbeState, PROBE_STATE_IDLE)
                    .set(DatasourceConnectionHealthEntity::getProbeOwner, null)
                    .set(DatasourceConnectionHealthEntity::getProbeRunId, null)
                    .set(DatasourceConnectionHealthEntity::getProbeStartedAt, null)
                    .set(DatasourceConnectionHealthEntity::getProbeLeaseUntil, null);
        }
        int updated = healthMapper.update(null, updateWrapper);
        if (updated > 0) {
            insertHistory(definition, runtimeClusterId, runId, probeMode, timeoutSeconds, status, execution);
            result.setStatus(status);
            result.setLastTestAt(execution.endedAt);
            result.setNextProbeAt(nextProbeAt);
            result.setTimeoutSeconds(timeoutSeconds);
            result.setTesting(keepProbeRunning);
            result.setStale(false);
            result.setBusy(false);
            result.setRecentConnectionTests(history(definition.getTenantId(), runtimeClusterId, definition.getConnectionFingerprint(), 7, recentLimit()));
            return result;
        }
        return snapshotResult(definition.getTenantId(), runtimeClusterId, definition.getConnectionFingerprint(), true);
    }

    private void insertHistory(DataSourceDefinition definition, Long runtimeClusterId,
                               String runId,
                               String probeMode,
                               int timeoutSeconds,
                               DataSourceConnectionStatus status,
                               ProbeExecution execution) {
        DatasourceConnectionTestRecordEntity record = new DatasourceConnectionTestRecordEntity();
        record.setTenantId(definition.getTenantId());
        record.setRuntimeClusterId(runtimeClusterId);
        record.setConnectionFingerprint(definition.getConnectionFingerprint());
        record.setDatasourceId(definition.getId());
        record.setDatasourceName(definition.getName());
        record.setTypeCode(definition.getTypeCode());
        record.setProbeRunId(runId);
        record.setProbeMode(probeMode);
        record.setConnectionStatus(status.name());
        record.setStartedAt(execution.startedAt);
        record.setEndedAt(execution.endedAt);
        record.setDurationMs(execution.result.getDurationMs());
        record.setTimeoutSeconds(timeoutSeconds);
        record.setMessage(safeMessage(execution.result.getMessage()));
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException ignored) {
            // A matched probe run must produce at most one history row.
        }
    }

    private boolean claimProbe(String tenantId, String fingerprint, String runId, int timeoutSeconds, String probeMode) {
        return claimProbe(tenantId, null, fingerprint, runId, timeoutSeconds, probeMode);
    }
    private boolean claimProbe(String tenantId, Long runtimeClusterId, String fingerprint, String runId, int timeoutSeconds, String probeMode) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plusSeconds(Math.max(1, timeoutSeconds) + 30L);
        int updated = healthMapper.update(null, new LambdaUpdateWrapper<DatasourceConnectionHealthEntity>()
                .eq(DatasourceConnectionHealthEntity::getTenantId, tenantId)
                .eq(runtimeClusterId != null, DatasourceConnectionHealthEntity::getRuntimeClusterId, runtimeClusterId)
                .isNull(runtimeClusterId == null, DatasourceConnectionHealthEntity::getRuntimeClusterId)
                .eq(DatasourceConnectionHealthEntity::getConnectionFingerprint, fingerprint)
                .and(wrapper -> wrapper.ne(DatasourceConnectionHealthEntity::getProbeState, PROBE_STATE_RUNNING)
                        .or()
                        .lt(DatasourceConnectionHealthEntity::getProbeLeaseUntil, now)
                        .or()
                        .isNull(DatasourceConnectionHealthEntity::getProbeLeaseUntil))
                .set(DatasourceConnectionHealthEntity::getProbeState, PROBE_STATE_RUNNING)
                .set(DatasourceConnectionHealthEntity::getProbeOwner, identity.instanceId())
                .set(DatasourceConnectionHealthEntity::getProbeRunId, runId)
                .set(DatasourceConnectionHealthEntity::getProbeStartedAt, now)
                .set(DatasourceConnectionHealthEntity::getProbeLeaseUntil, leaseUntil));
        return updated > 0;
    }

    private void releaseProbeLease(String tenantId, String fingerprint, String runId) { releaseProbeLease(tenantId, null, fingerprint, runId); }
    private void releaseProbeLease(String tenantId, Long runtimeClusterId, String fingerprint, String runId) {
        healthMapper.update(null, new LambdaUpdateWrapper<DatasourceConnectionHealthEntity>()
                .eq(DatasourceConnectionHealthEntity::getTenantId, tenantId)
                .eq(runtimeClusterId != null, DatasourceConnectionHealthEntity::getRuntimeClusterId, runtimeClusterId)
                .isNull(runtimeClusterId == null, DatasourceConnectionHealthEntity::getRuntimeClusterId)
                .eq(DatasourceConnectionHealthEntity::getConnectionFingerprint, fingerprint)
                .eq(DatasourceConnectionHealthEntity::getProbeRunId, runId)
                .set(DatasourceConnectionHealthEntity::getProbeState, PROBE_STATE_IDLE)
                .set(DatasourceConnectionHealthEntity::getProbeOwner, null)
                .set(DatasourceConnectionHealthEntity::getProbeRunId, null)
                .set(DatasourceConnectionHealthEntity::getProbeStartedAt, null)
                .set(DatasourceConnectionHealthEntity::getProbeLeaseUntil, null));
    }

    private void releaseProbeAndCapacityWhenTaskCompletes(final Future<ConnectionTestResult> future,
                                                          final String tenantId,
                                                          final String fingerprint,
                                                          final String runId,
                                                          final String probeMode,
                                                          final int timeoutSeconds) {
        releaseProbeAndCapacityWhenTaskCompletes(future, tenantId, null, fingerprint, runId, probeMode, timeoutSeconds);
    }
    private void releaseProbeAndCapacityWhenTaskCompletes(final Future<ConnectionTestResult> future,
                                                          final String tenantId, final Long runtimeClusterId,
                                                          final String fingerprint,
                                                          final String runId,
                                                          final String probeMode,
                                                          final int timeoutSeconds) {
        Thread watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    waitForTaskCompletionAndExtendLease(future, tenantId, runtimeClusterId,
                            fingerprint, runId, timeoutSeconds);
                    if (hasText(tenantId) && hasText(fingerprint) && hasText(runId)) {
                        releaseProbeLease(tenantId, runtimeClusterId, fingerprint, runId);
                    }
                } finally {
                    releaseCapacity(probeMode);
                }
            }
        }, "datasource-probe-release-watch-" + (hasText(runId) ? runId : IdWorker.getIdStr()));
        watcher.setDaemon(true);
        watcher.start();
    }

    private void waitForTaskCompletionAndExtendLease(Future<ConnectionTestResult> future,
                                                     String tenantId,
                                                     String fingerprint,
                                                     String runId,
                                                     int timeoutSeconds) {
        waitForTaskCompletionAndExtendLease(future, tenantId, null, fingerprint, runId, timeoutSeconds);
    }

    private void waitForTaskCompletionAndExtendLease(Future<ConnectionTestResult> future,
                                                     String tenantId,
                                                     Long runtimeClusterId,
                                                     String fingerprint,
                                                     String runId,
                                                     int timeoutSeconds) {
        if (future == null) {
            return;
        }
        int waitSeconds = Math.max(1, Math.min(Math.max(1, timeoutSeconds), 30));
        while (true) {
            try {
                future.get(waitSeconds, TimeUnit.SECONDS);
                return;
            } catch (TimeoutException e) {
                extendProbeLease(tenantId, runtimeClusterId, fingerprint, runId, timeoutSeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                return;
            }
        }
    }

    private void extendProbeLease(String tenantId, String fingerprint, String runId, int timeoutSeconds) {
        extendProbeLease(tenantId, null, fingerprint, runId, timeoutSeconds);
    }

    private void extendProbeLease(String tenantId, Long runtimeClusterId, String fingerprint,
                                  String runId, int timeoutSeconds) {
        if (!hasText(tenantId) || !hasText(fingerprint) || !hasText(runId)) {
            return;
        }
        healthMapper.update(null, new LambdaUpdateWrapper<DatasourceConnectionHealthEntity>()
                .eq(DatasourceConnectionHealthEntity::getTenantId, tenantId)
                .eq(runtimeClusterId != null, DatasourceConnectionHealthEntity::getRuntimeClusterId, runtimeClusterId)
                .isNull(runtimeClusterId == null, DatasourceConnectionHealthEntity::getRuntimeClusterId)
                .eq(DatasourceConnectionHealthEntity::getConnectionFingerprint, fingerprint)
                .eq(DatasourceConnectionHealthEntity::getProbeRunId, runId)
                .set(DatasourceConnectionHealthEntity::getProbeLeaseUntil, nextLeaseUntil(timeoutSeconds)));
    }

    private ConnectionTestResult waitForRunningProbe(String tenantId, String fingerprint) { return waitForRunningProbe(tenantId, null, fingerprint); }
    private ConnectionTestResult waitForRunningProbe(String tenantId, Long runtimeClusterId, String fingerprint) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(manualWaitRunningSeconds());
        while (System.nanoTime() < deadline) {
            DatasourceConnectionHealthEntity health = selectHealth(tenantId, runtimeClusterId, fingerprint);
            if (!isRunning(health, LocalDateTime.now())) {
                return snapshotResult(tenantId, runtimeClusterId, fingerprint, false);
            }
            sleepQuietly(200L);
        }
        return snapshotResult(tenantId, runtimeClusterId, fingerprint, true);
    }

    private ConnectionTestResult snapshotResult(String tenantId, String fingerprint, boolean testing) { return snapshotResult(tenantId, null, fingerprint, testing); }
    private ConnectionTestResult snapshotResult(String tenantId, Long runtimeClusterId, String fingerprint, boolean testing) {
        DatasourceConnectionHealthEntity health = selectHealth(tenantId, runtimeClusterId, fingerprint);
        ConnectionTestResult result = new ConnectionTestResult();
        DataSourceConnectionStatus status = parseStatus(health == null ? null : health.getConnectionStatus());
        result.setStatus(status);
        result.setSuccess(status == DataSourceConnectionStatus.AVAILABLE);
        result.setMessage(health == null ? null : safeMessage(health.getLastConnectionTestMessage()));
        result.setDurationMs(health == null ? null : health.getLastConnectionTestDurationMs());
        result.setLastTestAt(health == null ? null : health.getLastConnectionTestAt());
        result.setNextProbeAt(health == null ? null : health.getNextProbeAt());
        result.setTesting(testing);
        result.setBusy(false);
        result.setStale(isStale(health));
        result.setRecentConnectionTests(history(tenantId, runtimeClusterId, fingerprint, 7, recentLimit()));
        return result;
    }

    private ConnectionTestResult busyResult(String tenantId, String fingerprint, String message) { return busyResult(tenantId, null, fingerprint, message); }
    private ConnectionTestResult busyResult(String tenantId, Long runtimeClusterId, String fingerprint, String message) {
        ConnectionTestResult result = snapshotResult(tenantId, runtimeClusterId, fingerprint, false);
        result.setBusy(true);
        result.setMessage(message);
        return result;
    }

    private boolean tryAcquireCapacity(String probeMode) {
        if (PROBE_MODE_MANUAL.equals(probeMode)) {
            if (!manualSemaphore.tryAcquire()) {
                return false;
            }
            if (!globalSemaphore.tryAcquire()) {
                manualSemaphore.release();
                return false;
            }
            return true;
        }
        if (!scheduledSemaphore.tryAcquire()) {
            return false;
        }
        int reserved = Math.max(0, datasourceHealthProperties().getManualReservedConcurrency() == null
                ? 1
                : datasourceHealthProperties().getManualReservedConcurrency().intValue());
        if (globalSemaphore.availablePermits() <= reserved || !globalSemaphore.tryAcquire()) {
            scheduledSemaphore.release();
            return false;
        }
        return true;
    }

    private void releaseCapacity(String probeMode) {
        globalSemaphore.release();
        if (PROBE_MODE_MANUAL.equals(probeMode)) {
            manualSemaphore.release();
        } else {
            scheduledSemaphore.release();
        }
    }

    private ConnectionTestResult normalizeResult(ConnectionTestResult result,
                                                 long startedNanos,
                                                 boolean timeout,
                                                 int timeoutSeconds) {
        if (result == null) {
            result = new ConnectionTestResult();
            result.setSuccess(false);
            result.setMessage("Connection test returned no result");
        }
        DataSourceConnectionStatus status = result.isSuccess()
                ? DataSourceConnectionStatus.AVAILABLE
                : DataSourceConnectionStatus.UNAVAILABLE;
        result.setStatus(status);
        if (result.getDurationMs() == null) {
            result.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        }
        result.setMessage(safeMessage(timeout ? "Connection test timed out after " + timeoutSeconds + " seconds" : result.getMessage()));
        result.setTimeoutSeconds(timeoutSeconds);
        return result;
    }

    private ConnectionTestResult timeoutResult(long startedNanos, int timeoutSeconds) {
        ConnectionTestResult result = new ConnectionTestResult();
        result.setSuccess(false);
        result.setStatus(DataSourceConnectionStatus.UNAVAILABLE);
        result.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        result.setMessage("Connection test timed out after " + timeoutSeconds + " seconds");
        result.setTimeoutSeconds(timeoutSeconds);
        return result;
    }

    private ConnectionTestResult failureResult(long startedNanos, Exception e) {
        ConnectionTestResult result = new ConnectionTestResult();
        result.setSuccess(false);
        result.setStatus(DataSourceConnectionStatus.UNAVAILABLE);
        result.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        result.setMessage(safeExceptionMessage(e));
        return result;
    }

    private LocalDateTime nextProbeAt(DataSourceConnectionStatus status, int failureCount, LocalDateTime baseTime) {
        int seconds = jitterSeconds();
        int jitter = seconds <= 0 ? 0 : ThreadLocalRandom.current().nextInt(seconds + 1);
        if (status == DataSourceConnectionStatus.AVAILABLE) {
            return baseTime.plusMinutes(scheduledIntervalMinutes()).plusSeconds(jitter);
        }
        int backoff = failureBackoffMinutes(failureCount);
        return baseTime.plusMinutes(backoff).plusSeconds(jitter);
    }

    private int failureBackoffMinutes(int failureCount) {
        int base = failureBackoffBaseMinutes();
        int max = failureBackoffMaxMinutes();
        int safeFailureCount = Math.max(1, failureCount);
        long value = base;
        for (int index = 1; index < safeFailureCount; index++) {
            value = value * 2L;
            if (value >= max) {
                return max;
            }
        }
        return (int) Math.min(value, max);
    }

    private LocalDateTime nextLeaseUntil(int timeoutSeconds) {
        return LocalDateTime.now().plusSeconds(Math.max(1, timeoutSeconds) + 30L);
    }

    private DataSourceConnectionStatus resolveStatus(ConnectionTestResult result) {
        if (result != null && result.getStatus() != null) {
            return result.getStatus();
        }
        return result != null && result.isSuccess()
                ? DataSourceConnectionStatus.AVAILABLE
                : DataSourceConnectionStatus.UNAVAILABLE;
    }

    private DatasourceConnectionHealthEntity selectHealth(String tenantId, String fingerprint) {
        return selectHealth(tenantId, null, fingerprint);
    }
    private DatasourceConnectionHealthEntity selectHealth(String tenantId, Long runtimeClusterId, String fingerprint) {
        if (!hasText(tenantId) || !hasText(fingerprint)) {
            return null;
        }
        return healthMapper.selectOne(new LambdaQueryWrapper<DatasourceConnectionHealthEntity>()
                .eq(DatasourceConnectionHealthEntity::getTenantId, tenantId)
                .eq(runtimeClusterId != null, DatasourceConnectionHealthEntity::getRuntimeClusterId, runtimeClusterId)
                .isNull(runtimeClusterId == null, DatasourceConnectionHealthEntity::getRuntimeClusterId)
                .eq(DatasourceConnectionHealthEntity::getConnectionFingerprint, fingerprint)
                .last("limit 1"));
    }

    private void applyHealth(DataSourceDefinition definition, DatasourceConnectionHealthEntity health) {
        definition.setConnectionStatus(parseStatus(health.getConnectionStatus()));
        definition.setLastConnectionTestAt(health.getLastConnectionTestAt());
        definition.setLastConnectionTestMessage(safeMessage(health.getLastConnectionTestMessage()));
        definition.setLastConnectionTestDurationMs(health.getLastConnectionTestDurationMs());
        definition.setConnectionTesting(isRunning(health, LocalDateTime.now()));
        definition.setConnectionStale(isStale(health));
        definition.setNextConnectionProbeAt(health.getNextProbeAt());
    }

    private void applyHealth(DataSourceListView view, DatasourceConnectionHealthEntity health) {
        view.setConnectionStatus(parseStatus(health.getConnectionStatus()));
        view.setLastConnectionTestAt(health.getLastConnectionTestAt());
        view.setLastConnectionTestMessage(safeMessage(health.getLastConnectionTestMessage()));
        view.setLastConnectionTestDurationMs(health.getLastConnectionTestDurationMs());
        view.setConnectionTesting(isRunning(health, LocalDateTime.now()));
        view.setConnectionStale(isStale(health));
        view.setNextConnectionProbeAt(health.getNextProbeAt());
    }

    private boolean isRunning(DatasourceConnectionHealthEntity health, LocalDateTime now) {
        return health != null
                && PROBE_STATE_RUNNING.equalsIgnoreCase(health.getProbeState())
                && health.getProbeLeaseUntil() != null
                && health.getProbeLeaseUntil().isAfter(now);
    }

    private boolean isStale(DatasourceConnectionHealthEntity health) {
        if (health == null || health.getLastConnectionTestAt() == null) {
            return false;
        }
        return health.getLastConnectionTestAt().isBefore(LocalDateTime.now().minusMinutes(staleAfterMinutes()));
    }

    private DatasourceConnectionTestRecordView toView(DatasourceConnectionTestRecordEntity record) {
        DatasourceConnectionTestRecordView view = new DatasourceConnectionTestRecordView();
        view.setStatus(parseStatus(record.getConnectionStatus()));
        view.setRuntimeClusterId(record.getRuntimeClusterId());
        view.setStartedAt(record.getStartedAt());
        view.setEndedAt(record.getEndedAt());
        view.setTestedAt(record.getEndedAt());
        view.setDurationMs(record.getDurationMs());
        view.setProbeMode(record.getProbeMode());
        view.setTimeoutSeconds(record.getTimeoutSeconds());
        view.setMessage(safeMessage(record.getMessage()));
        view.setDatasourceId(record.getDatasourceId());
        view.setDatasourceName(record.getDatasourceName());
        return view;
    }

    private DatasourceConnectionTrendPointView toTrendPointView(DatasourceConnectionTestRecordEntity record) {
        DatasourceConnectionTrendPointView view = new DatasourceConnectionTrendPointView();
        view.setStatus(parseStatus(record.getConnectionStatus()));
        view.setTestedAt(record.getEndedAt());
        view.setEndedAt(record.getEndedAt());
        return view;
    }

    private int effectiveTimeout(Integer datasourceOverride, String typeCode, boolean manual) {
        Integer configured = datasourceOverride;
        if (configured == null && hasText(typeCode)) {
            StudioPlatformProperties.TypeDefaultProperties typeDefault = datasourceHealthProperties().getTypeDefaults().get(typeCode);
            if (typeDefault != null) {
                configured = manual ? typeDefault.getManualTimeoutSeconds() : typeDefault.getScheduledTimeoutSeconds();
            }
        }
        if (configured == null) {
            StudioPlatformProperties.ProbeProperties probe = manual
                    ? datasourceHealthProperties().getManual()
                    : datasourceHealthProperties().getScheduled();
            configured = probe == null ? null : probe.getDefaultTimeoutSeconds();
        }
        int defaultValue = manual ? 30 : 30;
        int value = configured == null ? defaultValue : configured.intValue();
        return Math.max(1, Math.min(value, maxTimeoutSeconds()));
    }

    private int safeHistoryLimit(Integer limit) {
        int defaultLimit = historyProperties().getHistoryQueryDefaultLimit() == null
                ? 1000
                : historyProperties().getHistoryQueryDefaultLimit().intValue();
        int maxLimit = historyProperties().getHistoryQueryMaxLimit() == null
                ? 2000
                : historyProperties().getHistoryQueryMaxLimit().intValue();
        int requested = limit == null ? defaultLimit : limit.intValue();
        return Math.max(1, Math.min(requested, Math.max(1, maxLimit)));
    }

    private int resolveManualConcurrency() {
        StudioPlatformProperties.ProbeProperties probe = datasourceHealthProperties().getManual();
        Integer configured = probe == null ? null : probe.getMaxConcurrency();
        return Math.max(1, configured == null ? 2 : configured.intValue());
    }

    private int resolveScheduledConcurrency() {
        StudioPlatformProperties.ProbeProperties probe = datasourceHealthProperties().getScheduled();
        Integer configured = probe == null ? null : probe.getMaxConcurrency();
        return Math.max(1, configured == null ? 3 : configured.intValue());
    }

    private int resolveGlobalConcurrency() {
        Integer configured = datasourceHealthProperties().getGlobalMaxConcurrency();
        return Math.max(1, configured == null ? 5 : configured.intValue());
    }

    private int maxTimeoutSeconds() {
        Integer configured = datasourceHealthProperties().getMaxTimeoutSeconds();
        return Math.max(1, configured == null ? 120 : configured.intValue());
    }

    private int manualWaitRunningSeconds() {
        Integer configured = datasourceHealthProperties().getManualWaitRunningSeconds();
        return Math.max(0, configured == null ? 3 : configured.intValue());
    }

    private int scheduledIntervalMinutes() {
        Integer configured = datasourceHealthProperties().getScheduledIntervalMinutes();
        return Math.max(1, configured == null ? 15 : configured.intValue());
    }

    private int staleAfterMinutes() {
        Integer configured = datasourceHealthProperties().getStaleAfterMinutes();
        return Math.max(1, configured == null ? 30 : configured.intValue());
    }

    private int failureBackoffBaseMinutes() {
        Integer configured = datasourceHealthProperties().getFailureBackoffBaseMinutes();
        return Math.max(1, configured == null ? 30 : configured.intValue());
    }

    private int failureBackoffMaxMinutes() {
        Integer configured = datasourceHealthProperties().getFailureBackoffMaxMinutes();
        return Math.max(failureBackoffBaseMinutes(), configured == null ? 120 : configured.intValue());
    }

    private int jitterSeconds() {
        Integer configured = datasourceHealthProperties().getJitterSeconds();
        return Math.max(0, configured == null ? 60 : configured.intValue());
    }

    private StudioPlatformProperties.DatasourceHealthProperties datasourceHealthProperties() {
        if (properties.getDatasourceHealth() == null) {
            properties.setDatasourceHealth(new StudioPlatformProperties.DatasourceHealthProperties());
        }
        return properties.getDatasourceHealth();
    }

    private StudioPlatformProperties.HistoryProperties historyProperties() {
        if (datasourceHealthProperties().getHistory() == null) {
            datasourceHealthProperties().setHistory(new StudioPlatformProperties.HistoryProperties());
        }
        return datasourceHealthProperties().getHistory();
    }

    private DataSourceConnectionStatus parseStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return DataSourceConnectionStatus.UNKNOWN;
        }
        try {
            return DataSourceConnectionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return DataSourceConnectionStatus.UNKNOWN;
        }
    }

    private String healthKey(String tenantId, String fingerprint) {
        return (tenantId == null ? "" : tenantId) + "\n" + (fingerprint == null ? "" : fingerprint);
    }

    private String clusterHealthKey(String tenantId, Long runtimeClusterId, String fingerprint) {
        return (tenantId == null ? "" : tenantId) + "\n" + runtimeClusterId + "\n"
                + (fingerprint == null ? "" : fingerprint);
    }

    private String truncate(String message) {
        if (message == null || message.length() <= MAX_CONNECTION_TEST_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_CONNECTION_TEST_MESSAGE_LENGTH);
    }

    private String safeMessage(String message) {
        if (!hasText(message)) {
            return message;
        }
        return truncate(stripExceptionClassPrefix(message.trim()));
    }

    private String safeExceptionMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = null;
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 16) {
            if (hasText(current.getMessage())) {
                message = current.getMessage();
            }
            current = current.getCause();
            depth++;
        }
        if (hasText(message)) {
            return safeMessage(message);
        }
        return throwable.getClass().getSimpleName();
    }

    private String stripExceptionClassPrefix(String message) {
        String result = message;
        while (result != null) {
            int separator = result.indexOf(": ");
            if (separator <= 0) {
                return result;
            }
            String prefix = result.substring(0, separator);
            if (!looksLikeExceptionClass(prefix)) {
                return result;
            }
            result = result.substring(separator + 2).trim();
        }
        return message;
    }

    private boolean looksLikeExceptionClass(String prefix) {
        if (!hasText(prefix)) {
            return false;
        }
        String simpleName = prefix.trim();
        int dotIndex = simpleName.lastIndexOf('.');
        if (dotIndex >= 0) {
            simpleName = simpleName.substring(dotIndex + 1);
        }
        return simpleName.endsWith("Exception") || simpleName.endsWith("Error");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ProbeExecution {
        private final LocalDateTime startedAt;
        private final LocalDateTime endedAt;
        private final ConnectionTestResult result;
        private final Future<ConnectionTestResult> future;
        private final boolean timedOut;

        private ProbeExecution(LocalDateTime startedAt,
                               LocalDateTime endedAt,
                               ConnectionTestResult result,
                               Future<ConnectionTestResult> future,
                               boolean timedOut) {
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.result = result;
            this.future = future;
            this.timedOut = timedOut;
        }
    }
}
