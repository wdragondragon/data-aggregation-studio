package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.entity.CollectionTaskScheduleEntity;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.DispatchTriggerStatus;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class CollectionTaskScheduleRunner {

    private final CollectionTaskService collectionTaskService;
    private final DispatchService dispatchService;
    private final CronScheduleDueEvaluator cronScheduleDueEvaluator;
    private final WorkerAuthorizationService workerAuthorizationService;
    private final ClusterLockService clusterLockService;
    private final StudioPlatformProperties properties;

    public CollectionTaskScheduleRunner(CollectionTaskService collectionTaskService,
                                        DispatchService dispatchService,
                                        CronScheduleDueEvaluator cronScheduleDueEvaluator,
                                        WorkerAuthorizationService workerAuthorizationService,
                                        ClusterLockService clusterLockService,
                                        StudioPlatformProperties properties) {
        this.collectionTaskService = collectionTaskService;
        this.dispatchService = dispatchService;
        this.cronScheduleDueEvaluator = cronScheduleDueEvaluator;
        this.workerAuthorizationService = workerAuthorizationService;
        this.clusterLockService = clusterLockService;
        this.properties = properties;
    }

    @Scheduled(initialDelay = 30000L, fixedDelay = 30000L)
    public void dispatchDueCollectionTasks() {
        clusterLockService.runIfAcquired("scheduler:collection-task", this::dispatchDueCollectionTasksLocked);
    }

    private void dispatchDueCollectionTasksLocked() {
        List<CollectionTaskScheduleEntity> schedules = collectionTaskService.findEnabledSchedules();
        LocalDateTime now = LocalDateTime.now();
        int processed = 0;
        int batchSize = schedulerBatchSize();
        for (CollectionTaskScheduleEntity schedule : schedules) {
            if (processed >= batchSize) {
                log.info("Collection task scheduler reached batch limit {}", batchSize);
                break;
            }
            LocalDateTime scheduledFireTime = dueTime(schedule, now);
            if (scheduledFireTime == null) {
                continue;
            }
            if (!workerAuthorizationService.hasAvailableWorker(schedule.getTenantId(), schedule.getProjectId())) {
                log.info("Skip collection task {} because project {} has no authorized online worker",
                        schedule.getCollectionTaskId(), schedule.getProjectId());
                continue;
            }
            DispatchTriggerStatus status = dispatchService.triggerScheduledCollectionTaskIfIdle(schedule.getCollectionTaskId(), scheduledFireTime);
            if (status == DispatchTriggerStatus.LOCK_BUSY) {
                log.info("Skip collection task {} because another instance is dispatching it", schedule.getCollectionTaskId());
                continue;
            }
            if (status != DispatchTriggerStatus.TRIGGERED) {
                log.info("Skip collection task {} because a previous instance is still active", schedule.getCollectionTaskId());
            }
            collectionTaskService.markScheduleTriggered(schedule.getCollectionTaskId(), now);
            processed++;
        }
    }

    private LocalDateTime dueTime(CollectionTaskScheduleEntity schedule, LocalDateTime now) {
        return cronScheduleDueEvaluator.nextDueTime(
                schedule.getCronExpression(),
                schedule.getTimezone(),
                schedule.getLastTriggeredAt(),
                now
        );
    }

    private int schedulerBatchSize() {
        Integer batchSize = properties.getDispatch() == null ? null : properties.getDispatch().getSchedulerBatchSize();
        return Math.max(1, batchSize == null ? 500 : batchSize.intValue());
    }
}
