package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.core.spi.ExecutionEventPublisher;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ExecutionEventService implements ExecutionEventPublisher {

    private final RunRecordMapper runRecordMapper;
    private final DispatchTaskMapper dispatchTaskMapper;
    private final CollectionTaskDefinitionMapper collectionTaskDefinitionMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final DispatchService dispatchService;
    private final RunMetricSummaryMapper runMetricSummaryMapper;
    private final FollowSubscriptionService followSubscriptionService;
    private final NotificationService notificationService;
    private final DataModelLineageService dataModelLineageService;
    private final QualityIssueService qualityIssueService;
    private final CollectionTaskIncrementalStateService collectionTaskIncrementalStateService;
    private final StaleExecutionRecoveryService staleExecutionRecoveryService;

    public ExecutionEventService(RunRecordMapper runRecordMapper,
                                 DispatchTaskMapper dispatchTaskMapper,
                                 CollectionTaskDefinitionMapper collectionTaskDefinitionMapper,
                                 WorkflowDefinitionMapper workflowDefinitionMapper,
                                 DispatchService dispatchService,
                                 RunMetricSummaryMapper runMetricSummaryMapper,
                                 FollowSubscriptionService followSubscriptionService,
                                 NotificationService notificationService,
                                 DataModelLineageService dataModelLineageService,
                                 QualityIssueService qualityIssueService,
                                 CollectionTaskIncrementalStateService collectionTaskIncrementalStateService,
                                 StaleExecutionRecoveryService staleExecutionRecoveryService) {
        this.runRecordMapper = runRecordMapper;
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.collectionTaskDefinitionMapper = collectionTaskDefinitionMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.dispatchService = dispatchService;
        this.runMetricSummaryMapper = runMetricSummaryMapper;
        this.followSubscriptionService = followSubscriptionService;
        this.notificationService = notificationService;
        this.dataModelLineageService = dataModelLineageService;
        this.qualityIssueService = qualityIssueService;
        this.collectionTaskIncrementalStateService = collectionTaskIncrementalStateService;
        this.staleExecutionRecoveryService = staleExecutionRecoveryService;
    }

    @Override
    @Transactional
    public void publish(ExecutionEvent event) {
        RunRecordEntity entity = event.getRunRecordId() == null
                ? null
                : runRecordMapper.selectById(event.getRunRecordId());
        if (entity == null) {
            entity = new RunRecordEntity();
            entity.setId(event.getRunRecordId());
        }
        entity.setExecutionType(event.getExecutionType() == null ? null : event.getExecutionType().name());
        entity.setWorkflowRunId(event.getWorkflowRunId());
        entity.setWorkflowDefinitionId(event.getWorkflowDefinitionId());
        entity.setWorkflowVersionId(event.getWorkflowVersionId());
        entity.setCollectionTaskId(event.getCollectionTaskId());
        entity.setQualityTaskId(event.getQualityTaskId());
        entity.setProjectId(event.getProjectId());
        entity.setNodeCode(event.getNodeCode());
        entity.setWorkerGroupCode(event.getWorkerGroupCode());
        entity.setWorkerCode(event.getWorkerCode());
        entity.setWorkerInstanceId(event.getWorkerInstanceId());
        entity.setWorkerPodName(event.getWorkerPodName());
        entity.setWorkerNodeName(event.getWorkerNodeName());
        entity.setStatus(event.getEventType());
        Map<String, Object> sanitizedPayload = RunRecordMessageSanitizer.sanitizePayload(event.getPayload());
        entity.setPayloadJson(sanitizedPayload);
        entity.setResultJson(sanitizedPayload);
        if (event.getTriggeredByUserId() != null) {
            entity.setTriggeredByUserId(event.getTriggeredByUserId());
        }
        entity.setStartedAt(event.getStartedAt() == null ? event.getOccurredAt() : event.getStartedAt());
        entity.setEndedAt(event.getEndedAt() == null ? event.getOccurredAt() : event.getEndedAt());
        entity.setLogFilePath(event.getLogFilePath());
        entity.setLogSizeBytes(event.getLogSizeBytes());
        entity.setLogCharset(event.getLogCharset());
        entity.setLogStorageType(event.getLogStorageType());
        entity.setLogObjectBucket(event.getLogObjectBucket());
        entity.setLogObjectKey(event.getLogObjectKey());
        entity.setLogChunkCount(event.getLogChunkCount());
        entity.setLogStatus(event.getLogStatus());
        entity.setLogErrorSummary(event.getLogErrorSummary());
        entity.setMessage(RunRecordMessageSanitizer.sanitizeAndTruncateMessage(resolveMessage(event)));
        runMetricSummaryMapper.applyToEntity(entity, event.getPayload());
        if (entity.getId() == null) {
            runRecordMapper.insert(entity);
        } else {
            runRecordMapper.updateById(entity);
        }
        dispatchService.continueWorkflowRun(event);
        dataModelLineageService.updateCollectionTaskRunStatus(event);
        maybeUpdateCollectionIncrementalState(entity, event);
        qualityIssueService.handleExecutionEvent(event, entity);
        maybeNotifyCollectionTaskRun(entity, event);
        maybeNotifyWorkflowRun(entity, event);
    }

    private void maybeUpdateCollectionIncrementalState(RunRecordEntity entity, ExecutionEvent event) {
        if (entity == null
                || event == null
                || !"SUCCESS".equalsIgnoreCase(event.getEventType())
                || entity.getCollectionTaskId() == null) {
            return;
        }
        collectionTaskIncrementalStateService.updateFromExecutionResult(entity.getCollectionTaskId(), entity.getId(), event.getPayload());
    }

    private String resolveMessage(ExecutionEvent event) {
        if (event.getPayload() != null) {
            Object error = event.getPayload().get("error");
            if (error != null) {
                return String.valueOf(error);
            }
            Object message = event.getPayload().get("message");
            if (message != null) {
                return String.valueOf(message);
            }
        }
        return event.getEventType();
    }

    private void maybeNotifyCollectionTaskRun(RunRecordEntity entity, ExecutionEvent event) {
        if (entity == null
                || event == null
                || event.getExecutionType() == null
                || event.getExecutionType() != com.jdragon.studio.dto.enums.DispatchExecutionType.COLLECTION_TASK
                || entity.getCollectionTaskId() == null
                || !isTerminalStatus(entity.getStatus())) {
            return;
        }
        CollectionTaskDefinitionEntity task = collectionTaskDefinitionMapper.selectById(entity.getCollectionTaskId());
        if (task == null) {
            return;
        }
        Set<Long> recipientUserIds = new LinkedHashSet<Long>();
        addRecipient(recipientUserIds, task.getCreatedBy());
        addRecipient(recipientUserIds, entity.getTriggeredByUserId());
        Map<Long, Long> taskFollowerProjectIds = followSubscriptionService.followerUserProjectIds(entity.getTenantId(), entity.getProjectId(),
                StudioConstants.FOLLOW_TARGET_COLLECTION_TASK, task.getId());
        addFollowersForProject(recipientUserIds, taskFollowerProjectIds, entity.getProjectId());
        recipientUserIds.addAll(followSubscriptionService.followerUserIds(entity.getTenantId(), entity.getProjectId(),
                StudioConstants.FOLLOW_TARGET_COLLECTION_TASK_RUN, entity.getId()));
        if (recipientUserIds.isEmpty()) {
            notifySharedCollectionTaskFollowers(entity, task, taskFollowerProjectIds, recipientUserIds);
            return;
        }
        notificationService.notifyUsers(new ArrayList<Long>(recipientUserIds),
                new NotificationCommand()
                        .setCategory(StudioConstants.NOTIFICATION_CATEGORY_COLLECTION_TASK_RUN)
                        .setTitle("采集任务运行已结束")
                        .setContent("采集任务 " + safeName(task.getName(), String.valueOf(task.getId()))
                                + " 本次运行状态为 " + entity.getStatus() + "。")
                        .setTargetType(StudioConstants.FOLLOW_TARGET_COLLECTION_TASK_RUN)
                        .setTargetId(entity.getId())
                        .setTargetPath("/collection-task-runs?collectionTaskId=" + task.getId() + "&runRecordId=" + entity.getId())
                        .setTargetTenantId(entity.getTenantId())
                        .setTargetProjectId(entity.getProjectId())
                        .setDedupeKey("collection-task-run:" + entity.getId() + ":" + entity.getStatus()));
        notifySharedCollectionTaskFollowers(entity, task, taskFollowerProjectIds, recipientUserIds);
    }

    private void maybeNotifyWorkflowRun(RunRecordEntity entity, ExecutionEvent event) {
        if (entity == null
                || event == null
                || entity.getWorkflowRunId() == null
                || entity.getWorkflowDefinitionId() == null
                || !isTerminalStatus(entity.getStatus())) {
            return;
        }
        if (staleExecutionRecoveryService.hasActiveWorkflowRunInstance(entity.getTenantId(), entity.getProjectId(), entity.getWorkflowRunId())) {
            return;
        }
        Long failedRunCount = runRecordMapper.selectCount(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getTenantId, entity.getTenantId())
                .eq(RunRecordEntity::getProjectId, entity.getProjectId())
                .eq(RunRecordEntity::getWorkflowRunId, entity.getWorkflowRunId())
                .eq(RunRecordEntity::getStatus, "FAILED"));
        String finalStatus = failedRunCount != null && failedRunCount.longValue() > 0L ? "FAILED" : "SUCCESS";
        WorkflowDefinitionEntity workflow = workflowDefinitionMapper.selectById(entity.getWorkflowDefinitionId());
        if (workflow == null) {
            return;
        }
        Set<Long> recipientUserIds = new LinkedHashSet<Long>();
        addRecipient(recipientUserIds, workflow.getCreatedBy());
        addRecipient(recipientUserIds, entity.getTriggeredByUserId());
        Map<Long, Long> workflowFollowerProjectIds = followSubscriptionService.followerUserProjectIds(entity.getTenantId(), entity.getProjectId(),
                StudioConstants.FOLLOW_TARGET_WORKFLOW, workflow.getId());
        addFollowersForProject(recipientUserIds, workflowFollowerProjectIds, entity.getProjectId());
        recipientUserIds.addAll(followSubscriptionService.followerUserIds(entity.getTenantId(), entity.getProjectId(),
                StudioConstants.FOLLOW_TARGET_WORKFLOW_RUN, entity.getWorkflowRunId()));
        if (recipientUserIds.isEmpty()) {
            notifySharedWorkflowFollowers(entity, workflow, finalStatus, workflowFollowerProjectIds, recipientUserIds);
            return;
        }
        notificationService.notifyUsers(new ArrayList<Long>(recipientUserIds),
                new NotificationCommand()
                        .setCategory(StudioConstants.NOTIFICATION_CATEGORY_WORKFLOW_RUN)
                        .setTitle("工作流运行已结束")
                        .setContent("工作流 " + safeName(workflow.getName(), String.valueOf(workflow.getId()))
                                + " 本次运行状态为 " + finalStatus + "。")
                        .setTargetType(StudioConstants.FOLLOW_TARGET_WORKFLOW_RUN)
                        .setTargetId(entity.getWorkflowRunId())
                        .setTargetPath("/runs/" + entity.getWorkflowRunId())
                        .setTargetTenantId(entity.getTenantId())
                        .setTargetProjectId(entity.getProjectId())
                        .setDedupeKey("workflow-run:" + entity.getWorkflowRunId() + ":" + finalStatus));
        notifySharedWorkflowFollowers(entity, workflow, finalStatus, workflowFollowerProjectIds, recipientUserIds);
    }

    private void addFollowersForProject(Set<Long> recipientUserIds, Map<Long, Long> followerProjectIds, Long projectId) {
        if (recipientUserIds == null || followerProjectIds == null || projectId == null) {
            return;
        }
        for (Map.Entry<Long, Long> entry : followerProjectIds.entrySet()) {
            if (entry.getValue() != null && entry.getValue().longValue() == projectId.longValue()) {
                addRecipient(recipientUserIds, entry.getKey());
            }
        }
    }

    private void notifySharedWorkflowFollowers(RunRecordEntity entity,
                                               WorkflowDefinitionEntity workflow,
                                               String finalStatus,
                                               Map<Long, Long> followerProjectIds,
                                               Set<Long> excludedUserIds) {
        Map<Long, List<Long>> usersByProject = sharedFollowersByProject(entity.getProjectId(), followerProjectIds, excludedUserIds);
        for (Map.Entry<Long, List<Long>> entry : usersByProject.entrySet()) {
            notificationService.notifyUsers(entry.getValue(),
                    new NotificationCommand()
                            .setCategory(StudioConstants.NOTIFICATION_CATEGORY_WORKFLOW_RUN)
                            .setTitle("工作流运行已结束")
                            .setContent("工作流 " + safeName(workflow.getName(), String.valueOf(workflow.getId()))
                                    + " 本次运行状态为 " + finalStatus + "。")
                            .setTargetType(StudioConstants.FOLLOW_TARGET_WORKFLOW_RUN)
                            .setTargetId(entity.getWorkflowRunId())
                            .setTargetPath("/workflows/" + workflow.getId())
                            .setTargetTenantId(entity.getTenantId())
                            .setTargetProjectId(entry.getKey())
                            .setDedupeKey("workflow-run:" + entity.getWorkflowRunId() + ":" + finalStatus));
        }
    }

    private void notifySharedCollectionTaskFollowers(RunRecordEntity entity,
                                                     CollectionTaskDefinitionEntity task,
                                                     Map<Long, Long> followerProjectIds,
                                                     Set<Long> excludedUserIds) {
        Map<Long, List<Long>> usersByProject = sharedFollowersByProject(entity.getProjectId(), followerProjectIds, excludedUserIds);
        for (Map.Entry<Long, List<Long>> entry : usersByProject.entrySet()) {
            notificationService.notifyUsers(entry.getValue(),
                    new NotificationCommand()
                            .setCategory(StudioConstants.NOTIFICATION_CATEGORY_COLLECTION_TASK_RUN)
                            .setTitle("采集任务运行已结束")
                            .setContent("采集任务 " + safeName(task.getName(), String.valueOf(task.getId()))
                                    + " 本次运行状态为 " + entity.getStatus() + "。")
                            .setTargetType(StudioConstants.FOLLOW_TARGET_COLLECTION_TASK_RUN)
                            .setTargetId(entity.getId())
                            .setTargetPath("/collection-tasks/" + task.getId() + "/edit")
                            .setTargetTenantId(entity.getTenantId())
                            .setTargetProjectId(entry.getKey())
                            .setDedupeKey("collection-task-run:" + entity.getId() + ":" + entity.getStatus()));
        }
    }

    private Map<Long, List<Long>> sharedFollowersByProject(Long ownerProjectId,
                                                           Map<Long, Long> followerProjectIds,
                                                           Set<Long> excludedUserIds) {
        Map<Long, List<Long>> usersByProject = new LinkedHashMap<Long, List<Long>>();
        if (ownerProjectId == null || followerProjectIds == null || followerProjectIds.isEmpty()) {
            return usersByProject;
        }
        for (Map.Entry<Long, Long> entry : followerProjectIds.entrySet()) {
            Long userId = entry.getKey();
            Long projectId = entry.getValue();
            if (userId == null
                    || projectId == null
                    || projectId.longValue() == ownerProjectId.longValue()
                    || (excludedUserIds != null && excludedUserIds.contains(userId))) {
                continue;
            }
            List<Long> users = usersByProject.get(projectId);
            if (users == null) {
                users = new ArrayList<Long>();
                usersByProject.put(projectId, users);
            }
            users.add(userId);
        }
        return usersByProject;
    }

    private void addRecipient(Set<Long> recipients, Long userId) {
        if (userId != null) {
            recipients.add(userId);
        }
    }

    private boolean isTerminalStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }

    private String safeName(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}

