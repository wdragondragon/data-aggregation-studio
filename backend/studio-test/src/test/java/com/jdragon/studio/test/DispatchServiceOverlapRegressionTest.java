package com.jdragon.studio.test;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.QualityTaskStatus;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.QualityTaskDefinitionView;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.StaleExecutionRecoveryService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.infra.service.WorkflowService;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatchServiceOverlapRegressionTest {

    @Test
    void shouldRejectWorkflowTriggerWhenPreviousRunIsStillActive() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        StaleExecutionRecoveryService staleExecutionRecoveryService = mock(StaleExecutionRecoveryService.class);
        WorkflowDefinitionView workflow = new WorkflowDefinitionView();
        workflow.setId(100L);
        workflow.setTenantId("default");
        workflow.setProjectId(1000L);

        when(workflowService.get(100L)).thenReturn(workflow);
        when(securityService.currentProjectId()).thenReturn(null);
        when(workerAuthorizationService.hasAvailableWorker("default", 1000L)).thenReturn(true);
        when(staleExecutionRecoveryService.hasActiveWorkflowRun("default", 1000L, 100L)).thenReturn(true);

        DispatchService dispatchService = new DispatchService(
                dispatchTaskMapper,
                runRecordMapper,
                mock(WorkflowDefinitionMapper.class),
                workflowService,
                mock(CollectionTaskService.class),
                mock(QualityTaskService.class),
                securityService,
                workerAuthorizationService,
                staleExecutionRecoveryService,
                executableClusterLock("dispatch:workflow:default:1000:100:manual")
        );

        assertThatThrownBy(() -> dispatchService.triggerManualRun(100L))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Workflow already has an active run");

        verify(dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }

    @Test
    void shouldRejectWorkflowTriggerWhenManualTriggerLockIsBusy() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        StaleExecutionRecoveryService staleExecutionRecoveryService = mock(StaleExecutionRecoveryService.class);
        WorkflowDefinitionView workflow = new WorkflowDefinitionView();
        workflow.setId(100L);
        workflow.setTenantId("default");
        workflow.setProjectId(1000L);

        when(workflowService.get(100L)).thenReturn(workflow);
        when(securityService.currentProjectId()).thenReturn(null);
        when(workerAuthorizationService.hasAvailableWorker("default", 1000L)).thenReturn(true);

        DispatchService dispatchService = new DispatchService(
                dispatchTaskMapper,
                runRecordMapper,
                mock(WorkflowDefinitionMapper.class),
                workflowService,
                mock(CollectionTaskService.class),
                mock(QualityTaskService.class),
                securityService,
                workerAuthorizationService,
                staleExecutionRecoveryService,
                busyClusterLock("dispatch:workflow:default:1000:100:manual")
        );

        assertThatThrownBy(() -> dispatchService.triggerManualRun(100L))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Workflow already has an active run");

        verify(dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }

    @Test
    void shouldRejectCollectionTaskTriggerWhenPreviousRunIsStillActive() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        StaleExecutionRecoveryService staleExecutionRecoveryService = mock(StaleExecutionRecoveryService.class);
        CollectionTaskDefinitionView definition = collectionTaskDefinition();

        when(collectionTaskService.requireOnline(200L)).thenReturn(definition);
        when(securityService.currentProjectId()).thenReturn(null);
        when(workerAuthorizationService.hasAvailableWorker("default", 2000L)).thenReturn(true);
        when(staleExecutionRecoveryService.hasActiveCollectionTaskRun("default", 2000L, 200L)).thenReturn(true);

        DispatchService dispatchService = new DispatchService(
                dispatchTaskMapper,
                runRecordMapper,
                mock(WorkflowDefinitionMapper.class),
                mock(WorkflowService.class),
                collectionTaskService,
                mock(QualityTaskService.class),
                securityService,
                workerAuthorizationService,
                staleExecutionRecoveryService,
                executableClusterLock("dispatch:collection:default:2000:200:manual")
        );

        assertThatThrownBy(() -> dispatchService.triggerCollectionTask(200L))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Collection task already has an active run");

        verify(dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }

    @Test
    void shouldRejectCollectionTaskTriggerWhenManualTriggerLockIsBusy() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        StaleExecutionRecoveryService staleExecutionRecoveryService = mock(StaleExecutionRecoveryService.class);
        CollectionTaskDefinitionView definition = collectionTaskDefinition();

        when(collectionTaskService.requireOnline(200L)).thenReturn(definition);
        when(securityService.currentProjectId()).thenReturn(null);
        when(workerAuthorizationService.hasAvailableWorker("default", 2000L)).thenReturn(true);

        DispatchService dispatchService = new DispatchService(
                dispatchTaskMapper,
                runRecordMapper,
                mock(WorkflowDefinitionMapper.class),
                mock(WorkflowService.class),
                collectionTaskService,
                mock(QualityTaskService.class),
                securityService,
                workerAuthorizationService,
                staleExecutionRecoveryService,
                busyClusterLock("dispatch:collection:default:2000:200:manual")
        );

        assertThatThrownBy(() -> dispatchService.triggerCollectionTask(200L))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Collection task already has an active run");

        verify(dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }

    @Test
    void shouldRejectQualityTaskTriggerWhenPreviousRunIsStillActive() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        StaleExecutionRecoveryService staleExecutionRecoveryService = mock(StaleExecutionRecoveryService.class);
        QualityTaskDefinitionView definition = qualityTaskDefinition();

        when(qualityTaskService.requireOnline(300L)).thenReturn(definition);
        when(securityService.currentProjectId()).thenReturn(null);
        when(workerAuthorizationService.hasAvailableWorker("default", 3000L)).thenReturn(true);
        when(staleExecutionRecoveryService.hasActiveQualityTaskRun("default", 3000L, 300L)).thenReturn(true);

        DispatchService dispatchService = new DispatchService(
                dispatchTaskMapper,
                runRecordMapper,
                mock(WorkflowDefinitionMapper.class),
                mock(WorkflowService.class),
                mock(CollectionTaskService.class),
                qualityTaskService,
                securityService,
                workerAuthorizationService,
                staleExecutionRecoveryService,
                executableClusterLock("dispatch:quality:default:3000:300:manual")
        );

        assertThatThrownBy(() -> dispatchService.triggerQualityTask(300L))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Quality task already has an active run");

        verify(dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }

    @Test
    void shouldRejectQualityTaskTriggerWhenManualTriggerLockIsBusy() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        StaleExecutionRecoveryService staleExecutionRecoveryService = mock(StaleExecutionRecoveryService.class);
        QualityTaskDefinitionView definition = qualityTaskDefinition();

        when(qualityTaskService.requireOnline(300L)).thenReturn(definition);
        when(securityService.currentProjectId()).thenReturn(null);
        when(workerAuthorizationService.hasAvailableWorker("default", 3000L)).thenReturn(true);

        DispatchService dispatchService = new DispatchService(
                dispatchTaskMapper,
                runRecordMapper,
                mock(WorkflowDefinitionMapper.class),
                mock(WorkflowService.class),
                mock(CollectionTaskService.class),
                qualityTaskService,
                securityService,
                workerAuthorizationService,
                staleExecutionRecoveryService,
                busyClusterLock("dispatch:quality:default:3000:300:manual")
        );

        assertThatThrownBy(() -> dispatchService.triggerQualityTask(300L))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Quality task already has an active run");

        verify(dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }

    private CollectionTaskDefinitionView collectionTaskDefinition() {
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setId(200L);
        definition.setTenantId("default");
        definition.setProjectId(2000L);
        definition.setStatus(CollectionTaskStatus.ONLINE);
        return definition;
    }

    private QualityTaskDefinitionView qualityTaskDefinition() {
        QualityTaskDefinitionView definition = new QualityTaskDefinitionView();
        definition.setId(300L);
        definition.setTenantId("default");
        definition.setProjectId(3000L);
        definition.setStatus(QualityTaskStatus.ONLINE);
        return definition;
    }

    private ClusterLockService executableClusterLock(String lockName) {
        ClusterLockService clusterLockService = mock(ClusterLockService.class);
        doAnswer(invocation -> invocation.getArgument(3, Supplier.class).get())
                .when(clusterLockService)
                .executeIfAcquiredNonReentrant(eq(lockName), eq(10L), eq(false),
                        any(Supplier.class), any(Supplier.class));
        return clusterLockService;
    }

    private ClusterLockService busyClusterLock(String lockName) {
        ClusterLockService clusterLockService = mock(ClusterLockService.class);
        doAnswer(invocation -> invocation.getArgument(4, Supplier.class).get())
                .when(clusterLockService)
                .executeIfAcquiredNonReentrant(eq(lockName), eq(10L), eq(false),
                        any(Supplier.class), any(Supplier.class));
        return clusterLockService;
    }
}
