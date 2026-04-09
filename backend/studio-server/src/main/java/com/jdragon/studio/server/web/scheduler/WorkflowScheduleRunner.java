package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.infra.entity.WorkflowScheduleEntity;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class WorkflowScheduleRunner {

    private final WorkflowService workflowService;
    private final DispatchService dispatchService;
    private final CronScheduleDueEvaluator cronScheduleDueEvaluator;

    public WorkflowScheduleRunner(WorkflowService workflowService,
                                  DispatchService dispatchService,
                                  CronScheduleDueEvaluator cronScheduleDueEvaluator) {
        this.workflowService = workflowService;
        this.dispatchService = dispatchService;
        this.cronScheduleDueEvaluator = cronScheduleDueEvaluator;
    }

    @Scheduled(fixedDelay = 30000L)
    public void dispatchDueWorkflows() {
        List<WorkflowScheduleEntity> schedules = workflowService.findEnabledSchedules();
        LocalDateTime now = LocalDateTime.now();
        for (WorkflowScheduleEntity schedule : schedules) {
            WorkflowDefinitionView workflow = workflowService.get(schedule.getWorkflowDefinitionId());
            if (workflow == null || !Boolean.TRUE.equals(workflow.getPublished())) {
                continue;
            }
            if (!cronScheduleDueEvaluator.isDue(
                    schedule.getCronExpression(),
                    schedule.getTimezone(),
                    schedule.getLastTriggeredAt(),
                    now)) {
                continue;
            }
            boolean triggered = dispatchService.triggerWorkflowIfIdle(schedule.getWorkflowDefinitionId());
            if (!triggered) {
                log.info("Skip workflow {} because a previous run is still active", schedule.getWorkflowDefinitionId());
            }
            workflowService.markScheduleTriggered(schedule.getWorkflowDefinitionId(), now);
        }
    }
}
