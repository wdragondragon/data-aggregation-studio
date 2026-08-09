package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.FileTransferTaskDefinitionEntity;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.FileTransferRunService;
import com.jdragon.studio.infra.service.FileTransferTaskService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class FileTransferTaskScheduleRunner {

    private final FileTransferTaskService taskService;
    private final FileTransferRunService runService;
    private final CronScheduleDueEvaluator dueEvaluator;
    private final WorkerAuthorizationService workerAuthorizationService;
    private final ClusterLockService clusterLockService;
    private final StudioPlatformProperties properties;

    public FileTransferTaskScheduleRunner(FileTransferTaskService taskService,
                                          FileTransferRunService runService,
                                          CronScheduleDueEvaluator dueEvaluator,
                                          WorkerAuthorizationService workerAuthorizationService,
                                          ClusterLockService clusterLockService,
                                          StudioPlatformProperties properties) {
        this.taskService = taskService;
        this.runService = runService;
        this.dueEvaluator = dueEvaluator;
        this.workerAuthorizationService = workerAuthorizationService;
        this.clusterLockService = clusterLockService;
        this.properties = properties;
    }

    @Scheduled(initialDelay = 30000L, fixedDelay = 30000L)
    public void dispatchDueTasks() {
        clusterLockService.runIfAcquired("scheduler:file-transfer-task", this::dispatchLocked);
    }

    private void dispatchLocked() {
        List<FileTransferTaskDefinitionEntity> tasks = taskService.findEnabledSchedules();
        LocalDateTime now = LocalDateTime.now();
        int processed = 0;
        int limit = schedulerBatchSize();
        for (FileTransferTaskDefinitionEntity task : tasks) {
            if (processed >= limit) {
                break;
            }
            LocalDateTime fireTime = dueEvaluator.nextDueTime(task.getCronExpression(), task.getTimezone(),
                    task.getLastTriggeredAt(), now);
            if (fireTime == null) {
                continue;
            }
            if (!workerAuthorizationService.hasAvailableWorker(task.getTenantId(), task.getProjectId())) {
                log.info("Skip file transfer task {} because no authorized Worker is online", task.getId());
                continue;
            }
            try {
                dispatchTaskInContext(task, fireTime, now);
                processed++;
            } catch (RuntimeException exception) {
                log.info("Skip file transfer task {}: {}", task.getId(), exception.getMessage());
            }
        }
    }

    private void dispatchTaskInContext(FileTransferTaskDefinitionEntity task,
                                       LocalDateTime fireTime,
                                       LocalDateTime triggeredAt) {
        StudioRequestContext previous = StudioRequestContextHolder.getContext();
        StudioRequestContext context = new StudioRequestContext();
        context.setTenantId(task.getTenantId());
        context.setProjectId(task.getProjectId());
        context.setUserId(task.getCreatedBy());
        context.setUsername("file-transfer-scheduler");
        StudioRequestContextHolder.setContext(context);
        try {
            runService.triggerTask(task.getId(), "SCHEDULED", fireTime);
            taskService.markScheduleTriggered(task.getId(), triggeredAt);
        } finally {
            if (previous == null) {
                StudioRequestContextHolder.clear();
            } else {
                StudioRequestContextHolder.setContext(previous);
            }
        }
    }

    private int schedulerBatchSize() {
        Integer batchSize = properties.getDispatch() == null
                ? null : properties.getDispatch().getSchedulerBatchSize();
        return Math.max(1, batchSize == null ? 500 : batchSize.intValue());
    }
}
