package com.jdragon.studio.test;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.WorkflowScheduleEntity;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.DispatchTriggerStatus;
import com.jdragon.studio.infra.service.WorkflowService;
import com.jdragon.studio.server.web.scheduler.CronScheduleDueEvaluator;
import com.jdragon.studio.server.web.scheduler.WorkflowScheduleRunner;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowScheduleRunnerRegressionTest {

    @Test
    void shouldTriggerPublishedWorkflowWhenSevenPartCronIsDue() {
        WorkflowService workflowService = mock(WorkflowService.class);
        DispatchService dispatchService = mock(DispatchService.class);
        CronScheduleDueEvaluator evaluator = new CronScheduleDueEvaluator();

        WorkflowScheduleEntity schedule = new WorkflowScheduleEntity();
        schedule.setWorkflowDefinitionId(10L);
        schedule.setCronExpression("0/1 * * * * ? *");
        schedule.setEnabled(1);
        schedule.setTimezone("Asia/Shanghai");
        schedule.setLastTriggeredAt(LocalDateTime.now().minusSeconds(3));

        WorkflowDefinitionView workflow = new WorkflowDefinitionView();
        workflow.setId(10L);
        workflow.setPublished(true);

        when(workflowService.findEnabledSchedules()).thenReturn(Collections.singletonList(schedule));
        when(workflowService.get(10L)).thenReturn(workflow);
        when(dispatchService.triggerScheduledWorkflowIfIdle(eq(10L), any(LocalDateTime.class)))
                .thenReturn(DispatchTriggerStatus.TRIGGERED);
        ClusterLockService clusterLockService = executableClusterLock("scheduler:workflow");

        WorkflowScheduleRunner runner = new WorkflowScheduleRunner(workflowService, dispatchService, evaluator,
                clusterLockService, new StudioPlatformProperties());
        runner.dispatchDueWorkflows();

        verify(dispatchService).triggerScheduledWorkflowIfIdle(eq(10L), any(LocalDateTime.class));
        verify(workflowService).markScheduleTriggered(eq(10L), any(LocalDateTime.class));
    }

    @Test
    void shouldSkipUnpublishedWorkflowEvenWhenScheduleIsDue() {
        WorkflowService workflowService = mock(WorkflowService.class);
        DispatchService dispatchService = mock(DispatchService.class);
        CronScheduleDueEvaluator evaluator = new CronScheduleDueEvaluator();

        WorkflowScheduleEntity schedule = new WorkflowScheduleEntity();
        schedule.setWorkflowDefinitionId(20L);
        schedule.setCronExpression("0/1 * * * * ? *");
        schedule.setEnabled(1);
        schedule.setTimezone("Asia/Shanghai");
        schedule.setLastTriggeredAt(LocalDateTime.now().minusSeconds(3));

        WorkflowDefinitionView workflow = new WorkflowDefinitionView();
        workflow.setId(20L);
        workflow.setPublished(false);

        when(workflowService.findEnabledSchedules()).thenReturn(Collections.singletonList(schedule));
        when(workflowService.get(20L)).thenReturn(workflow);

        WorkflowScheduleRunner runner = new WorkflowScheduleRunner(workflowService, dispatchService, evaluator,
                executableClusterLock("scheduler:workflow"), new StudioPlatformProperties());
        runner.dispatchDueWorkflows();

        verify(dispatchService, never()).triggerScheduledWorkflowIfIdle(any(Long.class), any(LocalDateTime.class));
        verify(workflowService, never()).markScheduleTriggered(any(Long.class), any(LocalDateTime.class));
    }

    private ClusterLockService executableClusterLock(String lockName) {
        ClusterLockService clusterLockService = mock(ClusterLockService.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(clusterLockService).runIfAcquired(eq(lockName), any(Runnable.class));
        return clusterLockService;
    }
}
