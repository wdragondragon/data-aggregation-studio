package com.jdragon.studio.test;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.WorkflowScheduleEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.DispatchTriggerStatus;
import com.jdragon.studio.infra.service.StudioAccessService;
import com.jdragon.studio.infra.service.StudioExecutionContextService;
import com.jdragon.studio.infra.service.WorkflowService;
import com.jdragon.studio.server.web.scheduler.CronScheduleDueEvaluator;
import com.jdragon.studio.server.web.scheduler.WorkflowScheduleRunner;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowScheduleRunnerRegressionTest {

    @AfterEach
    void clearContext() {
        StudioRequestContextHolder.clear();
    }

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
        schedule.setTenantId("tenant-a");
        schedule.setProjectId(501L);

        WorkflowDefinitionEntity definition = new WorkflowDefinitionEntity();
        definition.setId(10L);
        definition.setTenantId("tenant-a");
        definition.setProjectId(501L);
        definition.setCreatedBy(77L);
        definition.setPublished(1);

        WorkflowDefinitionView workflow = new WorkflowDefinitionView();
        workflow.setId(10L);
        workflow.setPublished(true);

        when(workflowService.findEnabledSchedules()).thenReturn(Collections.singletonList(schedule));
        when(workflowService.findScheduledDefinition(10L, "tenant-a", 501L)).thenReturn(definition);
        when(workflowService.get(10L)).thenReturn(workflow);
        StudioExecutionContextService executionContextService = executionContextService(77L,
                "tenant-a", 501L, "PROJECT_ADMIN");
        doAnswer(invocation -> {
            org.junit.jupiter.api.Assertions.assertEquals(77L,
                    StudioRequestContextHolder.getContext().getUserId());
            org.junit.jupiter.api.Assertions.assertEquals(List.of("PROJECT_ADMIN"),
                    StudioRequestContextHolder.getContext().getEffectiveRoleCodes());
            return DispatchTriggerStatus.TRIGGERED;
        }).when(dispatchService).triggerScheduledWorkflowIfIdle(eq(10L), any(LocalDateTime.class));
        ClusterLockService clusterLockService = executableClusterLock("scheduler:workflow");

        WorkflowScheduleRunner runner = new WorkflowScheduleRunner(workflowService, dispatchService, evaluator,
                clusterLockService, executionContextService, new StudioPlatformProperties());
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
        schedule.setTenantId("tenant-a");
        schedule.setProjectId(501L);

        WorkflowDefinitionEntity definition = new WorkflowDefinitionEntity();
        definition.setId(20L);
        definition.setTenantId("tenant-a");
        definition.setProjectId(501L);
        definition.setCreatedBy(77L);
        definition.setPublished(0);

        when(workflowService.findEnabledSchedules()).thenReturn(Collections.singletonList(schedule));
        when(workflowService.findScheduledDefinition(20L, "tenant-a", 501L)).thenReturn(definition);

        WorkflowScheduleRunner runner = new WorkflowScheduleRunner(workflowService, dispatchService, evaluator,
                executableClusterLock("scheduler:workflow"), executionContextService(77L,
                        "tenant-a", 501L, "PROJECT_ADMIN"), new StudioPlatformProperties());
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

    private StudioExecutionContextService executionContextService(Long userId, String tenantId,
                                                                   Long projectId, String role) {
        StudioAccessService accessService = mock(StudioAccessService.class);
        StudioRequestContext context = new StudioRequestContext();
        context.setUserId(userId);
        context.setTenantId(tenantId);
        context.setProjectId(projectId);
        context.setEffectiveRoleCodes(List.of(role));
        when(accessService.buildExecutionContext(userId, tenantId, projectId)).thenReturn(context);
        return new StudioExecutionContextService(accessService);
    }
}
