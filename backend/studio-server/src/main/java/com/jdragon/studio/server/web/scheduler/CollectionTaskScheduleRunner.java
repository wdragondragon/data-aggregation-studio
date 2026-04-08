package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.entity.CollectionTaskScheduleEntity;
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

    public CollectionTaskScheduleRunner(CollectionTaskService collectionTaskService,
                                        DispatchService dispatchService,
                                        CronScheduleDueEvaluator cronScheduleDueEvaluator,
                                        WorkerAuthorizationService workerAuthorizationService) {
        this.collectionTaskService = collectionTaskService;
        this.dispatchService = dispatchService;
        this.cronScheduleDueEvaluator = cronScheduleDueEvaluator;
        this.workerAuthorizationService = workerAuthorizationService;
    }

    @Scheduled(fixedDelay = 30000L)
    public void dispatchDueCollectionTasks() {
        List<CollectionTaskScheduleEntity> schedules = collectionTaskService.findEnabledSchedules();
        LocalDateTime now = LocalDateTime.now();
        for (CollectionTaskScheduleEntity schedule : schedules) {
            if (!isDue(schedule, now)) {
                continue;
            }
            if (!workerAuthorizationService.hasAvailableWorker(schedule.getTenantId(), schedule.getProjectId())) {
                log.info("Skip collection task {} because project {} has no authorized online worker",
                        schedule.getCollectionTaskId(), schedule.getProjectId());
                continue;
            }
            boolean triggered = dispatchService.triggerCollectionTaskIfIdle(schedule.getCollectionTaskId());
            if (!triggered) {
                log.info("Skip collection task {} because a previous instance is still active", schedule.getCollectionTaskId());
                continue;
            }
            collectionTaskService.markScheduleTriggered(schedule.getCollectionTaskId(), now);
        }
    }

    private boolean isDue(CollectionTaskScheduleEntity schedule, LocalDateTime now) {
        return cronScheduleDueEvaluator.isDue(
                schedule.getCronExpression(),
                schedule.getTimezone(),
                schedule.getLastTriggeredAt(),
                now
        );
    }
}
