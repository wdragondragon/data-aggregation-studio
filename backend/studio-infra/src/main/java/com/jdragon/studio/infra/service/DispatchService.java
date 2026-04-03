package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.WorkflowEdgeDefinition;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.core.spi.WorkflowDispatcher;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

@Service
public class DispatchService implements WorkflowDispatcher {

    private final DispatchTaskMapper dispatchTaskMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final WorkflowService workflowService;

    public DispatchService(DispatchTaskMapper dispatchTaskMapper,
                           WorkflowDefinitionMapper workflowDefinitionMapper,
                           WorkflowService workflowService) {
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.workflowService = workflowService;
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
        Set<String> inbound = new HashSet<String>();
        for (WorkflowEdgeDefinition edge : workflow.getEdges()) {
            inbound.add(edge.getToNodeCode());
        }
        for (WorkflowNodeDefinition node : workflow.getNodes()) {
            if (!inbound.contains(node.getNodeCode())) {
                DispatchTaskEntity task = new DispatchTaskEntity();
                task.setWorkflowDefinitionId(workflow.getId());
                task.setWorkflowVersionId(workflow.getVersionId());
                task.setNodeCode(node.getNodeCode());
                task.setStatus("QUEUED");
                task.setAttempts(0);
                task.setMaxRetries(3);
                LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("nodeType", node.getNodeType() == null ? null : node.getNodeType().name());
                payload.put("config", node.getConfig());
                payload.put("fieldMappings", node.getFieldMappings());
                task.setPayloadJson(payload);
                dispatchTaskMapper.insert(task);
            }
        }
    }

    public List<DispatchTaskEntity> queuedTasks() {
        return dispatchTaskMapper.selectList(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getStatus, "QUEUED"));
    }
}

