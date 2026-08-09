package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.FileTransferTaskDefinitionEntity;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.FileTransferRunService;
import com.jdragon.studio.infra.service.FileTransferTaskService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferTaskScheduleRunnerTest {

    @AfterEach
    void clearContext() {
        StudioRequestContextHolder.clear();
    }

    @Test
    void dispatchesEachTaskInsideItsTenantAndProjectContext() {
        FileTransferTaskService taskService = mock(FileTransferTaskService.class);
        FileTransferRunService runService = mock(FileTransferRunService.class);
        CronScheduleDueEvaluator dueEvaluator = mock(CronScheduleDueEvaluator.class);
        WorkerAuthorizationService authorization = mock(WorkerAuthorizationService.class);
        FileTransferTaskDefinitionEntity task = new FileTransferTaskDefinitionEntity();
        task.setId(700L);
        task.setTenantId("tenant-b");
        task.setProjectId(300L);
        task.setCreatedBy(88L);
        task.setCronExpression("0 * * * * ?");
        task.setTimezone("Asia/Shanghai");
        when(taskService.findEnabledSchedules()).thenReturn(List.of(task));
        when(dueEvaluator.nextDueTime(any(), any(), any(), any()))
                .thenReturn(LocalDateTime.of(2026, 8, 7, 10, 0));
        when(authorization.hasAvailableWorker("tenant-b", 300L)).thenReturn(true);
        doAnswer(invocation -> {
            assertEquals("tenant-b", StudioRequestContextHolder.getContext().getTenantId());
            assertEquals(300L, StudioRequestContextHolder.getContext().getProjectId());
            assertEquals(88L, StudioRequestContextHolder.getContext().getUserId());
            return null;
        }).when(runService).triggerTask(eq(700L), eq("SCHEDULED"), any());
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getDispatch().setSchedulerBatchSize(null);
        FileTransferTaskScheduleRunner runner = new FileTransferTaskScheduleRunner(
                taskService, runService, dueEvaluator, authorization,
                mock(ClusterLockService.class), properties);

        ReflectionTestUtils.invokeMethod(runner, "dispatchLocked");

        verify(taskService).markScheduleTriggered(eq(700L), any());
        assertNull(StudioRequestContextHolder.getContext());
    }
}
