package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.enums.EdgeCondition;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.WorkflowEdgeDefinition;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.core.spi.WorkflowDispatcher;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    public DispatchService(DispatchTaskMapper dispatchTaskMapper,
                           RunRecordMapper runRecordMapper,
                           WorkflowDefinitionMapper workflowDefinitionMapper,
                           WorkflowService workflowService,
                           CollectionTaskService collectionTaskService) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.workflowService = workflowService;
        this.collectionTaskService = collectionTaskService;
    }

    @Override
    public void dispatchReadyNodes() {
        List<WorkflowDefinitionEntity> definitions = workflowDefinitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .eq(WorkflowDefinitionEntity::getPublished, 1));
        for (WorkflowDefinitionEntity definition : definitions) {
            triggerManualRun(definition.getId());
        }
    }

    @Override
    @Transactional
    public void triggerManualRun(Long workflowDefinitionId) {
        WorkflowDefinitionView workflow = workflowService.get(workflowDefinitionId);
        if (workflow == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow not found");
        }
        Long workflowRunId = IdWorker.getId();
        Set<String> inbound = new HashSet<String>();
        for (WorkflowEdgeDefinition edge : workflow.getEdges()) {
            inbound.add(edge.getToNodeCode());
        }
        for (WorkflowNodeDefinition node : workflow.getNodes()) {
            if (!inbound.contains(node.getNodeCode())) {
                dispatchTaskMapper.insert(buildWorkflowNodeTask(workflow, workflowRunId, node));
            }
        }
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

        for (WorkflowNodeDefinition candidate : collectDownstreamNodes(workflow, event.getNodeCode(), event.getEventType())) {
            if (alreadyDispatched(event.getWorkflowRunId(), candidate.getNodeCode())) {
                continue;
            }
            if (!isNodeReady(workflow, event.getWorkflowRunId(), candidate.getNodeCode())) {
                continue;
            }
            dispatchTaskMapper.insert(buildWorkflowNodeTask(workflow, event.getWorkflowRunId(), candidate));
        }
    }

    @Transactional
    public void triggerCollectionTask(Long collectionTaskId) {
        collectionTaskService.requireOnline(collectionTaskId);
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setExecutionType(DispatchExecutionType.COLLECTION_TASK.name());
        task.setCollectionTaskId(collectionTaskId);
        task.setNodeCode("collection_task_" + collectionTaskId);
        task.setStatus("QUEUED");
        task.setAttempts(0);
        task.setMaxRetries(3);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("executionType", DispatchExecutionType.COLLECTION_TASK.name());
        payload.put("nodeType", "COLLECTION_TASK");
        payload.put("collectionTaskId", collectionTaskId);
        task.setPayloadJson(payload);
        dispatchTaskMapper.insert(task);
    }

    public List<DispatchTaskEntity> queuedTasks() {
        return dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getStatus, "QUEUED"));
    }

    private DispatchTaskEntity buildWorkflowNodeTask(WorkflowDefinitionView workflow,
                                                     Long workflowRunId,
                                                     WorkflowNodeDefinition node) {
        DispatchTaskEntity task = new DispatchTaskEntity();
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
        task.setPayloadJson(payload);
        return task;
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

    private Long resolveCollectionTaskId(WorkflowNodeDefinition node) {
        if (node.getConfig() == null) {
            return null;
        }
        Object collectionTaskId = node.getConfig().get("collectionTaskId");
        if (collectionTaskId instanceof Number) {
            return ((Number) collectionTaskId).longValue();
        }
        if (collectionTaskId instanceof String && !((String) collectionTaskId).trim().isEmpty()) {
            return Long.parseLong(((String) collectionTaskId).trim());
        }
        return null;
    }
}

