package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.QualityTaskDefinitionView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.QualityTaskScheduleEntity;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.DispatchTriggerStatus;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.server.web.scheduler.CronScheduleDueEvaluator;
import com.jdragon.studio.server.web.scheduler.QualityTaskScheduleRunner;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityTaskScheduleRunnerRegressionTest {

    @Test
    void shouldMarkScheduleTriggeredWhenDueTaskIsSkippedBecausePreviousInstanceIsStillActive() {
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        DispatchService dispatchService = mock(DispatchService.class);
        WorkerAuthorizationService workerAuthorizationService = mock(WorkerAuthorizationService.class);
        CronScheduleDueEvaluator evaluator = new CronScheduleDueEvaluator();

        QualityTaskScheduleEntity schedule = new QualityTaskScheduleEntity();
        schedule.setQualityTaskId(11L);
        schedule.setTenantId("default");
        schedule.setProjectId(110L);
        schedule.setCronExpression("0/1 * * * * ? *");
        schedule.setEnabled(1);
        schedule.setTimezone("Asia/Shanghai");
        schedule.setLastTriggeredAt(LocalDateTime.now().minusSeconds(3));

        QualityTaskDefinitionView definition = new QualityTaskDefinitionView();
        definition.setId(11L);
        definition.setTenantId("default");
        definition.setProjectId(110L);

        when(qualityTaskService.findEnabledSchedules()).thenReturn(Collections.singletonList(schedule));
        when(qualityTaskService.requireOnline(11L)).thenReturn(definition);
        when(workerAuthorizationService.hasAvailableWorker("default", 110L)).thenReturn(true);
        when(dispatchService.triggerScheduledQualityTaskIfIdle(eq(11L), any(LocalDateTime.class)))
                .thenReturn(DispatchTriggerStatus.SKIPPED_ACTIVE);
        ClusterLockService clusterLockService = mock(ClusterLockService.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(clusterLockService).runIfAcquired(eq("scheduler:quality-task"), any(Runnable.class));

        QualityTaskScheduleRunner runner = new QualityTaskScheduleRunner(
                qualityTaskService,
                dispatchService,
                evaluator,
                workerAuthorizationService,
                clusterLockService,
                new StudioPlatformProperties());
        runner.dispatchDueQualityTasks();

        verify(dispatchService).triggerScheduledQualityTaskIfIdle(eq(11L), any(LocalDateTime.class));
        verify(qualityTaskService).markScheduleTriggered(eq(11L), any(LocalDateTime.class));
    }
}
