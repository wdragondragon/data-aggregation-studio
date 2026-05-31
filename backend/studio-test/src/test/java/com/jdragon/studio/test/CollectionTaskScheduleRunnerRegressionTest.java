package com.jdragon.studio.test;

import com.jdragon.studio.infra.entity.CollectionTaskScheduleEntity;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.DispatchTriggerStatus;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.server.web.scheduler.CollectionTaskScheduleRunner;
import com.jdragon.studio.server.web.scheduler.CronScheduleDueEvaluator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionTaskScheduleRunnerRegressionTest {

    @Test
    void shouldMarkScheduleTriggeredWhenDueTaskIsSkippedBecausePreviousInstanceIsStillActive() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        DispatchService dispatchService = mock(DispatchService.class);
        CronScheduleDueEvaluator evaluator = new CronScheduleDueEvaluator();

        CollectionTaskScheduleEntity schedule = new CollectionTaskScheduleEntity();
        schedule.setCollectionTaskId(10L);
        schedule.setTenantId("default");
        schedule.setProjectId(100L);
        schedule.setCronExpression("0/1 * * * * ? *");
        schedule.setEnabled(1);
        schedule.setTimezone("Asia/Shanghai");
        schedule.setLastTriggeredAt(LocalDateTime.now().minusSeconds(3));

        when(collectionTaskService.findEnabledSchedules()).thenReturn(Collections.singletonList(schedule));
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        when(workerAuthorizationService.hasAvailableWorker("default", 100L)).thenReturn(true);
        when(dispatchService.triggerScheduledCollectionTaskIfIdle(eq(10L), any(LocalDateTime.class)))
                .thenReturn(DispatchTriggerStatus.SKIPPED_ACTIVE);
        ClusterLockService clusterLockService = mock(ClusterLockService.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(clusterLockService).runIfAcquired(eq("scheduler:collection-task"), any(Runnable.class));

        CollectionTaskScheduleRunner runner = new CollectionTaskScheduleRunner(
                collectionTaskService,
                dispatchService,
                evaluator,
                workerAuthorizationService,
                clusterLockService,
                new StudioPlatformProperties());
        runner.dispatchDueCollectionTasks();

        verify(dispatchService).triggerScheduledCollectionTaskIfIdle(eq(10L), any(LocalDateTime.class));
        verify(collectionTaskService).markScheduleTriggered(eq(10L), any(LocalDateTime.class));
    }
}
