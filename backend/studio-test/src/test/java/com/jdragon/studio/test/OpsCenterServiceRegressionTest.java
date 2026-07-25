package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.OpsCenterHealthStatus;
import com.jdragon.studio.dto.model.OpsCenterOverviewView;
import com.jdragon.studio.dto.model.OpsCenterQueueItemView;
import com.jdragon.studio.dto.model.OpsCenterLogEventView;
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
import com.jdragon.studio.infra.entity.ProtocolConversionAccessLogEntity;
import com.jdragon.studio.infra.entity.QualityTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessLogMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.service.OpsCenterService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsCenterServiceRegressionTest {

    @BeforeAll
    static void initMyBatisLambdaCaches() {
        initTableInfo(DispatchTaskEntity.class);
        initTableInfo(RunRecordEntity.class);
        initTableInfo(DataServiceAccessLogEntity.class);
        initTableInfo(DataIngestionAccessLogEntity.class);
        initTableInfo(ProtocolConversionAccessLogEntity.class);
        initTableInfo(ProjectWorkerBindingEntity.class);
        initTableInfo(WorkerLeaseEntity.class);
        initTableInfo(WorkflowDefinitionEntity.class);
        initTableInfo(CollectionTaskDefinitionEntity.class);
        initTableInfo(QualityTaskDefinitionEntity.class);
    }

    @Test
    void shouldBeHealthyWhenOnlyWorkerIsOnline() {
        Fixture fixture = new Fixture();
        fixture.withOnlineWorker("default-pool");

        OpsCenterOverviewView overview = fixture.service.overview(new OpsCenterQueryRequest());

        assertEquals(OpsCenterHealthStatus.HEALTHY, overview.getHealthStatus());
        assertEquals(Long.valueOf(0L), overview.getFailedRuns());
        assertEquals(Long.valueOf(0L), overview.getQueuedTasks());
        assertEquals(Long.valueOf(0L), overview.getServiceFailures());
        assertEquals(Long.valueOf(0L), overview.getIngestionFailures());
        assertEquals(Long.valueOf(0L), overview.getLogFailures());
        assertEquals(Integer.valueOf(1), overview.getOnlineWorkerInstances());
    }

    @Test
    void shouldBeCriticalWhenProjectHasNoOnlineWorker() {
        Fixture fixture = new Fixture();
        fixture.withBindingOnly("default-pool");

        OpsCenterOverviewView overview = fixture.service.overview(new OpsCenterQueryRequest());

        assertEquals(OpsCenterHealthStatus.CRITICAL, overview.getHealthStatus());
        assertEquals(Integer.valueOf(0), overview.getOnlineWorkerInstances());
        assertEquals(Integer.valueOf(1), overview.getBoundWorkerGroups());
        assertTrue(overview.getHealthReasons().contains("当前项目已下发 Worker 组，但没有在线 Worker 实例"));
    }

    @Test
    void shouldBeCriticalWhenProjectHasNoWorkerGroupBinding() {
        Fixture fixture = new Fixture();

        OpsCenterOverviewView overview = fixture.service.overview(new OpsCenterQueryRequest());

        assertEquals(OpsCenterHealthStatus.CRITICAL, overview.getHealthStatus());
        assertEquals(Integer.valueOf(0), overview.getBoundWorkerGroups());
        assertEquals(Integer.valueOf(0), overview.getOnlineWorkerInstances());
        assertTrue(overview.getHealthReasons().contains("当前项目未下发任何 Worker 组，任务不会被 Worker 领取"));
    }

    @Test
    void shouldShowDiscoveredWorkerAsUnboundAndNotCountItAsAvailable() {
        Fixture fixture = new Fixture();
        fixture.withWorkerLease(lease("discovered-pool", "ONLINE", LocalDateTime.now().minusSeconds(5L), LocalDateTime.now().plusMinutes(1L)));

        OpsCenterOverviewView overview = fixture.service.overview(new OpsCenterQueryRequest());
        PageView<OpsCenterWorkerGroupView> workerPage = fixture.service.queryWorkers(new OpsCenterQueryRequest());

        assertEquals(OpsCenterHealthStatus.CRITICAL, overview.getHealthStatus());
        assertEquals(Integer.valueOf(0), overview.getBoundWorkerGroups());
        assertEquals(Integer.valueOf(0), overview.getOnlineWorkerInstances());
        assertEquals(1L, workerPage.getTotal());
        assertFalse(Boolean.TRUE.equals(workerPage.getItems().get(0).getBoundToProject()));
        assertEquals(Integer.valueOf(1), workerPage.getItems().get(0).getOnlineInstanceCount());
        assertEquals("ONLINE", workerPage.getItems().get(0).getDisplayStatus());
    }

    @Test
    void shouldNotCountExpiredOnlineLeaseAsOnline() {
        Fixture fixture = new Fixture();
        fixture.withBinding("default-pool", 1);
        fixture.withWorkerLease(lease("default-pool", "ONLINE", LocalDateTime.now().minusMinutes(2L), LocalDateTime.now().minusMinutes(1L)));

        PageView<OpsCenterWorkerGroupView> page = fixture.service.queryWorkers(new OpsCenterQueryRequest());
        OpsCenterOverviewView overview = fixture.service.overview(new OpsCenterQueryRequest());

        assertEquals(1L, page.getTotal());
        assertEquals(Integer.valueOf(0), page.getItems().get(0).getOnlineInstanceCount());
        assertEquals("OFFLINE", page.getItems().get(0).getDisplayStatus());
        assertEquals(OpsCenterHealthStatus.CRITICAL, overview.getHealthStatus());
    }

    @Test
    void shouldBeCriticalWhenQueueWaitsMoreThanTenMinutes() {
        Fixture fixture = new Fixture();
        fixture.withOnlineWorker("default-pool");
        fixture.withDispatchTask(queueTask("QUEUED", LocalDateTime.now().minusMinutes(11L)));

        OpsCenterOverviewView overview = fixture.service.overview(new OpsCenterQueryRequest());
        PageView<OpsCenterQueueItemView> queuePage = fixture.service.queryQueue(new OpsCenterQueryRequest());

        assertEquals(OpsCenterHealthStatus.CRITICAL, overview.getHealthStatus());
        assertEquals(Long.valueOf(1L), overview.getQueuedTasks());
        assertTrue(overview.getHealthReasons().contains("存在排队超过 10 分钟的任务"));
        assertEquals(1L, queuePage.getTotal());
        assertTrue(queuePage.getItems().get(0).getQueuedDurationMs() >= 10L * 60L * 1000L);
    }

    @Test
    void shouldResolveQueueTargetNameFromTaskDefinition() {
        Fixture fixture = new Fixture();
        DispatchTaskEntity task = queueTask("QUEUED", LocalDateTime.now().minusMinutes(1L));
        task.setCollectionTaskId(900L);
        fixture.withDispatchTask(task);
        CollectionTaskDefinitionEntity definition = new CollectionTaskDefinitionEntity();
        definition.setName("订单采集任务");
        when(fixture.collectionTaskDefinitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(definition);

        PageView<OpsCenterQueueItemView> queuePage = fixture.service.queryQueue(new OpsCenterQueryRequest());

        assertEquals("订单采集任务", queuePage.getItems().get(0).getTargetName());
    }

    @Test
    void shouldUseDatabasePaginationForOpsCenterTableSections() {
        Fixture fixture = new Fixture();
        fixture.withDispatchTask(queueTask("QUEUED", LocalDateTime.now().minusMinutes(1L)));
        RunRecordEntity runRecord = runRecord("FAILED", LocalDateTime.now().minusMinutes(2L), LocalDateTime.now().minusMinutes(1L));
        runRecord.setLogStatus("UPLOAD_FAILED");
        runRecord.setLogErrorSummary("object storage unavailable");
        fixture.withRunRecord(runRecord);
        fixture.withServiceLog(serviceLog(0, 500, 200L));
        fixture.withIngestionLog(ingestionLog(1, 200, 300L, 1L));
        fixture.withProtocolConversionLog(protocolConversionLog(0, 500, 400L, 1L));
        OpsCenterQueryRequest request = new OpsCenterQueryRequest();
        request.setPageNo(1);
        request.setPageSize(8);

        fixture.service.queryQueue(request);
        fixture.service.queryRuns(request);
        fixture.service.queryServiceEvents(request);
        fixture.service.queryIngestionEvents(request);
        fixture.service.queryProtocolConversionEvents(request);
        fixture.service.queryLogEvents(request);

        verify(fixture.dispatchTaskMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        verify(fixture.runRecordMapper, times(2)).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        verify(fixture.dataServiceAccessLogMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        verify(fixture.dataIngestionAccessLogMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        verify(fixture.protocolConversionAccessLogMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void shouldBeWarningWhenRunOrServiceOrIngestionFailureExists() {
        Fixture fixture = new Fixture();
        fixture.withOnlineWorker("default-pool");
        fixture.withRunRecord(runRecord("FAILED", LocalDateTime.now().minusMinutes(2L), LocalDateTime.now().minusMinutes(1L)));
        fixture.withServiceLog(serviceLog(0, 500, 200L));
        fixture.withIngestionLog(ingestionLog(1, 200, 300L, 1L));
        fixture.withProtocolConversionLog(protocolConversionLog(0, 500, 400L, 1L));

        OpsCenterOverviewView overview = fixture.service.overview(new OpsCenterQueryRequest());

        assertEquals(OpsCenterHealthStatus.WARNING, overview.getHealthStatus());
        assertEquals(Long.valueOf(1L), overview.getFailedRuns());
        assertEquals(Long.valueOf(1L), overview.getServiceFailures());
        assertEquals(Long.valueOf(1L), overview.getIngestionFailures());
        assertEquals(Long.valueOf(1L), overview.getProtocolConversionFailures());
    }

    @Test
    void shouldSanitizeInternalExceptionPrefixesInOpsMessages() {
        Fixture fixture = new Fixture();
        fixture.withOnlineWorker("default-pool");
        RunRecordEntity runRecord = runRecord("FAILED", LocalDateTime.now().minusMinutes(2L), LocalDateTime.now().minusMinutes(1L));
        runRecord.setMessage("java.lang.RuntimeException: 不支持的类型对比");
        runRecord.setLogStatus("UPLOAD_FAILED");
        runRecord.setLogErrorSummary("java.lang.IllegalStateException: object storage unavailable");
        fixture.withRunRecord(runRecord);
        DataServiceAccessLogEntity serviceLog = serviceLog(0, 500, 200L);
        serviceLog.setErrorMessage("java.lang.RuntimeException: 服务调用失败");
        fixture.withServiceLog(serviceLog);
        DataIngestionAccessLogEntity ingestionLog = ingestionLog(1, 200, 300L, 1L);
        ingestionLog.setErrorMessage("java.lang.RuntimeException: 接入写入失败");
        fixture.withIngestionLog(ingestionLog);

        PageView<OpsCenterRunIncidentView> runPage = fixture.service.queryRuns(new OpsCenterQueryRequest());
        PageView<OpsCenterLogEventView> logPage = fixture.service.queryLogEvents(new OpsCenterQueryRequest());
        PageView<OpsCenterServiceEventView> servicePage = fixture.service.queryServiceEvents(new OpsCenterQueryRequest());
        PageView<OpsCenterServiceEventView> ingestionPage = fixture.service.queryIngestionEvents(new OpsCenterQueryRequest());

        assertEquals("不支持的类型对比", runPage.getItems().get(0).getMessage());
        assertEquals("object storage unavailable", runPage.getItems().get(0).getLogErrorSummary());
        assertEquals("object storage unavailable", logPage.getItems().get(0).getLogErrorSummary());
        assertEquals("服务调用失败", servicePage.getItems().get(0).getErrorMessage());
        assertEquals("接入写入失败", ingestionPage.getItems().get(0).getErrorMessage());
    }

    @Test
    void shouldExposeRuntimePlacementAcrossOpsSections() {
        Fixture fixture = new Fixture();
        fixture.withOnlineWorker("default-pool");
        fixture.withDispatchTask(queueTask("QUEUED", LocalDateTime.now().minusMinutes(1L)));
        fixture.withRunRecord(runRecord("FAILED", LocalDateTime.now().minusMinutes(2L), LocalDateTime.now().minusMinutes(1L)));
        fixture.withServiceLog(serviceLog(0, 500, 200L));
        fixture.withIngestionLog(ingestionLog(0, 500, 200L, 1L));
        fixture.withProtocolConversionLog(protocolConversionLog(0, 500, 200L, 1L));

        assertEquals(Long.valueOf(46L), fixture.service.queryQueue(new OpsCenterQueryRequest())
                .getItems().get(0).getTargetClusterId());
        OpsCenterRunIncidentView run = fixture.service.queryRuns(new OpsCenterQueryRequest()).getItems().get(0);
        assertEquals(Long.valueOf(46L), run.getRequestedClusterId());
        assertEquals(Long.valueOf(50L), run.getActualClusterId());
        assertEquals("cluster-50", run.getActualClusterCode());
        OpsCenterServiceEventView serviceEvent = fixture.service.queryServiceEvents(new OpsCenterQueryRequest()).getItems().get(0);
        assertEquals(Long.valueOf(46L), serviceEvent.getRequestedClusterId());
        assertEquals(Long.valueOf(50L), serviceEvent.getActualClusterId());
        OpsCenterServiceEventView protocolEvent = fixture.service.queryProtocolConversionEvents(new OpsCenterQueryRequest()).getItems().get(0);
        assertEquals(Long.valueOf(46L), protocolEvent.getRequestedClusterId());
        assertEquals(Long.valueOf(50L), protocolEvent.getActualClusterId());

        OpsCenterQueryRequest matchingWorkers = new OpsCenterQueryRequest();
        matchingWorkers.setActualClusterId(50L);
        assertEquals(1L, fixture.service.queryWorkers(matchingWorkers).getTotal());
        OpsCenterQueryRequest otherWorkers = new OpsCenterQueryRequest();
        otherWorkers.setActualClusterId(46L);
        assertEquals(0L, fixture.service.queryWorkers(otherWorkers).getTotal());
    }

    @Test
    void shouldIgnoreServiceAndIngestionEventsWhenFilterIsRunScoped() {
        Fixture fixture = new Fixture();
        fixture.withOnlineWorker("default-pool");
        fixture.withServiceLog(serviceLog(0, 500, 200L));
        fixture.withIngestionLog(ingestionLog(1, 200, 300L, 1L));
        fixture.withProtocolConversionLog(protocolConversionLog(0, 500, 300L, 1L));
        OpsCenterQueryRequest request = new OpsCenterQueryRequest();
        request.setExecutionType("WORKFLOW_NODE");
        request.setStatus("SUCCESS");
        request.setWorkerGroupCode("default-pool");

        OpsCenterOverviewView overview = fixture.service.overview(request);

        assertEquals(OpsCenterHealthStatus.HEALTHY, overview.getHealthStatus());
        assertEquals(Long.valueOf(0L), overview.getServiceFailures());
        assertEquals(Long.valueOf(0L), overview.getIngestionFailures());
        assertEquals(Long.valueOf(0L), overview.getProtocolConversionFailures());
        assertEquals(0L, fixture.service.queryServiceEvents(request).getTotal());
        assertEquals(0L, fixture.service.queryIngestionEvents(request).getTotal());
        assertEquals(0L, fixture.service.queryProtocolConversionEvents(request).getTotal());
    }

    @Test
    void shouldApplyRuntimeClusterFiltersToProtocolConversionEvents() {
        Fixture fixture = new Fixture();
        OpsCenterQueryRequest request = new OpsCenterQueryRequest();
        request.setRequestedClusterId(46L);
        request.setActualClusterId(50L);

        fixture.service.queryProtocolConversionEvents(request);

        ArgumentCaptor<LambdaQueryWrapper<ProtocolConversionAccessLogEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fixture.protocolConversionAccessLogMapper).selectPage(any(Page.class), captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("requested_cluster_id"));
        assertTrue(sqlSegment.contains("actual_cluster_id"));
    }

    @Test
    void shouldBeCriticalWhenRunLogIsUnavailable() {
        Fixture fixture = new Fixture();
        fixture.withOnlineWorker("default-pool");
        RunRecordEntity record = runRecord("SUCCESS", LocalDateTime.now().minusMinutes(2L), LocalDateTime.now().minusMinutes(1L));
        record.setLogStatus("UPLOAD_FAILED");
        record.setLogErrorSummary("object storage unavailable");
        fixture.withRunRecord(record);

        OpsCenterOverviewView overview = fixture.service.overview(new OpsCenterQueryRequest());

        assertEquals(OpsCenterHealthStatus.CRITICAL, overview.getHealthStatus());
        assertEquals(Long.valueOf(1L), overview.getLogFailures());
        assertTrue(overview.getHealthReasons().contains("存在运行日志读取或上传异常"));
    }

    @Test
    void shouldRejectInvalidTimeRange() {
        Fixture fixture = new Fixture();
        OpsCenterQueryRequest request = new OpsCenterQueryRequest();
        request.setStartTime("2026-06-02 00:00:00");
        request.setEndTime("2026-06-01 00:00:00");

        StudioException exception = assertThrows(StudioException.class, () -> fixture.service.overview(request));

        assertEquals(StudioErrorCode.BAD_REQUEST, exception.getCode());
    }

    @Test
    void shouldQueryRunRecordsWithinCurrentTenantAndProjectScope() {
        Fixture fixture = new Fixture();
        fixture.withOnlineWorker("default-pool");
        fixture.service.overview(new OpsCenterQueryRequest());

        ArgumentCaptor<LambdaQueryWrapper<RunRecordEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fixture.runRecordMapper).selectList(captor.capture());

        initTableInfo(RunRecordEntity.class);
        String sqlSegment = captor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("tenant_id"));
        assertTrue(sqlSegment.contains("project_id"));
    }

    private static DispatchTaskEntity queueTask(String status, LocalDateTime createdAt) {
        DispatchTaskEntity task = new DispatchTaskEntity();
        fillBase(task, 200L, createdAt);
        task.setExecutionType("COLLECTION_TASK");
        task.setStatus(status);
        task.setTargetClusterId(46L);
        task.setWorkerGroupCode("default-pool");
        task.setScheduledFireTime(createdAt.minusMinutes(1L));
        return task;
    }

    private static RunRecordEntity runRecord(String status, LocalDateTime startedAt, LocalDateTime endedAt) {
        RunRecordEntity record = new RunRecordEntity();
        fillBase(record, 300L, startedAt);
        record.setExecutionType("COLLECTION_TASK");
        record.setStatus(status);
        record.setRequestedClusterId(46L);
        record.setActualClusterId(50L);
        record.setActualClusterCode("cluster-50");
        record.setStartedAt(startedAt);
        record.setEndedAt(endedAt);
        record.setWorkerGroupCode("default-pool");
        record.setLogStatus("AVAILABLE");
        return record;
    }

    private static DataServiceAccessLogEntity serviceLog(Integer success, Integer httpStatus, Long durationMs) {
        DataServiceAccessLogEntity log = new DataServiceAccessLogEntity();
        fillBase(log, 400L, LocalDateTime.now().minusSeconds(30L));
        log.setServiceId(1L);
        log.setRequestedClusterId(46L);
        log.setActualClusterId(50L);
        log.setServiceNameSnapshot("服务失败样例");
        log.setOccurredAt(LocalDateTime.now().minusSeconds(30L));
        log.setSuccess(success);
        log.setHttpStatus(httpStatus);
        log.setDurationMs(durationMs);
        return log;
    }

    private static DataIngestionAccessLogEntity ingestionLog(Integer success, Integer httpStatus, Long durationMs, Long failedCount) {
        DataIngestionAccessLogEntity log = new DataIngestionAccessLogEntity();
        fillBase(log, 500L, LocalDateTime.now().minusSeconds(20L));
        log.setServiceId(2L);
        log.setRequestedClusterId(46L);
        log.setActualClusterId(50L);
        log.setServiceNameSnapshot("接入失败样例");
        log.setOccurredAt(LocalDateTime.now().minusSeconds(20L));
        log.setSuccess(success);
        log.setHttpStatus(httpStatus);
        log.setDurationMs(durationMs);
        log.setReceivedCount(1L);
        log.setSuccessCount(0L);
        log.setFailedCount(failedCount);
        return log;
    }

    private static ProtocolConversionAccessLogEntity protocolConversionLog(Integer success,
                                                                            Integer httpStatus,
                                                                            Long durationMs,
                                                                            Long failedCount) {
        ProtocolConversionAccessLogEntity log = new ProtocolConversionAccessLogEntity();
        fillBase(log, 600L, LocalDateTime.now().minusSeconds(10L));
        log.setServiceId(3L);
        log.setRequestedClusterId(46L);
        log.setActualClusterId(50L);
        log.setServiceNameSnapshot("协议转换失败样例");
        log.setOccurredAt(LocalDateTime.now().minusSeconds(10L));
        log.setSuccess(success);
        log.setHttpStatus(httpStatus);
        log.setDurationMs(durationMs);
        log.setReceivedCount(1L);
        log.setSuccessCount(0L);
        log.setFailedCount(failedCount);
        return log;
    }

    private static WorkerLeaseEntity lease(String groupCode,
                                           String status,
                                           LocalDateTime lastHeartbeatAt,
                                           LocalDateTime leaseExpiresAt) {
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setTenantId("default");
        lease.setRuntimeClusterId(50L);
        lease.setRuntimeClusterCode("cluster-50");
        lease.setWorkerGroupCode(groupCode);
        lease.setWorkerCode("worker-" + groupCode);
        lease.setInstanceId("instance-" + groupCode);
        lease.setPodName("pod-" + groupCode);
        lease.setNodeName("node-a");
        lease.setStatus(status);
        lease.setLastHeartbeatAt(lastHeartbeatAt);
        lease.setLeaseExpiresAt(leaseExpiresAt);
        return lease;
    }

    private static ProjectWorkerBindingEntity binding(String groupCode, Integer enabled) {
        ProjectWorkerBindingEntity binding = new ProjectWorkerBindingEntity();
        binding.setTenantId("default");
        binding.setProjectId(100L);
        binding.setWorkerGroupCode(groupCode);
        binding.setWorkerCode(groupCode);
        binding.setEnabled(enabled);
        return binding;
    }

    private static void fillBase(com.jdragon.studio.infra.entity.BaseProjectTenantEntity entity, Long id, LocalDateTime createdAt) {
        entity.setId(id);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt);
    }

    private static void initTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }

    private static final class Fixture {
        private final DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        private final RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        private final WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        private final ProjectWorkerBindingMapper projectWorkerBindingMapper = mock(ProjectWorkerBindingMapper.class);
        private final DataServiceAccessLogMapper dataServiceAccessLogMapper = mock(DataServiceAccessLogMapper.class);
        private final DataIngestionAccessLogMapper dataIngestionAccessLogMapper = mock(DataIngestionAccessLogMapper.class);
        private final ProtocolConversionAccessLogMapper protocolConversionAccessLogMapper = mock(ProtocolConversionAccessLogMapper.class);
        private final WorkflowDefinitionMapper workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
        private final CollectionTaskDefinitionMapper collectionTaskDefinitionMapper = mock(CollectionTaskDefinitionMapper.class);
        private final QualityTaskDefinitionMapper qualityTaskDefinitionMapper = mock(QualityTaskDefinitionMapper.class);
        private final StudioSecurityService securityService = mock(StudioSecurityService.class);
        private final OpsCenterService service = new OpsCenterService(
                dispatchTaskMapper,
                runRecordMapper,
                workerLeaseMapper,
                projectWorkerBindingMapper,
                dataServiceAccessLogMapper,
                dataIngestionAccessLogMapper,
                protocolConversionAccessLogMapper,
                workflowDefinitionMapper,
                collectionTaskDefinitionMapper,
                qualityTaskDefinitionMapper,
                securityService
        );

        private Fixture() {
            when(securityService.currentTenantId()).thenReturn("default");
            when(securityService.currentProjectId()).thenReturn(100L);
            when(dispatchTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
            when(runRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
            when(workerLeaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
            when(projectWorkerBindingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
            when(dataServiceAccessLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
            when(dataIngestionAccessLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
            when(protocolConversionAccessLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
            when(dispatchTaskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(invocation -> emptyPage(invocation.getArgument(0)));
            when(runRecordMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(invocation -> emptyPage(invocation.getArgument(0)));
            when(dataServiceAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(invocation -> emptyPage(invocation.getArgument(0)));
            when(dataIngestionAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(invocation -> emptyPage(invocation.getArgument(0)));
            when(protocolConversionAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(invocation -> emptyPage(invocation.getArgument(0)));
        }

        private void withOnlineWorker(String groupCode) {
            withBinding(groupCode, 1);
            withWorkerLease(lease(groupCode, "ONLINE", LocalDateTime.now().minusSeconds(5L), LocalDateTime.now().plusMinutes(1L)));
        }

        private void withBindingOnly(String groupCode) {
            withBinding(groupCode, 1);
            when(workerLeaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        }

        private void withBinding(String groupCode, Integer enabled) {
            when(projectWorkerBindingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(binding(groupCode, enabled)));
        }

        private void withWorkerLease(WorkerLeaseEntity lease) {
            when(workerLeaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(lease));
        }

        private void withDispatchTask(DispatchTaskEntity task) {
            when(dispatchTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(task));
            when(dispatchTaskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(invocation -> singleItemPage(invocation.getArgument(0), task));
        }

        private void withRunRecord(RunRecordEntity record) {
            when(runRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(record));
            when(runRecordMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(invocation -> singleItemPage(invocation.getArgument(0), record));
        }

        private void withServiceLog(DataServiceAccessLogEntity log) {
            when(dataServiceAccessLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(log));
            when(dataServiceAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(invocation -> singleItemPage(invocation.getArgument(0), log));
        }

        private void withIngestionLog(DataIngestionAccessLogEntity log) {
            when(dataIngestionAccessLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(log));
            when(dataIngestionAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(invocation -> singleItemPage(invocation.getArgument(0), log));
        }

        private void withProtocolConversionLog(ProtocolConversionAccessLogEntity log) {
            when(protocolConversionAccessLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(log));
            when(protocolConversionAccessLogMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenAnswer(invocation -> singleItemPage(invocation.getArgument(0), log));
        }

        private static <T> Page<T> emptyPage(Page<T> requestPage) {
            Page<T> page = new Page<T>(requestPage.getCurrent(), requestPage.getSize());
            page.setRecords(Collections.emptyList());
            page.setTotal(0L);
            return page;
        }

        private static <T> Page<T> singleItemPage(Page<T> requestPage, T item) {
            Page<T> page = new Page<T>(requestPage.getCurrent(), requestPage.getSize());
            page.setRecords(Collections.singletonList(item));
            page.setTotal(1L);
            return page;
        }
    }
}
