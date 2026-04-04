package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.EdgeCondition;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.dto.model.TransformerBinding;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.WorkflowEdgeDefinition;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.WorkflowScheduleDefinition;
import com.jdragon.studio.dto.model.request.WorkflowSaveRequest;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.entity.WorkflowEdgeEntity;
import com.jdragon.studio.infra.entity.WorkflowNodeEntity;
import com.jdragon.studio.infra.entity.WorkflowScheduleEntity;
import com.jdragon.studio.infra.entity.WorkflowVersionEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkflowEdgeMapper;
import com.jdragon.studio.infra.mapper.WorkflowNodeMapper;
import com.jdragon.studio.infra.mapper.WorkflowScheduleMapper;
import com.jdragon.studio.infra.mapper.WorkflowVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowService {

    private final WorkflowDefinitionMapper definitionMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final WorkflowScheduleMapper scheduleMapper;
    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;

    public WorkflowService(WorkflowDefinitionMapper definitionMapper,
                           WorkflowVersionMapper versionMapper,
                           WorkflowNodeMapper nodeMapper,
                           WorkflowEdgeMapper edgeMapper,
                           WorkflowScheduleMapper scheduleMapper,
                           DispatchTaskMapper dispatchTaskMapper,
                           RunRecordMapper runRecordMapper) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.scheduleMapper = scheduleMapper;
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
    }

    public List<WorkflowDefinitionView> list() {
        List<WorkflowDefinitionEntity> definitions = definitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .orderByAsc(WorkflowDefinitionEntity::getCode));
        List<WorkflowDefinitionView> result = new ArrayList<WorkflowDefinitionView>();
        for (WorkflowDefinitionEntity definition : definitions) {
            result.add(get(definition.getId()));
        }
        return result;
    }

    public WorkflowDefinitionView get(Long definitionId) {
        WorkflowDefinitionEntity definition = definitionMapper.selectById(definitionId);
        if (definition == null) {
            return null;
        }
        WorkflowDefinitionView view = new WorkflowDefinitionView();
        view.setId(definition.getId());
        view.setCode(definition.getCode());
        view.setName(definition.getName());
        view.setPublished(definition.getPublished() != null && definition.getPublished() == 1);
        WorkflowVersionEntity version = definition.getCurrentVersionId() == null ? null : versionMapper.selectById(definition.getCurrentVersionId());
        if (version != null) {
            view.setVersionId(version.getId());
            view.setVersionNumber(version.getVersionNumber());
            List<WorkflowNodeEntity> nodeEntities = nodeMapper.selectList(new LambdaQueryWrapper<WorkflowNodeEntity>()
                    .eq(WorkflowNodeEntity::getWorkflowVersionId, version.getId()));
            List<WorkflowEdgeEntity> edgeEntities = edgeMapper.selectList(new LambdaQueryWrapper<WorkflowEdgeEntity>()
                    .eq(WorkflowEdgeEntity::getWorkflowVersionId, version.getId()));
            List<WorkflowNodeDefinition> nodes = new ArrayList<WorkflowNodeDefinition>();
            for (WorkflowNodeEntity nodeEntity : nodeEntities) {
                WorkflowNodeDefinition node = new WorkflowNodeDefinition();
                node.setNodeCode(nodeEntity.getNodeCode());
                node.setNodeName(nodeEntity.getNodeName());
                if (nodeEntity.getNodeType() != null) {
                    node.setNodeType(NodeType.valueOf(nodeEntity.getNodeType()));
                }
                node.setConfig(nodeEntity.getConfigJson());
                List<FieldMappingDefinition> mappings = new ArrayList<FieldMappingDefinition>();
                if (nodeEntity.getFieldMappingsJson() != null) {
                    for (Map<String, Object> item : nodeEntity.getFieldMappingsJson()) {
                        FieldMappingDefinition mapping = new FieldMappingDefinition();
                        mapping.setSourceAlias(asString(item.get("sourceAlias")));
                        mapping.setSourceField(asString(item.get("sourceField")));
                        mapping.setTargetField(asString(item.get("targetField")));
                        mapping.setExpression(item.get("expression") == null ? null : String.valueOf(item.get("expression")));
                        mapping.setTransformers(toTransformers(item.get("transformers")));
                        mappings.add(mapping);
                    }
                }
                node.setFieldMappings(mappings);
                nodes.add(node);
            }
            List<WorkflowEdgeDefinition> edges = new ArrayList<WorkflowEdgeDefinition>();
            for (WorkflowEdgeEntity edgeEntity : edgeEntities) {
                WorkflowEdgeDefinition edge = new WorkflowEdgeDefinition();
                edge.setFromNodeCode(edgeEntity.getFromNodeCode());
                edge.setToNodeCode(edgeEntity.getToNodeCode());
                if (edgeEntity.getConditionType() != null) {
                    edge.setCondition(EdgeCondition.valueOf(edgeEntity.getConditionType()));
                }
                edges.add(edge);
            }
            view.setNodes(nodes);
            view.setEdges(edges);
        }
        WorkflowScheduleEntity scheduleEntity = scheduleMapper.selectOne(new LambdaQueryWrapper<WorkflowScheduleEntity>()
                .eq(WorkflowScheduleEntity::getWorkflowDefinitionId, definitionId)
                .last("limit 1"));
        if (scheduleEntity != null) {
            WorkflowScheduleDefinition schedule = new WorkflowScheduleDefinition();
            schedule.setCronExpression(scheduleEntity.getCronExpression());
            schedule.setEnabled(scheduleEntity.getEnabled() != null && scheduleEntity.getEnabled() == 1);
            schedule.setTimezone(scheduleEntity.getTimezone());
            view.setSchedule(schedule);
        }
        return view;
    }

    @Transactional
    public WorkflowDefinitionView save(WorkflowSaveRequest request) {
        validateGraph(request);
        WorkflowDefinitionEntity definition = request.getDefinitionId() == null
                ? new WorkflowDefinitionEntity()
                : definitionMapper.selectById(request.getDefinitionId());
        if (definition == null) {
            definition = new WorkflowDefinitionEntity();
        }
        definition.setCode(request.getCode());
        definition.setName(request.getName());
        if (definition.getId() == null) {
            definitionMapper.insert(definition);
        } else {
            definitionMapper.updateById(definition);
        }

        WorkflowVersionEntity version = new WorkflowVersionEntity();
        version.setDefinitionId(definition.getId());
        version.setVersionNumber(nextVersion(definition.getId()));
        version.setPublished(0);
        version.setGraphJson(new LinkedHashMap<String, Object>());
        version.setScheduleJson(toScheduleJson(request.getSchedule()));
        versionMapper.insert(version);

        for (WorkflowNodeDefinition node : request.getNodes()) {
            WorkflowNodeEntity entity = new WorkflowNodeEntity();
            entity.setWorkflowVersionId(version.getId());
            entity.setNodeCode(node.getNodeCode());
            entity.setNodeName(node.getNodeName());
            entity.setNodeType(node.getNodeType() == null ? null : node.getNodeType().name());
            entity.setConfigJson(node.getConfig());
            entity.setFieldMappingsJson(toMappings(node.getFieldMappings()));
            nodeMapper.insert(entity);
        }

        for (WorkflowEdgeDefinition edge : request.getEdges()) {
            WorkflowEdgeEntity entity = new WorkflowEdgeEntity();
            entity.setWorkflowVersionId(version.getId());
            entity.setFromNodeCode(edge.getFromNodeCode());
            entity.setToNodeCode(edge.getToNodeCode());
            entity.setConditionType(edge.getCondition() == null ? EdgeCondition.ON_SUCCESS.name() : edge.getCondition().name());
            edgeMapper.insert(entity);
        }

        WorkflowScheduleEntity scheduleEntity = scheduleMapper.selectOne(new LambdaQueryWrapper<WorkflowScheduleEntity>()
                .eq(WorkflowScheduleEntity::getWorkflowDefinitionId, definition.getId())
                .last("limit 1"));
        if (scheduleEntity == null) {
            scheduleEntity = new WorkflowScheduleEntity();
            scheduleEntity.setWorkflowDefinitionId(definition.getId());
        }
        if (request.getSchedule() != null) {
            scheduleEntity.setCronExpression(request.getSchedule().getCronExpression());
            scheduleEntity.setEnabled(Boolean.TRUE.equals(request.getSchedule().getEnabled()) ? 1 : 0);
            scheduleEntity.setTimezone(request.getSchedule().getTimezone());
            if (scheduleEntity.getId() == null) {
                scheduleMapper.insert(scheduleEntity);
            } else {
                scheduleMapper.updateById(scheduleEntity);
            }
        }

        definition.setCurrentVersionId(version.getId());
        definitionMapper.updateById(definition);
        return get(definition.getId());
    }

    @Transactional
    public WorkflowDefinitionView publish(Long definitionId) {
        WorkflowDefinitionEntity definition = definitionMapper.selectById(definitionId);
        if (definition == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow not found");
        }
        WorkflowVersionEntity version = versionMapper.selectById(definition.getCurrentVersionId());
        if (version == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Workflow version not found");
        }
        version.setPublished(1);
        versionMapper.updateById(version);
        definition.setPublished(1);
        definitionMapper.updateById(definition);
        return get(definitionId);
    }

    @Transactional
    public void delete(Long definitionId) {
        List<WorkflowVersionEntity> versions = versionMapper.selectList(new LambdaQueryWrapper<WorkflowVersionEntity>()
                .eq(WorkflowVersionEntity::getDefinitionId, definitionId));
        List<Long> versionIds = new ArrayList<Long>();
        for (WorkflowVersionEntity version : versions) {
            versionIds.add(version.getId());
        }
        if (!versionIds.isEmpty()) {
            nodeMapper.delete(new LambdaQueryWrapper<WorkflowNodeEntity>()
                    .in(WorkflowNodeEntity::getWorkflowVersionId, versionIds));
            edgeMapper.delete(new LambdaQueryWrapper<WorkflowEdgeEntity>()
                    .in(WorkflowEdgeEntity::getWorkflowVersionId, versionIds));
        }
        versionMapper.delete(new LambdaQueryWrapper<WorkflowVersionEntity>()
                .eq(WorkflowVersionEntity::getDefinitionId, definitionId));
        scheduleMapper.delete(new LambdaQueryWrapper<WorkflowScheduleEntity>()
                .eq(WorkflowScheduleEntity::getWorkflowDefinitionId, definitionId));
        dispatchTaskMapper.delete(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getWorkflowDefinitionId, definitionId));
        runRecordMapper.delete(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getWorkflowDefinitionId, definitionId));
        definitionMapper.deleteById(definitionId);
    }

    private int nextVersion(Long definitionId) {
        List<WorkflowVersionEntity> versions = versionMapper.selectList(new LambdaQueryWrapper<WorkflowVersionEntity>()
                .eq(WorkflowVersionEntity::getDefinitionId, definitionId));
        int max = 0;
        for (WorkflowVersionEntity version : versions) {
            if (version.getVersionNumber() != null && version.getVersionNumber() > max) {
                max = version.getVersionNumber();
            }
        }
        return max + 1;
    }

    private Map<String, Object> toScheduleJson(WorkflowScheduleDefinition schedule) {
        Map<String, Object> map = new HashMap<String, Object>();
        if (schedule == null) {
            return map;
        }
        map.put("cronExpression", schedule.getCronExpression());
        map.put("enabled", schedule.getEnabled());
        map.put("timezone", schedule.getTimezone());
        return map;
    }

    private List<Map<String, Object>> toMappings(List<FieldMappingDefinition> mappings) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (mappings == null) {
            return items;
        }
        for (FieldMappingDefinition mapping : mappings) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("sourceAlias", mapping.getSourceAlias());
            item.put("sourceField", mapping.getSourceField());
            item.put("targetField", mapping.getTargetField());
            item.put("expression", mapping.getExpression());
            item.put("transformers", toTransformerMaps(mapping.getTransformers()));
            items.add(item);
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private List<TransformerBinding> toTransformers(Object candidate) {
        List<TransformerBinding> result = new ArrayList<TransformerBinding>();
        if (!(candidate instanceof List)) {
            return result;
        }
        for (Object item : (List<Object>) candidate) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) item;
            TransformerBinding binding = new TransformerBinding();
            binding.setTransformerCode(asString(map.get("transformerCode")));
            Object parameters = map.get("parameters");
            if (parameters instanceof Map) {
                binding.setParameters(new LinkedHashMap<String, Object>((Map<String, Object>) parameters));
            }
            result.add(binding);
        }
        return result;
    }

    private List<Map<String, Object>> toTransformerMaps(List<TransformerBinding> bindings) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (bindings == null) {
            return items;
        }
        for (TransformerBinding binding : bindings) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("transformerCode", binding.getTransformerCode());
            item.put("parameters", binding.getParameters());
            items.add(item);
        }
        return items;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return "null".equalsIgnoreCase(text) ? null : text;
    }

    private void validateGraph(WorkflowSaveRequest request) {
        Map<String, Integer> inbound = new HashMap<String, Integer>();
        for (WorkflowNodeDefinition node : request.getNodes()) {
            inbound.put(node.getNodeCode(), 0);
        }
        for (WorkflowEdgeDefinition edge : request.getEdges()) {
            if (!inbound.containsKey(edge.getFromNodeCode()) || !inbound.containsKey(edge.getToNodeCode())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Edge references undefined node");
            }
            inbound.put(edge.getToNodeCode(), inbound.get(edge.getToNodeCode()) + 1);
        }
        if (request.getNodes().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Workflow requires at least one node");
        }
    }
}

