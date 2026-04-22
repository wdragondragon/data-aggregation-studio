package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.entity.QualityTaskScheduleEntity;
import com.jdragon.studio.infra.service.DispatchService;
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

    public QualityTaskScheduleRunner(QualityTaskService qualityTaskService,
                                     DispatchService dispatchService,
                                     CronScheduleDueEvaluator cronScheduleDueEvaluator,
                                     WorkerAuthorizationService workerAuthorizationService) {
        this.qualityTaskService = qualityTaskService;
        this.dispatchService = dispatchService;
        this.cronScheduleDueEvaluator = cronScheduleDueEvaluator;
        this.workerAuthorizationService = workerAuthorizationService;
    }

    @Scheduled(initialDelay = 30000L, fixedDelay = 30000L)
    public void dispatchDueQualityTasks() {
        List<QualityTaskScheduleEntity> schedules = qualityTaskService.findEnabledSchedules();
        LocalDateTime now = LocalDateTime.now();
        for (QualityTaskScheduleEntity schedule : schedules) {
            if (!cronScheduleDueEvaluator.isDue(
                    schedule.getCronExpression(),
                    schedule.getTimezone(),
                    schedule.getLastTriggeredAt(),
                    now)) {
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
            boolean triggered = dispatchService.triggerQualityTaskIfIdle(schedule.getQualityTaskId());
            if (!triggered) {
                log.info("Skip quality task {} because a previous instance is still active", schedule.getQualityTaskId());
            }
            qualityTaskService.markScheduleTriggered(schedule.getQualityTaskId(), now);
        }
    }
}
