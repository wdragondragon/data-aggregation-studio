package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.OpsCenterHealthStatus;
import com.jdragon.studio.dto.model.OpsCenterLogEventView;
import com.jdragon.studio.dto.model.OpsCenterMetricCardView;
import com.jdragon.studio.dto.model.OpsCenterOptionsView;
import com.jdragon.studio.dto.model.OpsCenterOverviewView;
import com.jdragon.studio.dto.model.OpsCenterQueueItemView;
import com.jdragon.studio.dto.model.OpsCenterRunIncidentView;
import com.jdragon.studio.dto.model.OpsCenterServiceEventView;
import com.jdragon.studio.dto.model.OpsCenterWorkerGroupView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.OpsCenterQueryRequest;
import com.jdragon.studio.infra.entity.DataIngestionAccessLogEntity;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.QualityTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class OpsCenterService {

    private static final DateTimeFormatter REQUEST_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long SLOW_CALL_THRESHOLD_MS = 1000L;
    private static final long SLOW_RUN_THRESHOLD_MS = Duration.ofMinutes(5L).toMillis();
    private static final long CRITICAL_QUEUE_WAIT_MS = Duration.ofMinutes(10L).toMillis();
    private static final long WORKER_RECENT_HOURS = 24L;

    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final WorkerLeaseMapper workerLeaseMapper;
    private final ProjectWorkerBindingMapper projectWorkerBindingMapper;
    private final DataServiceAccessLogMapper dataServiceAccessLogMapper;
    private final DataIngestionAccessLogMapper dataIngestionAccessLogMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final CollectionTaskDefinitionMapper collectionTaskDefinitionMapper;
    private final QualityTaskDefinitionMapper qualityTaskDefinitionMapper;
    private final StudioSecurityService securityService;

    public OpsCenterService(DispatchTaskMapper dispatchTaskMapper,
                            RunRecordMapper runRecordMapper,
                            WorkerLeaseMapper workerLeaseMapper,
                            ProjectWorkerBindingMapper projectWorkerBindingMapper,
                            DataServiceAccessLogMapper dataServiceAccessLogMapper,
                            DataIngestionAccessLogMapper dataIngestionAccessLogMapper,
                            WorkflowDefinitionMapper workflowDefinitionMapper,
                            CollectionTaskDefinitionMapper collectionTaskDefinitionMapper,
                            QualityTaskDefinitionMapper qualityTaskDefinitionMapper,
                            StudioSecurityService securityService) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.workerLeaseMapper = workerLeaseMapper;
        this.projectWorkerBindingMapper = projectWorkerBindingMapper;
        this.dataServiceAccessLogMapper = dataServiceAccessLogMapper;
        this.dataIngestionAccessLogMapper = dataIngestionAccessLogMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.collectionTaskDefinitionMapper = collectionTaskDefinitionMapper;
        this.qualityTaskDefinitionMapper = qualityTaskDefinitionMapper;
        this.securityService = securityService;
    }

    public OpsCenterOptionsView options() {
        Scope scope = scope(null);
        OpsCenterOptionsView view = new OpsCenterOptionsView();
        view.setExecutionTypes(Arrays.asList("WORKFLOW_NODE", "COLLECTION_TASK", "QUALITY_TASK"));
        view.setStatuses(Arrays.asList("QUEUED", "RUNNING", "SUCCESS", "FAILED", "STOPPED"));
        view.setWorkerGroups(workerGroupCodes(scope));
        return view;
    }

    public OpsCenterOverviewView overview(OpsCenterQueryRequest request) {
        Scope scope = scope(request);
        List<RunRecordEntity> runRecords = runRecords(scope, request, false);
        List<DispatchTaskEntity> queueTasks = queueTasks(scope, request);
        List<DataServiceAccessLogEntity> serviceLogs = serviceLogs(scope, request);
        List<DataIngestionAccessLogEntity> ingestionLogs = ingestionLogs(scope, request);
        List<OpsCenterWorkerGroupView> workerGroups = workerGroups(scope);
        long failedRuns = count(runRecords, record -> isFailedStatus(record.getStatus()));
        long runningRuns = count(runRecords, record -> isRunningStatus(record.getStatus()));
        long slowRuns = count(runRecords, record -> isSlowRun(record, scope.now));
        long queuedTasks = count(queueTasks, task -> "QUEUED".equalsIgnoreCase(task.getStatus()));
        long runningQueueTasks = count(queueTasks, task -> "RUNNING".equalsIgnoreCase(task.getStatus()));
        long serviceFailures = count(serviceLogs, log -> !isSuccess(log.getSuccess()));
        long serviceSlowCalls = count(serviceLogs, log -> safeLong(log.getDurationMs()) >= SLOW_CALL_THRESHOLD_MS);
        long ingestionFailures = count(ingestionLogs, log -> !isSuccess(log.getSuccess()) || safeLong(log.getFailedCount()) > 0L);
        long ingestionSlowCalls = count(ingestionLogs, log -> safeLong(log.getDurationMs()) >= SLOW_CALL_THRESHOLD_MS);
        long logFailures = count(runRecords, this::isLogAbnormal);
        int onlineWorkerInstances = 0;
        int boundWorkerGroups = 0;
        for (OpsCenterWorkerGroupView workerGroup : workerGroups) {
            if (Boolean.TRUE.equals(workerGroup.getBoundToProject())) {
                boundWorkerGroups++;
                if (Boolean.TRUE.equals(workerGroup.getEnabled())) {
                    onlineWorkerInstances += safeInt(workerGroup.getOnlineInstanceCount());
                }
            }
        }
        Long longestQueueWaitMs = longestQueueWait(queueTasks, scope.now);

        OpsCenterOverviewView view = new OpsCenterOverviewView();
        view.setStartTime(scope.startTime);
        view.setEndTime(scope.endTime);
        view.setRunTotal(Long.valueOf(runRecords.size()));
        view.setFailedRuns(Long.valueOf(failedRuns));
        view.setRunningRuns(Long.valueOf(runningRuns));
        view.setSlowRuns(Long.valueOf(slowRuns));
        view.setQueuedTasks(Long.valueOf(queuedTasks));
        view.setRunningQueueTasks(Long.valueOf(runningQueueTasks));
        view.setServiceFailures(Long.valueOf(serviceFailures));
        view.setServiceSlowCalls(Long.valueOf(serviceSlowCalls));
        view.setIngestionFailures(Long.valueOf(ingestionFailures));
        view.setIngestionSlowCalls(Long.valueOf(ingestionSlowCalls));
        view.setLogFailures(Long.valueOf(logFailures));
        view.setOnlineWorkerInstances(Integer.valueOf(onlineWorkerInstances));
        view.setBoundWorkerGroups(Integer.valueOf(boundWorkerGroups));
        evaluateHealth(view, longestQueueWaitMs);
        view.setMetrics(metrics(view));
        return view;
    }

    public PageView<OpsCenterQueueItemView> queryQueue(OpsCenterQueryRequest request) {
        Scope scope = scope(request);
        int pageNo = normalizePageNo(request);
        int pageSize = normalizePageSize(request);
        Page<DispatchTaskEntity> page = dispatchTaskMapper.selectPage(new Page<DispatchTaskEntity>(pageNo, pageSize),
                queueTaskQuery(scope, request)
                        .orderByAsc(DispatchTaskEntity::getCreatedAt)
                        .orderByAsc(DispatchTaskEntity::getId));
        TargetNameResolver targetNameResolver = new TargetNameResolver(scope);
        List<OpsCenterQueueItemView> views = new ArrayList<OpsCenterQueueItemView>();
        for (DispatchTaskEntity entity : page.getRecords()) {
            views.add(toQueueItem(entity, scope.now, targetNameResolver));
        }
        return PageView.of(pageNo, pageSize, page.getTotal(), views);
    }

    public PageView<OpsCenterRunIncidentView> queryRuns(OpsCenterQueryRequest request) {
        Scope scope = scope(request);
        int pageNo = normalizePageNo(request);
        int pageSize = normalizePageSize(request);
        Page<RunRecordEntity> page = runRecordMapper.selectPage(new Page<RunRecordEntity>(pageNo, pageSize),
                runRecordQuery(scope, request, true)
                        .orderByDesc(RunRecordEntity::getCreatedAt)
                        .orderByDesc(RunRecordEntity::getId));
        List<OpsCenterRunIncidentView> views = new ArrayList<OpsCenterRunIncidentView>();
        for (RunRecordEntity entity : page.getRecords()) {
            views.add(toRunIncident(entity, scope.now));
        }
        return PageView.of(pageNo, pageSize, page.getTotal(), views);
    }

    public PageView<OpsCenterWorkerGroupView> queryWorkers(OpsCenterQueryRequest request) {
        Scope scope = scope(request);
        List<OpsCenterWorkerGroupView> groups = workerGroups(scope);
        if (StringUtils.hasText(request == null ? null : request.getWorkerGroupCode())) {
            String expected = request.getWorkerGroupCode().trim();
            groups.removeIf(group -> !expected.equals(group.getWorkerGroupCode()));
        }
        groups.sort(Comparator
                .comparing((OpsCenterWorkerGroupView group) -> !Boolean.TRUE.equals(group.getBoundToProject()))
                .thenComparing(OpsCenterWorkerGroupView::getWorkerGroupCode, Comparator.nullsLast(String::compareToIgnoreCase)));
        return page(request, groups);
    }

    public PageView<OpsCenterServiceEventView> queryServiceEvents(OpsCenterQueryRequest request) {
        Scope scope = scope(request);
        int pageNo = normalizePageNo(request);
        int pageSize = normalizePageSize(request);
        if (isRunScopedFilter(request)) {
            return PageView.of(pageNo, pageSize, 0L, new ArrayList<OpsCenterServiceEventView>());
        }
        Page<DataServiceAccessLogEntity> page = dataServiceAccessLogMapper.selectPage(
                new Page<DataServiceAccessLogEntity>(pageNo, pageSize),
                serviceLogQuery(scope, request)
                        .and(wrapper -> wrapper.ne(DataServiceAccessLogEntity::getSuccess, 1)
                                .or()
                                .isNull(DataServiceAccessLogEntity::getSuccess)
                                .or()
                                .ge(DataServiceAccessLogEntity::getDurationMs, SLOW_CALL_THRESHOLD_MS))
                        .orderByDesc(DataServiceAccessLogEntity::getOccurredAt)
                        .orderByDesc(DataServiceAccessLogEntity::getId));
        List<OpsCenterServiceEventView> views = new ArrayList<OpsCenterServiceEventView>();
        for (DataServiceAccessLogEntity log : page.getRecords()) {
            views.add(toServiceEvent(log));
        }
        return PageView.of(pageNo, pageSize, page.getTotal(), views);
    }

    public PageView<OpsCenterServiceEventView> queryIngestionEvents(OpsCenterQueryRequest request) {
        Scope scope = scope(request);
        int pageNo = normalizePageNo(request);
        int pageSize = normalizePageSize(request);
        if (isRunScopedFilter(request)) {
            return PageView.of(pageNo, pageSize, 0L, new ArrayList<OpsCenterServiceEventView>());
        }
        Page<DataIngestionAccessLogEntity> page = dataIngestionAccessLogMapper.selectPage(
                new Page<DataIngestionAccessLogEntity>(pageNo, pageSize),
                ingestionLogQuery(scope, request)
                        .and(wrapper -> wrapper.ne(DataIngestionAccessLogEntity::getSuccess, 1)
                                .or()
                                .isNull(DataIngestionAccessLogEntity::getSuccess)
                                .or()
                                .ge(DataIngestionAccessLogEntity::getDurationMs, SLOW_CALL_THRESHOLD_MS)
                                .or()
                                .gt(DataIngestionAccessLogEntity::getFailedCount, 0L))
                        .orderByDesc(DataIngestionAccessLogEntity::getOccurredAt)
                        .orderByDesc(DataIngestionAccessLogEntity::getId));
        List<OpsCenterServiceEventView> views = new ArrayList<OpsCenterServiceEventView>();
        for (DataIngestionAccessLogEntity log : page.getRecords()) {
            views.add(toIngestionEvent(log));
        }
        return PageView.of(pageNo, pageSize, page.getTotal(), views);
    }

    public PageView<OpsCenterLogEventView> queryLogEvents(OpsCenterQueryRequest request) {
        Scope scope = scope(request);
        int pageNo = normalizePageNo(request);
        int pageSize = normalizePageSize(request);
        Page<RunRecordEntity> page = runRecordMapper.selectPage(new Page<RunRecordEntity>(pageNo, pageSize),
                runRecordQuery(scope, request, false)
                        .and(wrapper -> logAbnormalSqlCondition(wrapper))
                        .orderByDesc(RunRecordEntity::getCreatedAt)
                        .orderByDesc(RunRecordEntity::getId));
        List<OpsCenterLogEventView> views = new ArrayList<OpsCenterLogEventView>();
        for (RunRecordEntity entity : page.getRecords()) {
            views.add(toLogEvent(entity));
        }
        return PageView.of(pageNo, pageSize, page.getTotal(), views);
    }

    private void evaluateHealth(OpsCenterOverviewView view, Long longestQueueWaitMs) {
        List<String> reasons = view.getHealthReasons();
        OpsCenterHealthStatus status = OpsCenterHealthStatus.HEALTHY;
        if (safeInt(view.getBoundWorkerGroups()) <= 0) {
            status = OpsCenterHealthStatus.CRITICAL;
            reasons.add("当前项目未下发任何 Worker 组，任务不会被 Worker 领取");
        } else if (safeInt(view.getOnlineWorkerInstances()) <= 0) {
            status = OpsCenterHealthStatus.CRITICAL;
            reasons.add("当前项目已下发 Worker 组，但没有在线 Worker 实例");
        }
        if (longestQueueWaitMs != null && longestQueueWaitMs.longValue() >= CRITICAL_QUEUE_WAIT_MS) {
            status = OpsCenterHealthStatus.CRITICAL;
            reasons.add("存在排队超过 10 分钟的任务");
        }
        if (safeLong(view.getLogFailures()) > 0L) {
            status = OpsCenterHealthStatus.CRITICAL;
            reasons.add("存在运行日志读取或上传异常");
        }
        if (OpsCenterHealthStatus.HEALTHY.equals(status)
                && (safeLong(view.getFailedRuns()) > 0L
                || safeLong(view.getServiceFailures()) > 0L
                || safeLong(view.getIngestionFailures()) > 0L
                || safeLong(view.getServiceSlowCalls()) > 0L
                || safeLong(view.getIngestionSlowCalls()) > 0L
                || safeLong(view.getSlowRuns()) > 0L
                || safeLong(view.getQueuedTasks()) > 0L)) {
            status = OpsCenterHealthStatus.WARNING;
            reasons.add("时间窗口内存在失败、慢调用或排队任务");
        }
        view.setHealthStatus(status);
        if (OpsCenterHealthStatus.HEALTHY.equals(status)) {
            view.setHealthMessage("当前项目运行状态健康");
        } else if (OpsCenterHealthStatus.WARNING.equals(status)) {
            view.setHealthMessage("当前项目存在需要关注的运行事件");
        } else {
            view.setHealthMessage("当前项目存在阻断性运维风险");
        }
    }

    private List<OpsCenterMetricCardView> metrics(OpsCenterOverviewView view) {
        List<OpsCenterMetricCardView> metrics = new ArrayList<OpsCenterMetricCardView>();
        metrics.add(metric("queuedTasks", "排队任务", view.getQueuedTasks(), safeLong(view.getQueuedTasks()) > 0L ? OpsCenterHealthStatus.WARNING : OpsCenterHealthStatus.HEALTHY, "等待 Worker 领取"));
        metrics.add(metric("failedRuns", "失败运行", view.getFailedRuns(), safeLong(view.getFailedRuns()) > 0L ? OpsCenterHealthStatus.WARNING : OpsCenterHealthStatus.HEALTHY, "时间窗口内失败运行"));
        metrics.add(metric("slowRuns", "慢任务", view.getSlowRuns(), safeLong(view.getSlowRuns()) > 0L ? OpsCenterHealthStatus.WARNING : OpsCenterHealthStatus.HEALTHY, "运行超过 5 分钟"));
        metrics.add(metric("onlineWorkers", "在线 Worker", Long.valueOf(safeInt(view.getOnlineWorkerInstances())), safeInt(view.getOnlineWorkerInstances()) <= 0 ? OpsCenterHealthStatus.CRITICAL : OpsCenterHealthStatus.HEALTHY, "已绑定且在线实例"));
        metrics.add(metric("serviceFailures", "服务失败", view.getServiceFailures(), safeLong(view.getServiceFailures()) > 0L ? OpsCenterHealthStatus.WARNING : OpsCenterHealthStatus.HEALTHY, "数据服务调用失败"));
        metrics.add(metric("ingestionFailures", "接入失败", view.getIngestionFailures(), safeLong(view.getIngestionFailures()) > 0L ? OpsCenterHealthStatus.WARNING : OpsCenterHealthStatus.HEALTHY, "接入服务调用或写入失败"));
        metrics.add(metric("logFailures", "日志异常", view.getLogFailures(), safeLong(view.getLogFailures()) > 0L ? OpsCenterHealthStatus.CRITICAL : OpsCenterHealthStatus.HEALTHY, "运行日志不可用"));
        return metrics;
    }

    private OpsCenterMetricCardView metric(String key, String label, Long value, OpsCenterHealthStatus status, String hint) {
        OpsCenterMetricCardView view = new OpsCenterMetricCardView();
        view.setKey(key);
        view.setLabel(label);
        view.setValue(value == null ? Long.valueOf(0L) : value);
        view.setStatus(status);
        view.setHint(hint);
        return view;
    }

    private List<DispatchTaskEntity> queueTasks(Scope scope, OpsCenterQueryRequest request) {
        return dispatchTaskMapper.selectList(queueTaskQuery(scope, request)
                .orderByAsc(DispatchTaskEntity::getCreatedAt));
    }

    private LambdaQueryWrapper<DispatchTaskEntity> queueTaskQuery(Scope scope, OpsCenterQueryRequest request) {
        String executionType = request == null ? null : request.getExecutionType();
        String status = request == null ? null : request.getStatus();
        String workerGroupCode = request == null ? null : request.getWorkerGroupCode();
        return new LambdaQueryWrapper<DispatchTaskEntity>()
                .select(DispatchTaskEntity::getId,
                        DispatchTaskEntity::getTenantId,
                        DispatchTaskEntity::getProjectId,
                        DispatchTaskEntity::getDeleted,
                        DispatchTaskEntity::getCreatedAt,
                        DispatchTaskEntity::getUpdatedAt,
                        DispatchTaskEntity::getExecutionType,
                        DispatchTaskEntity::getWorkflowRunId,
                        DispatchTaskEntity::getWorkflowDefinitionId,
                        DispatchTaskEntity::getWorkflowVersionId,
                        DispatchTaskEntity::getCollectionTaskId,
                        DispatchTaskEntity::getQualityTaskId,
                        DispatchTaskEntity::getNodeCode,
                        DispatchTaskEntity::getStatus,
                        DispatchTaskEntity::getWorkerGroupCode,
                        DispatchTaskEntity::getLeaseOwner,
                        DispatchTaskEntity::getWorkerInstanceId,
                        DispatchTaskEntity::getScheduledFireTime,
                        DispatchTaskEntity::getAttempts,
                        DispatchTaskEntity::getMaxRetries)
                .eq(DispatchTaskEntity::getTenantId, scope.tenantId)
                .eq(DispatchTaskEntity::getProjectId, scope.projectId)
                .ge(DispatchTaskEntity::getCreatedAt, scope.startTime)
                .le(DispatchTaskEntity::getCreatedAt, scope.endTime)
                .in(DispatchTaskEntity::getStatus, Arrays.asList("QUEUED", "RUNNING"))
                .eq(hasText(executionType), DispatchTaskEntity::getExecutionType, upper(executionType))
                .eq(hasText(status), DispatchTaskEntity::getStatus, upper(status))
                .eq(hasText(workerGroupCode), DispatchTaskEntity::getWorkerGroupCode, trim(workerGroupCode));
    }

    private List<RunRecordEntity> runRecords(Scope scope, OpsCenterQueryRequest request, boolean incidentsOnly) {
        return runRecordMapper.selectList(runRecordQuery(scope, request, incidentsOnly)
                .orderByDesc(RunRecordEntity::getCreatedAt));
    }

    private LambdaQueryWrapper<RunRecordEntity> runRecordQuery(Scope scope, OpsCenterQueryRequest request, boolean incidentsOnly) {
        String executionType = request == null ? null : request.getExecutionType();
        String status = request == null ? null : request.getStatus();
        String workerGroupCode = request == null ? null : request.getWorkerGroupCode();
        LambdaQueryWrapper<RunRecordEntity> query = new LambdaQueryWrapper<RunRecordEntity>()
                .select(RunRecordEntity::getId,
                        RunRecordEntity::getTenantId,
                        RunRecordEntity::getProjectId,
                        RunRecordEntity::getDeleted,
                        RunRecordEntity::getCreatedAt,
                        RunRecordEntity::getUpdatedAt,
                        RunRecordEntity::getExecutionType,
                        RunRecordEntity::getWorkflowRunId,
                        RunRecordEntity::getWorkflowDefinitionId,
                        RunRecordEntity::getWorkflowVersionId,
                        RunRecordEntity::getCollectionTaskId,
                        RunRecordEntity::getQualityTaskId,
                        RunRecordEntity::getNodeCode,
                        RunRecordEntity::getStatus,
                        RunRecordEntity::getWorkerGroupCode,
                        RunRecordEntity::getWorkerCode,
                        RunRecordEntity::getWorkerInstanceId,
                        RunRecordEntity::getWorkerPodName,
                        RunRecordEntity::getWorkerNodeName,
                        RunRecordEntity::getMessage,
                        RunRecordEntity::getStartedAt,
                        RunRecordEntity::getEndedAt,
                        RunRecordEntity::getLogStorageType,
                        RunRecordEntity::getLogStatus,
                        RunRecordEntity::getLogErrorSummary)
                .eq(RunRecordEntity::getTenantId, scope.tenantId)
                .eq(RunRecordEntity::getProjectId, scope.projectId)
                .ge(RunRecordEntity::getCreatedAt, scope.startTime)
                .le(RunRecordEntity::getCreatedAt, scope.endTime)
                .eq(hasText(executionType), RunRecordEntity::getExecutionType, upper(executionType))
                .eq(hasText(status), RunRecordEntity::getStatus, upper(status))
                .eq(hasText(workerGroupCode), RunRecordEntity::getWorkerGroupCode, trim(workerGroupCode));
        if (incidentsOnly && !hasText(status)) {
            query.and(wrapper -> wrapper.in(RunRecordEntity::getStatus, Arrays.asList("FAILED", "ERROR", "RUNNING", "QUEUED"))
                    .or()
                    .and(inner -> logAbnormalSqlCondition(inner))
                    .or()
                    .and(inner -> slowRunSqlCondition(inner, scope.now)));
        }
        return query;
    }

    private List<DataServiceAccessLogEntity> serviceLogs(Scope scope, OpsCenterQueryRequest request) {
        if (isRunScopedFilter(request)) {
            return Collections.emptyList();
        }
        return dataServiceAccessLogMapper.selectList(serviceLogQuery(scope, request)
                .orderByDesc(DataServiceAccessLogEntity::getOccurredAt));
    }

    private LambdaQueryWrapper<DataServiceAccessLogEntity> serviceLogQuery(Scope scope, OpsCenterQueryRequest request) {
        LambdaQueryWrapper<DataServiceAccessLogEntity> query = new LambdaQueryWrapper<DataServiceAccessLogEntity>()
                .select(DataServiceAccessLogEntity::getId,
                        DataServiceAccessLogEntity::getTenantId,
                        DataServiceAccessLogEntity::getProjectId,
                        DataServiceAccessLogEntity::getServiceId,
                        DataServiceAccessLogEntity::getServiceCodeSnapshot,
                        DataServiceAccessLogEntity::getServiceNameSnapshot,
                        DataServiceAccessLogEntity::getServiceStatusSnapshot,
                        DataServiceAccessLogEntity::getSubscriptionId,
                        DataServiceAccessLogEntity::getSubscriptionNameSnapshot,
                        DataServiceAccessLogEntity::getRequestMethod,
                        DataServiceAccessLogEntity::getOccurredAt,
                        DataServiceAccessLogEntity::getDurationMs,
                        DataServiceAccessLogEntity::getSuccess,
                        DataServiceAccessLogEntity::getHttpStatus,
                        DataServiceAccessLogEntity::getErrorCode,
                        DataServiceAccessLogEntity::getErrorMessage,
                        DataServiceAccessLogEntity::getRowCount)
                .eq(DataServiceAccessLogEntity::getTenantId, scope.tenantId)
                .eq(DataServiceAccessLogEntity::getProjectId, scope.projectId)
                .ge(DataServiceAccessLogEntity::getOccurredAt, scope.startTime)
                .le(DataServiceAccessLogEntity::getOccurredAt, scope.endTime);
        applyServiceAccessLogStatusFilter(query, request == null ? null : request.getStatus());
        return query;
    }

    private List<DataIngestionAccessLogEntity> ingestionLogs(Scope scope, OpsCenterQueryRequest request) {
        if (isRunScopedFilter(request)) {
            return Collections.emptyList();
        }
        return dataIngestionAccessLogMapper.selectList(ingestionLogQuery(scope, request)
                .orderByDesc(DataIngestionAccessLogEntity::getOccurredAt));
    }

    private LambdaQueryWrapper<DataIngestionAccessLogEntity> ingestionLogQuery(Scope scope, OpsCenterQueryRequest request) {
        LambdaQueryWrapper<DataIngestionAccessLogEntity> query = new LambdaQueryWrapper<DataIngestionAccessLogEntity>()
                .select(DataIngestionAccessLogEntity::getId,
                        DataIngestionAccessLogEntity::getTenantId,
                        DataIngestionAccessLogEntity::getProjectId,
                        DataIngestionAccessLogEntity::getServiceId,
                        DataIngestionAccessLogEntity::getServiceCodeSnapshot,
                        DataIngestionAccessLogEntity::getServiceNameSnapshot,
                        DataIngestionAccessLogEntity::getServiceStatusSnapshot,
                        DataIngestionAccessLogEntity::getSubscriptionId,
                        DataIngestionAccessLogEntity::getSubscriptionNameSnapshot,
                        DataIngestionAccessLogEntity::getRequestMethod,
                        DataIngestionAccessLogEntity::getOccurredAt,
                        DataIngestionAccessLogEntity::getDurationMs,
                        DataIngestionAccessLogEntity::getSuccess,
                        DataIngestionAccessLogEntity::getHttpStatus,
                        DataIngestionAccessLogEntity::getErrorCode,
                        DataIngestionAccessLogEntity::getErrorMessage,
                        DataIngestionAccessLogEntity::getReceivedCount,
                        DataIngestionAccessLogEntity::getSuccessCount,
                        DataIngestionAccessLogEntity::getFailedCount)
                .eq(DataIngestionAccessLogEntity::getTenantId, scope.tenantId)
                .eq(DataIngestionAccessLogEntity::getProjectId, scope.projectId)
                .ge(DataIngestionAccessLogEntity::getOccurredAt, scope.startTime)
                .le(DataIngestionAccessLogEntity::getOccurredAt, scope.endTime);
        applyIngestionAccessLogStatusFilter(query, request == null ? null : request.getStatus());
        return query;
    }

    private void applyServiceAccessLogStatusFilter(LambdaQueryWrapper<DataServiceAccessLogEntity> query, String status) {
        String normalized = upper(status);
        if (!hasText(normalized)) {
            return;
        }
        if ("SUCCESS".equals(normalized)) {
            query.eq(DataServiceAccessLogEntity::getSuccess, 1);
            return;
        }
        if ("FAILED".equals(normalized) || "ERROR".equals(normalized)) {
            query.and(wrapper -> wrapper.ne(DataServiceAccessLogEntity::getSuccess, 1)
                    .or()
                    .isNull(DataServiceAccessLogEntity::getSuccess));
            return;
        }
        query.eq(DataServiceAccessLogEntity::getId, Long.valueOf(-1L));
    }

    private void applyIngestionAccessLogStatusFilter(LambdaQueryWrapper<DataIngestionAccessLogEntity> query, String status) {
        String normalized = upper(status);
        if (!hasText(normalized)) {
            return;
        }
        if ("SUCCESS".equals(normalized)) {
            query.eq(DataIngestionAccessLogEntity::getSuccess, 1);
            return;
        }
        if ("FAILED".equals(normalized) || "ERROR".equals(normalized)) {
            query.and(wrapper -> wrapper.ne(DataIngestionAccessLogEntity::getSuccess, 1)
                    .or()
                    .isNull(DataIngestionAccessLogEntity::getSuccess));
            return;
        }
        query.eq(DataIngestionAccessLogEntity::getId, Long.valueOf(-1L));
    }

    private LambdaQueryWrapper<RunRecordEntity> logAbnormalSqlCondition(LambdaQueryWrapper<RunRecordEntity> wrapper) {
        return wrapper.and(inner -> inner.isNotNull(RunRecordEntity::getLogErrorSummary)
                        .ne(RunRecordEntity::getLogErrorSummary, ""))
                .or()
                .and(inner -> inner.isNotNull(RunRecordEntity::getLogStatus)
                        .ne(RunRecordEntity::getLogStatus, "")
                        .notIn(RunRecordEntity::getLogStatus, Arrays.asList("AVAILABLE", "WRITING")));
    }

    private LambdaQueryWrapper<RunRecordEntity> slowRunSqlCondition(LambdaQueryWrapper<RunRecordEntity> wrapper, LocalDateTime now) {
        LocalDateTime thresholdStartTime = now.minus(Duration.ofMillis(SLOW_RUN_THRESHOLD_MS));
        return wrapper.isNotNull(RunRecordEntity::getStartedAt)
                .and(inner -> inner
                        .and(running -> running.isNull(RunRecordEntity::getEndedAt)
                                .le(RunRecordEntity::getStartedAt, thresholdStartTime))
                        .or()
                        .and(completed -> completed.isNotNull(RunRecordEntity::getEndedAt)
                                .apply("timestampdiff(microsecond, started_at, ended_at) >= {0}",
                                        Long.valueOf(SLOW_RUN_THRESHOLD_MS * 1000L))));
    }

    private List<OpsCenterWorkerGroupView> workerGroups(Scope scope) {
        LocalDateTime recentThreshold = scope.now.minusHours(WORKER_RECENT_HOURS);
        List<ProjectWorkerBindingEntity> bindings = projectWorkerBindingMapper.selectList(new LambdaQueryWrapper<ProjectWorkerBindingEntity>()
                .select(ProjectWorkerBindingEntity::getTenantId,
                        ProjectWorkerBindingEntity::getProjectId,
                        ProjectWorkerBindingEntity::getWorkerGroupCode,
                        ProjectWorkerBindingEntity::getWorkerCode,
                        ProjectWorkerBindingEntity::getEnabled)
                .eq(ProjectWorkerBindingEntity::getTenantId, scope.tenantId)
                .eq(ProjectWorkerBindingEntity::getProjectId, scope.projectId)
                .orderByAsc(ProjectWorkerBindingEntity::getWorkerGroupCode));
        List<WorkerLeaseEntity> leases = workerLeaseMapper.selectList(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .select(WorkerLeaseEntity::getTenantId,
                        WorkerLeaseEntity::getWorkerGroupCode,
                        WorkerLeaseEntity::getWorkerCode,
                        WorkerLeaseEntity::getInstanceId,
                        WorkerLeaseEntity::getPodName,
                        WorkerLeaseEntity::getNodeName,
                        WorkerLeaseEntity::getStatus,
                        WorkerLeaseEntity::getLastHeartbeatAt,
                        WorkerLeaseEntity::getLeaseExpiresAt)
                .eq(WorkerLeaseEntity::getTenantId, scope.tenantId)
                .orderByDesc(WorkerLeaseEntity::getLastHeartbeatAt));
        Map<String, ProjectWorkerBindingEntity> bindingMap = new LinkedHashMap<String, ProjectWorkerBindingEntity>();
        for (ProjectWorkerBindingEntity binding : bindings) {
            String groupCode = resolveWorkerGroupCode(binding);
            if (hasText(groupCode)) {
                bindingMap.put(groupCode, binding);
            }
        }
        Map<String, List<WorkerLeaseEntity>> leaseMap = new LinkedHashMap<String, List<WorkerLeaseEntity>>();
        for (WorkerLeaseEntity lease : leases) {
            String groupCode = resolveWorkerGroupCode(lease);
            if (!hasText(groupCode)) {
                continue;
            }
            if (!isOnlineLease(lease, scope.now) && !isRecentLease(lease, recentThreshold)) {
                continue;
            }
            leaseMap.computeIfAbsent(groupCode, key -> new ArrayList<WorkerLeaseEntity>()).add(lease);
        }
        Set<String> groupCodes = new LinkedHashSet<String>();
        groupCodes.addAll(bindingMap.keySet());
        groupCodes.addAll(leaseMap.keySet());
        List<OpsCenterWorkerGroupView> result = new ArrayList<OpsCenterWorkerGroupView>();
        for (String groupCode : groupCodes) {
            result.add(toWorkerGroup(groupCode, bindingMap.get(groupCode), leaseMap.get(groupCode), scope.now));
        }
        return result;
    }

    private List<String> workerGroupCodes(Scope scope) {
        List<OpsCenterWorkerGroupView> groups = workerGroups(scope);
        List<String> codes = new ArrayList<String>();
        for (OpsCenterWorkerGroupView group : groups) {
            if (hasText(group.getWorkerGroupCode())) {
                codes.add(group.getWorkerGroupCode());
            }
        }
        Collections.sort(codes, String::compareToIgnoreCase);
        return codes;
    }

    private OpsCenterWorkerGroupView toWorkerGroup(String groupCode,
                                                   ProjectWorkerBindingEntity binding,
                                                   List<WorkerLeaseEntity> leases,
                                                   LocalDateTime now) {
        List<WorkerLeaseEntity> safeLeases = leases == null ? Collections.<WorkerLeaseEntity>emptyList() : leases;
        WorkerLeaseEntity latest = latestLease(safeLeases);
        int onlineCount = 0;
        for (WorkerLeaseEntity lease : safeLeases) {
            if (isOnlineLease(lease, now)) {
                onlineCount++;
            }
        }
        OpsCenterWorkerGroupView view = new OpsCenterWorkerGroupView();
        view.setWorkerGroupCode(groupCode);
        view.setBoundToProject(Boolean.valueOf(binding != null));
        view.setEnabled(Boolean.valueOf(binding == null || binding.getEnabled() == null || binding.getEnabled().intValue() != 0));
        view.setOnlineInstanceCount(Integer.valueOf(onlineCount));
        view.setRecentInstanceCount(Integer.valueOf(safeLeases.size()));
        view.setDisplayStatus(onlineCount > 0 ? "ONLINE" : safeLeases.isEmpty() ? "NO_INSTANCE" : "OFFLINE");
        if (latest != null) {
            view.setLatestHeartbeatAt(latest.getLastHeartbeatAt());
            view.setLatestWorkerCode(latest.getWorkerCode());
            view.setLatestWorkerInstanceId(latest.getInstanceId());
            view.setLatestPodName(latest.getPodName());
            view.setLatestNodeName(latest.getNodeName());
            view.setLeaseExpiresAt(latest.getLeaseExpiresAt());
        }
        return view;
    }

    private OpsCenterQueueItemView toQueueItem(DispatchTaskEntity entity, LocalDateTime now, TargetNameResolver targetNameResolver) {
        OpsCenterQueueItemView view = new OpsCenterQueueItemView();
        copyBase(view, entity.getId(), entity.getTenantId(), entity.getProjectId(), entity.getDeleted(), entity.getCreatedAt(), entity.getUpdatedAt());
        view.setExecutionType(entity.getExecutionType());
        view.setWorkflowRunId(entity.getWorkflowRunId());
        view.setWorkflowDefinitionId(entity.getWorkflowDefinitionId());
        view.setWorkflowVersionId(entity.getWorkflowVersionId());
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setQualityTaskId(entity.getQualityTaskId());
        view.setTargetName(targetNameResolver.resolve(entity));
        view.setNodeCode(entity.getNodeCode());
        view.setStatus(entity.getStatus());
        view.setWorkerGroupCode(entity.getWorkerGroupCode());
        view.setLeaseOwner(entity.getLeaseOwner());
        view.setWorkerInstanceId(entity.getWorkerInstanceId());
        view.setScheduledFireTime(entity.getScheduledFireTime());
        view.setQueuedDurationMs(durationMs(entity.getCreatedAt(), now));
        view.setScheduleDelayMs(durationMs(entity.getScheduledFireTime(), entity.getCreatedAt()));
        view.setAttempts(entity.getAttempts());
        view.setMaxRetries(entity.getMaxRetries());
        return view;
    }

    private OpsCenterRunIncidentView toRunIncident(RunRecordEntity entity, LocalDateTime now) {
        OpsCenterRunIncidentView view = new OpsCenterRunIncidentView();
        copyBase(view, entity.getId(), entity.getTenantId(), entity.getProjectId(), entity.getDeleted(), entity.getCreatedAt(), entity.getUpdatedAt());
        view.setExecutionType(entity.getExecutionType());
        view.setWorkflowRunId(entity.getWorkflowRunId());
        view.setWorkflowDefinitionId(entity.getWorkflowDefinitionId());
        view.setWorkflowVersionId(entity.getWorkflowVersionId());
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setQualityTaskId(entity.getQualityTaskId());
        view.setNodeCode(entity.getNodeCode());
        view.setStatus(entity.getStatus());
        view.setMessage(safeMessage(entity.getMessage()));
        view.setWorkerGroupCode(entity.getWorkerGroupCode());
        view.setWorkerCode(entity.getWorkerCode());
        view.setWorkerInstanceId(entity.getWorkerInstanceId());
        view.setWorkerPodName(entity.getWorkerPodName());
        view.setWorkerNodeName(entity.getWorkerNodeName());
        view.setStartedAt(entity.getStartedAt());
        view.setEndedAt(entity.getEndedAt());
        view.setDurationMs(runDurationMs(entity, now));
        view.setLogStorageType(entity.getLogStorageType());
        view.setLogStatus(entity.getLogStatus());
        view.setLogErrorSummary(safeMessage(entity.getLogErrorSummary()));
        view.setSlow(Boolean.valueOf(isSlowRun(entity, now)));
        view.setLogAbnormal(Boolean.valueOf(isLogAbnormal(entity)));
        return view;
    }

    private OpsCenterServiceEventView toServiceEvent(DataServiceAccessLogEntity entity) {
        OpsCenterServiceEventView view = new OpsCenterServiceEventView();
        view.setId(entity.getId());
        view.setServiceId(entity.getServiceId());
        view.setServiceCode(entity.getServiceCodeSnapshot());
        view.setServiceName(entity.getServiceNameSnapshot());
        view.setServiceStatus(entity.getServiceStatusSnapshot());
        view.setSubscriptionId(entity.getSubscriptionId());
        view.setSubscriptionName(entity.getSubscriptionNameSnapshot());
        view.setRequestMethod(entity.getRequestMethod());
        view.setOccurredAt(entity.getOccurredAt());
        view.setDurationMs(entity.getDurationMs());
        view.setSuccess(Boolean.valueOf(isSuccess(entity.getSuccess())));
        view.setHttpStatus(entity.getHttpStatus());
        view.setErrorCode(entity.getErrorCode());
        view.setErrorMessage(safeMessage(entity.getErrorMessage()));
        view.setRowCount(entity.getRowCount());
        view.setSlow(Boolean.valueOf(safeLong(entity.getDurationMs()) >= SLOW_CALL_THRESHOLD_MS));
        return view;
    }

    private OpsCenterServiceEventView toIngestionEvent(DataIngestionAccessLogEntity entity) {
        OpsCenterServiceEventView view = new OpsCenterServiceEventView();
        view.setId(entity.getId());
        view.setServiceId(entity.getServiceId());
        view.setServiceCode(entity.getServiceCodeSnapshot());
        view.setServiceName(entity.getServiceNameSnapshot());
        view.setServiceStatus(entity.getServiceStatusSnapshot());
        view.setSubscriptionId(entity.getSubscriptionId());
        view.setSubscriptionName(entity.getSubscriptionNameSnapshot());
        view.setRequestMethod(entity.getRequestMethod());
        view.setOccurredAt(entity.getOccurredAt());
        view.setDurationMs(entity.getDurationMs());
        view.setSuccess(Boolean.valueOf(isSuccess(entity.getSuccess())));
        view.setHttpStatus(entity.getHttpStatus());
        view.setErrorCode(entity.getErrorCode());
        view.setErrorMessage(safeMessage(entity.getErrorMessage()));
        view.setReceivedCount(entity.getReceivedCount());
        view.setWrittenCount(entity.getSuccessCount());
        view.setFailedCount(entity.getFailedCount());
        view.setSlow(Boolean.valueOf(safeLong(entity.getDurationMs()) >= SLOW_CALL_THRESHOLD_MS));
        return view;
    }

    private OpsCenterLogEventView toLogEvent(RunRecordEntity entity) {
        OpsCenterLogEventView view = new OpsCenterLogEventView();
        copyBase(view, entity.getId(), entity.getTenantId(), entity.getProjectId(), entity.getDeleted(), entity.getCreatedAt(), entity.getUpdatedAt());
        view.setExecutionType(entity.getExecutionType());
        view.setWorkflowRunId(entity.getWorkflowRunId());
        view.setWorkflowDefinitionId(entity.getWorkflowDefinitionId());
        view.setCollectionTaskId(entity.getCollectionTaskId());
        view.setQualityTaskId(entity.getQualityTaskId());
        view.setNodeCode(entity.getNodeCode());
        view.setStatus(entity.getStatus());
        view.setWorkerGroupCode(entity.getWorkerGroupCode());
        view.setWorkerInstanceId(entity.getWorkerInstanceId());
        view.setStartedAt(entity.getStartedAt());
        view.setEndedAt(entity.getEndedAt());
        view.setLogStorageType(entity.getLogStorageType());
        view.setLogStatus(entity.getLogStatus());
        view.setLogErrorSummary(safeMessage(entity.getLogErrorSummary()));
        return view;
    }

    private void copyBase(com.jdragon.studio.dto.model.BaseDefinition view,
                          Long id,
                          String tenantId,
                          Long projectId,
                          Integer deleted,
                          LocalDateTime createdAt,
                          LocalDateTime updatedAt) {
        view.setId(id);
        view.setTenantId(tenantId);
        view.setProjectId(projectId);
        view.setDeleted(Boolean.valueOf(deleted != null && deleted.intValue() == 1));
        view.setCreatedAt(createdAt);
        view.setUpdatedAt(updatedAt);
    }

    private Scope scope(OpsCenterQueryRequest request) {
        String tenantId = securityService.currentTenantId();
        Long projectId = securityService.currentProjectId();
        if (projectId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Current project is required");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = parseTime(request == null ? null : request.getEndTime(), now);
        LocalDateTime start = parseTime(request == null ? null : request.getStartTime(), end.minusDays(1L));
        if (start.isAfter(end)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Start time must be earlier than end time");
        }
        return new Scope(tenantId, projectId, start, end, now);
    }

    private LocalDateTime parseTime(String value, LocalDateTime defaultValue) {
        if (!hasText(value)) {
            return defaultValue;
        }
        String text = value.trim();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text, REQUEST_TIME_FORMATTER);
            } catch (DateTimeParseException ex) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid time format: " + text);
            }
        }
    }

    private <T> PageView<T> page(OpsCenterQueryRequest request, List<T> values) {
        PageSlice<T> slice = slice(request, values);
        return PageView.of(slice.pageNo, slice.pageSize, slice.total, slice.items);
    }

    private <T> PageSlice<T> slice(OpsCenterQueryRequest request, List<T> values) {
        List<T> safeValues = values == null ? Collections.<T>emptyList() : values;
        int pageNo = normalizePageNo(request);
        int pageSize = normalizePageSize(request);
        int fromIndex = Math.min((pageNo - 1) * pageSize, safeValues.size());
        int toIndex = Math.min(fromIndex + pageSize, safeValues.size());
        return new PageSlice<T>(pageNo, pageSize, safeValues.size(), new ArrayList<T>(safeValues.subList(fromIndex, toIndex)));
    }

    private int normalizePageNo(OpsCenterQueryRequest request) {
        return request == null || request.getPageNo() == null || request.getPageNo().intValue() <= 0 ? 1 : request.getPageNo().intValue();
    }

    private int normalizePageSize(OpsCenterQueryRequest request) {
        return request == null || request.getPageSize() == null || request.getPageSize().intValue() <= 0 ? 20 : Math.min(request.getPageSize().intValue(), 200);
    }

    private boolean isLogAbnormal(RunRecordEntity entity) {
        if (entity == null) {
            return false;
        }
        if (hasText(entity.getLogErrorSummary())) {
            return true;
        }
        String status = upper(entity.getLogStatus());
        return hasText(status) && !"AVAILABLE".equals(status) && !"WRITING".equals(status);
    }

    private boolean isSlowRun(RunRecordEntity entity, LocalDateTime now) {
        return runDurationMs(entity, now) >= SLOW_RUN_THRESHOLD_MS;
    }

    private Long runDurationMs(RunRecordEntity entity, LocalDateTime now) {
        if (entity == null || entity.getStartedAt() == null) {
            return Long.valueOf(0L);
        }
        LocalDateTime end = entity.getEndedAt() == null ? now : entity.getEndedAt();
        return durationMs(entity.getStartedAt(), end);
    }

    private Long longestQueueWait(List<DispatchTaskEntity> queueTasks, LocalDateTime now) {
        Long longest = null;
        for (DispatchTaskEntity task : queueTasks) {
            if (!"QUEUED".equalsIgnoreCase(task.getStatus())) {
                continue;
            }
            Long wait = durationMs(task.getCreatedAt(), now);
            if (wait != null && (longest == null || wait.longValue() > longest.longValue())) {
                longest = wait;
            }
        }
        return longest;
    }

    private Long durationMs(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) {
            return Long.valueOf(0L);
        }
        return Long.valueOf(Duration.between(start, end).toMillis());
    }

    private boolean isOnlineLease(WorkerLeaseEntity lease, LocalDateTime now) {
        if (lease == null || !"ONLINE".equalsIgnoreCase(lease.getStatus())) {
            return false;
        }
        return (lease.getLeaseExpiresAt() != null && lease.getLeaseExpiresAt().isAfter(now))
                || (lease.getLastHeartbeatAt() != null && !lease.getLastHeartbeatAt().isBefore(now.minusSeconds(30L)));
    }

    private boolean isRecentLease(WorkerLeaseEntity lease, LocalDateTime threshold) {
        return lease != null && lease.getLastHeartbeatAt() != null && !lease.getLastHeartbeatAt().isBefore(threshold);
    }

    private WorkerLeaseEntity latestLease(List<WorkerLeaseEntity> leases) {
        if (leases == null || leases.isEmpty()) {
            return null;
        }
        WorkerLeaseEntity latest = null;
        for (WorkerLeaseEntity lease : leases) {
            if (latest == null || compareTime(lease.getLastHeartbeatAt(), latest.getLastHeartbeatAt()) > 0) {
                latest = lease;
            }
        }
        return latest;
    }

    private int compareTime(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private String resolveWorkerGroupCode(ProjectWorkerBindingEntity binding) {
        if (binding == null) {
            return null;
        }
        return hasText(binding.getWorkerGroupCode()) ? binding.getWorkerGroupCode().trim() : trim(binding.getWorkerCode());
    }

    private String resolveWorkerGroupCode(WorkerLeaseEntity lease) {
        if (lease == null) {
            return null;
        }
        return hasText(lease.getWorkerGroupCode()) ? lease.getWorkerGroupCode().trim() : trim(lease.getWorkerCode());
    }

    private boolean isSuccess(Integer value) {
        return value != null && value.intValue() == 1;
    }

    private boolean isFailedStatus(String value) {
        String status = upper(value);
        return "FAILED".equals(status) || "ERROR".equals(status);
    }

    private boolean isRunningStatus(String value) {
        String status = upper(value);
        return "RUNNING".equals(status) || "QUEUED".equals(status);
    }

    private boolean isRunScopedFilter(OpsCenterQueryRequest request) {
        return request != null && (hasText(request.getExecutionType()) || hasText(request.getWorkerGroupCode()));
    }

    private boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String safeMessage(String message) {
        if (!hasText(message)) {
            return message;
        }
        return stripExceptionClassPrefix(message.trim());
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

    private long safeLong(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    private <T> long count(List<T> values, Predicate<T> predicate) {
        long result = 0L;
        if (values == null || predicate == null) {
            return result;
        }
        for (T value : values) {
            if (predicate.test(value)) {
                result++;
            }
        }
        return result;
    }

    private interface Predicate<T> {
        boolean test(T value);
    }

    private static final class PageSlice<T> {
        private final int pageNo;
        private final int pageSize;
        private final int total;
        private final List<T> items;

        private PageSlice(int pageNo, int pageSize, int total, List<T> items) {
            this.pageNo = pageNo;
            this.pageSize = pageSize;
            this.total = total;
            this.items = items;
        }
    }

    private final class TargetNameResolver {
        private final Scope scope;
        private final Map<Long, String> workflowNames = new LinkedHashMap<Long, String>();
        private final Map<Long, String> collectionTaskNames = new LinkedHashMap<Long, String>();
        private final Map<Long, String> qualityTaskNames = new LinkedHashMap<Long, String>();

        private TargetNameResolver(Scope scope) {
            this.scope = scope;
        }

        private String resolve(DispatchTaskEntity task) {
            if (task == null) {
                return "-";
            }
            if (task.getWorkflowDefinitionId() != null) {
                return resolveWorkflowName(task.getWorkflowDefinitionId());
            }
            if (task.getCollectionTaskId() != null) {
                return resolveCollectionTaskName(task.getCollectionTaskId());
            }
            if (task.getQualityTaskId() != null) {
                return resolveQualityTaskName(task.getQualityTaskId());
            }
            return "-";
        }

        private String resolveWorkflowName(Long definitionId) {
            if (!workflowNames.containsKey(definitionId)) {
                WorkflowDefinitionEntity entity = workflowDefinitionMapper.selectOne(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                        .select(WorkflowDefinitionEntity::getId,
                                WorkflowDefinitionEntity::getName)
                        .eq(WorkflowDefinitionEntity::getTenantId, scope.tenantId)
                        .eq(WorkflowDefinitionEntity::getProjectId, scope.projectId)
                        .eq(WorkflowDefinitionEntity::getId, definitionId));
                workflowNames.put(definitionId, entity == null ? "-" : entity.getName());
            }
            return workflowNames.get(definitionId);
        }

        private String resolveCollectionTaskName(Long taskId) {
            if (!collectionTaskNames.containsKey(taskId)) {
                CollectionTaskDefinitionEntity entity = collectionTaskDefinitionMapper.selectOne(new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
                        .select(CollectionTaskDefinitionEntity::getId,
                                CollectionTaskDefinitionEntity::getName)
                        .eq(CollectionTaskDefinitionEntity::getTenantId, scope.tenantId)
                        .eq(CollectionTaskDefinitionEntity::getProjectId, scope.projectId)
                        .eq(CollectionTaskDefinitionEntity::getId, taskId));
                collectionTaskNames.put(taskId, entity == null ? "-" : entity.getName());
            }
            return collectionTaskNames.get(taskId);
        }

        private String resolveQualityTaskName(Long taskId) {
            if (!qualityTaskNames.containsKey(taskId)) {
                QualityTaskDefinitionEntity entity = qualityTaskDefinitionMapper.selectOne(new LambdaQueryWrapper<QualityTaskDefinitionEntity>()
                        .select(QualityTaskDefinitionEntity::getId,
                                QualityTaskDefinitionEntity::getTaskName)
                        .eq(QualityTaskDefinitionEntity::getTenantId, scope.tenantId)
                        .eq(QualityTaskDefinitionEntity::getProjectId, scope.projectId)
                        .eq(QualityTaskDefinitionEntity::getId, taskId));
                qualityTaskNames.put(taskId, entity == null ? "-" : entity.getTaskName());
            }
            return qualityTaskNames.get(taskId);
        }
    }

    private static final class Scope {
        private final String tenantId;
        private final Long projectId;
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;
        private final LocalDateTime now;

        private Scope(String tenantId, Long projectId, LocalDateTime startTime, LocalDateTime endTime, LocalDateTime now) {
            this.tenantId = tenantId;
            this.projectId = projectId;
            this.startTime = startTime;
            this.endTime = endTime;
            this.now = now;
        }
    }
}
