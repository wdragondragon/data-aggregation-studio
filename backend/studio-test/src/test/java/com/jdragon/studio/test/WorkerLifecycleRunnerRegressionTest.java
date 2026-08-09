package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import com.jdragon.studio.worker.runtime.WorkflowDispatchNodeResolver;
import com.jdragon.studio.worker.runtime.runner.WorkerLifecycleRunner;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerLifecycleRunnerRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(DispatchTaskEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "worker-lifecycle-regression-test"),
                    DispatchTaskEntity.class);
        }
        if (TableInfoHelper.getTableInfo(RunRecordEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "worker-lifecycle-regression-test"),
                    RunRecordEntity.class);
        }
    }

    @Test
    void shouldResolveCollectionTaskFromDispatchColumnWithoutPayloadConfig() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        CollectionTaskAssemblerService assemblerService = mock(CollectionTaskAssemblerService.class);

        CollectionTaskDefinitionView onlineTask = new CollectionTaskDefinitionView();
        onlineTask.setId(2040396020474507266L);
        onlineTask.setName("test");

        Map<String, Object> assembledConfig = new LinkedHashMap<String, Object>();
        assembledConfig.put("reader", Collections.singletonMap("type", "mysql8"));

        when(collectionTaskService.requireOnlineForExecution(eq(2040396020474507266L))).thenReturn(onlineTask);
        when(assemblerService.assemble(eq(onlineTask))).thenReturn(assembledConfig);
        WorkflowDispatchNodeResolver nodeResolver = mock(WorkflowDispatchNodeResolver.class);
        WorkflowNodeDefinition versionedNode = new WorkflowNodeDefinition();
        versionedNode.setNodeCode("collection_task_1775321775573");
        versionedNode.setNodeType(NodeType.COLLECTION_TASK);
        when(nodeResolver.resolve(any())).thenReturn(versionedNode);

        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                mock(com.jdragon.studio.infra.mapper.DispatchTaskMapper.class),
                mock(com.jdragon.studio.infra.mapper.WorkerLeaseMapper.class),
                mock(com.jdragon.studio.infra.mapper.RunRecordMapper.class),
                Collections.<NodeExecutor>emptyList(),
                mock(com.jdragon.studio.core.spi.ExecutionEventPublisher.class),
                new StudioPlatformProperties(),
                collectionTaskService,
                mock(QualityTaskService.class),
                assemblerService,
                mock(RunLogFileService.class),
                mock(WorkerAuthorizationService.class),
                clusterInstanceIdentity("test-instance"),
                nodeResolver
        );

        DispatchTaskEntity dispatchTask = new DispatchTaskEntity();
        dispatchTask.setNodeCode("collection_task_1775321775573");
        dispatchTask.setExecutionType("WORKFLOW_NODE");
        dispatchTask.setCollectionTaskId(2040396020474507266L);
        dispatchTask.setPayloadJson(new LinkedHashMap<String, Object>());

        WorkflowNodeDefinition node = (WorkflowNodeDefinition) ReflectionTestUtils.invokeMethod(runner, "toNode", dispatchTask);

        assertNotNull(node);
        assertEquals("test", node.getNodeName());
        assertEquals(assembledConfig, node.getConfig());
        verify(collectionTaskService).requireOnlineForExecution(2040396020474507266L);
        verify(collectionTaskService, never()).requireOnline(2040396020474507266L);
    }

    @Test
    void shouldAllowManualFileTransferDispatchWithoutPresetTaskIdentity() {
        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                mock(DispatchTaskMapper.class),
                mock(WorkerLeaseMapper.class),
                mock(RunRecordMapper.class),
                Collections.<NodeExecutor>emptyList(),
                mock(com.jdragon.studio.core.spi.ExecutionEventPublisher.class),
                new StudioPlatformProperties(),
                mock(CollectionTaskService.class),
                mock(QualityTaskService.class),
                mock(CollectionTaskAssemblerService.class),
                mock(RunLogFileService.class),
                mock(WorkerAuthorizationService.class),
                clusterInstanceIdentity("manual-transfer-worker"),
                mock(WorkflowDispatchNodeResolver.class)
        );

        DispatchTaskEntity dispatchTask = new DispatchTaskEntity();
        dispatchTask.setExecutionType("FILE_TRANSFER");
        dispatchTask.setNodeCode("file_transfer_run_42");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("nodeType", NodeType.FILE_TRANSFER.name());
        payload.put("fileTransferRunId", 42L);
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("fileTransferRunId", 42L);
        config.put("fileTransferTaskId", null);
        payload.put("config", config);
        dispatchTask.setPayloadJson(payload);

        WorkflowNodeDefinition node = (WorkflowNodeDefinition) ReflectionTestUtils.invokeMethod(
                runner, "toNode", dispatchTask);

        assertNotNull(node);
        assertEquals(NodeType.FILE_TRANSFER, node.getNodeType());
        assertEquals(42L, node.getConfig().get("fileTransferRunId"));
        assertNull(node.getConfig().get("fileTransferTaskId"));
    }

    @Test
    void shouldRecoverInterruptedRunningTasksOwnedByCurrentWorkerOnStartup() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        ExecutionEventPublisher executionEventPublisher = mock(ExecutionEventPublisher.class);
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        CollectionTaskAssemblerService assemblerService = mock(CollectionTaskAssemblerService.class);
        RunLogFileService runLogFileService = mock(RunLogFileService.class);

        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setWorkerCode("studio-online-worker-01");
        properties.setRuntimeClusterCode("DEFAULT-LOCAL");
        ClusterInstanceIdentity clusterInstanceIdentity = clusterInstanceIdentity("worker-instance-01");

        DispatchTaskEntity staleTask = new DispatchTaskEntity();
        staleTask.setId(1L);
        staleTask.setStatus("RUNNING");
        staleTask.setLeaseOwner("studio-online-worker-01");
        staleTask.setWorkerInstanceId("worker-instance-01");
        staleTask.setTargetClusterId(10L);
        staleTask.setRunRecordId(2L);
        staleTask.setWorkflowRunId(3L);
        staleTask.setWorkflowDefinitionId(4L);
        staleTask.setWorkflowVersionId(5L);
        staleTask.setExecutionType("WORKFLOW_NODE");
        staleTask.setNodeCode("collection_task_1");
        staleTask.setCreatedAt(LocalDateTime.of(2026, 4, 5, 7, 0, 0));
        staleTask.setPayloadJson(new LinkedHashMap<String, Object>());

        RunRecordEntity staleRunRecord = new RunRecordEntity();
        staleRunRecord.setId(2L);
        staleRunRecord.setWorkflowRunId(3L);
        staleRunRecord.setWorkflowDefinitionId(4L);
        staleRunRecord.setWorkflowVersionId(5L);
        staleRunRecord.setExecutionType("WORKFLOW_NODE");
        staleRunRecord.setNodeCode("collection_task_1");
        staleRunRecord.setWorkerCode("studio-online-worker-01");
        staleRunRecord.setWorkerInstanceId("worker-instance-01");
        staleRunRecord.setStatus("RUNNING");
        staleRunRecord.setStartedAt(LocalDateTime.of(2026, 4, 5, 7, 0, 1));
        staleRunRecord.setLogFilePath("2026-04-05/run-2.log");
        staleRunRecord.setLogCharset("UTF-8");

        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(staleTask), Collections.emptyList());
        when(dispatchTaskMapper.update(any(DispatchTaskEntity.class), any())).thenReturn(1);
        when(runRecordMapper.selectById(eq(2L))).thenReturn(staleRunRecord);
        when(runRecordMapper.update(any(RunRecordEntity.class), any())).thenReturn(1);
        when(runLogFileService.fileSize(eq("2026-04-05/run-2.log"))).thenReturn(158L);
        doNothing().when(executionEventPublisher).publish(any(ExecutionEvent.class));

        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                dispatchTaskMapper,
                workerLeaseMapper,
                runRecordMapper,
                Collections.<NodeExecutor>emptyList(),
                executionEventPublisher,
                properties,
                collectionTaskService,
                mock(QualityTaskService.class),
                assemblerService,
                runLogFileService,
                mock(WorkerAuthorizationService.class),
                clusterInstanceIdentity,
                mock(WorkflowDispatchNodeResolver.class)
        );
        registerRuntimeCluster(runner, 10L, "default", "DEFAULT-LOCAL");

        runner.recoverLeasedRunningTasks();

        assertEquals("FAILED", staleTask.getStatus());
        assertEquals(Boolean.TRUE, staleTask.getPayloadJson().get("recovered"));
        verify(dispatchTaskMapper).update(any(DispatchTaskEntity.class), any());
        verify(executionEventPublisher).publish(any(ExecutionEvent.class));
    }

    @Test
    void shouldNotExecuteQueuedTaskWhenAtomicClaimFails() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        NodeExecutor nodeExecutor = mock(NodeExecutor.class);
        RunLogFileService runLogFileService = mock(RunLogFileService.class);

        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setWorkerCode("worker-a");
        properties.setRuntimeClusterCode("DEFAULT-LOCAL");

        DispatchTaskEntity queuedTask = queuedTask();
        when(workerLeaseMapper.selectOne(any())).thenReturn(onlineLease("worker-a", "instance-a"));
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(queuedTask));
        when(workerAuthorizationService.isProjectRuntimeClusterGrantEnabled("default", 100L, 10L)).thenReturn(true);
        when(workerAuthorizationService.isRuntimeClusterAuthorizedForProject("default", 100L, 10L)).thenReturn(true);
        when(dispatchTaskMapper.update(any(DispatchTaskEntity.class), any())).thenReturn(0);

        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                dispatchTaskMapper,
                workerLeaseMapper,
                mock(RunRecordMapper.class),
                Collections.singletonList(nodeExecutor),
                mock(ExecutionEventPublisher.class),
                properties,
                mock(CollectionTaskService.class),
                mock(QualityTaskService.class),
                mock(CollectionTaskAssemblerService.class),
                runLogFileService,
                workerAuthorizationService,
                clusterInstanceIdentity("instance-a"),
                mock(WorkflowDispatchNodeResolver.class)
        );
        registerRuntimeCluster(runner, 10L, "default", "DEFAULT-LOCAL");
        ReflectionTestUtils.setField(runner, "acceptingTasks", true);

        runner.pollAndExecute();

        ArgumentCaptor<DispatchTaskEntity> updateCaptor = ArgumentCaptor.forClass(DispatchTaskEntity.class);
        verify(dispatchTaskMapper).update(updateCaptor.capture(), any());
        assertEquals("worker-a", updateCaptor.getValue().getWorkerGroupCode());
        assertEquals("worker-a", updateCaptor.getValue().getLeaseOwner());
        assertNull(updateCaptor.getValue().getPayloadJson());
        verify(runLogFileService, never()).prepare(any(Long.class));
        verify(nodeExecutor, never()).execute(any(), any());
    }

    @Test
    void shouldUseWorkerGroupForClaimOwnershipWithClusterAuthorization() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);

        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setWorkerGroupCode("group-a");
        properties.setWorkerCode("pod-a");
        properties.setRuntimeClusterCode("DEFAULT-LOCAL");

        DispatchTaskEntity queuedTask = queuedTask();
        when(workerLeaseMapper.selectOne(any())).thenReturn(onlineLease("group-a", "pod-a", "instance-a"));
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(queuedTask));
        when(workerAuthorizationService.isProjectRuntimeClusterGrantEnabled("default", 100L, 10L)).thenReturn(true);
        when(workerAuthorizationService.isRuntimeClusterAuthorizedForProject("default", 100L, 10L)).thenReturn(true);
        when(dispatchTaskMapper.update(any(DispatchTaskEntity.class), any())).thenReturn(0);

        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                dispatchTaskMapper,
                workerLeaseMapper,
                mock(RunRecordMapper.class),
                Collections.<NodeExecutor>emptyList(),
                mock(ExecutionEventPublisher.class),
                properties,
                mock(CollectionTaskService.class),
                mock(QualityTaskService.class),
                mock(CollectionTaskAssemblerService.class),
                mock(RunLogFileService.class),
                workerAuthorizationService,
                clusterInstanceIdentity("instance-a"),
                mock(WorkflowDispatchNodeResolver.class)
        );
        registerRuntimeCluster(runner, 10L, "default", "DEFAULT-LOCAL");
        ReflectionTestUtils.setField(runner, "acceptingTasks", true);

        runner.pollAndExecute();

        verify(workerAuthorizationService).isRuntimeClusterAuthorizedForProject("default", 100L, 10L);
        ArgumentCaptor<DispatchTaskEntity> updateCaptor = ArgumentCaptor.forClass(DispatchTaskEntity.class);
        verify(dispatchTaskMapper).update(updateCaptor.capture(), any());
        assertEquals("group-a", updateCaptor.getValue().getWorkerGroupCode());
        assertEquals("group-a", updateCaptor.getValue().getLeaseOwner());
        assertEquals("instance-a", updateCaptor.getValue().getWorkerInstanceId());
    }

    @Test
    void shouldAllowSameWorkerGroupDifferentInstancesToHeartbeat() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setWorkerGroupCode("default-pool");
        properties.setWorkerCode("default-pool-pod-a");
        properties.setRuntimeClusterCode("DEFAULT-LOCAL");

        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                dispatchTaskMapper,
                workerLeaseMapper,
                mock(RunRecordMapper.class),
                Collections.<NodeExecutor>emptyList(),
                mock(ExecutionEventPublisher.class),
                properties,
                mock(CollectionTaskService.class),
                mock(QualityTaskService.class),
                mock(CollectionTaskAssemblerService.class),
                mock(RunLogFileService.class),
                mock(WorkerAuthorizationService.class),
                clusterInstanceIdentity("pod-uid-a"),
                mock(WorkflowDispatchNodeResolver.class)
        );
        registerRuntimeCluster(runner, 10L, "default", "DEFAULT-LOCAL");

        when(workerLeaseMapper.selectOne(any())).thenReturn(null);
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.<DispatchTaskEntity>emptyList());

        runner.heartbeat();

        ArgumentCaptor<WorkerLeaseEntity> insertCaptor = ArgumentCaptor.forClass(WorkerLeaseEntity.class);
        verify(workerLeaseMapper).insert(insertCaptor.capture());
        assertEquals("default-pool", insertCaptor.getValue().getWorkerGroupCode());
        assertEquals("default-pool-pod-a", insertCaptor.getValue().getWorkerCode());
        assertEquals("WORKER", insertCaptor.getValue().getWorkerKind());
        assertEquals("pod-uid-a", insertCaptor.getValue().getInstanceId());
        assertTrue(runner.isAcceptingTasks());
    }

    @Test
    void shouldSkipQueuedTaskWhenWorkerIsNotAuthorizedForProject() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setWorkerCode("worker-a");
        properties.setRuntimeClusterCode("DEFAULT-LOCAL");

        DispatchTaskEntity queuedTask = queuedTask();
        when(workerLeaseMapper.selectOne(any())).thenReturn(onlineLease("worker-a", "instance-a"));
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(queuedTask));
        when(workerAuthorizationService.isProjectRuntimeClusterGrantEnabled("default", 100L, 10L)).thenReturn(true);
        when(workerAuthorizationService.isRuntimeClusterAuthorizedForProject("default", 100L, 10L)).thenReturn(false);

        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                dispatchTaskMapper,
                workerLeaseMapper,
                mock(RunRecordMapper.class),
                Collections.<NodeExecutor>emptyList(),
                mock(ExecutionEventPublisher.class),
                properties,
                mock(CollectionTaskService.class),
                mock(QualityTaskService.class),
                mock(CollectionTaskAssemblerService.class),
                mock(RunLogFileService.class),
                workerAuthorizationService,
                clusterInstanceIdentity("instance-a"),
                mock(WorkflowDispatchNodeResolver.class)
        );
        registerRuntimeCluster(runner, 10L, "default", "DEFAULT-LOCAL");
        ReflectionTestUtils.setField(runner, "acceptingTasks", true);

        runner.pollAndExecute();

        verify(dispatchTaskMapper, never()).update(any(DispatchTaskEntity.class), any());
    }

    @Test
    void shouldNotPollBeforeCurrentLeaseIsEstablished() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setWorkerCode("worker-a");

        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                dispatchTaskMapper,
                mock(WorkerLeaseMapper.class),
                mock(RunRecordMapper.class),
                Collections.<NodeExecutor>emptyList(),
                mock(ExecutionEventPublisher.class),
                properties,
                mock(CollectionTaskService.class),
                mock(QualityTaskService.class),
                mock(CollectionTaskAssemblerService.class),
                mock(RunLogFileService.class),
                mock(WorkerAuthorizationService.class),
                clusterInstanceIdentity("instance-a"),
                mock(WorkflowDispatchNodeResolver.class)
        );

        runner.pollAndExecute();

        verify(dispatchTaskMapper, never()).selectList(any());
    }

    @Test
    void shouldNormalizeTemporalExecutionResultsBeforeDispatchPersistence() {
        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(
                mock(DispatchTaskMapper.class),
                mock(WorkerLeaseMapper.class),
                mock(RunRecordMapper.class),
                Collections.<NodeExecutor>emptyList(),
                mock(ExecutionEventPublisher.class),
                new StudioPlatformProperties(),
                mock(CollectionTaskService.class),
                mock(QualityTaskService.class),
                mock(CollectionTaskAssemblerService.class),
                mock(RunLogFileService.class),
                mock(WorkerAuthorizationService.class),
                clusterInstanceIdentity("instance-a"),
                mock(WorkflowDispatchNodeResolver.class)
        );
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("eventTime", LocalDateTime.of(2026, 7, 26, 23, 45, 0));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sqlResult", Collections.singletonMap("rows", Collections.singletonList(row)));

        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                runner, "normalizePayloadForPersistence", payload);

        @SuppressWarnings("unchecked")
        Map<String, Object> sqlResult = (Map<String, Object>) normalized.get("sqlResult");
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedRow = (Map<String, Object>) ((java.util.List<?>) sqlResult.get("rows")).get(0);
        assertEquals("2026-07-26T23:45:00", normalizedRow.get("eventTime"));
    }

    private ClusterInstanceIdentity clusterInstanceIdentity(String instanceId) {
        ClusterInstanceIdentity identity = mock(ClusterInstanceIdentity.class);
        when(identity.instanceId()).thenReturn(instanceId);
        when(identity.hostName()).thenReturn("localhost");
        when(identity.podName()).thenReturn("pod");
        when(identity.nodeName()).thenReturn("node");
        return identity;
    }

    private DispatchTaskEntity queuedTask() {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setId(1L);
        task.setTenantId("default");
        task.setProjectId(100L);
        task.setStatus("QUEUED");
        task.setTargetClusterId(10L);
        task.setExecutionType("WORKFLOW_NODE");
        task.setNodeCode("node_a");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("nodeType", NodeType.HTTP.name());
        task.setPayloadJson(payload);
        return task;
    }

    private WorkerLeaseEntity onlineLease(String workerCode, String instanceId) {
        return onlineLease(workerCode, workerCode, instanceId);
    }

    private WorkerLeaseEntity onlineLease(String workerGroupCode, String workerCode, String instanceId) {
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setWorkerGroupCode(workerGroupCode);
        lease.setWorkerCode(workerCode);
        lease.setInstanceId(instanceId);
        lease.setStatus("ONLINE");
        lease.setLastHeartbeatAt(LocalDateTime.now());
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        return lease;
    }

    private void registerRuntimeCluster(WorkerLifecycleRunner runner, Long clusterId,
                                        String tenantId, String clusterCode) {
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(clusterId);
        cluster.setTenantId(tenantId);
        cluster.setCode(clusterCode);
        cluster.setEnabled(1);
        RuntimeClusterMapper mapper = mock(RuntimeClusterMapper.class);
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(cluster));
        ReflectionTestUtils.invokeMethod(runner, "setRuntimeClusterMapper", mapper);
    }
}
