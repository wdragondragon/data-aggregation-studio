package com.jdragon.studio.worker.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.WorkflowNodeEntity;
import com.jdragon.studio.infra.entity.WorkflowVersionEntity;
import com.jdragon.studio.infra.mapper.WorkflowNodeMapper;
import com.jdragon.studio.infra.mapper.WorkflowVersionMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

@Component
public class WorkflowDispatchNodeResolver {

    private static final TypeReference<List<FieldMappingDefinition>> FIELD_MAPPINGS_TYPE =
            new TypeReference<List<FieldMappingDefinition>>() { };

    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final ObjectMapper objectMapper;

    public WorkflowDispatchNodeResolver(WorkflowVersionMapper workflowVersionMapper,
                                        WorkflowNodeMapper workflowNodeMapper,
                                        ObjectMapper objectMapper) {
        this.workflowVersionMapper = workflowVersionMapper;
        this.workflowNodeMapper = workflowNodeMapper;
        this.objectMapper = objectMapper;
    }

    public WorkflowNodeDefinition resolve(DispatchTaskEntity task) {
        if (task == null || task.getWorkflowDefinitionId() == null
                || task.getWorkflowVersionId() == null || !hasText(task.getNodeCode())) {
            throw new IllegalStateException("Workflow dispatch is missing its immutable node reference");
        }
        WorkflowVersionEntity version = workflowVersionMapper.selectById(task.getWorkflowVersionId());
        if (!matchesTask(version, task)) {
            throw new IllegalStateException("Workflow dispatch version snapshot is unavailable");
        }
        WorkflowNodeEntity entity = workflowNodeMapper.selectOne(new LambdaQueryWrapper<WorkflowNodeEntity>()
                .eq(WorkflowNodeEntity::getWorkflowVersionId, task.getWorkflowVersionId())
                .eq(WorkflowNodeEntity::getNodeCode, task.getNodeCode())
                .eq(WorkflowNodeEntity::getTenantId, task.getTenantId())
                .eq(WorkflowNodeEntity::getProjectId, task.getProjectId())
                .last("limit 1"));
        if (entity == null || !hasText(entity.getNodeType())) {
            throw new IllegalStateException("Workflow dispatch node snapshot is unavailable");
        }
        WorkflowNodeDefinition node = new WorkflowNodeDefinition();
        node.setNodeCode(entity.getNodeCode());
        node.setNodeName(entity.getNodeName());
        try {
            node.setNodeType(NodeType.valueOf(entity.getNodeType()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Workflow dispatch node type is invalid");
        }
        node.setConfig(entity.getConfigJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(entity.getConfigJson()));
        node.setFieldMappings(entity.getFieldMappingsJson() == null
                ? new ArrayList<FieldMappingDefinition>()
                : objectMapper.convertValue(entity.getFieldMappingsJson(), FIELD_MAPPINGS_TYPE));
        return node;
    }

    private boolean matchesTask(WorkflowVersionEntity version, DispatchTaskEntity task) {
        return version != null
                && Objects.equals(version.getDefinitionId(), task.getWorkflowDefinitionId())
                && Objects.equals(version.getTenantId(), task.getTenantId())
                && Objects.equals(version.getProjectId(), task.getProjectId());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
