package com.jdragon.studio.test;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.WorkflowService;
import com.jdragon.studio.server.web.controller.CollectionTaskController;
import com.jdragon.studio.server.web.controller.QualityTaskController;
import com.jdragon.studio.server.web.controller.WorkflowController;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ManualTriggerResponseSlimmingRegressionTest {

    @Test
    void workflowTriggerShouldNotLoadFullWorkflowDetailForUnusedResponseBody() {
        WorkflowService workflowService = mock(WorkflowService.class);
        DispatchService dispatchService = mock(DispatchService.class);
        WorkflowController controller = new WorkflowController(workflowService, dispatchService);

        Result<Void> result = controller.trigger(1001L);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNull();
        verify(dispatchService).triggerManualRun(1001L);
        verify(workflowService, never()).get(1001L);
    }

    @Test
    void collectionTaskTriggerShouldNotLoadFullTaskDetailForUnusedResponseBody() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        DispatchService dispatchService = mock(DispatchService.class);
        CollectionTaskController controller = new CollectionTaskController(collectionTaskService, dispatchService);

        Result<Void> result = controller.trigger(2001L);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNull();
        verify(dispatchService).triggerCollectionTask(2001L);
        verify(collectionTaskService, never()).get(2001L);
    }

    @Test
    void qualityTaskTriggerShouldNotLoadFullTaskDetailForUnusedResponseBody() {
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        DispatchService dispatchService = mock(DispatchService.class);
        QualityTaskController controller = new QualityTaskController(qualityTaskService, dispatchService);

        Result<Void> result = controller.trigger(3001L);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNull();
        verify(dispatchService).triggerQualityTask(3001L);
        verify(qualityTaskService, never()).get(3001L);
    }
}
