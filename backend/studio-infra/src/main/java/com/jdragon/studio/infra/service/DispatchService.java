package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.core.spi.WorkflowDispatcher;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.enums.EdgeCondition;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.WorkflowEdgeDefinition;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DispatchService implements WorkflowDispatcher {

    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final WorkflowService workflowService;
    private final CollectionTaskService collectionTaskService;
    private final StudioSecurityService securityService;
    private final WorkerAuthorizationService workerAuthorizationService;

    public DispatchService(DispatchTaskMapper dispatchTaskMapper,
                           RunRecordMapper runRecordMapper,
                           WorkflowDefinitionMapper workflowDefinitionMapper,
                           WorkflowService workflowService,
                           CollectionTaskService collectionTaskService,
                           StudioSecurityService securityService,
                           WorkerAuthorizationService workerAuthorizationService) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.workflowService = workflowService;
        this.collectionTaskService = collectionTaskService;
        this.securityService = securityService;
        this.workerAuthorizationService = workerAuthorizationService;
    }

    @Override
    public void dispatchReadyNodes() {
        List<WorkflowDefinitionEntity> definitions = workflowDefinitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .eq(WorkflowDefinitionEntity::getPublished, 1));
        for (WorkflowDefinitionEntity definition : definitions) {
            if (!workerAuthorizationService.hasAvailableWorker(definition.getTenantId(), definition.getProjectId())) {
                continue;
            }
            triggerWorkflowIfIdle(definition.getId(), definition.getProjectId());
        }
    }

    @Override
    @Transactional
    public void triggerManualRun(Long workflowDefinitionId) {
        WorkflowDefinitionView workflow = requireWorkflow(workflowDefinitionId);
        Long runtimeProjectId = resolveRuntimeProjectId(securityService.currentProjectId(), workflow.getProjectId());
        workerAuthorizationService.assertProjectHasAvailableWorker(workflow.getTenantId(), runtimeProjectId);
        if (!triggerWorkflowIfIdle(workflow, runtimeProjectId, true)) {
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
    public void triggerCollectionTask(Long collectionTaskId) {
        CollectionTaskDefinitionView definition = collectionTaskService.requireOnline(collectionTaskId);
        Long runtimeProjectId = resolveRuntimeProjectId(securityService.currentProjectId(), definition.getProjectId());
        workerAuthorizationService.assertProjectHasAvailableWorker(definition.getTenantId(), runtimeProjectId);
        if (!triggerCollectionTaskIfIdle(definition, runtimeProjectId, true)) {
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

    public List<DispatchTaskEntity> queuedTasks() {
        LambdaQueryWrapper<DispatchTaskEntity> queryWrapper = new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, securityService.currentTenantId())
                .eq(DispatchTaskEntity::getStatus, "QUEUED");
        if (securityService.currentProjectId() != null) {
            queryWrapper.eq(DispatchTaskEntity::getProjectId, securityService.currentProjectId());
        }
        return dispatchTaskMapper.selectList(queryWrapper);
    }

    private boolean triggerWorkflowIfIdle(WorkflowDefinitionView workflow,
                                          Long runtimeProjectId,
                                          boolean workerRequired) {
        Long resolvedProjectId = resolveRuntimeProjectId(runtimeProjectId, workflow.getProjectId());
        if (!workerAuthorizationService.hasAvailableWorker(workflow.getTenantId(), resolvedProjectId)) {
            if (workerRequired) {
                workerAuthorizationService.assertProjectHasAvailableWorker(workflow.getTenantId(), resolvedProjectId);
            }
            return false;
        }
        if (hasActiveWorkflowRun(workflow.getTenantId(), workflow.getId(), resolvedProjectId)) {
            return false;
        }
        Long workflowRunId = IdWorker.getId();
        Set<String> inbound = new HashSet<String>();
        for (WorkflowEdgeDefinition edge : workflow.getEdges()) {
            inbound.add(edge.getToNodeCode());
        }
        for (WorkflowNodeDefinition node : workflow.getNodes()) {
            if (!inbound.contains(node.getNodeCode())) {
                dispatchTaskMapper.insert(buildWorkflowNodeTask(workflow, workflowRunId, node, resolvedProjectId));
            }
        }
        return true;
    }

    @Transactional
    public void continueWorkflowRun(ExecutionEvent event) {
        if (event == null
                || event.getExecutionType() != DispatchExecutionType.WORKFLOW_NODE
                || event.getWorkflowDefinitionId() == null
                || event.getWorkflowRunId() == null
                || event.getNodeCode() == null
                || !isTerminalStatus(event.getEventType())) {
            return;
        }

        WorkflowDefinitionView workflow = workflowService.get(event.getWorkflowDefinitionId());
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
            dispatchTaskMapper.insert(buildWorkflowNodeTask(workflow, event.getWorkflowRunId(), candidate, runtimeProjectId));
        }
    }

    private DispatchTaskEntity buildWorkflowNodeTask(WorkflowDefinitionView workflow,
                                                     Long workflowRunId,
                                                     WorkflowNodeDefinition node,
                                                     Long runtimeProjectId) {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setTenantId(workflow.getTenantId());
        task.setProjectId(runtimeProjectId);
        task.setExecutionType(DispatchExecutionType.WORKFLOW_NODE.name());
        task.setWorkflowRunId(workflowRunId);
        task.setWorkflowDefinitionId(workflow.getId());
        task.setWorkflowVersionId(workflow.getVersionId());
        task.setCollectionTaskId(resolveCollectionTaskId(node));
        task.setNodeCode(node.getNodeCode());
        task.setStatus("QUEUED");
        task.setAttempts(0);
        task.setMaxRetries(3);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executionType", DispatchExecutionType.WORKFLOW_NODE.name());
        payload.put("workflowRunId", workflowRunId);
        payload.put("nodeType", node.getNodeType() == null ? null : node.getNodeType().name());
        payload.put("config", node.getConfig());
        payload.put("fieldMappings", node.getFieldMappings());
        payload.put("projectId", runtimeProjectId);
        task.setPayloadJson(payload);
        return task;
    }

    private boolean triggerCollectionTaskIfIdle(CollectionTaskDefinitionView definition,
                                                Long runtimeProjectId,
                                                boolean workerRequired) {
        Long resolvedProjectId = resolveRuntimeProjectId(runtimeProjectId, definition.getProjectId());
        if (!workerAuthorizationService.hasAvailableWorker(definition.getTenantId(), resolvedProjectId)) {
            if (workerRequired) {
                workerAuthorizationService.assertProjectHasAvailableWorker(definition.getTenantId(), resolvedProjectId);
            }
            return false;
        }
        if (hasActiveCollectionTaskRun(definition.getTenantId(), definition.getId(), resolvedProjectId)) {
            return false;
        }
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setTenantId(definition.getTenantId());
        task.setProjectId(resolvedProjectId);
        task.setExecutionType(DispatchExecutionType.COLLECTION_TASK.name());
        task.setCollectionTaskId(definition.getId());
        task.setNodeCode("collection_task_" + definition.getId());
        task.setStatus("QUEUED");
        task.setAttempts(0);
        task.setMaxRetries(3);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executionType", DispatchExecutionType.COLLECTION_TASK.name());
        payload.put("nodeType", "COLLECTION_TASK");
        payload.put("collectionTaskId", definition.getId());
        payload.put("projectId", resolvedProjectId);
        task.setPayloadJson(payload);
        dispatchTaskMapper.insert(task);
        return true;
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
            return "FAILED".equalsIgnoreCase(eventType);
        }
        return "SUCCESS".equalsIgnoreCase(eventType);
    }

    private boolean isTerminalStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }

    private boolean hasActiveWorkflowRun(String tenantId, Long workflowDefinitionId, Long projectId) {
        if (workflowDefinitionId == null || tenantId == null) {
            return false;
        }
        Long activeTasks = dispatchTaskMapper.selectCount(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, tenantId)
                .eq(DispatchTaskEntity::getWorkflowDefinitionId, workflowDefinitionId)
                .eq(projectId != null, DispatchTaskEntity::getProjectId, projectId)
                .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING"));
        if (activeTasks != null && activeTasks > 0) {
            return true;
        }
        Long activeRecords = runRecordMapper.selectCount(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getTenantId, tenantId)
                .eq(RunRecordEntity::getWorkflowDefinitionId, workflowDefinitionId)
                .eq(projectId != null, RunRecordEntity::getProjectId, projectId)
                .eq(RunRecordEntity::getStatus, "RUNNING"));
        return activeRecords != null && activeRecords > 0;
    }

    private boolean hasActiveCollectionTaskRun(String tenantId, Long collectionTaskId, Long projectId) {
        if (collectionTaskId == null || tenantId == null) {
            return false;
        }
        Long activeTasks = dispatchTaskMapper.selectCount(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, tenantId)
                .eq(DispatchTaskEntity::getCollectionTaskId, collectionTaskId)
                .eq(projectId != null, DispatchTaskEntity::getProjectId, projectId)
                .in(DispatchTaskEntity::getStatus, "QUEUED", "RUNNING"));
        if (activeTasks != null && activeTasks > 0) {
            return true;
        }
        Long activeRecords = runRecordMapper.selectCount(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getTenantId, tenantId)
                .eq(RunRecordEntity::getCollectionTaskId, collectionTaskId)
                .eq(projectId != null, RunRecordEntity::getProjectId, projectId)
                .eq(RunRecordEntity::getStatus, "RUNNING"));
        return activeRecords != null && activeRecords > 0;
    }

    private WorkflowDefinitionView requireWorkflow(Long workflowDefinitionId) {
        WorkflowDefinitionView workflow = workflowService.get(workflowDefinitionId);
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

    private Long parseLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Long.parseLong(((String) value).trim());
        }
        return null;
    }
}
