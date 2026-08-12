package com.jdragon.studio.server.web.scheduler;

import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.entity.WorkflowScheduleEntity;
import com.jdragon.studio.infra.service.ClusterLockService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.DispatchTriggerStatus;
import com.jdragon.studio.infra.service.StudioExecutionContextService;
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
    private final ClusterLockService clusterLockService;
    private final StudioExecutionContextService executionContextService;
    private final StudioPlatformProperties properties;

    public WorkflowScheduleRunner(WorkflowService workflowService,
                                  DispatchService dispatchService,
                                  CronScheduleDueEvaluator cronScheduleDueEvaluator,
                                  ClusterLockService clusterLockService,
                                  StudioExecutionContextService executionContextService,
                                  StudioPlatformProperties properties) {
        this.workflowService = workflowService;
        this.dispatchService = dispatchService;
        this.cronScheduleDueEvaluator = cronScheduleDueEvaluator;
        this.clusterLockService = clusterLockService;
        this.executionContextService = executionContextService;
        this.properties = properties;
    }

    @Scheduled(initialDelay = 30000L, fixedDelay = 30000L)
    public void dispatchDueWorkflows() {
        clusterLockService.runIfAcquired("scheduler:workflow", this::dispatchDueWorkflowsLocked);
    }

    private void dispatchDueWorkflowsLocked() {
        List<WorkflowScheduleEntity> schedules = workflowService.findEnabledSchedules();
        LocalDateTime now = LocalDateTime.now();
        int processed = 0;
        int batchSize = schedulerBatchSize();
        for (WorkflowScheduleEntity schedule : schedules) {
            if (processed >= batchSize) {
                log.info("Workflow scheduler reached batch limit {}", batchSize);
                break;
            }
            WorkflowDefinitionEntity definition = workflowService.findScheduledDefinition(
                    schedule.getWorkflowDefinitionId(), schedule.getTenantId(), schedule.getProjectId());
            if (definition == null || !Integer.valueOf(1).equals(definition.getPublished())) {
                continue;
            }
            LocalDateTime scheduledFireTime = cronScheduleDueEvaluator.nextDueTime(
                    schedule.getCronExpression(),
                    schedule.getTimezone(),
                    schedule.getLastTriggeredAt(),
                    now);
            if (scheduledFireTime == null) {
                continue;
            }
            DispatchTriggerStatus status;
            try {
                status = executionContextService.callAs(definition.getCreatedBy(), definition.getTenantId(),
                        definition.getProjectId(), () -> {
                            WorkflowDefinitionView workflow = workflowService.get(schedule.getWorkflowDefinitionId());
                            if (workflow == null || !Boolean.TRUE.equals(workflow.getPublished())) {
                                return null;
                            }
                            return dispatchService.triggerScheduledWorkflowIfIdle(
                                    schedule.getWorkflowDefinitionId(), scheduledFireTime);
                        });
            } catch (RuntimeException exception) {
                log.info("Skip workflow {}: {}", schedule.getWorkflowDefinitionId(), exception.getMessage());
                continue;
            }
            if (status == null) {
                continue;
            }
            if (status == DispatchTriggerStatus.LOCK_BUSY) {
                log.info("Skip workflow {} because another instance is dispatching it", schedule.getWorkflowDefinitionId());
                continue;
            }
            if (status != DispatchTriggerStatus.TRIGGERED) {
                log.info("Skip workflow {} because a previous run is still active", schedule.getWorkflowDefinitionId());
            }
            workflowService.markScheduleTriggered(schedule.getWorkflowDefinitionId(), now);
            processed++;
        }
    }

    private int schedulerBatchSize() {
        Integer batchSize = properties.getDispatch() == null ? null : properties.getDispatch().getSchedulerBatchSize();
        return Math.max(1, batchSize == null ? 500 : batchSize.intValue());
    }
}
