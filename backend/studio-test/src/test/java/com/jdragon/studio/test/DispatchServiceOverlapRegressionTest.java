package com.jdragon.studio.test;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.StaleExecutionRecoveryService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.infra.service.WorkflowService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
                staleExecutionRecoveryService
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
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setId(200L);
        definition.setTenantId("default");
        definition.setProjectId(2000L);
        definition.setStatus(CollectionTaskStatus.ONLINE);

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
                staleExecutionRecoveryService
        );

        assertThatThrownBy(() -> dispatchService.triggerCollectionTask(200L))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Collection task already has an active run");

        verify(dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }
}
