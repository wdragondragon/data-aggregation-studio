package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.EdgeCondition;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.TransformerBinding;
import com.jdragon.studio.dto.model.WorkflowDefinitionView;
import com.jdragon.studio.dto.model.WorkflowEdgeDefinition;
import com.jdragon.studio.dto.model.WorkflowListView;
import com.jdragon.studio.dto.model.WorkflowOptionView;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.dto.model.WorkflowScheduleDefinition;
import com.jdragon.studio.dto.model.request.WorkflowSaveRequest;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.entity.WorkflowEdgeEntity;
import com.jdragon.studio.infra.entity.WorkflowNodeEntity;
import com.jdragon.studio.infra.entity.WorkflowScheduleEntity;
import com.jdragon.studio.infra.entity.WorkflowVersionEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.QualityTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.FileTransferTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.FileTransferTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkflowEdgeMapper;
import com.jdragon.studio.infra.mapper.WorkflowNodeMapper;
import com.jdragon.studio.infra.mapper.WorkflowScheduleMapper;
import com.jdragon.studio.infra.mapper.WorkflowVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Set;

@Service
public class WorkflowService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 500;

    private final WorkflowDefinitionMapper definitionMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final WorkflowScheduleMapper scheduleMapper;
    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private RuntimeClusterSelectionService runtimeClusterSelectionService;
    private RuntimeResourceRevisionService runtimeResourceRevisionService;
    private CollectionTaskDefinitionMapper collectionTaskDefinitionMapper;
    private QualityTaskDefinitionMapper qualityTaskDefinitionMapper;
    private DataDevelopmentScriptMapper dataDevelopmentScriptMapper;
    private DataModelMapper dataModelMapper;
    private FileTransferTaskDefinitionMapper fileTransferTaskDefinitionMapper;
    private WorkflowConsistencyBindingValidator workflowConsistencyBindingValidator;

    public WorkflowService(WorkflowDefinitionMapper definitionMapper,
                           WorkflowVersionMapper versionMapper,
                           WorkflowNodeMapper nodeMapper,
                           WorkflowEdgeMapper edgeMapper,
                           WorkflowScheduleMapper scheduleMapper,
                           DispatchTaskMapper dispatchTaskMapper,
                           RunRecordMapper runRecordMapper,
                           StudioSecurityService securityService,
                           ProjectResourceAccessService projectResourceAccessService) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.scheduleMapper = scheduleMapper;
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    public List<WorkflowDefinitionView> list() {
        List<WorkflowDefinitionEntity> definitions = definitionMapper.selectList(buildAccessibleQuery()
                .orderByAsc(WorkflowDefinitionEntity::getCode));
        List<WorkflowDefinitionView> result = new ArrayList<WorkflowDefinitionView>();
        for (WorkflowDefinitionEntity definition : definitions) {
            result.add(get(definition.getId()));
        }
        return result;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeClusterSelectionService(RuntimeClusterSelectionService runtimeClusterSelectionService) { this.runtimeClusterSelectionService = runtimeClusterSelectionService; }

    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeResourceRevisionService(RuntimeResourceRevisionService runtimeResourceRevisionService) {
        this.runtimeResourceRevisionService = runtimeResourceRevisionService;
    }

    @Autowired
    void setWorkflowResourceMappers(CollectionTaskDefinitionMapper collectionTaskDefinitionMapper,
                                    QualityTaskDefinitionMapper qualityTaskDefinitionMapper,
                                    DataDevelopmentScriptMapper dataDevelopmentScriptMapper,
                                    DataModelMapper dataModelMapper,
                                    FileTransferTaskDefinitionMapper fileTransferTaskDefinitionMapper) {
        this.collectionTaskDefinitionMapper = collectionTaskDefinitionMapper;
        this.qualityTaskDefinitionMapper = qualityTaskDefinitionMapper;
        this.dataDevelopmentScriptMapper = dataDevelopmentScriptMapper;
        this.dataModelMapper = dataModelMapper;
        this.fileTransferTaskDefinitionMapper = fileTransferTaskDefinitionMapper;
    }

    @Autowired
    void setWorkflowConsistencyBindingValidator(
            WorkflowConsistencyBindingValidator workflowConsistencyBindingValidator) {
        this.workflowConsistencyBindingValidator = workflowConsistencyBindingValidator;
    }

    public List<WorkflowListView> listSummaries() {
        List<WorkflowDefinitionEntity> definitions = definitionMapper.selectList(selectSummaryColumns(buildAccessibleQuery())
                .orderByAsc(WorkflowDefinitionEntity::getCode));
        return toListViews(definitions);
    }

    public PageView<WorkflowListView> listSummaryPage(Integer pageNo, Integer pageSize) {
        int current = normalizePageNo(pageNo);
        int size = normalizePageSize(pageSize);
        Page<WorkflowDefinitionEntity> page = new Page<WorkflowDefinitionEntity>(current, size);
        LambdaQueryWrapper<WorkflowDefinitionEntity> queryWrapper = selectSummaryColumns(buildAccessibleQuery())
                .orderByAsc(WorkflowDefinitionEntity::getCode);
        Page<WorkflowDefinitionEntity> entityPage = definitionMapper.selectPage(page, queryWrapper);
        return PageView.of(current, size, entityPage.getTotal(), toListViews(entityPage.getRecords()));
    }

    public List<WorkflowOptionView> listOptions() {
        return listOptions(null);
    }

    public List<WorkflowOptionView> listOptions(Long runtimeClusterId) {
        List<WorkflowDefinitionEntity> definitions = definitionMapper.selectList(buildAccessibleQuery()
                .select(WorkflowDefinitionEntity::getId,
                        WorkflowDefinitionEntity::getProjectId,
                        WorkflowDefinitionEntity::getRuntimeClusterId,
                        WorkflowDefinitionEntity::getName)
                .eq(runtimeClusterId != null, WorkflowDefinitionEntity::getRuntimeClusterId, runtimeClusterId)
                .orderByAsc(WorkflowDefinitionEntity::getName)
                .orderByAsc(WorkflowDefinitionEntity::getId));
        List<WorkflowOptionView> result = new ArrayList<WorkflowOptionView>();
        for (WorkflowDefinitionEntity definition : definitions) {
            WorkflowOptionView view = new WorkflowOptionView();
            view.setId(definition.getId());
            view.setProjectId(definition.getProjectId());
            view.setRuntimeClusterId(definition.getRuntimeClusterId());
            view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null
                    : runtimeClusterSelectionService.runtimeClusterName(definition.getProjectId(), definition.getRuntimeClusterId()));
            view.setName(definition.getName());
            result.add(view);
        }
        return result;
    }

    private List<WorkflowListView> toListViews(List<WorkflowDefinitionEntity> definitions) {
        Map<Long, WorkflowScheduleDefinition> schedules = scheduleMap(definitions);
        List<WorkflowListView> result = new ArrayList<WorkflowListView>();
        for (WorkflowDefinitionEntity definition : definitions) {
            WorkflowListView view = toBasicListView(definition);
            view.setSchedule(schedules.get(definition.getId()));
            result.add(view);
        }
        if (runtimeClusterSelectionService != null) {
            runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_WORKFLOW, result);
        }
        return result;
    }

    public Long countPublishedSummaries() {
        Long count = definitionMapper.selectCount(buildAccessibleQuery()
                .eq(WorkflowDefinitionEntity::getPublished, Integer.valueOf(1)));
        return count == null ? 0L : count;
    }

    public Long countScheduledSummaries() {
        List<WorkflowDefinitionEntity> definitions = definitionMapper.selectList(buildAccessibleQuery()
                .select(WorkflowDefinitionEntity::getId));
        if (definitions.isEmpty()) {
            return 0L;
        }
        List<Long> definitionIds = new ArrayList<Long>();
        for (WorkflowDefinitionEntity definition : definitions) {
            if (definition.getId() != null) {
                definitionIds.add(definition.getId());
            }
        }
        if (definitionIds.isEmpty()) {
            return 0L;
        }
        List<WorkflowScheduleEntity> schedules = scheduleMapper.selectList(new LambdaQueryWrapper<WorkflowScheduleEntity>()
                .select(WorkflowScheduleEntity::getWorkflowDefinitionId)
                .in(WorkflowScheduleEntity::getWorkflowDefinitionId, definitionIds)
                .eq(WorkflowScheduleEntity::getEnabled, Integer.valueOf(1)));
        Set<Long> scheduledDefinitionIds = new HashSet<Long>();
        for (WorkflowScheduleEntity schedule : schedules) {
            if (schedule.getWorkflowDefinitionId() != null) {
                scheduledDefinitionIds.add(schedule.getWorkflowDefinitionId());
            }
        }
        return Long.valueOf(scheduledDefinitionIds.size());
    }

    public List<WorkflowListView> listRecentSummaries(int limit) {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 20);
        List<WorkflowDefinitionEntity> definitions = definitionMapper.selectList(selectSummaryColumns(buildAccessibleQuery())
                .orderByDesc(WorkflowDefinitionEntity::getUpdatedAt)
                .orderByDesc(WorkflowDefinitionEntity::getCreatedAt)
                .last("limit " + safeLimit));
        return toListViews(definitions);
    }

    public List<WorkflowDefinitionView> listOwnedByCurrentProject() {
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        List<WorkflowDefinitionEntity> definitions = definitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .eq(WorkflowDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(WorkflowDefinitionEntity::getProjectId, currentProjectId)
                .orderByAsc(WorkflowDefinitionEntity::getCode));
        List<WorkflowDefinitionView> result = new ArrayList<WorkflowDefinitionView>();
        for (WorkflowDefinitionEntity definition : definitions) {
            result.add(get(definition.getId()));
        }
        return result;
    }

    public List<WorkflowListView> listOwnedSummariesByCurrentProject() {
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        List<WorkflowDefinitionEntity> definitions = definitionMapper.selectList(selectSummaryColumns(new LambdaQueryWrapper<WorkflowDefinitionEntity>())
                .eq(WorkflowDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(WorkflowDefinitionEntity::getProjectId, currentProjectId)
                .orderByAsc(WorkflowDefinitionEntity::getCode));
        return toListViews(definitions);
    }

    public WorkflowDefinitionView get(Long definitionId) {
        WorkflowDefinitionEntity definition = findAccessibleEntity(definitionId);
        if (definition == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow not found: " + definitionId);
        }
        WorkflowVersionEntity version = definition.getCurrentVersionId() == null
                ? null : versionMapper.selectById(definition.getCurrentVersionId());
        WorkflowDefinitionView view = toView(definition, version);
        return runtimeClusterSelectionService == null ? view
                : runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_WORKFLOW, view);
    }

    public WorkflowDefinitionView getVersion(Long definitionId, Long versionId) {
        WorkflowDefinitionEntity definition = findAccessibleEntity(definitionId);
        if (definition == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow not found: " + definitionId);
        }
        WorkflowVersionEntity version = versionId == null ? null : versionMapper.selectById(versionId);
        if (version == null || !definitionId.equals(version.getDefinitionId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow version not found: " + versionId);
        }
        WorkflowDefinitionView view = toView(definition, version);
        return runtimeClusterSelectionService == null ? view
                : runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_WORKFLOW, view);
    }

    private WorkflowDefinitionView toView(WorkflowDefinitionEntity definition, WorkflowVersionEntity version) {
        WorkflowDefinitionView view = new WorkflowDefinitionView();
        view.setId(definition.getId());
        view.setTenantId(definition.getTenantId());
        view.setProjectId(definition.getProjectId());
        view.setDeleted(definition.getDeleted() != null && definition.getDeleted() == 1);
        view.setCreatedAt(definition.getCreatedAt());
        view.setUpdatedAt(definition.getUpdatedAt());
        view.setRuntimeClusterId(definition.getRuntimeClusterId());
        view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null
                : runtimeClusterSelectionService.runtimeClusterName(definition.getProjectId(), definition.getRuntimeClusterId()));
        view.setCode(definition.getCode());
        view.setName(definition.getName());
        view.setPublished(definition.getPublished() != null && definition.getPublished() == 1);
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
                .eq(WorkflowScheduleEntity::getWorkflowDefinitionId, definition.getId())
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

    private LambdaQueryWrapper<WorkflowDefinitionEntity> selectSummaryColumns(LambdaQueryWrapper<WorkflowDefinitionEntity> queryWrapper) {
        return queryWrapper.select(WorkflowDefinitionEntity::getId,
                WorkflowDefinitionEntity::getTenantId,
                WorkflowDefinitionEntity::getProjectId,
                WorkflowDefinitionEntity::getDeleted,
                WorkflowDefinitionEntity::getCreatedAt,
                WorkflowDefinitionEntity::getUpdatedAt,
                WorkflowDefinitionEntity::getRuntimeClusterId,
                WorkflowDefinitionEntity::getCode,
                WorkflowDefinitionEntity::getName,
                WorkflowDefinitionEntity::getCurrentVersionId,
                WorkflowDefinitionEntity::getPublished);
    }

    private WorkflowListView toBasicListView(WorkflowDefinitionEntity definition) {
        WorkflowListView view = new WorkflowListView();
        view.setId(definition.getId());
        view.setTenantId(definition.getTenantId());
        view.setProjectId(definition.getProjectId());
        view.setDeleted(definition.getDeleted() != null && definition.getDeleted() == 1);
        view.setCreatedAt(definition.getCreatedAt());
        view.setUpdatedAt(definition.getUpdatedAt());
        view.setRuntimeClusterId(definition.getRuntimeClusterId());
        view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null : runtimeClusterSelectionService.runtimeClusterName(definition.getProjectId(), definition.getRuntimeClusterId()));
        view.setCode(definition.getCode());
        view.setName(definition.getName());
        view.setVersionId(definition.getCurrentVersionId());
        view.setPublished(definition.getPublished() != null && definition.getPublished() == 1);
        return view;
    }

    private Map<Long, WorkflowScheduleDefinition> scheduleMap(List<WorkflowDefinitionEntity> definitions) {
        Map<Long, WorkflowScheduleDefinition> result = new LinkedHashMap<Long, WorkflowScheduleDefinition>();
        if (definitions == null || definitions.isEmpty()) {
            return result;
        }
        Set<Long> ids = new HashSet<Long>();
        for (WorkflowDefinitionEntity definition : definitions) {
            if (definition.getId() != null) {
                ids.add(definition.getId());
            }
        }
        if (ids.isEmpty()) {
            return result;
        }
        List<WorkflowScheduleEntity> schedules = scheduleMapper.selectList(new LambdaQueryWrapper<WorkflowScheduleEntity>()
                .select(WorkflowScheduleEntity::getWorkflowDefinitionId,
                        WorkflowScheduleEntity::getCronExpression,
                        WorkflowScheduleEntity::getEnabled,
                        WorkflowScheduleEntity::getTimezone)
                .in(WorkflowScheduleEntity::getWorkflowDefinitionId, ids));
        for (WorkflowScheduleEntity schedule : schedules) {
            if (schedule.getWorkflowDefinitionId() != null && !result.containsKey(schedule.getWorkflowDefinitionId())) {
                result.put(schedule.getWorkflowDefinitionId(), toScheduleDefinition(schedule));
            }
        }
        return result;
    }

    private WorkflowScheduleDefinition toScheduleDefinition(WorkflowScheduleEntity scheduleEntity) {
        WorkflowScheduleDefinition schedule = new WorkflowScheduleDefinition();
        schedule.setCronExpression(scheduleEntity.getCronExpression());
        schedule.setEnabled(scheduleEntity.getEnabled() != null && scheduleEntity.getEnabled() == 1);
        schedule.setTimezone(scheduleEntity.getTimezone());
        return schedule;
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? DEFAULT_PAGE_NO : pageNo;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    @Transactional
    public WorkflowDefinitionView save(WorkflowSaveRequest request) {
        validateGraph(request);
        if (workflowConsistencyBindingValidator != null) {
            workflowConsistencyBindingValidator.validate(securityService.currentTenantId(), request.getNodes());
        }
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        WorkflowDefinitionEntity definition = request.getDefinitionId() == null
                ? new WorkflowDefinitionEntity()
                : requireWritableEntity(request.getDefinitionId());
        if (definition == null) {
            definition = new WorkflowDefinitionEntity();
        }
        Long runtimeClusterId = runtimeClusterSelectionService.resolveForResourceSave(
                currentProjectId, request.getRuntimeClusterId(), definition.getRuntimeClusterId(),
                definition.getId() != null);
        validateNodeRuntimeClusters(currentProjectId, runtimeClusterId, request.getNodes(), true);
        ensureUniqueCode(currentProjectId, request.getCode(), definition.getId());
        ensureUniqueName(currentProjectId, request.getName(), definition.getId());
        definition.setTenantId(securityService.currentTenantId());
        definition.setProjectId(currentProjectId);
        definition.setRuntimeClusterId(runtimeClusterId);
        definition.setCode(request.getCode());
        definition.setName(request.getName());
        definition.setPublished(0);
        if (definition.getId() == null && definition.getCreatedBy() == null) {
            definition.setCreatedBy(securityService.currentUserId());
        }
        if (definition.getId() == null) {
            definitionMapper.insert(definition);
        } else {
            definitionMapper.updateById(definition);
        }

        WorkflowVersionEntity version = new WorkflowVersionEntity();
        version.setTenantId(definition.getTenantId());
        version.setProjectId(definition.getProjectId());
        version.setDefinitionId(definition.getId());
        version.setRuntimeClusterId(runtimeClusterId);
        version.setVersionNumber(nextVersion(definition.getId()));
        version.setPublished(0);
        version.setGraphJson(toGraphJson(request));
        version.setScheduleJson(toScheduleJson(request.getSchedule()));
        versionMapper.insert(version);

        for (WorkflowNodeDefinition node : request.getNodes()) {
            WorkflowNodeEntity entity = new WorkflowNodeEntity();
            entity.setTenantId(definition.getTenantId());
            entity.setProjectId(definition.getProjectId());
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
            entity.setTenantId(definition.getTenantId());
            entity.setProjectId(definition.getProjectId());
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
        scheduleEntity.setTenantId(definition.getTenantId());
        scheduleEntity.setProjectId(definition.getProjectId());
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
        runtimeClusterSelectionService.markResourceValid(StudioConstants.RESOURCE_TYPE_WORKFLOW, definition.getId());
        return get(definition.getId());
    }

    @Transactional
    public WorkflowDefinitionView publish(Long definitionId) {
        WorkflowDefinitionEntity definition = requireWritableEntity(definitionId);
        WorkflowDefinitionView draft = get(definitionId);
        runtimeClusterSelectionService.assertResourceValid(StudioConstants.RESOURCE_TYPE_WORKFLOW, definitionId);
        runtimeClusterSelectionService.resolveForSave(definition.getProjectId(), definition.getRuntimeClusterId());
        validateNodeRuntimeClusters(definition.getProjectId(), definition.getRuntimeClusterId(), draft.getNodes());
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

    public WorkflowDefinitionView requireRunnableOnCluster(Long definitionId, Long runtimeClusterId) {
        WorkflowDefinitionView workflow = get(definitionId);
        if (!Boolean.TRUE.equals(workflow.getPublished())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Workflow is not published");
        }
        validateNodeResourceRevisions(workflow.getNodes());
        Set<Long> datasourceIds = collectNodeDatasourceIds(workflow.getTenantId(), workflow.getNodes());
        if (workflow.getNodes() != null) {
            for (WorkflowNodeDefinition node : workflow.getNodes()) {
                if (node != null) collectDatasourceIds(node.getConfig(), datasourceIds);
            }
        }
        runtimeClusterSelectionService.validateManualOverride(
                workflow.getProjectId(), runtimeClusterId, datasourceIds);
        workflow.setRuntimeClusterId(runtimeClusterId);
        workflow.setRuntimeClusterName(runtimeClusterSelectionService.runtimeClusterName(workflow.getProjectId(), runtimeClusterId));
        workflow.setRuntimeValid(Boolean.TRUE);
        workflow.setRuntimeValidationMessage(null);
        return workflow;
    }

    public WorkflowDefinitionView requireRunnable(Long definitionId) {
        WorkflowDefinitionView workflow = get(definitionId);
        if (!Boolean.TRUE.equals(workflow.getPublished())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Workflow is not published");
        }
        runtimeClusterSelectionService.assertResourceValid(StudioConstants.RESOURCE_TYPE_WORKFLOW, workflow.getId());
        runtimeClusterSelectionService.resolveForSave(workflow.getProjectId(), workflow.getRuntimeClusterId());
        validateNodeRuntimeClusters(workflow.getProjectId(), workflow.getRuntimeClusterId(), workflow.getNodes());
        return workflow;
    }

    private Set<Long> collectNodeDatasourceIds(String workflowTenantId, List<WorkflowNodeDefinition> nodes) {
        Set<Long> datasourceIds = new HashSet<Long>();
        if (nodes == null) {
            return datasourceIds;
        }
        for (WorkflowNodeDefinition node : nodes) {
            if (node == null || node.getNodeType() == null) {
                continue;
            }
            if (node.getNodeType() == NodeType.COLLECTION_TASK && collectionTaskDefinitionMapper != null) {
                CollectionTaskDefinitionEntity resource = collectionTaskDefinitionMapper.selectById(
                        parseLong(node.getConfig(), "collectionTaskId"));
                if (resource == null) {
                    throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow collection task was not found");
                }
                assertWorkflowResourceReadable(workflowTenantId, StudioConstants.RESOURCE_TYPE_COLLECTION_TASK,
                        resource.getTenantId(), resource.getProjectId(), resource.getId(),
                        "Workflow collection task was not found");
                collectDatasourceIds(resource.getSourceBindingsJson(), datasourceIds);
                collectDatasourceIds(resource.getTargetBindingJson(), datasourceIds);
            } else if (node.getNodeType() == NodeType.QUALITY_TASK && qualityTaskDefinitionMapper != null) {
                QualityTaskDefinitionEntity resource = qualityTaskDefinitionMapper.selectById(
                        parseLong(node.getConfig(), "qualityTaskId"));
                if (resource == null) {
                    throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow quality task was not found");
                }
                assertWorkflowResourceReadable(workflowTenantId, StudioConstants.RESOURCE_TYPE_QUALITY_TASK,
                        resource.getTenantId(), resource.getProjectId(), resource.getId(),
                        "Workflow quality task was not found");
                if (resource.getDatasourceId() != null) {
                    datasourceIds.add(resource.getDatasourceId());
                }
            } else if (node.getNodeType() == NodeType.DATA_SCRIPT && dataDevelopmentScriptMapper != null) {
                DataDevelopmentScriptEntity resource = dataDevelopmentScriptMapper.selectById(
                        parseLong(node.getConfig(), "scriptId"));
                if (resource == null) {
                    throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow data script was not found");
                }
                assertWorkflowResourceReadable(workflowTenantId, StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT,
                        resource.getTenantId(), resource.getProjectId(), resource.getId(),
                        "Workflow data script was not found");
                collectScriptDatasourceIds(workflowTenantId, resource, datasourceIds);
            } else if (node.getNodeType() == NodeType.FILE_TRANSFER && fileTransferTaskDefinitionMapper != null) {
                FileTransferTaskDefinitionEntity resource = requirePublishedFileTransferTask(
                        workflowTenantId, node);
                if (resource.getTargetDatasourceId() != null) {
                    datasourceIds.add(resource.getTargetDatasourceId());
                }
            }
        }
        return datasourceIds;
    }

    private void validateNodeRuntimeClusters(Long projectId, Long runtimeClusterId, List<WorkflowNodeDefinition> nodes) {
        validateNodeRuntimeClusters(projectId, runtimeClusterId, nodes, false);
    }

    private void validateNodeResourceRevisions(List<WorkflowNodeDefinition> nodes) {
        if (nodes == null) return;
        for (WorkflowNodeDefinition node : nodes) {
            if (node == null || node.getNodeType() == null) continue;
            String currentRevision = null;
            if (node.getNodeType() == NodeType.COLLECTION_TASK && collectionTaskDefinitionMapper != null) {
                CollectionTaskDefinitionEntity resource = collectionTaskDefinitionMapper.selectById(
                        parseLong(node.getConfig(), "collectionTaskId"));
                if (resource == null) throw new StudioException(StudioErrorCode.NOT_FOUND,
                        "Workflow collection task was not found");
                assertWorkflowResourceReadable(securityService.currentTenantId(),
                        StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, resource.getTenantId(), resource.getProjectId(),
                        resource.getId(), "Workflow collection task was not found");
                currentRevision = collectionRevision(resource);
            } else if (node.getNodeType() == NodeType.QUALITY_TASK && qualityTaskDefinitionMapper != null) {
                QualityTaskDefinitionEntity resource = qualityTaskDefinitionMapper.selectById(
                        parseLong(node.getConfig(), "qualityTaskId"));
                if (resource == null) throw new StudioException(StudioErrorCode.NOT_FOUND,
                        "Workflow quality task was not found");
                assertWorkflowResourceReadable(securityService.currentTenantId(),
                        StudioConstants.RESOURCE_TYPE_QUALITY_TASK, resource.getTenantId(), resource.getProjectId(),
                        resource.getId(), "Workflow quality task was not found");
                currentRevision = qualityRevision(resource);
            } else if (node.getNodeType() == NodeType.DATA_SCRIPT && dataDevelopmentScriptMapper != null) {
                DataDevelopmentScriptEntity resource = dataDevelopmentScriptMapper.selectById(
                        parseLong(node.getConfig(), "scriptId"));
                if (resource == null) throw new StudioException(StudioErrorCode.NOT_FOUND,
                        "Workflow data script was not found");
                assertWorkflowResourceReadable(securityService.currentTenantId(),
                        StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT, resource.getTenantId(),
                        resource.getProjectId(), resource.getId(), "Workflow data script was not found");
                currentRevision = scriptRevision(resource);
            } else if (node.getNodeType() == NodeType.FILE_TRANSFER
                    && fileTransferTaskDefinitionMapper != null) {
                FileTransferTaskDefinitionEntity resource = requirePublishedFileTransferTask(
                        securityService.currentTenantId(), node);
                currentRevision = fileTransferRevision(resource);
            }
            applyResourceRevision(node, currentRevision, false);
        }
    }

    private void validateNodeRuntimeClusters(Long projectId, Long runtimeClusterId,
                                             List<WorkflowNodeDefinition> nodes,
                                             boolean refreshResourceRevision) {
        if (runtimeClusterSelectionService == null || nodes == null) {
            return;
        }
        Set<Long> directDatasourceIds = new HashSet<Long>();
        for (WorkflowNodeDefinition node : nodes) {
            if (node == null || node.getNodeType() == null) {
                continue;
            }
            Long resourceClusterId = null;
            if (node.getNodeType() == NodeType.COLLECTION_TASK && collectionTaskDefinitionMapper != null) {
                CollectionTaskDefinitionEntity resource = collectionTaskDefinitionMapper.selectById(parseLong(node.getConfig(), "collectionTaskId"));
                resourceClusterId = resource == null ? null : resource.getRuntimeClusterId();
                if (resource != null) {
                    assertWorkflowResourceReadable(securityService.currentTenantId(),
                            StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, resource.getTenantId(), resource.getProjectId(),
                            resource.getId(), "Workflow collection task was not found");
                    runtimeClusterSelectionService.assertResourceValid(
                            StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, resource.getId());
                }
                applyResourceRevision(node, resource == null ? null : collectionRevision(resource), refreshResourceRevision);
            } else if (node.getNodeType() == NodeType.QUALITY_TASK && qualityTaskDefinitionMapper != null) {
                QualityTaskDefinitionEntity resource = qualityTaskDefinitionMapper.selectById(parseLong(node.getConfig(), "qualityTaskId"));
                resourceClusterId = resource == null ? null : resource.getRuntimeClusterId();
                if (resource != null) {
                    assertWorkflowResourceReadable(securityService.currentTenantId(),
                            StudioConstants.RESOURCE_TYPE_QUALITY_TASK, resource.getTenantId(), resource.getProjectId(),
                            resource.getId(), "Workflow quality task was not found");
                    runtimeClusterSelectionService.assertResourceValid(
                            StudioConstants.RESOURCE_TYPE_QUALITY_TASK, resource.getId());
                }
                applyResourceRevision(node, resource == null ? null : qualityRevision(resource), refreshResourceRevision);
            } else if (node.getNodeType() == NodeType.DATA_SCRIPT && dataDevelopmentScriptMapper != null) {
                DataDevelopmentScriptEntity resource = dataDevelopmentScriptMapper.selectById(parseLong(node.getConfig(), "scriptId"));
                resourceClusterId = resource == null ? null : resource.getRuntimeClusterId();
                if (resource != null) {
                    assertWorkflowResourceReadable(securityService.currentTenantId(),
                            StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT, resource.getTenantId(),
                            resource.getProjectId(), resource.getId(), "Workflow data script was not found");
                    runtimeClusterSelectionService.assertResourceValid(
                            StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT, resource.getId());
                }
                applyResourceRevision(node, resource == null ? null : scriptRevision(resource), refreshResourceRevision);
            } else if (node.getNodeType() == NodeType.FILE_TRANSFER
                    && fileTransferTaskDefinitionMapper != null) {
                FileTransferTaskDefinitionEntity resource = requirePublishedFileTransferTask(
                        securityService.currentTenantId(), node);
                resourceClusterId = resource.getTargetRuntimeClusterId();
                runtimeClusterSelectionService.assertExistingResourceRunnable(resource.getProjectId(),
                        resource.getSourceRuntimeClusterId(), List.of(resource.getSourceDatasourceId()));
                runtimeClusterSelectionService.assertExistingResourceRunnable(resource.getProjectId(),
                        resource.getTargetRuntimeClusterId(), List.of(resource.getTargetDatasourceId()));
                applyResourceRevision(node, fileTransferRevision(resource), refreshResourceRevision);
            }
            if (resourceClusterId != null || node.getNodeType() == NodeType.COLLECTION_TASK
                    || node.getNodeType() == NodeType.QUALITY_TASK || node.getNodeType() == NodeType.DATA_SCRIPT
                    || node.getNodeType() == NodeType.FILE_TRANSFER) {
                if (runtimeClusterId == null ? resourceClusterId != null : !runtimeClusterId.equals(resourceClusterId)) {
                    throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                            "Workflow node resource must use the same runtime cluster as the workflow");
                }
            }
            collectDatasourceIds(node.getConfig(), directDatasourceIds);
        }
        directDatasourceIds.addAll(collectNodeDatasourceIds(securityService.currentTenantId(), nodes));
        runtimeClusterSelectionService.validateDatasourceSelection(projectId, runtimeClusterId, directDatasourceIds);
    }

    private void applyResourceRevision(WorkflowNodeDefinition node, String currentRevision,
                                       boolean refreshResourceRevision) {
        if (node == null || currentRevision == null) {
            return;
        }
        Map<String, Object> config = node.getConfig() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(node.getConfig());
        Object savedRevision = config.get("_resourceRevision");
        if (!refreshResourceRevision && savedRevision != null
                && !currentRevision.equals(String.valueOf(savedRevision))) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Workflow node resource configuration changed; save and publish the workflow again");
        }
        if (refreshResourceRevision || savedRevision == null) {
            config.put("_resourceRevision", currentRevision);
            node.setConfig(config);
        }
    }

    private String collectionRevision(CollectionTaskDefinitionEntity resource) {
        return runtimeResourceRevisionService == null
                ? (resource.getUpdatedAt() == null ? null : resource.getUpdatedAt().toString())
                : runtimeResourceRevisionService.collectionTaskRevision(resource.getId());
    }

    private String qualityRevision(QualityTaskDefinitionEntity resource) {
        return runtimeResourceRevisionService == null
                ? (resource.getUpdatedAt() == null ? null : resource.getUpdatedAt().toString())
                : runtimeResourceRevisionService.qualityTaskRevision(resource.getId());
    }

    private String scriptRevision(DataDevelopmentScriptEntity resource) {
        return runtimeResourceRevisionService == null
                ? (resource.getUpdatedAt() == null ? null : resource.getUpdatedAt().toString())
                : runtimeResourceRevisionService.scriptRevision(resource.getId());
    }

    private String fileTransferRevision(FileTransferTaskDefinitionEntity resource) {
        return resource == null || resource.getId() == null || resource.getPublishedVersion() == null
                ? null
                : "file-transfer:" + resource.getId() + ":" + resource.getPublishedVersion();
    }

    private FileTransferTaskDefinitionEntity requirePublishedFileTransferTask(
            String workflowTenantId, WorkflowNodeDefinition node) {
        Long taskId = parseLong(node == null ? null : node.getConfig(), "fileTransferTaskId");
        FileTransferTaskDefinitionEntity resource = taskId == null || fileTransferTaskDefinitionMapper == null
                ? null : fileTransferTaskDefinitionMapper.selectById(taskId);
        if (resource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND,
                    "Workflow file transfer task was not found");
        }
        assertWorkflowResourceReadable(workflowTenantId,
                StudioConstants.RESOURCE_TYPE_FILE_TRANSFER_TASK, resource.getTenantId(),
                resource.getProjectId(), resource.getId(), "Workflow file transfer task was not found");
        if (!"ONLINE".equalsIgnoreCase(resource.getStatus()) || resource.getPublishedVersion() == null
                || resource.getPublishedSnapshotJson() == null || resource.getPublishedSnapshotJson().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Workflow file transfer task must be published before use");
        }
        if (resource.getSourceRuntimeClusterId() == null || resource.getSourceDatasourceId() == null
                || resource.getTargetRuntimeClusterId() == null || resource.getTargetDatasourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Workflow file transfer task placement is incomplete");
        }
        return resource;
    }

    private void collectScriptDatasourceIds(String workflowTenantId,
                                            DataDevelopmentScriptEntity script,
                                            Set<Long> datasourceIds) {
        if (script.getDatasourceId() != null) {
            datasourceIds.add(script.getDatasourceId());
        }
        if (dataModelMapper == null || script.getExecutionConfigJson() == null) {
            return;
        }
        Set<Long> modelIds = new HashSet<Long>();
        collectModelIds(script.getExecutionConfigJson(), modelIds);
        for (Long modelId : modelIds) {
            DataModelEntity model = dataModelMapper.selectById(modelId);
            if (model == null) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow data model was not found");
            }
            assertWorkflowResourceReadable(workflowTenantId, StudioConstants.RESOURCE_TYPE_DATA_MODEL,
                    model.getTenantId(), model.getProjectId(), model.getId(), "Workflow data model was not found");
            if (model.getDatasourceId() != null) {
                datasourceIds.add(model.getDatasourceId());
            }
        }
    }

    private void collectModelIds(Object value, Set<Long> target) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).toLowerCase();
                if (key.endsWith("modelid") || key.endsWith("modelids")) {
                    collectLongIds(entry.getValue(), target);
                }
                collectModelIds(entry.getValue(), target);
            }
        } else if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                collectModelIds(item, target);
            }
        }
    }

    private void collectLongIds(Object value, Set<Long> target) {
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                collectLongIds(item, target);
            }
            return;
        }
        if (value instanceof Map) {
            collectLongIds(((Map<?, ?>) value).get("id"), target);
            return;
        }
        Long id = parseLongValue(value);
        if (id != null) {
            target.add(id);
        }
    }

    private void assertWorkflowResourceReadable(String workflowTenantId,
                                                String resourceType,
                                                String resourceTenantId,
                                                Long resourceProjectId,
                                                Long resourceId,
                                                String notFoundMessage) {
        if (workflowTenantId == null || !workflowTenantId.equals(resourceTenantId)) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, notFoundMessage);
        }
        projectResourceAccessService.assertReadable(resourceType, resourceProjectId, resourceId, notFoundMessage);
    }

    private Long parseLong(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            try {
                return Long.valueOf(((String) value).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void collectDatasourceIds(Object value, Set<Long> target) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).toLowerCase();
                if (key.contains("datasource") && key.endsWith("id")) {
                    Long id = parseLongValue(entry.getValue());
                    if (id != null) target.add(id);
                }
                collectDatasourceIds(entry.getValue(), target);
            }
        } else if (value instanceof Collection) {
            for (Object item : (Collection<Object>) value) collectDatasourceIds(item, target);
        }
    }

    private Long parseLongValue(Object value) {
        if (value instanceof Number) return Long.valueOf(((Number) value).longValue());
        if (value instanceof String) {
            try { return Long.valueOf(((String) value).trim()); } catch (NumberFormatException ex) { return null; }
        }
        return null;
    }

    private Map<String, Object> toGraphJson(WorkflowSaveRequest request) {
        Map<String, Object> graph = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        for (WorkflowNodeDefinition node : request.getNodes()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("nodeCode", node.getNodeCode());
            item.put("nodeName", node.getNodeName());
            item.put("nodeType", node.getNodeType() == null ? null : node.getNodeType().name());
            item.put("config", node.getConfig());
            item.put("fieldMappings", toMappings(node.getFieldMappings()));
            nodes.add(item);
        }
        List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
        for (WorkflowEdgeDefinition edge : request.getEdges()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("fromNodeCode", edge.getFromNodeCode());
            item.put("toNodeCode", edge.getToNodeCode());
            item.put("condition", edge.getCondition() == null ? EdgeCondition.ON_SUCCESS.name() : edge.getCondition().name());
            edges.add(item);
        }
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    public List<WorkflowScheduleEntity> findEnabledSchedules() {
        return scheduleMapper.selectList(new LambdaQueryWrapper<WorkflowScheduleEntity>()
                .eq(WorkflowScheduleEntity::getEnabled, 1));
    }

    @Transactional
    public void markScheduleTriggered(Long workflowDefinitionId, LocalDateTime triggeredAt) {
        WorkflowScheduleEntity scheduleEntity = scheduleMapper.selectOne(new LambdaQueryWrapper<WorkflowScheduleEntity>()
                .eq(WorkflowScheduleEntity::getWorkflowDefinitionId, workflowDefinitionId)
                .last("limit 1"));
        if (scheduleEntity == null) {
            return;
        }
        scheduleEntity.setLastTriggeredAt(triggeredAt);
        scheduleMapper.updateById(scheduleEntity);
    }

    @Transactional
    public void delete(Long definitionId) {
        requireWritableEntity(definitionId);
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

    private LambdaQueryWrapper<WorkflowDefinitionEntity> buildAccessibleQuery() {
        LambdaQueryWrapper<WorkflowDefinitionEntity> queryWrapper = new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .eq(WorkflowDefinitionEntity::getTenantId, securityService.currentTenantId());
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return queryWrapper;
        }
        List<Long> sharedIds = projectResourceAccessService.sharedResourceIdList(StudioConstants.RESOURCE_TYPE_WORKFLOW);
        if (sharedIds.isEmpty()) {
            queryWrapper.eq(WorkflowDefinitionEntity::getProjectId, currentProjectId);
            return queryWrapper;
        }
        queryWrapper.and(wrapper -> wrapper.eq(WorkflowDefinitionEntity::getProjectId, currentProjectId)
                .or()
                .in(WorkflowDefinitionEntity::getId, sharedIds));
        return queryWrapper;
    }

    private WorkflowDefinitionEntity findAccessibleEntity(Long definitionId) {
        WorkflowDefinitionEntity definition = definitionMapper.selectById(definitionId);
        if (definition == null || !java.util.Objects.equals(securityService.currentTenantId(), definition.getTenantId())) {
            return null;
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_WORKFLOW,
                definition.getProjectId(), definition.getId(), "Workflow not found");
        return definition;
    }

    private WorkflowDefinitionEntity requireWritableEntity(Long definitionId) {
        WorkflowDefinitionEntity definition = definitionMapper.selectById(definitionId);
        if (definition == null || !java.util.Objects.equals(securityService.currentTenantId(), definition.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Workflow not found");
        }
        projectResourceAccessService.assertWritable(definition.getProjectId());
        return definition;
    }

    private void ensureUniqueCode(Long projectId, String code, Long selfId) {
        if (projectId == null || code == null || code.trim().isEmpty()) {
            return;
        }
        List<WorkflowDefinitionEntity> duplicates = definitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .eq(WorkflowDefinitionEntity::getProjectId, projectId)
                .eq(WorkflowDefinitionEntity::getCode, code.trim()));
        for (WorkflowDefinitionEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Workflow code already exists in the current project");
        }
    }

    private void ensureUniqueName(Long projectId, String name, Long selfId) {
        if (projectId == null || name == null || name.trim().isEmpty()) {
            return;
        }
        List<WorkflowDefinitionEntity> duplicates = definitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .eq(WorkflowDefinitionEntity::getProjectId, projectId)
                .eq(WorkflowDefinitionEntity::getName, name.trim()));
        for (WorkflowDefinitionEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Workflow name already exists in the current project");
        }
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

    private List<TransformerBinding> toTransformers(Object candidate) {
        List<TransformerBinding> result = new ArrayList<TransformerBinding>();
        if (!(candidate instanceof List<?>)) {
            return result;
        }
        for (Object item : (List<?>) candidate) {
            Map<String, Object> map = copyObjectMap(item);
            if (map == null) {
                continue;
            }
            TransformerBinding binding = new TransformerBinding();
            binding.setTransformerCode(asString(map.get("transformerCode")));
            Map<String, Object> parameters = copyObjectMap(map.get("parameters"));
            if (parameters != null) {
                binding.setParameters(parameters);
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

    private Map<String, Object> copyObjectMap(Object candidate) {
        if (!(candidate instanceof Map<?, ?>)) {
            return null;
        }
        Map<?, ?> source = (Map<?, ?>) candidate;
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
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

