package com.jdragon.studio.test;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.StaleExecutionRecoveryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

        verify(dispatchTaskMapper, never()).updateById(any(DispatchTaskEntity.class));
        verify(runRecordMapper, never()).updateById(any(RunRecordEntity.class));
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
        when(workerLeaseMapper.selectOne(any())).thenReturn(null);

        service.recoverWorkflow("default", 1L, 10L);

        ArgumentCaptor<RunRecordEntity> captor = ArgumentCaptor.forClass(RunRecordEntity.class);
        verify(runRecordMapper).updateById(captor.capture());
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

        assertFalse(service.hasActiveWorkflowRun("default", 1L, 10L));
        service.recoverWorkflow("default", 1L, 10L);

        ArgumentCaptor<RunRecordEntity> captor = ArgumentCaptor.forClass(RunRecordEntity.class);
        verify(runRecordMapper).updateById(captor.capture());
        RunRecordEntity updated = captor.getValue();
        assertTrue("FAILED".equalsIgnoreCase(updated.getStatus()));
        assertTrue(updated.getMessage().contains("later terminal record exists"));
    }

    private StaleExecutionRecoveryService service(DispatchTaskMapper dispatchTaskMapper,
                                                  RunRecordMapper runRecordMapper,
                                                  WorkerLeaseMapper workerLeaseMapper) {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getDispatch().setWorkerOfflineGraceMinutes(120L);
        return new StaleExecutionRecoveryService(dispatchTaskMapper, runRecordMapper, workerLeaseMapper, properties);
    }
}
