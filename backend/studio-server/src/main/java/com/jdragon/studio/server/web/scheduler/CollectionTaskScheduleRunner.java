package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.infra.entity.CollectionTaskScheduleEntity;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Component
public class CollectionTaskScheduleRunner {

    private final CollectionTaskService collectionTaskService;
    private final DispatchService dispatchService;

    public CollectionTaskScheduleRunner(CollectionTaskService collectionTaskService,
                                        DispatchService dispatchService) {
        this.collectionTaskService = collectionTaskService;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelay = 30000L)
    public void dispatchDueCollectionTasks() {
        List<CollectionTaskScheduleEntity> schedules = collectionTaskService.findEnabledSchedules();
        LocalDateTime now = LocalDateTime.now();
        for (CollectionTaskScheduleEntity schedule : schedules) {
            if (!isDue(schedule, now)) {
                continue;
            }
            dispatchService.triggerCollectionTask(schedule.getCollectionTaskId());
            collectionTaskService.markScheduleTriggered(schedule.getCollectionTaskId(), now);
        }
    }

    private boolean isDue(CollectionTaskScheduleEntity schedule, LocalDateTime now) {
        if (schedule.getCronExpression() == null || schedule.getCronExpression().trim().isEmpty()) {
            return false;
        }
        CronExpression expression = CronExpression.parse(schedule.getCronExpression().trim());
        ZoneId zoneId = ZoneId.of(schedule.getTimezone() == null || schedule.getTimezone().trim().isEmpty()
                ? "Asia/Shanghai"
                : schedule.getTimezone().trim());
        ZonedDateTime nowAtZone = now.atZone(zoneId);
        ZonedDateTime reference = (schedule.getLastTriggeredAt() == null
                ? now.minusMinutes(1)
                : schedule.getLastTriggeredAt()).atZone(zoneId);
        ZonedDateTime next = expression.next(reference);
        return next != null && !next.isAfter(nowAtZone);
    }
}
