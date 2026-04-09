package com.jdragon.studio.test;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.StudioSecurityService;
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

        when(dispatchTaskMapper.selectCount(any())).thenReturn(1L);

        DispatchService dispatchService = new DispatchService(
                dispatchTaskMapper,
                runRecordMapper,
                mock(WorkflowDefinitionMapper.class),
                mock(WorkflowService.class),
                mock(CollectionTaskService.class),
                mock(StudioSecurityService.class),
                mock(WorkerAuthorizationService.class)
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

        when(dispatchTaskMapper.selectCount(any())).thenReturn(0L);
        when(runRecordMapper.selectCount(any())).thenReturn(1L);

        DispatchService dispatchService = new DispatchService(
                dispatchTaskMapper,
                runRecordMapper,
                mock(WorkflowDefinitionMapper.class),
                mock(WorkflowService.class),
                mock(CollectionTaskService.class),
                mock(StudioSecurityService.class),
                mock(WorkerAuthorizationService.class)
        );

        assertThatThrownBy(() -> dispatchService.triggerCollectionTask(200L))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Collection task already has an active run");

        verify(dispatchTaskMapper, never()).insert(any(DispatchTaskEntity.class));
    }
}
