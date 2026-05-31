package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.QualityTaskScheduleEntity;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.DispatchTriggerStatus;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class QualityTaskScheduleRunner {

    private final QualityTaskService qualityTaskService;
    private final DispatchService dispatchService;
    private final CronScheduleDueEvaluator cronScheduleDueEvaluator;
    private final WorkerAuthorizationService workerAuthorizationService;
    private final ClusterLockService clusterLockService;
    private final StudioPlatformProperties properties;

    public QualityTaskScheduleRunner(QualityTaskService qualityTaskService,
                                     DispatchService dispatchService,
                                     CronScheduleDueEvaluator cronScheduleDueEvaluator,
                                     WorkerAuthorizationService workerAuthorizationService,
                                     ClusterLockService clusterLockService,
                                     StudioPlatformProperties properties) {
        this.qualityTaskService = qualityTaskService;
        this.dispatchService = dispatchService;
        this.cronScheduleDueEvaluator = cronScheduleDueEvaluator;
        this.workerAuthorizationService = workerAuthorizationService;
        this.clusterLockService = clusterLockService;
        this.properties = properties;
    }

    @Scheduled(initialDelay = 30000L, fixedDelay = 30000L)
    public void dispatchDueQualityTasks() {
        clusterLockService.runIfAcquired("scheduler:quality-task", this::dispatchDueQualityTasksLocked);
    }

    private void dispatchDueQualityTasksLocked() {
        List<QualityTaskScheduleEntity> schedules = qualityTaskService.findEnabledSchedules();
        LocalDateTime now = LocalDateTime.now();
        int processed = 0;
        int batchSize = schedulerBatchSize();
        for (QualityTaskScheduleEntity schedule : schedules) {
            if (processed >= batchSize) {
                log.info("Quality task scheduler reached batch limit {}", batchSize);
                break;
            }
            LocalDateTime scheduledFireTime = cronScheduleDueEvaluator.nextDueTime(
                    schedule.getCronExpression(),
                    schedule.getTimezone(),
                    schedule.getLastTriggeredAt(),
                    now);
            if (scheduledFireTime == null) {
                continue;
            }
            try {
                qualityTaskService.requireOnline(schedule.getQualityTaskId());
            } catch (Exception ex) {
                log.info("Skip quality task {} because it is not online: {}",
                        schedule.getQualityTaskId(), ex.getMessage());
                continue;
            }
            if (!workerAuthorizationService.hasAvailableWorker(schedule.getTenantId(), schedule.getProjectId())) {
                log.info("Skip quality task {} because project {} has no authorized online worker",
                        schedule.getQualityTaskId(), schedule.getProjectId());
                continue;
            }
            DispatchTriggerStatus status = dispatchService.triggerScheduledQualityTaskIfIdle(schedule.getQualityTaskId(), scheduledFireTime);
            if (status == DispatchTriggerStatus.LOCK_BUSY) {
                log.info("Skip quality task {} because another instance is dispatching it", schedule.getQualityTaskId());
                continue;
            }
            if (status != DispatchTriggerStatus.TRIGGERED) {
                log.info("Skip quality task {} because a previous instance is still active", schedule.getQualityTaskId());
            }
            qualityTaskService.markScheduleTriggered(schedule.getQualityTaskId(), now);
            processed++;
        }
    }

    private int schedulerBatchSize() {
        Integer batchSize = properties.getDispatch() == null ? null : properties.getDispatch().getSchedulerBatchSize();
        return Math.max(1, batchSize == null ? 500 : batchSize.intValue());
    }
}
