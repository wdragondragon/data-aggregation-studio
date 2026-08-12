package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.core.spi.WorkflowDispatcher;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.enums.EdgeCondition;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.QualityTaskDefinitionView;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.WorkflowEdgeDefinition;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class DispatchService implements WorkflowDispatcher {

    private static final long MANUAL_TRIGGER_LOCK_LEASE_SECONDS = 10L;

    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final WorkflowService workflowService;
    private final CollectionTaskService collectionTaskService;
    private final QualityTaskService qualityTaskService;
    private final StudioSecurityService securityService;
    private final WorkerAuthorizationService workerAuthorizationService;
    private final StaleExecutionRecoveryService staleExecutionRecoveryService;
    private final ClusterLockService clusterLockService;
    private RuntimeValidationService runtimeValidationService;
    private RuntimeResourceRevisionService runtimeResourceRevisionService;
    private FileTransferRunService fileTransferRunService;
    private StudioExecutionContextService executionContextService;

    public DispatchService(DispatchTaskMapper dispatchTaskMapper,
                           RunRecordMapper runRecordMapper,
                           WorkflowDefinitionMapper workflowDefinitionMapper,
                           WorkflowService workflowService,
                           CollectionTaskService collectionTaskService,
                           QualityTaskService qualityTaskService,
                           StudioSecurityService securityService,
                           WorkerAuthorizationService workerAuthorizationService,
                           StaleExecutionRecoveryService staleExecutionRecoveryService,
                           ClusterLockService clusterLockService) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.workflowService = workflowService;
        this.collectionTaskService = collectionTaskService;
        this.qualityTaskService = qualityTaskService;
        this.securityService = securityService;
        this.workerAuthorizationService = workerAuthorizationService;
        this.staleExecutionRecoveryService = staleExecutionRecoveryService;
        this.clusterLockService = clusterLockService;
    }

    @Autowired
    void setRuntimeValidationService(RuntimeValidationService runtimeValidationService) {
        this.runtimeValidationService = runtimeValidationService;
    }

    @Autowired
    void setRuntimeResourceRevisionService(RuntimeResourceRevisionService runtimeResourceRevisionService) {
        this.runtimeResourceRevisionService = runtimeResourceRevisionService;
    }

    @Autowired
    void setFileTransferRunService(FileTransferRunService fileTransferRunService) {
        this.fileTransferRunService = fileTransferRunService;
    }

    @Autowired
    void setExecutionContextService(StudioExecutionContextService executionContextService) {
        this.executionContextService = executionContextService;
    }

    @Override
    public void dispatchReadyNodes() {
        List<WorkflowDefinitionEntity> definitions = workflowDefinitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .eq(WorkflowDefinitionEntity::getPublished, 1));
        for (WorkflowDefinitionEntity definition : definitions) {
            if (executionContextService == null) {
                triggerWorkflowIfIdle(definition.getId(), definition.getProjectId());
            } else {
                executionContextService.runAs(definition.getCreatedBy(), definition.getTenantId(),
                        definition.getProjectId(),
                        () -> triggerWorkflowIfIdle(definition.getId(), definition.getProjectId()));
            }
        }
    }

    @Override
    @Transactional
    public void triggerManualRun(Long workflowDefinitionId) {
        triggerManualRun(workflowDefinitionId, null);
    }

    @Transactional
    public void triggerManualRun(Long workflowDefinitionId, Long runtimeClusterOverrideId) {
        WorkflowDefinitionView workflow = requireWorkflow(workflowDefinitionId);
        boolean manualOverride = runtimeClusterOverrideId != null
                && !runtimeClusterOverrideId.equals(workflow.getRuntimeClusterId());
        if (manualOverride) {
            workflow = workflowService.requireRunnableOnCluster(workflowDefinitionId, runtimeClusterOverrideId);
        }
        assertCurrentProjectOwnsResource(workflow.getProjectId());
        Long runtimeProjectId = resolveRuntimeProjectId(securityService.currentProjectId(), workflow.getProjectId());
        staleExecutionRecoveryService.recoverWorkflow(workflow.getTenantId(), runtimeProjectId, workflow.getId());
        if (triggerWorkflowIfIdleStatus(workflow, runtimeProjectId, true, null,
                true, MANUAL_TRIGGER_LOCK_LEASE_SECONDS, false,
                manualOverride) != DispatchTriggerStatus.TRIGGERED) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Workflow already has an active run");
        }
    }

    @Transactional
    public boolean triggerWorkflowIfIdle(Long workflowDefinitionId) {
        return triggerWorkflowIfIdle(workflowDefinitionId, null);
    }

    @Transactional
    public boolean triggerWorkflowIfIdle(Long workflowDefinitionId, Long runtimeProjectId) {
        return triggerWorkflowIfIdle(requireWorkflow(workflowDefinitionId), runtimeProjectId, false);
    }

    @Transactional
    public DispatchTriggerStatus triggerScheduledWorkflowIfIdle(Long workflowDefinitionId, LocalDateTime scheduledFireTime) {
        return triggerWorkflowIfIdleStatus(requireWorkflow(workflowDefinitionId), null, false, scheduledFireTime, true);
    }

    @Transactional
    public void triggerCollectionTask(Long collectionTaskId) {
        triggerCollectionTask(collectionTaskId, null);
    }

    @Transactional
    public void triggerCollectionTask(Long collectionTaskId, Long runtimeClusterOverrideId) {
        CollectionTaskDefinitionView definition = collectionTaskService.requireOnline(collectionTaskId);
        boolean manualOverride = runtimeClusterOverrideId != null
                && !runtimeClusterOverrideId.equals(definition.getRuntimeClusterId());
        if (manualOverride) {
            definition = collectionTaskService.requireRunnableOnCluster(collectionTaskId, runtimeClusterOverrideId);
        }
        assertCurrentProjectOwnsResource(definition.getProjectId());
        Long runtimeProjectId = resolveRuntimeProjectId(securityService.currentProjectId(), definition.getProjectId());
        staleExecutionRecoveryService.recoverCollectionTask(definition.getTenantId(), runtimeProjectId, definition.getId());
        if (triggerCollectionTaskIfIdleStatus(definition, runtimeProjectId, true, null,
                true, MANUAL_TRIGGER_LOCK_LEASE_SECONDS, false,
                manualOverride) != DispatchTriggerStatus.TRIGGERED) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Collection task already has an active run");
        }
    }

    @Transactional
    public boolean triggerCollectionTaskIfIdle(Long collectionTaskId) {
        return triggerCollectionTaskIfIdle(collectionTaskId, null);
    }

    @Transactional
    public boolean triggerCollectionTaskIfIdle(Long collectionTaskId, Long runtimeProjectId) {
        return triggerCollectionTaskIfIdle(collectionTaskService.requireOnline(collectionTaskId), runtimeProjectId, false);
    }

    @Transactional
    public DispatchTriggerStatus triggerScheduledCollectionTaskIfIdle(Long collectionTaskId, LocalDateTime scheduledFireTime) {
        return triggerCollectionTaskIfIdleStatus(collectionTaskService.requireOnline(collectionTaskId), null, false, scheduledFireTime, true);
    }

    @Transactional
    public void triggerQualityTask(Long qualityTaskId) {
        triggerQualityTask(qualityTaskId, null);
    }

    @Transactional
    public void triggerQualityTask(Long qualityTaskId, Long runtimeClusterOverrideId) {
        QualityTaskDefinitionView definition = qualityTaskService.requireOnline(qualityTaskId);
        boolean manualOverride = runtimeClusterOverrideId != null
                && !runtimeClusterOverrideId.equals(definition.getRuntimeClusterId());
        if (manualOverride) {
            definition = qualityTaskService.requireRunnableOnCluster(qualityTaskId, runtimeClusterOverrideId);
        }
        assertCurrentProjectOwnsResource(definition.getProjectId());
        Long runtimeProjectId = resolveRuntimeProjectId(securityService.currentProjectId(), definition.getProjectId());
        staleExecutionRecoveryService.recoverQualityTask(definition.getTenantId(), runtimeProjectId, definition.getId());
        if (triggerQualityTaskIfIdleStatus(definition, runtimeProjectId, true, null,
                true, MANUAL_TRIGGER_LOCK_LEASE_SECONDS, false,
                manualOverride) != DispatchTriggerStatus.TRIGGERED) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Quality task already has an active run");
        }
    }

    @Transactional
    public boolean triggerQualityTaskIfIdle(Long qualityTaskId) {
        return triggerQualityTaskIfIdle(qualityTaskId, null);
    }

    @Transactional
    public boolean triggerQualityTaskIfIdle(Long qualityTaskId, Long runtimeProjectId) {
        return triggerQualityTaskIfIdle(qualityTaskService.requireOnline(qualityTaskId), runtimeProjectId, false);
    }

    @Transactional
    public DispatchTriggerStatus triggerScheduledQualityTaskIfIdle(Long qualityTaskId, LocalDateTime scheduledFireTime) {
        return triggerQualityTaskIfIdleStatus(qualityTaskService.requireOnline(qualityTaskId), null, false, scheduledFireTime, true);
    }

    public List<DispatchTaskEntity> queuedTasks() {
        LambdaQueryWrapper<DispatchTaskEntity> queryWrapper = new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, securityService.currentTenantId())
                .eq(DispatchTaskEntity::getStatus, "QUEUED");
        if (securityService.currentProjectId() != null) {
            queryWrapper.eq(DispatchTaskEntity::getProjectId, securityService.currentProjectId());
        }
        return dispatchTaskMapper.selectList(queryWrapper);
    }

    private void assertCurrentProjectOwnsResource(Long ownerProjectId) {
        Long currentProjectId = securityService.currentProjectId();
        if (currentProjectId == null || ownerProjectId == null) {
            return;
        }
        if (currentProjectId.longValue() != ownerProjectId.longValue()) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Resource belongs to another project");
        }
    }

    private boolean triggerWorkflowIfIdle(WorkflowDefinitionView workflow,
                                          Long runtimeProjectId,
                                          boolean workerRequired) {
        return triggerWorkflowIfIdleStatus(workflow, runtimeProjectId, workerRequired, null, false) == DispatchTriggerStatus.TRIGGERED;
    }

    private DispatchTriggerStatus triggerWorkflowIfIdleStatus(WorkflowDefinitionView workflow,
                                                              Long runtimeProjectId,
                                                              boolean workerRequired,
                                                              LocalDateTime scheduledFireTime,
                                                              boolean clusterGuard) {
        return triggerWorkflowIfIdleStatus(workflow, runtimeProjectId, workerRequired, scheduledFireTime,
                clusterGuard, null, true, false);
    }

    private DispatchTriggerStatus triggerWorkflowIfIdleStatus(WorkflowDefinitionView workflow,
                                                              Long runtimeProjectId,
                                                              boolean workerRequired,
                                                              LocalDateTime scheduledFireTime,
                                                              boolean clusterGuard,
                                                              Long lockLeaseSeconds,
                                                              boolean releaseLock,
                                                              boolean bypassPersistedRuntimeValidation) {
        Long resolvedProjectId = resolveRuntimeProjectId(runtimeProjectId, workflow.getProjectId());
        if (!bypassPersistedRuntimeValidation) {
            assertRuntimeValid(StudioConstants.RESOURCE_TYPE_WORKFLOW, workflow.getId());
        }
        if (clusterGuard) {
            String lockName = triggerLockName("workflow", workflow.getTenantId(), resolvedProjectId, workflow.getId(), scheduledFireTime);
            if (lockLeaseSeconds != null) {
                return clusterLockService.executeIfAcquiredNonReentrant(lockName, lockLeaseSeconds.longValue(), releaseLock,
                        () -> triggerWorkflowAfterLock(workflow, resolvedProjectId, scheduledFireTime),
                        () -> DispatchTriggerStatus.LOCK_BUSY);
            }
            return clusterLockService.executeIfAcquiredNonReentrant(lockName,
                    () -> triggerWorkflowAfterLock(workflow, resolvedProjectId, scheduledFireTime),
                    () -> DispatchTriggerStatus.LOCK_BUSY);
        }
        return triggerWorkflowAfterLock(workflow, resolvedProjectId, scheduledFireTime);
    }

    private DispatchTriggerStatus triggerWorkflowAfterLock(WorkflowDefinitionView workflow,
                                                           Long resolvedProjectId,
                                                           LocalDateTime scheduledFireTime) {
        staleExecutionRecoveryService.recoverWorkflow(workflow.getTenantId(), resolvedProjectId, workflow.getId());
        if (hasActiveWorkflowRun(workflow.getTenantId(), workflow.getId(), resolvedProjectId)) {
            return DispatchTriggerStatus.SKIPPED_ACTIVE;
        }
        Long workflowRunId = IdWorker.getId();
        Set<String> inbound = new HashSet<String>();
        for (WorkflowEdgeDefinition edge : workflow.getEdges()) {
            inbound.add(edge.getToNodeCode());
        }
        for (WorkflowNodeDefinition node : workflow.getNodes()) {
            if (!inbound.contains(node.getNodeCode())) {
                insertDispatchTask(buildWorkflowNodeTask(workflow, workflowRunId, node, resolvedProjectId,
                        workflow.getRuntimeClusterId(), scheduledFireTime));
            }
        }
        return DispatchTriggerStatus.TRIGGERED;
    }

    @Transactional
    public void continueWorkflowRun(ExecutionEvent event) {
        if (event == null
                || event.getExecutionType() != DispatchExecutionType.WORKFLOW_NODE
                || event.getWorkflowDefinitionId() == null
                || event.getWorkflowVersionId() == null
                || event.getWorkflowRunId() == null
                || event.getNodeCode() == null
                || !isTerminalStatus(event.getEventType())) {
            return;
        }

        if (executionContextService == null) {
            continueWorkflowRunInContext(event);
            return;
        }
        WorkflowDefinitionEntity definition = workflowDefinitionMapper.selectById(event.getWorkflowDefinitionId());
        if (definition == null) {
            return;
        }
        Long executionUserId = event.getTriggeredByUserId() == null
                ? definition.getCreatedBy() : event.getTriggeredByUserId();
        Long executionProjectId = event.getProjectId() == null
                ? definition.getProjectId() : event.getProjectId();
        executionContextService.runAs(executionUserId, definition.getTenantId(), executionProjectId,
                () -> continueWorkflowRunInContext(event));
    }

    private void continueWorkflowRunInContext(ExecutionEvent event) {
        WorkflowDefinitionView workflow = workflowService.getVersion(event.getWorkflowDefinitionId(),
                event.getWorkflowVersionId());
        if (workflow == null) {
            return;
        }
        Long runtimeProjectId = event.getProjectId();

        for (WorkflowNodeDefinition candidate : collectDownstreamNodes(workflow, event.getNodeCode(), event.getEventType())) {
            if (alreadyDispatched(event.getWorkflowRunId(), candidate.getNodeCode())) {
                continue;
            }
            if (!isNodeReady(workflow, event.getWorkflowRunId(), candidate.getNodeCode())) {
                continue;
            }
            insertDispatchTask(buildWorkflowNodeTask(workflow, event.getWorkflowRunId(), candidate, runtimeProjectId,
                    event.getRequestedClusterId(), null));
        }
    }

    private DispatchTaskEntity buildWorkflowNodeTask(WorkflowDefinitionView workflow,
                                                     Long workflowRunId,
                                                     WorkflowNodeDefinition node,
                                                     Long runtimeProjectId,
                                                     Long runtimeClusterId,
                                                     LocalDateTime scheduledFireTime) {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setTenantId(workflow.getTenantId());
        task.setProjectId(runtimeProjectId);
        task.setExecutionType(DispatchExecutionType.WORKFLOW_NODE.name());
        task.setWorkflowRunId(workflowRunId);
        task.setWorkflowDefinitionId(workflow.getId());
        task.setWorkflowVersionId(workflow.getVersionId());
        task.setCollectionTaskId(resolveCollectionTaskId(node));
        task.setQualityTaskId(resolveQualityTaskId(node));
        String resourceRevision = node.getConfig() == null || node.getConfig().get("_resourceRevision") == null
                ? null : String.valueOf(node.getConfig().get("_resourceRevision"));
        Long fileTransferTaskId = null;
        FileTransferRunEntity fileTransferRun = null;
        if (node.getNodeType() == NodeType.FILE_TRANSFER) {
            fileTransferTaskId = resolveFileTransferTaskId(node)
                    .orElseThrow(() -> new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Workflow file transfer task id is required"));
            if (fileTransferRunService == null) {
                throw new IllegalStateException("File transfer run service is unavailable");
            }
            fileTransferRun = fileTransferRunService.createWorkflowRunSkeleton(
                    fileTransferTaskId, runtimeProjectId, runtimeClusterId, "WORKFLOW", resourceRevision);
            task.setFileTransferTaskId(fileTransferTaskId);
            task.setFileTransferRunId(fileTransferRun.getId());
        }
        task.setNodeCode(node.getNodeCode());
        task.setStatus("QUEUED");
        task.setTargetClusterId(runtimeClusterId);
        task.setResourceRevision(resourceRevision);
        task.setScheduledFireTime(scheduledFireTime);
        task.setAttempts(0);
        task.setMaxRetries(3);
        task.setTriggeredByUserId(securityService.currentUserId());
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executionType", DispatchExecutionType.WORKFLOW_NODE.name());
        payload.put("workflowRunId", workflowRunId);
        payload.put("projectId", runtimeProjectId);
        payload.put("runtimeClusterId", runtimeClusterId);
        payload.put("scheduledFireTime", scheduledFireTime == null ? null : scheduledFireTime.toString());
        if (fileTransferRun != null) {
            payload.put("fileTransferTaskId", fileTransferTaskId);
            payload.put("fileTransferRunId", fileTransferRun.getId());
            LinkedHashMap<String, Object> config = new LinkedHashMap<String, Object>();
            config.put("fileTransferTaskId", fileTransferTaskId);
            config.put("fileTransferRunId", fileTransferRun.getId());
            payload.put("config", config);
        }
        task.setPayloadJson(payload);
        return task;
    }

    private boolean triggerCollectionTaskIfIdle(CollectionTaskDefinitionView definition,
                                                Long runtimeProjectId,
                                                boolean workerRequired) {
        return triggerCollectionTaskIfIdleStatus(definition, runtimeProjectId, workerRequired, null, false) == DispatchTriggerStatus.TRIGGERED;
    }

    private DispatchTriggerStatus triggerCollectionTaskIfIdleStatus(CollectionTaskDefinitionView definition,
                                                                    Long runtimeProjectId,
                                                                    boolean workerRequired,
                                                                    LocalDateTime scheduledFireTime,
                                                                    boolean clusterGuard) {
        return triggerCollectionTaskIfIdleStatus(definition, runtimeProjectId, workerRequired, scheduledFireTime,
                clusterGuard, null, true, false);
    }

    private DispatchTriggerStatus triggerCollectionTaskIfIdleStatus(CollectionTaskDefinitionView definition,
                                                                    Long runtimeProjectId,
                                                                    boolean workerRequired,
                                                                    LocalDateTime scheduledFireTime,
                                                                    boolean clusterGuard,
                                                                    Long lockLeaseSeconds,
                                                                    boolean releaseLock,
                                                                    boolean bypassPersistedRuntimeValidation) {
        Long resolvedProjectId = resolveRuntimeProjectId(runtimeProjectId, definition.getProjectId());
        if (!bypassPersistedRuntimeValidation) {
            assertRuntimeValid(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, definition.getId());
        }
        if (clusterGuard) {
            String lockName = triggerLockName("collection", definition.getTenantId(), resolvedProjectId, definition.getId(), scheduledFireTime);
            if (lockLeaseSeconds != null) {
                return clusterLockService.executeIfAcquiredNonReentrant(lockName, lockLeaseSeconds.longValue(), releaseLock,
                        () -> triggerCollectionTaskAfterLock(definition, resolvedProjectId, scheduledFireTime,
                                bypassPersistedRuntimeValidation),
                        () -> DispatchTriggerStatus.LOCK_BUSY);
            }
            return clusterLockService.executeIfAcquiredNonReentrant(lockName,
                    () -> triggerCollectionTaskAfterLock(definition, resolvedProjectId, scheduledFireTime,
                            bypassPersistedRuntimeValidation),
                    () -> DispatchTriggerStatus.LOCK_BUSY);
        }
        return triggerCollectionTaskAfterLock(definition, resolvedProjectId, scheduledFireTime,
                bypassPersistedRuntimeValidation);
    }

    private DispatchTriggerStatus triggerCollectionTaskAfterLock(CollectionTaskDefinitionView definition,
                                                                 Long resolvedProjectId,
                                                                 LocalDateTime scheduledFireTime,
                                                                 boolean manualOverride) {
        staleExecutionRecoveryService.recoverCollectionTask(definition.getTenantId(), resolvedProjectId, definition.getId());
        if (hasActiveCollectionTaskRun(definition.getTenantId(), definition.getId(), resolvedProjectId)) {
            return DispatchTriggerStatus.SKIPPED_ACTIVE;
        }
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setTenantId(definition.getTenantId());
        task.setProjectId(resolvedProjectId);
        task.setExecutionType(DispatchExecutionType.COLLECTION_TASK.name());
        task.setCollectionTaskId(definition.getId());
        task.setNodeCode("collection_task_" + definition.getId());
        task.setStatus("QUEUED");
        task.setTargetClusterId(definition.getRuntimeClusterId());
        task.setResourceRevision(stableCollectionRevision(definition, manualOverride));
        task.setScheduledFireTime(scheduledFireTime);
        task.setAttempts(0);
        task.setMaxRetries(3);
        task.setTriggeredByUserId(securityService.currentUserId());
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executionType", DispatchExecutionType.COLLECTION_TASK.name());
        payload.put("nodeType", "COLLECTION_TASK");
        payload.put("collectionTaskId", definition.getId());
        payload.put("projectId", resolvedProjectId);
        payload.put("runtimeClusterId", definition.getRuntimeClusterId());
        payload.put("scheduledFireTime", scheduledFireTime == null ? null : scheduledFireTime.toString());
        task.setPayloadJson(payload);
        insertDispatchTask(task);
        return DispatchTriggerStatus.TRIGGERED;
    }

    private boolean triggerQualityTaskIfIdle(QualityTaskDefinitionView definition,
                                             Long runtimeProjectId,
                                             boolean workerRequired) {
        return triggerQualityTaskIfIdleStatus(definition, runtimeProjectId, workerRequired, null, false) == DispatchTriggerStatus.TRIGGERED;
    }

    private DispatchTriggerStatus triggerQualityTaskIfIdleStatus(QualityTaskDefinitionView definition,
                                                                 Long runtimeProjectId,
                                                                 boolean workerRequired,
                                                                 LocalDateTime scheduledFireTime,
                                                                 boolean clusterGuard) {
        return triggerQualityTaskIfIdleStatus(definition, runtimeProjectId, workerRequired, scheduledFireTime,
                clusterGuard, null, true, false);
    }

    private DispatchTriggerStatus triggerQualityTaskIfIdleStatus(QualityTaskDefinitionView definition,
                                                                 Long runtimeProjectId,
                                                                 boolean workerRequired,
                                                                 LocalDateTime scheduledFireTime,
                                                                 boolean clusterGuard,
                                                                 Long lockLeaseSeconds,
                                                                 boolean releaseLock,
                                                                 boolean bypassPersistedRuntimeValidation) {
        Long resolvedProjectId = resolveRuntimeProjectId(runtimeProjectId, definition.getProjectId());
        if (!bypassPersistedRuntimeValidation) {
            assertRuntimeValid(StudioConstants.RESOURCE_TYPE_QUALITY_TASK, definition.getId());
        }
        if (clusterGuard) {
            String lockName = triggerLockName("quality", definition.getTenantId(), resolvedProjectId, definition.getId(), scheduledFireTime);
            if (lockLeaseSeconds != null) {
                return clusterLockService.executeIfAcquiredNonReentrant(lockName, lockLeaseSeconds.longValue(), releaseLock,
                        () -> triggerQualityTaskAfterLock(definition, resolvedProjectId, scheduledFireTime,
                                bypassPersistedRuntimeValidation),
                        () -> DispatchTriggerStatus.LOCK_BUSY);
            }
            return clusterLockService.executeIfAcquiredNonReentrant(lockName,
                    () -> triggerQualityTaskAfterLock(definition, resolvedProjectId, scheduledFireTime,
                            bypassPersistedRuntimeValidation),
                    () -> DispatchTriggerStatus.LOCK_BUSY);
        }
        return triggerQualityTaskAfterLock(definition, resolvedProjectId, scheduledFireTime,
                bypassPersistedRuntimeValidation);
    }

    private DispatchTriggerStatus triggerQualityTaskAfterLock(QualityTaskDefinitionView definition,
                                                              Long resolvedProjectId,
                                                              LocalDateTime scheduledFireTime,
                                                              boolean manualOverride) {
        staleExecutionRecoveryService.recoverQualityTask(definition.getTenantId(), resolvedProjectId, definition.getId());
        if (hasActiveQualityTaskRun(definition.getTenantId(), definition.getId(), resolvedProjectId)) {
            return DispatchTriggerStatus.SKIPPED_ACTIVE;
        }
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setTenantId(definition.getTenantId());
        task.setProjectId(resolvedProjectId);
        task.setExecutionType(DispatchExecutionType.QUALITY_TASK.name());
        task.setQualityTaskId(definition.getId());
        task.setNodeCode("quality_task_" + definition.getId());
        task.setStatus("QUEUED");
        task.setTargetClusterId(definition.getRuntimeClusterId());
        task.setResourceRevision(stableQualityRevision(definition, manualOverride));
        task.setScheduledFireTime(scheduledFireTime);
        task.setAttempts(0);
        task.setMaxRetries(3);
        task.setTriggeredByUserId(securityService.currentUserId());
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executionType", DispatchExecutionType.QUALITY_TASK.name());
        payload.put("nodeType", "QUALITY_TASK");
        payload.put("qualityTaskId", definition.getId());
        payload.put("projectId", resolvedProjectId);
        payload.put("runtimeClusterId", definition.getRuntimeClusterId());
        payload.put("scheduledFireTime", scheduledFireTime == null ? null : scheduledFireTime.toString());
        task.setPayloadJson(payload);
        insertDispatchTask(task);
        return DispatchTriggerStatus.TRIGGERED;
    }

    private void insertDispatchTask(DispatchTaskEntity task) {
        if (task == null || task.getTargetClusterId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "runtimeClusterId is required before a task can be dispatched");
        }
        dispatchTaskMapper.insert(task);
    }

    private String stableCollectionRevision(CollectionTaskDefinitionView definition, boolean manualOverride) {
        String revision = runtimeResourceRevisionService == null
                ? (definition.getUpdatedAt() == null ? null : definition.getUpdatedAt().toString())
                : runtimeResourceRevisionService.collectionTaskRevision(
                definition.getId(), definition.getUpdatedAt());
        CollectionTaskDefinitionView revalidated = manualOverride
                ? collectionTaskService.requireRunnableOnCluster(definition.getId(), definition.getRuntimeClusterId())
                : collectionTaskService.requireOnline(definition.getId());
        String stableRevision = runtimeResourceRevisionService == null
                ? (revalidated.getUpdatedAt() == null ? null : revalidated.getUpdatedAt().toString())
                : runtimeResourceRevisionService.collectionTaskRevision(
                revalidated.getId(), revalidated.getUpdatedAt());
        assertStableDispatchSnapshot(definition.getRuntimeClusterId(), revalidated.getRuntimeClusterId(),
                revision, stableRevision, "Collection task changed while dispatching; retry the run");
        return revision;
    }

    private String stableQualityRevision(QualityTaskDefinitionView definition, boolean manualOverride) {
        String revision = runtimeResourceRevisionService == null
                ? (definition.getUpdatedAt() == null ? null : definition.getUpdatedAt().toString())
                : runtimeResourceRevisionService.qualityTaskRevision(
                definition.getId(), definition.getUpdatedAt());
        QualityTaskDefinitionView revalidated = manualOverride
                ? qualityTaskService.requireRunnableOnCluster(definition.getId(), definition.getRuntimeClusterId())
                : qualityTaskService.requireOnline(definition.getId());
        String stableRevision = runtimeResourceRevisionService == null
                ? (revalidated.getUpdatedAt() == null ? null : revalidated.getUpdatedAt().toString())
                : runtimeResourceRevisionService.qualityTaskRevision(
                revalidated.getId(), revalidated.getUpdatedAt());
        assertStableDispatchSnapshot(definition.getRuntimeClusterId(), revalidated.getRuntimeClusterId(),
                revision, stableRevision, "Quality task changed while dispatching; retry the run");
        return revision;
    }

    private void assertStableDispatchSnapshot(Long expectedClusterId,
                                              Long actualClusterId,
                                              String expectedRevision,
                                              String actualRevision,
                                              String message) {
        if (!Objects.equals(expectedClusterId, actualClusterId)
                || !Objects.equals(expectedRevision, actualRevision)) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR, message);
        }
    }

    private List<WorkflowNodeDefinition> collectDownstreamNodes(WorkflowDefinitionView workflow,
                                                                String fromNodeCode,
                                                                String eventType) {
        List<WorkflowNodeDefinition> result = new ArrayList<WorkflowNodeDefinition>();
        for (WorkflowEdgeDefinition edge : workflow.getEdges()) {
            if (!fromNodeCode.equals(edge.getFromNodeCode())) {
                continue;
            }
            if (!matchesCondition(eventType, edge.getCondition())) {
                continue;
            }
            WorkflowNodeDefinition node = findNode(workflow, edge.getToNodeCode());
            if (node != null) {
                result.add(node);
            }
        }
        return result;
    }

    private WorkflowNodeDefinition findNode(WorkflowDefinitionView workflow, String nodeCode) {
        for (WorkflowNodeDefinition node : workflow.getNodes()) {
            if (nodeCode != null && nodeCode.equals(node.getNodeCode())) {
                return node;
            }
        }
        return null;
    }

    private boolean alreadyDispatched(Long workflowRunId, String nodeCode) {
        Long dispatchCount = dispatchTaskMapper.selectCount(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getWorkflowRunId, workflowRunId)
                .eq(DispatchTaskEntity::getNodeCode, nodeCode));
        if (dispatchCount != null && dispatchCount > 0) {
            return true;
        }
        Long runCount = runRecordMapper.selectCount(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getWorkflowRunId, workflowRunId)
                .eq(RunRecordEntity::getNodeCode, nodeCode));
        return runCount != null && runCount > 0;
    }

    private boolean isNodeReady(WorkflowDefinitionView workflow, Long workflowRunId, String nodeCode) {
        List<WorkflowEdgeDefinition> inboundEdges = new ArrayList<WorkflowEdgeDefinition>();
        for (WorkflowEdgeDefinition edge : workflow.getEdges()) {
            if (nodeCode.equals(edge.getToNodeCode())) {
                inboundEdges.add(edge);
            }
        }
        if (inboundEdges.isEmpty()) {
            return true;
        }

        Map<String, DispatchTaskEntity> activatedTasks = latestTaskByNode(workflowRunId);
        Map<String, RunRecordEntity> latestRecords = latestRecordByNode(workflowRunId);
        boolean hasActiveInbound = false;
        for (WorkflowEdgeDefinition edge : inboundEdges) {
            String predecessor = edge.getFromNodeCode();
            RunRecordEntity record = latestRecords.get(predecessor);
            DispatchTaskEntity task = activatedTasks.get(predecessor);
            boolean activated = record != null || task != null;
            if (!activated) {
                continue;
            }
            String predecessorStatus = record != null ? record.getStatus() : task.getStatus();
            if (!isTerminalStatus(predecessorStatus)) {
                return false;
            }
            if (!matchesCondition(predecessorStatus, edge.getCondition())) {
                return false;
            }
            hasActiveInbound = true;
        }
        return hasActiveInbound;
    }

    private Map<String, DispatchTaskEntity> latestTaskByNode(Long workflowRunId) {
        List<DispatchTaskEntity> tasks = dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getWorkflowRunId, workflowRunId)
                .orderByDesc(DispatchTaskEntity::getCreatedAt));
        LinkedHashMap<String, DispatchTaskEntity> result = new LinkedHashMap<String, DispatchTaskEntity>();
        for (DispatchTaskEntity task : tasks) {
            if (task.getNodeCode() == null || result.containsKey(task.getNodeCode())) {
                continue;
            }
            result.put(task.getNodeCode(), task);
        }
        return result;
    }

    private Map<String, RunRecordEntity> latestRecordByNode(Long workflowRunId) {
        List<RunRecordEntity> records = runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getWorkflowRunId, workflowRunId)
                .orderByDesc(RunRecordEntity::getStartedAt)
                .orderByDesc(RunRecordEntity::getCreatedAt));
        LinkedHashMap<String, RunRecordEntity> result = new LinkedHashMap<String, RunRecordEntity>();
        for (RunRecordEntity record : records) {
            if (record.getNodeCode() == null || result.containsKey(record.getNodeCode())) {
                continue;
            }
            result.put(record.getNodeCode(), record);
        }
        return result;
    }

    private boolean matchesCondition(String eventType, EdgeCondition condition) {
        EdgeCondition effectiveCondition = condition == null ? EdgeCondition.ON_SUCCESS : condition;
        if (effectiveCondition == EdgeCondition.ALWAYS) {
            return isTerminalStatus(eventType);
        }
        if (effectiveCondition == EdgeCondition.ON_FAILURE) {
            return "FAILED".equalsIgnoreCase(eventType) || "ERROR".equalsIgnoreCase(eventType);
        }
        return "SUCCESS".equalsIgnoreCase(eventType);
    }

    private boolean isTerminalStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)
                || "ERROR".equalsIgnoreCase(status);
    }

    private boolean hasActiveWorkflowRun(String tenantId, Long workflowDefinitionId, Long projectId) {
        return staleExecutionRecoveryService.hasActiveWorkflowRun(tenantId, projectId, workflowDefinitionId);
    }

    private boolean hasActiveCollectionTaskRun(String tenantId, Long collectionTaskId, Long projectId) {
        return staleExecutionRecoveryService.hasActiveCollectionTaskRun(tenantId, projectId, collectionTaskId);
    }

    private boolean hasActiveQualityTaskRun(String tenantId, Long qualityTaskId, Long projectId) {
        return staleExecutionRecoveryService.hasActiveQualityTaskRun(tenantId, projectId, qualityTaskId);
    }

    private WorkflowDefinitionView requireWorkflow(Long workflowDefinitionId) {
        WorkflowDefinitionView workflow = workflowService.requireRunnable(workflowDefinitionId);
        if (workflow == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow not found");
        }
        return workflow;
    }

    private Long resolveRuntimeProjectId(Long runtimeProjectId, Long ownerProjectId) {
        return runtimeProjectId != null ? runtimeProjectId : ownerProjectId;
    }

    private Long resolveCollectionTaskId(WorkflowNodeDefinition node) {
        if (node.getConfig() == null) {
            return null;
        }
        Object collectionTaskId = node.getConfig().get("collectionTaskId");
        return parseLong(collectionTaskId);
    }

    private Long resolveQualityTaskId(WorkflowNodeDefinition node) {
        if (node.getConfig() == null) {
            return null;
        }
        Object qualityTaskId = node.getConfig().get("qualityTaskId");
        return parseLong(qualityTaskId);
    }

    private Optional<Long> resolveFileTransferTaskId(WorkflowNodeDefinition node) {
        if (node == null || node.getNodeType() != NodeType.FILE_TRANSFER || node.getConfig() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(parseLong(node.getConfig().get("fileTransferTaskId")));
    }

    private String triggerLockName(String type, String tenantId, Long projectId, Long businessId, LocalDateTime scheduledFireTime) {
        String fireTime = scheduledFireTime == null ? "manual" : scheduledFireTime.toString();
        return "dispatch:" + type + ":" + safePart(tenantId) + ":" + safePart(projectId) + ":" + safePart(businessId) + ":" + fireTime;
    }

    private String safePart(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private Long parseLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Long.parseLong(((String) value).trim());
        }
        return null;
    }

    private void assertRuntimeValid(String resourceType, Long resourceId) {
        if (runtimeValidationService != null) {
            runtimeValidationService.assertResourceValid(resourceType, resourceId);
        }
    }
}
