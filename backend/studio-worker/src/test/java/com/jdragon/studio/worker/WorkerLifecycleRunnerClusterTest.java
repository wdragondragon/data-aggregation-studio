package com.jdragon.studio.worker;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.RuntimeClusterHeartbeatService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import com.jdragon.studio.worker.runtime.WorkflowDispatchNodeResolver;
import com.jdragon.studio.worker.runtime.runner.WorkerLifecycleRunner;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class WorkerLifecycleRunnerClusterTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(DispatchTaskEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DispatchTaskEntity.class);
        }
        if (TableInfoHelper.getTableInfo(RunRecordEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RunRecordEntity.class);
        }
    }

    @Test
    void shouldNotClaimQueuedTaskTargetedToAnotherRuntimeCluster() {
        Fixture fixture = fixture();
        DispatchTaskEntity taskForAnotherCluster = new DispatchTaskEntity();
        taskForAnotherCluster.setId(1001L);
        taskForAnotherCluster.setTargetClusterId(60L);
        taskForAnotherCluster.setStatus("QUEUED");
        when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(taskForAnotherCluster));
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(fixture.workerLeaseMapper.selectOne(any())).thenReturn(lease);

        fixture.runner.pollAndExecute();

        ArgumentCaptor<LambdaQueryWrapper<DispatchTaskEntity>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fixture.dispatchTaskMapper).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("target_cluster_id"));
        verify(fixture.dispatchTaskMapper, never()).update(any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void shouldFailQueuedTaskAfterProjectClusterAuthorizationIsRevoked() {
        Fixture fixture = fixture();
        DispatchTaskEntity queuedTask = queuedTask(1004L, 50L);
        when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(queuedTask));
        when(fixture.workerAuthorizationService.isProjectRuntimeClusterGrantEnabled("tenant-a", 10L, 50L))
                .thenReturn(false);
        when(fixture.dispatchTaskMapper.update(any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class)))
                .thenReturn(1);
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(fixture.workerLeaseMapper.selectOne(any())).thenReturn(lease);

        fixture.runner.pollAndExecute();

        ArgumentCaptor<DispatchTaskEntity> update = ArgumentCaptor.forClass(DispatchTaskEntity.class);
        ArgumentCaptor<LambdaUpdateWrapper<DispatchTaskEntity>> cas = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fixture.dispatchTaskMapper).update(update.capture(), cas.capture());
        assertEquals("FAILED", update.getValue().getStatus());
        assertEquals("RUNTIME_CLUSTER_AUTHORIZATION_REVOKED",
                update.getValue().getPayloadJson().get("exceptionType"));
        assertTrue(cas.getValue().getSqlSegment().contains("status"));
        assertTrue(cas.getValue().getSqlSegment().contains("target_cluster_id"));
        verify(fixture.runRecordMapper, never()).insert(any(RunRecordEntity.class));
    }

    @Test
    void shouldCloseUnauthorizedOldTaskAndContinueToLaterAuthorizedTask() {
        Fixture fixture = fixture();
        DispatchTaskEntity unauthorized = queuedTask(1007L, 50L);
        DispatchTaskEntity authorized = queuedTask(1008L, 50L);
        authorized.setProjectId(11L);
        when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(List.of(unauthorized, authorized));
        when(fixture.workerAuthorizationService.isProjectRuntimeClusterGrantEnabled("tenant-a", 10L, 50L))
                .thenReturn(false);
        when(fixture.workerAuthorizationService.isProjectRuntimeClusterGrantEnabled("tenant-a", 11L, 50L))
                .thenReturn(true);
        when(fixture.dispatchTaskMapper.update(any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class)))
                .thenReturn(1, 0);
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(fixture.workerLeaseMapper.selectOne(any())).thenReturn(lease);

        fixture.runner.pollAndExecute();

        ArgumentCaptor<DispatchTaskEntity> updates = ArgumentCaptor.forClass(DispatchTaskEntity.class);
        verify(fixture.dispatchTaskMapper, times(2)).update(updates.capture(), any(LambdaUpdateWrapper.class));
        assertEquals("FAILED", updates.getAllValues().get(0).getStatus());
        assertEquals("RUNNING", updates.getAllValues().get(1).getStatus());
        verify(fixture.runRecordMapper, never()).insert(any(RunRecordEntity.class));
    }

    @Test
    void explicitClusterAuthorizationShouldRejectRevokedProjectGrant() {
        Fixture fixture = fixture();
        DispatchTaskEntity queuedTask = queuedTask(1009L, 50L);
        when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(queuedTask));
        when(fixture.workerAuthorizationService.isProjectRuntimeClusterGrantEnabled("tenant-a", 10L, 50L))
                .thenReturn(false);
        when(fixture.dispatchTaskMapper.update(any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class)))
                .thenReturn(1);
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(fixture.workerLeaseMapper.selectOne(any())).thenReturn(lease);

        fixture.runner.pollAndExecute();

        verify(fixture.dispatchTaskMapper).update(
                any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class));
        assertEquals("FAILED", queuedTask.getStatus());
    }

    @Test
    void shouldNeverClaimQueuedTaskWithoutTargetClusterId() {
        Fixture fixture = fixture();
        DispatchTaskEntity unassigned = queuedTask(1010L, null);
        when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(unassigned));
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(fixture.workerLeaseMapper.selectOne(any())).thenReturn(lease);

        fixture.runner.pollAndExecute();

        verify(fixture.dispatchTaskMapper, never()).update(
                any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class));
        assertEquals("QUEUED", unassigned.getStatus());
    }

    @Test
    void shouldFailClaimedTaskWhenAuthorizationIsRevokedBeforeExecution() {
        Fixture fixture = fixture();
        DispatchTaskEntity queuedTask = queuedTask(1005L, 50L);
        when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(queuedTask));
        when(fixture.dispatchTaskMapper.selectById(1005L)).thenReturn(queuedTask);
        when(fixture.workerAuthorizationService.isProjectRuntimeClusterGrantEnabled("tenant-a", 10L, 50L))
                .thenReturn(true);
        when(fixture.workerAuthorizationService.isRuntimeClusterAuthorizedForProject("tenant-a", 10L, 50L))
                .thenReturn(true, false);
        when(fixture.dispatchTaskMapper.update(any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class)))
                .thenReturn(1, 1);
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(fixture.workerLeaseMapper.selectOne(any())).thenReturn(lease);

        fixture.runner.pollAndExecute();

        ArgumentCaptor<DispatchTaskEntity> updates = ArgumentCaptor.forClass(DispatchTaskEntity.class);
        verify(fixture.dispatchTaskMapper, times(2)).update(updates.capture(), any(LambdaUpdateWrapper.class));
        assertEquals("RUNNING", updates.getAllValues().get(0).getStatus());
        assertEquals("FAILED", updates.getAllValues().get(1).getStatus());
        assertEquals("RUNTIME_CLUSTER_AUTHORIZATION_REVOKED",
                updates.getAllValues().get(1).getPayloadJson().get("exceptionType"));
        verify(fixture.runRecordMapper, never()).insert(any(RunRecordEntity.class));
    }

    @Test
    void shouldCloseRunRecordWhenDispatchLinkCasFails() {
        Fixture fixture = fixture();
        DispatchTaskEntity queuedTask = queuedTask(1006L, 50L);
        when(fixture.dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(queuedTask));
        when(fixture.dispatchTaskMapper.selectById(1006L)).thenReturn(queuedTask);
        when(fixture.workerAuthorizationService.isProjectRuntimeClusterGrantEnabled("tenant-a", 10L, 50L))
                .thenReturn(true);
        when(fixture.workerAuthorizationService.isRuntimeClusterAuthorizedForProject("tenant-a", 10L, 50L))
                .thenReturn(true);
        when(fixture.dispatchTaskMapper.update(any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class)))
                .thenReturn(1, 0);
        doAnswer(invocation -> {
            RunRecordEntity record = invocation.getArgument(0);
            record.setId(2006L);
            return 1;
        }).when(fixture.runRecordMapper).insert(any(RunRecordEntity.class));
        when(fixture.runLogFileService.prepare(2006L)).thenReturn(
                new RunLogFileService.PreparedRunLog(2006L, "run-2006.log", Path.of("run-2006.log"), "UTF-8"));
        when(fixture.runRecordMapper.update(any(RunRecordEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(fixture.workerLeaseMapper.selectOne(any())).thenReturn(lease);

        fixture.runner.pollAndExecute();

        ArgumentCaptor<RunRecordEntity> runRecord = ArgumentCaptor.forClass(RunRecordEntity.class);
        ArgumentCaptor<LambdaUpdateWrapper<RunRecordEntity>> cas = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fixture.runRecordMapper).update(runRecord.capture(), cas.capture());
        assertEquals("FAILED", runRecord.getValue().getStatus());
        assertEquals("DISPATCH_RUN_RECORD_LINK_FAILED", runRecord.getValue().getPayloadJson().get("exceptionType"));
        assertTrue(cas.getValue().getSqlSegment().contains("worker_boot_id"));
    }

    @Test
    void shouldFenceOwnedTaskUpdatesByClaimTokenAndCurrentBootId() {
        Fixture fixture = fixture();
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setId(1002L);
        task.setClaimToken("claim-a");
        task.setStatus("SUCCESS");

        ReflectionTestUtils.invokeMethod(fixture.runner, "updateOwnedRunningTask", task);

        ArgumentCaptor<LambdaUpdateWrapper<DispatchTaskEntity>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fixture.dispatchTaskMapper).update(any(DispatchTaskEntity.class), update.capture());
        String conditions = update.getValue().getSqlSegment();
        assertTrue(conditions.contains("claim_token"));
        assertTrue(conditions.contains("worker_boot_id"));
        assertTrue(conditions.contains("worker_group_code"));
        assertFalse(conditions.contains("lease_owner"));
    }

    @Test
    void shouldRecoverPreviousBootTaskWithDispatchAndRunRecordCas() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        ExecutionEventPublisher publisher = mock(ExecutionEventPublisher.class);
        RunLogFileService runLogFileService = mock(RunLogFileService.class);
        ClusterInstanceIdentity identity = mock(ClusterInstanceIdentity.class);
        when(identity.instanceId()).thenReturn("instance-50");
        when(identity.bootId()).thenReturn("boot-new");
        when(identity.podName()).thenReturn("pod-50");
        when(identity.nodeName()).thenReturn("node-50");

        DispatchTaskEntity task = interruptedTask();
        RunRecordEntity runRecord = new RunRecordEntity();
        runRecord.setId(2001L);
        runRecord.setStatus("RUNNING");
        runRecord.setStartedAt(LocalDateTime.now().minusMinutes(3));
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(dispatchTaskMapper.update(any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(runRecordMapper.selectById(2001L)).thenReturn(runRecord);
        when(runRecordMapper.update(any(RunRecordEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(1);

        WorkerLifecycleRunner runner = enforcedRunner(dispatchTaskMapper, workerLeaseMapper, runRecordMapper,
                publisher, runLogFileService, identity);
        runner.recoverLeasedRunningTasks();

        ArgumentCaptor<LambdaQueryWrapper<DispatchTaskEntity>> recoveryQuery = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dispatchTaskMapper).selectList(recoveryQuery.capture());
        assertTrue(recoveryQuery.getValue().getSqlSegment().contains("worker_boot_id <>"));
        assertTrue(recoveryQuery.getValue().getSqlSegment().contains("target_cluster_id"));
        ArgumentCaptor<LambdaUpdateWrapper<DispatchTaskEntity>> dispatchCas = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(dispatchTaskMapper).update(any(DispatchTaskEntity.class), dispatchCas.capture());
        String dispatchConditions = dispatchCas.getValue().getSqlSegment();
        assertTrue(dispatchConditions.contains("status"));
        assertTrue(dispatchConditions.contains("claim_token"));
        assertTrue(dispatchConditions.contains("worker_boot_id"));
        assertTrue(dispatchConditions.contains("worker_group_code"));
        assertTrue(dispatchConditions.contains("lease_owner"));
        assertTrue(dispatchConditions.contains("worker_instance_id"));

        ArgumentCaptor<LambdaUpdateWrapper<RunRecordEntity>> runRecordCas = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runRecordMapper).update(any(RunRecordEntity.class), runRecordCas.capture());
        assertTrue(runRecordCas.getValue().getSqlSegment().contains("status"));
        verify(publisher).publish(any());
    }

    @Test
    void shouldSuppressRunRecordAndFailureEventWhenInterruptedTaskCasLosesRace() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        ExecutionEventPublisher publisher = mock(ExecutionEventPublisher.class);
        ClusterInstanceIdentity identity = mock(ClusterInstanceIdentity.class);
        when(identity.instanceId()).thenReturn("instance-50");
        when(identity.bootId()).thenReturn("boot-new");

        DispatchTaskEntity task = interruptedTask();
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(dispatchTaskMapper.update(any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(0);
        when(runRecordMapper.selectById(2001L)).thenReturn(new RunRecordEntity());

        WorkerLifecycleRunner runner = enforcedRunner(dispatchTaskMapper, mock(WorkerLeaseMapper.class), runRecordMapper,
                publisher, mock(RunLogFileService.class), identity);
        runner.recoverLeasedRunningTasks();

        verify(runRecordMapper, never()).update(any(RunRecordEntity.class), any(LambdaUpdateWrapper.class));
        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldSuppressFailureEventWhenRunRecordCasLosesRaceAfterDispatchRecovery() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        ExecutionEventPublisher publisher = mock(ExecutionEventPublisher.class);
        ClusterInstanceIdentity identity = mock(ClusterInstanceIdentity.class);
        when(identity.instanceId()).thenReturn("instance-50");
        when(identity.bootId()).thenReturn("boot-new");

        DispatchTaskEntity task = interruptedTask();
        RunRecordEntity runRecord = new RunRecordEntity();
        runRecord.setId(2001L);
        runRecord.setStatus("RUNNING");
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(dispatchTaskMapper.update(any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(runRecordMapper.selectById(2001L)).thenReturn(runRecord);
        when(runRecordMapper.update(any(RunRecordEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(0);

        WorkerLifecycleRunner runner = enforcedRunner(dispatchTaskMapper, mock(WorkerLeaseMapper.class), runRecordMapper,
                publisher, mock(RunLogFileService.class), identity);
        runner.recoverLeasedRunningTasks();

        verify(runRecordMapper).update(any(RunRecordEntity.class), any(LambdaUpdateWrapper.class));
        verify(publisher, never()).publish(any());
    }

    @Test
    void shouldWriteOneLeaseForEachTenantRuntimeCluster() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        ClusterInstanceIdentity identity = mock(ClusterInstanceIdentity.class);
        when(identity.instanceId()).thenReturn("instance-50");
        when(identity.bootId()).thenReturn("boot-50");

        WorkerLifecycleRunner runner = enforcedRunner(dispatchTaskMapper, workerLeaseMapper,
                mock(RunRecordMapper.class), mock(ExecutionEventPublisher.class),
                mock(RunLogFileService.class), identity);
        RuntimeClusterMapper runtimeClusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeClusterEntity tenantA = runtimeCluster(50L, "tenant-a");
        RuntimeClusterEntity tenantB = runtimeCluster(60L, "tenant-b");
        when(runtimeClusterMapper.selectList(any())).thenReturn(List.of(tenantA, tenantB));
        ReflectionTestUtils.invokeMethod(runner, "setRuntimeClusterMapper", runtimeClusterMapper);
        RuntimeClusterHeartbeatService heartbeatService = mock(RuntimeClusterHeartbeatService.class);
        ReflectionTestUtils.invokeMethod(runner, "setRuntimeClusterHeartbeatService", heartbeatService);
        when(workerLeaseMapper.selectOne(any())).thenReturn(null);

        runner.heartbeat();

        ArgumentCaptor<WorkerLeaseEntity> leases = ArgumentCaptor.forClass(WorkerLeaseEntity.class);
        verify(workerLeaseMapper, times(2)).insert(leases.capture());
        assertEquals(List.of("tenant-a", "tenant-b"), leases.getAllValues().stream()
                .map(WorkerLeaseEntity::getTenantId).toList());
        assertEquals(List.of(50L, 60L), leases.getAllValues().stream()
                .map(WorkerLeaseEntity::getRuntimeClusterId).toList());
        verify(heartbeatService).recordById(eq("tenant-a"), eq(50L), eq("instance-50"),
                any(), any(), any(), any());
        verify(heartbeatService).recordById(eq("tenant-b"), eq(60L), eq("instance-50"),
                any(), any(), any(), any());
    }

    private DispatchTaskEntity interruptedTask() {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setId(1003L);
        task.setStatus("RUNNING");
        task.setClaimToken("claim-old");
        task.setWorkerBootId("boot-old");
        task.setWorkerGroupCode("group-50");
        task.setLeaseOwner("group-50");
        task.setWorkerInstanceId("instance-50");
        task.setTargetClusterId(50L);
        task.setRunRecordId(2001L);
        task.setExecutionType("WORKFLOW_NODE");
        task.setCreatedAt(LocalDateTime.now().minusMinutes(3));
        return task;
    }

    private RuntimeClusterEntity runtimeCluster(Long id, String tenantId) {
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(id);
        cluster.setTenantId(tenantId);
        cluster.setCode("C50");
        cluster.setEnabled(1);
        return cluster;
    }

    private DispatchTaskEntity queuedTask(Long id, Long targetClusterId) {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setId(id);
        task.setTenantId("tenant-a");
        task.setProjectId(10L);
        task.setStatus("QUEUED");
        task.setExecutionType("WORKFLOW_NODE");
        task.setTargetClusterId(targetClusterId);
        task.setPayloadJson(new java.util.LinkedHashMap<String, Object>());
        return task;
    }

    private WorkerLifecycleRunner enforcedRunner(DispatchTaskMapper dispatchTaskMapper,
                                                  WorkerLeaseMapper workerLeaseMapper,
                                                  RunRecordMapper runRecordMapper,
                                                  ExecutionEventPublisher publisher,
                                                  RunLogFileService runLogFileService,
                                                  ClusterInstanceIdentity identity) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeClusterCode("C50");
        properties.setWorkerGroupCode("group-50");
        properties.setWorkerCode("worker-50");
        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(dispatchTaskMapper, workerLeaseMapper, runRecordMapper,
                Collections.<NodeExecutor>emptyList(), publisher, properties, mock(CollectionTaskService.class),
                mock(QualityTaskService.class), mock(CollectionTaskAssemblerService.class), runLogFileService,
                mock(WorkerAuthorizationService.class), identity, mock(WorkflowDispatchNodeResolver.class));
        RuntimeClusterMapper runtimeClusterMapper = mock(RuntimeClusterMapper.class);
        when(runtimeClusterMapper.selectList(any())).thenReturn(Collections.singletonList(runtimeCluster(50L, "tenant-a")));
        ReflectionTestUtils.invokeMethod(runner, "setRuntimeClusterMapper", runtimeClusterMapper);
        return runner;
    }

    private Fixture fixture() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeClusterCode("C50");
        properties.setWorkerGroupCode("group-50");
        properties.setWorkerCode("worker-50");
        properties.setInstanceId("instance-50");
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunLogFileService runLogFileService = mock(RunLogFileService.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        when(workerAuthorizationService.isProjectRuntimeClusterGrantEnabled(any(), any(), any())).thenReturn(true);
        when(workerAuthorizationService.isRuntimeClusterAuthorizedForProject(any(), any(), any())).thenReturn(true);
        WorkerLifecycleRunner runner = new WorkerLifecycleRunner(dispatchTaskMapper, workerLeaseMapper,
                runRecordMapper, Collections.<NodeExecutor>emptyList(), mock(ExecutionEventPublisher.class), properties,
                mock(CollectionTaskService.class), mock(QualityTaskService.class), mock(CollectionTaskAssemblerService.class),
                runLogFileService, workerAuthorizationService, new ClusterInstanceIdentity(properties),
                mock(WorkflowDispatchNodeResolver.class));
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeClusterEntity current = new RuntimeClusterEntity();
        current.setId(50L);
        current.setCode("C50");
        current.setEnabled(1);
        when(clusterMapper.selectList(any())).thenReturn(Collections.singletonList(current));
        ReflectionTestUtils.invokeMethod(runner, "setRuntimeClusterMapper", clusterMapper);
        ReflectionTestUtils.setField(runner, "acceptingTasks", true);
        return new Fixture(runner, dispatchTaskMapper, workerLeaseMapper, runRecordMapper,
                runLogFileService, workerAuthorizationService);
    }

    private static class Fixture {
        private final WorkerLifecycleRunner runner;
        private final DispatchTaskMapper dispatchTaskMapper;
        private final WorkerLeaseMapper workerLeaseMapper;
        private final RunRecordMapper runRecordMapper;
        private final RunLogFileService runLogFileService;
        private final WorkerAuthorizationService workerAuthorizationService;
        private Fixture(WorkerLifecycleRunner runner, DispatchTaskMapper dispatchTaskMapper,
                        WorkerLeaseMapper workerLeaseMapper, RunRecordMapper runRecordMapper,
                        RunLogFileService runLogFileService, WorkerAuthorizationService workerAuthorizationService) {
            this.runner = runner;
            this.dispatchTaskMapper = dispatchTaskMapper;
            this.workerLeaseMapper = workerLeaseMapper;
            this.runRecordMapper = runRecordMapper;
            this.runLogFileService = runLogFileService;
            this.workerAuthorizationService = workerAuthorizationService;
        }
    }
}
