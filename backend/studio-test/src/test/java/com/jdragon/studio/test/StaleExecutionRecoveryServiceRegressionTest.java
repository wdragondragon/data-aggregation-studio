package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.StaleExecutionRecoveryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaleExecutionRecoveryServiceRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(DispatchTaskEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DispatchTaskEntity.class);
        }
        if (TableInfoHelper.getTableInfo(RunRecordEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RunRecordEntity.class);
        }
        if (TableInfoHelper.getTableInfo(WorkerLeaseEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WorkerLeaseEntity.class);
        }
    }

    @Test
    void shouldKeepLongRunningTaskActiveWhenWorkerHeartbeatIsHealthy() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StaleExecutionRecoveryService service = service(dispatchTaskMapper, runRecordMapper, workerLeaseMapper);

        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setTenantId("default");
        task.setProjectId(1L);
        task.setWorkflowDefinitionId(10L);
        task.setStatus("RUNNING");
        task.setLeaseOwner("worker-a");
        task.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(30));
        task.setCreatedAt(LocalDateTime.now().minusHours(3));
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(runRecordMapper.selectList(any())).thenReturn(Collections.<RunRecordEntity>emptyList());

        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setTenantId("default");
        lease.setWorkerCode("worker-a");
        lease.setStatus("ONLINE");
        lease.setLastHeartbeatAt(LocalDateTime.now());
        when(workerLeaseMapper.selectOne(any())).thenReturn(lease);

        assertTrue(service.hasActiveWorkflowRun("default", 1L, 10L));
        service.recoverWorkflow("default", 1L, 10L);

        verify(dispatchTaskMapper, never()).update(any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class));
        verify(runRecordMapper, never()).update(any(RunRecordEntity.class), any(LambdaUpdateWrapper.class));
    }

    @Test
    void shouldFailRunningRecordWhenWorkerIsOfflinePastGrace() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StaleExecutionRecoveryService service = service(dispatchTaskMapper, runRecordMapper, workerLeaseMapper);

        RunRecordEntity record = new RunRecordEntity();
        record.setId(100L);
        record.setTenantId("default");
        record.setProjectId(1L);
        record.setWorkflowDefinitionId(10L);
        record.setWorkflowRunId(1000L);
        record.setNodeCode("A");
        record.setStatus("RUNNING");
        record.setWorkerCode("worker-a");
        record.setStartedAt(LocalDateTime.now().minusHours(3));

        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.<DispatchTaskEntity>emptyList());
        when(runRecordMapper.selectList(any())).thenReturn(Collections.singletonList(record));
        when(runRecordMapper.selectCount(any())).thenReturn(0L);
        when(runRecordMapper.update(any(RunRecordEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(workerLeaseMapper.selectOne(any())).thenReturn(null);

        service.recoverWorkflow("default", 1L, 10L);

        ArgumentCaptor<RunRecordEntity> captor = ArgumentCaptor.forClass(RunRecordEntity.class);
        verify(runRecordMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        RunRecordEntity updated = captor.getValue();
        assertTrue("FAILED".equalsIgnoreCase(updated.getStatus()));
        assertTrue(updated.getMessage().contains("worker heartbeat is offline"));
        assertTrue(Boolean.TRUE.equals(updated.getPayloadJson().get("recovered")));
    }

    @Test
    void shouldIgnoreOldRunningRecordWhenLaterTerminalRecordExistsForSameNode() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StaleExecutionRecoveryService service = service(dispatchTaskMapper, runRecordMapper, workerLeaseMapper);

        RunRecordEntity record = new RunRecordEntity();
        record.setId(100L);
        record.setTenantId("default");
        record.setProjectId(1L);
        record.setWorkflowDefinitionId(10L);
        record.setWorkflowRunId(1000L);
        record.setNodeCode("A");
        record.setStatus("RUNNING");
        record.setCreatedAt(LocalDateTime.now().minusHours(3));

        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.<DispatchTaskEntity>emptyList());
        when(runRecordMapper.selectList(any())).thenReturn(Collections.singletonList(record));
        when(runRecordMapper.selectCount(any())).thenReturn(1L);
        when(runRecordMapper.update(any(RunRecordEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertFalse(service.hasActiveWorkflowRun("default", 1L, 10L));
        service.recoverWorkflow("default", 1L, 10L);

        ArgumentCaptor<RunRecordEntity> captor = ArgumentCaptor.forClass(RunRecordEntity.class);
        verify(runRecordMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        RunRecordEntity updated = captor.getValue();
        assertTrue("FAILED".equalsIgnoreCase(updated.getStatus()));
        assertTrue(updated.getMessage().contains("later terminal record exists"));
    }

    @Test
    void shouldNotTreatNewBootLeaseAsActiveForOldBootDispatchTask() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StaleExecutionRecoveryService service = service(dispatchTaskMapper, runRecordMapper, workerLeaseMapper);

        DispatchTaskEntity task = staleDispatchTask();
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(runRecordMapper.selectList(any())).thenReturn(Collections.<RunRecordEntity>emptyList());

        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setWorkerGroupCode("worker-a");
        lease.setInstanceId("instance-a");
        lease.setBootId("boot-new");
        lease.setStatus("ONLINE");
        lease.setLastHeartbeatAt(LocalDateTime.now());
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(workerLeaseMapper.selectOne(any())).thenReturn(lease);

        assertFalse(service.hasActiveWorkflowRun("default", 1L, 10L));
    }

    @Test
    void shouldNotTreatNewBootLeaseAsActiveForOldBootRunRecord() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StaleExecutionRecoveryService service = service(dispatchTaskMapper, runRecordMapper, workerLeaseMapper);

        RunRecordEntity record = new RunRecordEntity();
        record.setId(101L);
        record.setTenantId("default");
        record.setProjectId(1L);
        record.setWorkflowDefinitionId(10L);
        record.setWorkflowRunId(1000L);
        record.setNodeCode("A");
        record.setStatus("RUNNING");
        record.setWorkerGroupCode("worker-a");
        record.setWorkerInstanceId("instance-a");
        record.setWorkerBootId("boot-old");
        record.setStartedAt(LocalDateTime.now().minusHours(3));

        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.<DispatchTaskEntity>emptyList());
        when(dispatchTaskMapper.selectCount(any())).thenReturn(0L);
        when(runRecordMapper.selectList(any())).thenReturn(Collections.singletonList(record));
        when(runRecordMapper.selectCount(any())).thenReturn(0L);

        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setWorkerGroupCode("worker-a");
        lease.setInstanceId("instance-a");
        lease.setBootId("boot-new");
        lease.setStatus("ONLINE");
        lease.setLastHeartbeatAt(LocalDateTime.now());
        lease.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(workerLeaseMapper.selectOne(any())).thenReturn(lease);

        assertFalse(service.hasActiveWorkflowRun("default", 1L, 10L));

        ArgumentCaptor<LambdaQueryWrapper<WorkerLeaseEntity>> leaseQuery =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workerLeaseMapper).selectOne(leaseQuery.capture());
        assertTrue(leaseQuery.getValue().getSqlSegment().contains("boot_id"));
    }

    @Test
    void shouldMatchActiveDispatchByRunRecordIdOrWorkflowNodeFallback() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        StaleExecutionRecoveryService service = service(
                dispatchTaskMapper, mock(RunRecordMapper.class), mock(WorkerLeaseMapper.class));
        RunRecordEntity record = new RunRecordEntity();
        record.setId(102L);
        record.setTenantId("default");
        record.setProjectId(1L);
        record.setWorkflowRunId(1000L);
        record.setNodeCode("A");
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.<DispatchTaskEntity>emptyList());

        Boolean active = ReflectionTestUtils.invokeMethod(service, "hasActiveDispatchForRecord",
                record, LocalDateTime.now());

        assertFalse(Boolean.TRUE.equals(active));
        ArgumentCaptor<LambdaQueryWrapper<DispatchTaskEntity>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dispatchTaskMapper).selectList(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("run_record_id"));
        assertTrue(sql.contains("workflow_run_id"));
        assertTrue(sql.contains("node_code"));
        assertTrue(sql.contains("OR"));
    }

    @Test
    void shouldFenceStaleDispatchFailureAndSkipLinkedRecordWhenCasLosesRace() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StaleExecutionRecoveryService service = service(dispatchTaskMapper, runRecordMapper, workerLeaseMapper);

        DispatchTaskEntity task = staleDispatchTask();
        task.setRunRecordId(300L);
        task.setProtectedPayloadCiphertext("ENC(stale-input)");
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(runRecordMapper.selectList(any())).thenReturn(Collections.<RunRecordEntity>emptyList());
        when(workerLeaseMapper.selectOne(any())).thenReturn(null);
        when(dispatchTaskMapper.update(any(DispatchTaskEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(0);

        service.recoverWorkflow("default", 1L, 10L);

        ArgumentCaptor<LambdaUpdateWrapper<DispatchTaskEntity>> cas = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(dispatchTaskMapper).update(any(DispatchTaskEntity.class), cas.capture());
        String conditions = cas.getValue().getSqlSegment();
        assertTrue(conditions.contains("status"));
        assertTrue(conditions.contains("claim_token"));
        assertTrue(conditions.contains("worker_boot_id"));
        assertTrue(conditions.contains("lease_expires_at"));
        assertTrue(cas.getValue().getSqlSet().contains("protected_payload_ciphertext"));
        verify(runRecordMapper, never()).selectById(300L);
    }

    @Test
    void shouldFenceRunRecordFailureByRunningStatus() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StaleExecutionRecoveryService service = service(dispatchTaskMapper, runRecordMapper, workerLeaseMapper);

        RunRecordEntity record = new RunRecordEntity();
        record.setId(400L);
        record.setTenantId("default");
        record.setProjectId(1L);
        record.setWorkflowDefinitionId(10L);
        record.setStatus("RUNNING");
        record.setStartedAt(LocalDateTime.now().minusHours(3));
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.<DispatchTaskEntity>emptyList());
        when(runRecordMapper.selectList(any())).thenReturn(Collections.singletonList(record));
        when(runRecordMapper.update(any(RunRecordEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(0);

        service.recoverWorkflow("default", 1L, 10L);

        ArgumentCaptor<LambdaUpdateWrapper<RunRecordEntity>> cas = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runRecordMapper).update(any(RunRecordEntity.class), cas.capture());
        assertTrue(cas.getValue().getSqlSegment().contains("status"));
        assertTrue("RUNNING".equalsIgnoreCase(record.getStatus()));
    }

    private DispatchTaskEntity staleDispatchTask() {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setId(200L);
        task.setTenantId("default");
        task.setProjectId(1L);
        task.setWorkflowDefinitionId(10L);
        task.setStatus("RUNNING");
        task.setClaimToken("claim-old");
        task.setWorkerBootId("boot-old");
        task.setWorkerGroupCode("worker-a");
        task.setLeaseOwner("worker-a");
        task.setWorkerInstanceId("instance-a");
        task.setLeaseExpiresAt(LocalDateTime.now().minusHours(3));
        task.setCreatedAt(LocalDateTime.now().minusHours(4));
        return task;
    }

    private StaleExecutionRecoveryService service(DispatchTaskMapper dispatchTaskMapper,
                                                  RunRecordMapper runRecordMapper,
                                                  WorkerLeaseMapper workerLeaseMapper) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getDispatch().setWorkerOfflineGraceMinutes(120L);
        return new StaleExecutionRecoveryService(dispatchTaskMapper, runRecordMapper, workerLeaseMapper, properties);
    }
}
