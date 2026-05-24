package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.LineageLevel;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.DataModelLineageContributorView;
import com.jdragon.studio.dto.model.DataModelLineageEdgeDetailView;
import com.jdragon.studio.dto.model.DataModelLineageEdgeView;
import com.jdragon.studio.dto.model.DataModelLineageNodeFieldView;
import com.jdragon.studio.dto.model.DataModelLineageNodeView;
import com.jdragon.studio.dto.model.DataModelLineageSummaryView;
import com.jdragon.studio.dto.model.DataModelLineageUnresolvedExpressionView;
import com.jdragon.studio.dto.model.DataModelLineageView;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.dto.model.request.DataModelManualLineageSaveRequest;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DataModelLineageRelationEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataModelLineageRelationMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

import static com.jdragon.studio.infra.service.DataModelLineageTextSupport.blankToNull;
import static com.jdragon.studio.infra.service.DataModelLineageTextSupport.containsIgnoreCase;
import static com.jdragon.studio.infra.service.DataModelLineageTextSupport.extractModelFields;
import static com.jdragon.studio.infra.service.DataModelLineageTextSupport.firstNonBlank;
import static com.jdragon.studio.infra.service.DataModelLineageTextSupport.isBlank;
import static com.jdragon.studio.infra.service.DataModelLineageTextSupport.resolveDatabaseName;
import static com.jdragon.studio.infra.service.DataModelLineageTextSupport.resolveStringMetadata;
import static com.jdragon.studio.infra.service.DataModelLineageTextSupport.safeText;

@Service
public class DataModelLineageService {

    private static final String SOURCE_TYPE_COLLECTION_TASK = "COLLECTION_TASK";
    private static final String SOURCE_TYPE_MANUAL = "MANUAL";
    private static final String MAPPING_MODE_DIRECT = "DIRECT";
    private static final String MAPPING_MODE_EXPRESSION = "EXPRESSION";
    private static final String MAPPING_MODE_UNRESOLVED_EXPRESSION = "UNRESOLVED_EXPRESSION";

    private final DataModelLineageRelationMapper relationMapper;
    private final CollectionTaskDefinitionMapper collectionTaskDefinitionMapper;
    private final DataModelMapper dataModelMapper;
    private final DatasourceMapper datasourceMapper;
    private final RunRecordMapper runRecordMapper;
    private final StudioUserMapper studioUserMapper;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final StudioSecurityService securityService;
    private final ObjectMapper objectMapper;
    private final Executor lineageRebuildExecutor;
    private final TransactionTemplate transactionTemplate;
    private final DataModelLineageExpressionResolver expressionResolver = new DataModelLineageExpressionResolver();
    private final DataModelLineageGraphAssembler graphAssembler;

    public DataModelLineageService(DataModelLineageRelationMapper relationMapper,
                                   CollectionTaskDefinitionMapper collectionTaskDefinitionMapper,
                                   DataModelMapper dataModelMapper,
                                   DatasourceMapper datasourceMapper,
                                   RunRecordMapper runRecordMapper,
                                   StudioUserMapper studioUserMapper,
                                   ProjectResourceAccessService projectResourceAccessService,
                                   StudioSecurityService securityService,
                                   ObjectMapper objectMapper,
                                   @Qualifier("lineageRebuildExecutor") Executor lineageRebuildExecutor,
                                   PlatformTransactionManager transactionManager) {
        this.relationMapper = relationMapper;
        this.collectionTaskDefinitionMapper = collectionTaskDefinitionMapper;
        this.dataModelMapper = dataModelMapper;
        this.datasourceMapper = datasourceMapper;
        this.runRecordMapper = runRecordMapper;
        this.studioUserMapper = studioUserMapper;
        this.projectResourceAccessService = projectResourceAccessService;
        this.securityService = securityService;
        this.objectMapper = objectMapper;
        this.lineageRebuildExecutor = lineageRebuildExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.graphAssembler = new DataModelLineageGraphAssembler(datasourceMapper);
    }

    public void scheduleTaskRebuildAfterCommit(final Long taskId) {
        if (taskId == null) {
            return;
        }
        scheduleAfterCommit(new Runnable() {
            @Override
            public void run() {
                lineageRebuildExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        transactionTemplate.executeWithoutResult(status -> rebuildCollectionTaskLineage(taskId));
                    }
                });
            }
        });
    }

    public void scheduleTaskDeleteAfterCommit(final Long taskId) {
        if (taskId == null) {
            return;
        }
        scheduleAfterCommit(new Runnable() {
            @Override
            public void run() {
                lineageRebuildExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        transactionTemplate.executeWithoutResult(status -> deleteCollectionTaskLineage(taskId));
                    }
                });
            }
        });
    }

    public void rebuildCollectionTaskLineage(Long taskId) {
        if (taskId == null) {
            return;
        }
        deleteCollectionTaskLineage(taskId);
        CollectionTaskDefinitionEntity task = collectionTaskDefinitionMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        List<CollectionTaskSourceBinding> sourceBindings = convertList(task.getSourceBindingsJson(), CollectionTaskSourceBinding.class);
        CollectionTaskTargetBinding targetBinding = convertMap(task.getTargetBindingJson(), CollectionTaskTargetBinding.class);
        List<FieldMappingDefinition> fieldMappings = convertList(task.getFieldMappingsJson(), FieldMappingDefinition.class);
        if (sourceBindings.isEmpty() || targetBinding == null || targetBinding.getModelId() == null) {
            return;
        }

        Map<Long, DatasourceEntity> datasourceMap = loadDatasourceMap(sourceBindings, targetBinding);
        Map<Long, DataModelEntity> modelMap = loadModelMap(sourceBindings, targetBinding);
        Map<String, Set<String>> aliasFields = expressionResolver.buildAliasFields(sourceBindings, modelMap);
        Set<String> uniqueKeys = new LinkedHashSet<String>();
        List<DataModelLineageRelationEntity> relations = new ArrayList<DataModelLineageRelationEntity>();

        for (CollectionTaskSourceBinding source : sourceBindings) {
            DatasourceEntity sourceDatasource = datasourceMap.get(source.getDatasourceId());
            DatasourceEntity targetDatasource = datasourceMap.get(targetBinding.getDatasourceId());
            DataModelEntity sourceModel = modelMap.get(source.getModelId());
            DataModelEntity targetModel = modelMap.get(targetBinding.getModelId());

            DataModelLineageRelationEntity databaseRelation = buildBaseRelation(task, source, sourceDatasource, sourceModel,
                    targetBinding, targetDatasource, targetModel);
            databaseRelation.setLevel(LineageLevel.DATABASE.name());
            appendUnique(relations, uniqueKeys, databaseRelation);

            DataModelLineageRelationEntity tableRelation = buildBaseRelation(task, source, sourceDatasource, sourceModel,
                    targetBinding, targetDatasource, targetModel);
            tableRelation.setLevel(LineageLevel.TABLE.name());
            appendUnique(relations, uniqueKeys, tableRelation);
        }

        for (FieldMappingDefinition mapping : fieldMappings) {
            appendFieldRelations(relations, uniqueKeys, task, sourceBindings, targetBinding, datasourceMap, modelMap, aliasFields, mapping);
        }

        for (DataModelLineageRelationEntity relation : relations) {
            relationMapper.insert(relation);
        }
    }

    public void deleteCollectionTaskLineage(Long taskId) {
        relationMapper.delete(new LambdaQueryWrapper<DataModelLineageRelationEntity>()
                .eq(DataModelLineageRelationEntity::getCollectionTaskId, taskId));
    }

    public void updateCollectionTaskRunStatus(ExecutionEvent event) {
        if (event == null || event.getCollectionTaskId() == null) {
            return;
        }
        String status = DataModelLineageRunStatusSupport.normalizeStatus(event.getEventType());
        if (isBlank(status)) {
            return;
        }
        LocalDateTime eventTime = DataModelLineageRunStatusSupport.resolveEventTime(event);
        LambdaQueryWrapper<DataModelLineageRelationEntity> query = new LambdaQueryWrapper<DataModelLineageRelationEntity>()
                .eq(DataModelLineageRelationEntity::getCollectionTaskId, event.getCollectionTaskId());
        if (event.getProjectId() != null) {
            query.eq(DataModelLineageRelationEntity::getProjectId, event.getProjectId());
        }
        List<DataModelLineageRelationEntity> relations = relationMapper.selectList(query);
        for (DataModelLineageRelationEntity relation : relations) {
            if (DataModelLineageRunStatusSupport.shouldUpdateRunStatus(relation, event, eventTime)) {
                relation.setLatestRunId(event.getRunRecordId());
                relation.setLatestRunStatus(status);
                relation.setLatestRunAt(eventTime);
                relationMapper.updateById(relation);
            }
        }
    }

    public DataModelLineageView getModelLineage(Long modelId, LineageLevel level) {
        DataModelEntity focusModel = requireReadableModel(modelId);
        List<DataModelLineageRelationEntity> relations = filterVisibleRelations(loadAccessibleRelations(level));
        enrichLatestRunStatus(relations);
        DataModelLineageGraphAssembler.LineageQueryContext context = graphAssembler.buildContext(focusModel, relations, level);
        DataModelLineageView view = new DataModelLineageView();
        view.setEditable(Boolean.valueOf(isLineageEditable(focusModel, level)));
        view.setSummary(graphAssembler.buildSummary(context));
        view.setNodes(context.nodes);
        view.setEdges(context.edges);
        view.setUnresolvedExpressions(context.unresolvedExpressions);
        return view;
    }

    public DataModelLineageEdgeDetailView getEdgeDetail(Long modelId, LineageLevel level, String edgeId) {
        DataModelEntity focusModel = requireReadableModel(modelId);
        DataModelLineageGraphAssembler.EdgeKey edgeKey = graphAssembler.decodeEdgeKey(edgeId);
        if (edgeKey == null || !Objects.equals(edgeKey.level, level.name())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Lineage edge not found: " + edgeId);
        }
        DatasourceEntity focusDatasource = focusModel.getDatasourceId() == null ? null : datasourceMapper.selectById(focusModel.getDatasourceId());
        String focusNodeId = graphAssembler.resolveFocusNodeId(level, focusModel, focusDatasource);
        List<DataModelLineageRelationEntity> relations = filterVisibleRelations(loadAccessibleRelations(level));
        enrichLatestRunStatus(relations);
        Set<String> reachableNodeIds = graphAssembler.traverseReachableNodeIds(relations, focusNodeId, level);
        List<DataModelLineageRelationEntity> matched = new ArrayList<DataModelLineageRelationEntity>();
        for (DataModelLineageRelationEntity relation : relations) {
            if (!reachableNodeIds.contains(graphAssembler.relationSourceNodeId(relation, level))
                    || !reachableNodeIds.contains(graphAssembler.relationTargetNodeId(relation, level))) {
                continue;
            }
            if (graphAssembler.matchesEdgeKey(edgeKey, relation, level)) {
                matched.add(relation);
            }
        }
        if (matched.isEmpty()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Lineage edge not found: " + edgeId);
        }
        return graphAssembler.buildEdgeDetail(edgeId, level, matched);
    }

    public void saveManualLineage(Long focusModelId, Long relationId, DataModelManualLineageSaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Manual lineage request is required");
        }
        if (request.getLevel() == null || request.getLevel() == LineageLevel.DATABASE) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Manual lineage only supports TABLE or FIELD level");
        }
        DataModelEntity focusModel = requireWritableModel(focusModelId);
        DataModelEntity sourceModel = requireReadableModel(request.getSourceModelId());
        DataModelEntity targetModel = requireReadableModel(request.getTargetModelId());
        if (!Objects.equals(sourceModel.getId(), focusModel.getId()) && !Objects.equals(targetModel.getId(), focusModel.getId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "The current model must participate in the manual lineage");
        }
        validateManualFieldKeys(request, sourceModel, targetModel);
        DataModelLineageRelationEntity relation = relationId == null ? new DataModelLineageRelationEntity() : requireWritableManualRelation(focusModel, relationId);
        DatasourceEntity sourceDatasource = sourceModel.getDatasourceId() == null ? null : datasourceMapper.selectById(sourceModel.getDatasourceId());
        DatasourceEntity targetDatasource = targetModel.getDatasourceId() == null ? null : datasourceMapper.selectById(targetModel.getDatasourceId());
        StudioUserEntity maintainer = currentUser();

        relation.setTenantId(securityService.currentTenantId());
        relation.setProjectId(projectResourceAccessService.requireCurrentProjectId());
        relation.setLevel(request.getLevel().name());
        relation.setSourceType(SOURCE_TYPE_MANUAL);
        relation.setCollectionTaskId(null);
        relation.setCollectionTaskNameSnapshot(null);
        relation.setSourceDatasourceId(sourceDatasource == null ? null : sourceDatasource.getId());
        relation.setSourceDatasourceNameSnapshot(sourceDatasource == null ? null : sourceDatasource.getName());
        relation.setSourceDatasourceTypeSnapshot(sourceDatasource == null ? null : sourceDatasource.getTypeCode());
        relation.setSourceDatabaseNameSnapshot(resolveDatabaseName(sourceDatasource));
        relation.setSourceHostSnapshot(resolveStringMetadata(sourceDatasource, "host", "endpoint"));
        relation.setSourcePortSnapshot(resolveStringMetadata(sourceDatasource, "port"));
        relation.setSourceModelId(sourceModel.getId());
        relation.setSourceModelNameSnapshot(sourceModel.getName());
        relation.setSourceModelLocatorSnapshot(sourceModel.getPhysicalLocator());
        relation.setTargetDatasourceId(targetDatasource == null ? null : targetDatasource.getId());
        relation.setTargetDatasourceNameSnapshot(targetDatasource == null ? null : targetDatasource.getName());
        relation.setTargetDatasourceTypeSnapshot(targetDatasource == null ? null : targetDatasource.getTypeCode());
        relation.setTargetDatabaseNameSnapshot(resolveDatabaseName(targetDatasource));
        relation.setTargetHostSnapshot(resolveStringMetadata(targetDatasource, "host", "endpoint"));
        relation.setTargetPortSnapshot(resolveStringMetadata(targetDatasource, "port"));
        relation.setTargetModelId(targetModel.getId());
        relation.setTargetModelNameSnapshot(targetModel.getName());
        relation.setTargetModelLocatorSnapshot(targetModel.getPhysicalLocator());
        relation.setSourceFieldKey(request.getLevel() == LineageLevel.FIELD ? blankToNull(request.getSourceFieldKey()) : null);
        relation.setTargetFieldKey(request.getLevel() == LineageLevel.FIELD ? blankToNull(request.getTargetFieldKey()) : null);
        relation.setMappingMode(MAPPING_MODE_DIRECT);
        relation.setExpressionSnapshot(null);
        relation.setManualMaintainerUserId(maintainer == null ? securityService.currentUserId() : maintainer.getId());
        relation.setManualMaintainerNameSnapshot(resolveUserDisplayName(maintainer));
        relation.setLatestRunId(null);
        relation.setLatestRunStatus("SUCCESS");
        relation.setLatestRunAt(LocalDateTime.now());

        ensureManualRelationUnique(relation, relationId);
        if (relation.getId() == null) {
            relationMapper.insert(relation);
        } else {
            relationMapper.updateById(relation);
        }
    }

    public void deleteManualLineage(Long focusModelId, Long relationId) {
        DataModelEntity focusModel = requireWritableModel(focusModelId);
        DataModelLineageRelationEntity relation = requireWritableManualRelation(focusModel, relationId);
        relationMapper.deleteById(relation.getId());
    }

    private void appendFieldRelations(List<DataModelLineageRelationEntity> relations,
                                      Set<String> uniqueKeys,
                                      CollectionTaskDefinitionEntity task,
                                      List<CollectionTaskSourceBinding> sourceBindings,
                                      CollectionTaskTargetBinding targetBinding,
                                      Map<Long, DatasourceEntity> datasourceMap,
                                      Map<Long, DataModelEntity> modelMap,
                                      Map<String, Set<String>> aliasFields,
                                      FieldMappingDefinition mapping) {
        if (mapping == null || isBlank(mapping.getTargetField())) {
            return;
        }
        if (!isBlank(mapping.getSourceField())) {
            CollectionTaskSourceBinding source = expressionResolver.findSourceBinding(sourceBindings, mapping.getSourceAlias());
            if (source != null) {
                appendDirectFieldRelation(relations, uniqueKeys, task, source, targetBinding, datasourceMap, modelMap,
                        mapping.getSourceField(), mapping.getTargetField(), null, MAPPING_MODE_DIRECT);
            }
            return;
        }
        if (isBlank(mapping.getExpression())) {
            return;
        }
        DataModelLineageExpressionResolver.ExpressionResolution resolution = expressionResolver.resolveExpressionMappings(mapping, sourceBindings, aliasFields);
        if (resolution.references.isEmpty()) {
            CollectionTaskSourceBinding source = sourceBindings.get(0);
            appendDirectFieldRelation(relations, uniqueKeys, task, source, targetBinding, datasourceMap, modelMap,
                    null, mapping.getTargetField(), mapping.getExpression(), MAPPING_MODE_UNRESOLVED_EXPRESSION);
            return;
        }
        for (DataModelLineageExpressionResolver.FieldReference reference : resolution.references) {
            CollectionTaskSourceBinding source = expressionResolver.findSourceBinding(sourceBindings, reference.sourceAlias);
            if (source == null) {
                continue;
            }
            appendDirectFieldRelation(relations, uniqueKeys, task, source, targetBinding, datasourceMap, modelMap,
                    reference.fieldName, mapping.getTargetField(), mapping.getExpression(), MAPPING_MODE_EXPRESSION);
        }
    }

    private void appendDirectFieldRelation(List<DataModelLineageRelationEntity> relations,
                                           Set<String> uniqueKeys,
                                           CollectionTaskDefinitionEntity task,
                                           CollectionTaskSourceBinding source,
                                           CollectionTaskTargetBinding target,
                                           Map<Long, DatasourceEntity> datasourceMap,
                                           Map<Long, DataModelEntity> modelMap,
                                           String sourceField,
                                           String targetField,
                                           String expression,
                                           String mappingMode) {
        DatasourceEntity sourceDatasource = datasourceMap.get(source.getDatasourceId());
        DatasourceEntity targetDatasource = datasourceMap.get(target.getDatasourceId());
        DataModelEntity sourceModel = modelMap.get(source.getModelId());
        DataModelEntity targetModel = modelMap.get(target.getModelId());
        DataModelLineageRelationEntity relation = buildBaseRelation(task, source, sourceDatasource, sourceModel,
                target, targetDatasource, targetModel);
        relation.setLevel(LineageLevel.FIELD.name());
        relation.setSourceFieldKey(sourceField);
        relation.setTargetFieldKey(targetField);
        relation.setExpressionSnapshot(expression);
        relation.setMappingMode(mappingMode);
        appendUnique(relations, uniqueKeys, relation);
    }

    private DataModelLineageRelationEntity buildBaseRelation(CollectionTaskDefinitionEntity task,
                                                             CollectionTaskSourceBinding source,
                                                             DatasourceEntity sourceDatasource,
                                                             DataModelEntity sourceModel,
                                                             CollectionTaskTargetBinding target,
                                                             DatasourceEntity targetDatasource,
                                                             DataModelEntity targetModel) {
        DataModelLineageRelationEntity relation = new DataModelLineageRelationEntity();
        relation.setTenantId(task.getTenantId());
        relation.setProjectId(task.getProjectId());
        relation.setSourceType(SOURCE_TYPE_COLLECTION_TASK);
        relation.setCollectionTaskId(task.getId());
        relation.setCollectionTaskNameSnapshot(task.getName());
        relation.setSourceDatasourceId(source.getDatasourceId());
        relation.setSourceDatasourceNameSnapshot(firstNonBlank(source.getDatasourceName(), sourceDatasource == null ? null : sourceDatasource.getName()));
        relation.setSourceDatasourceTypeSnapshot(firstNonBlank(source.getDatasourceTypeCode(), sourceDatasource == null ? null : sourceDatasource.getTypeCode()));
        relation.setSourceDatabaseNameSnapshot(resolveDatabaseName(sourceDatasource));
        relation.setSourceHostSnapshot(resolveStringMetadata(sourceDatasource, "host", "endpoint"));
        relation.setSourcePortSnapshot(resolveStringMetadata(sourceDatasource, "port"));
        relation.setSourceModelId(source.getModelId());
        relation.setSourceModelNameSnapshot(firstNonBlank(source.getModelName(), sourceModel == null ? null : sourceModel.getName()));
        relation.setSourceModelLocatorSnapshot(firstNonBlank(source.getModelPhysicalLocator(), sourceModel == null ? null : sourceModel.getPhysicalLocator()));
        relation.setTargetDatasourceId(target.getDatasourceId());
        relation.setTargetDatasourceNameSnapshot(firstNonBlank(target.getDatasourceName(), targetDatasource == null ? null : targetDatasource.getName()));
        relation.setTargetDatasourceTypeSnapshot(firstNonBlank(target.getDatasourceTypeCode(), targetDatasource == null ? null : targetDatasource.getTypeCode()));
        relation.setTargetDatabaseNameSnapshot(resolveDatabaseName(targetDatasource));
        relation.setTargetHostSnapshot(resolveStringMetadata(targetDatasource, "host", "endpoint"));
        relation.setTargetPortSnapshot(resolveStringMetadata(targetDatasource, "port"));
        relation.setTargetModelId(target.getModelId());
        relation.setTargetModelNameSnapshot(firstNonBlank(target.getModelName(), targetModel == null ? null : targetModel.getName()));
        relation.setTargetModelLocatorSnapshot(firstNonBlank(target.getModelPhysicalLocator(), targetModel == null ? null : targetModel.getPhysicalLocator()));
        relation.setLatestRunStatus(DataModelLineageRunStatusSupport.RUN_STATUS_NOT_RUN);
        return relation;
    }

    private void appendUnique(List<DataModelLineageRelationEntity> relations,
                              Set<String> uniqueKeys,
                              DataModelLineageRelationEntity relation) {
        String key = relationUniqueKey(relation);
        if (uniqueKeys.add(key)) {
            relations.add(relation);
        }
    }

    private String relationUniqueKey(DataModelLineageRelationEntity relation) {
        return String.join("|",
                safeText(relation.getSourceType()),
                safeText(relation.getCollectionTaskId()),
                safeText(relation.getLevel()),
                safeText(relation.getSourceDatasourceId()),
                safeText(relation.getSourceModelId()),
                safeText(relation.getSourceFieldKey()),
                safeText(relation.getTargetDatasourceId()),
                safeText(relation.getTargetModelId()),
                safeText(relation.getTargetFieldKey()),
                safeText(relation.getMappingMode()),
                safeText(relation.getExpressionSnapshot()));
    }

    private Map<Long, DatasourceEntity> loadDatasourceMap(List<CollectionTaskSourceBinding> sourceBindings,
                                                          CollectionTaskTargetBinding targetBinding) {
        Set<Long> ids = new LinkedHashSet<Long>();
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            if (sourceBinding.getDatasourceId() != null) {
                ids.add(sourceBinding.getDatasourceId());
            }
        }
        if (targetBinding != null && targetBinding.getDatasourceId() != null) {
            ids.add(targetBinding.getDatasourceId());
        }
        if (ids.isEmpty()) {
            return new LinkedHashMap<Long, DatasourceEntity>();
        }
        List<DatasourceEntity> items = datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getTenantId, securityService.currentTenantId())
                .in(DatasourceEntity::getId, ids));
        Map<Long, DatasourceEntity> result = new LinkedHashMap<Long, DatasourceEntity>();
        for (DatasourceEntity item : items) {
            result.put(item.getId(), item);
        }
        return result;
    }

    private Map<Long, DataModelEntity> loadModelMap(List<CollectionTaskSourceBinding> sourceBindings,
                                                    CollectionTaskTargetBinding targetBinding) {
        Set<Long> ids = new LinkedHashSet<Long>();
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            if (sourceBinding.getModelId() != null) {
                ids.add(sourceBinding.getModelId());
            }
        }
        if (targetBinding != null && targetBinding.getModelId() != null) {
            ids.add(targetBinding.getModelId());
        }
        if (ids.isEmpty()) {
            return new LinkedHashMap<Long, DataModelEntity>();
        }
        List<DataModelEntity> items = dataModelMapper.selectList(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getTenantId, securityService.currentTenantId())
                .in(DataModelEntity::getId, ids));
        Map<Long, DataModelEntity> result = new LinkedHashMap<Long, DataModelEntity>();
        for (DataModelEntity item : items) {
            result.put(item.getId(), item);
        }
        return result;
    }

    private DataModelEntity requireReadableModel(Long modelId) {
        DataModelEntity entity = dataModelMapper.selectById(modelId);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Model not found: " + modelId);
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATA_MODEL,
                entity.getProjectId(), entity.getId(), "Model not found: " + modelId);
        return entity;
    }

    private DataModelEntity requireWritableModel(Long modelId) {
        DataModelEntity entity = requireReadableModel(modelId);
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private boolean isLineageEditable(DataModelEntity focusModel, LineageLevel level) {
        return focusModel != null
                && level != LineageLevel.DATABASE
                && Objects.equals(focusModel.getProjectId(), projectResourceAccessService.currentProjectId());
    }

    private void validateManualFieldKeys(DataModelManualLineageSaveRequest request,
                                         DataModelEntity sourceModel,
                                         DataModelEntity targetModel) {
        if (request.getLevel() != LineageLevel.FIELD) {
            return;
        }
        if (isBlank(request.getSourceFieldKey()) || isBlank(request.getTargetFieldKey())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Source and target fields are required for field lineage");
        }
        Set<String> sourceFields = extractModelFields(sourceModel);
        Set<String> targetFields = extractModelFields(targetModel);
        if (!containsIgnoreCase(sourceFields, request.getSourceFieldKey())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Source field not found: " + request.getSourceFieldKey());
        }
        if (!containsIgnoreCase(targetFields, request.getTargetFieldKey())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Target field not found: " + request.getTargetFieldKey());
        }
    }

    private DataModelLineageRelationEntity requireWritableManualRelation(DataModelEntity focusModel, Long relationId) {
        DataModelLineageRelationEntity relation = relationMapper.selectById(relationId);
        if (relation == null || !SOURCE_TYPE_MANUAL.equalsIgnoreCase(relation.getSourceType())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Manual lineage relation not found: " + relationId);
        }
        projectResourceAccessService.assertWritable(relation.getProjectId());
        if (!Objects.equals(focusModel.getId(), relation.getSourceModelId()) && !Objects.equals(focusModel.getId(), relation.getTargetModelId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Manual lineage relation does not belong to the current model");
        }
        return relation;
    }

    private void ensureManualRelationUnique(DataModelLineageRelationEntity relation, Long selfId) {
        List<DataModelLineageRelationEntity> duplicates = relationMapper.selectList(new LambdaQueryWrapper<DataModelLineageRelationEntity>()
                .eq(DataModelLineageRelationEntity::getTenantId, relation.getTenantId())
                .eq(DataModelLineageRelationEntity::getProjectId, relation.getProjectId())
                .eq(DataModelLineageRelationEntity::getSourceType, SOURCE_TYPE_MANUAL)
                .eq(DataModelLineageRelationEntity::getLevel, relation.getLevel())
                .eq(DataModelLineageRelationEntity::getSourceModelId, relation.getSourceModelId())
                .eq(DataModelLineageRelationEntity::getTargetModelId, relation.getTargetModelId())
                .eq(DataModelLineageRelationEntity::getSourceFieldKey, relation.getSourceFieldKey())
                .eq(DataModelLineageRelationEntity::getTargetFieldKey, relation.getTargetFieldKey()));
        for (DataModelLineageRelationEntity duplicate : duplicates) {
            if (selfId != null && Objects.equals(selfId, duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "The same manual lineage relation already exists");
        }
    }

    private StudioUserEntity currentUser() {
        Long userId = securityService.currentUserId();
        return userId == null ? null : studioUserMapper.selectById(userId);
    }

    private String resolveUserDisplayName(StudioUserEntity user) {
        if (user == null) {
            return securityService.currentUsername();
        }
        return firstNonBlank(user.getDisplayName(), user.getUsername(), securityService.currentUsername());
    }

    private List<DataModelLineageRelationEntity> loadAccessibleRelations(LineageLevel level) {
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        List<Long> sharedTaskIds = projectResourceAccessService.sharedResourceIdList(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK);
        LambdaQueryWrapper<DataModelLineageRelationEntity> query = new LambdaQueryWrapper<DataModelLineageRelationEntity>()
                .eq(DataModelLineageRelationEntity::getTenantId, securityService.currentTenantId())
                .eq(DataModelLineageRelationEntity::getLevel, level.name());
        if (sharedTaskIds.isEmpty()) {
            query.eq(DataModelLineageRelationEntity::getProjectId, currentProjectId);
        } else {
            query.and(wrapper -> wrapper.eq(DataModelLineageRelationEntity::getProjectId, currentProjectId)
                    .or(inner -> inner.eq(DataModelLineageRelationEntity::getSourceType, SOURCE_TYPE_COLLECTION_TASK)
                            .in(DataModelLineageRelationEntity::getCollectionTaskId, sharedTaskIds)));
        }
        return relationMapper.selectList(query);
    }

    private List<DataModelLineageRelationEntity> filterVisibleRelations(List<DataModelLineageRelationEntity> relations) {
        if (relations == null || relations.isEmpty()) {
            return new ArrayList<DataModelLineageRelationEntity>();
        }
        Set<Long> visibleModelIds = visibleModelIds();
        Set<Long> visibleDatasourceIds = visibleDatasourceIds();
        List<DataModelLineageRelationEntity> filtered = new ArrayList<DataModelLineageRelationEntity>();
        for (DataModelLineageRelationEntity relation : relations) {
            if (isVisibleRelation(relation, visibleModelIds, visibleDatasourceIds)) {
                filtered.add(relation);
            }
        }
        return filtered;
    }

    private void enrichLatestRunStatus(List<DataModelLineageRelationEntity> relations) {
        if (relations == null || relations.isEmpty()) {
            return;
        }
        Map<Long, RunRecordEntity> latestByTaskId = loadLatestRunsByTaskId(relations);
        for (DataModelLineageRelationEntity relation : relations) {
            RunRecordEntity latestRun = latestByTaskId.get(relation.getCollectionTaskId());
            if (latestRun == null) {
                continue;
            }
            LocalDateTime latestRunTime = DataModelLineageRunStatusSupport.resolveRunTime(latestRun);
            if (!DataModelLineageRunStatusSupport.shouldApplyLatestRun(relation, latestRun, latestRunTime)) {
                continue;
            }
            relation.setLatestRunId(latestRun.getId());
            relation.setLatestRunStatus(latestRun.getStatus());
            relation.setLatestRunAt(latestRunTime);
        }
    }

    private Map<Long, RunRecordEntity> loadLatestRunsByTaskId(Collection<DataModelLineageRelationEntity> relations) {
        if (relations == null || relations.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> taskIds = new LinkedHashSet<Long>();
        Map<Long, Set<Long>> projectIdsByTaskId = new LinkedHashMap<Long, Set<Long>>();
        for (DataModelLineageRelationEntity relation : relations) {
            if (relation != null && relation.getCollectionTaskId() != null) {
                taskIds.add(relation.getCollectionTaskId());
                if (relation.getProjectId() != null) {
                    projectIdsByTaskId.computeIfAbsent(relation.getCollectionTaskId(), key -> new LinkedHashSet<Long>())
                            .add(relation.getProjectId());
                }
            }
        }
        if (taskIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, RunRecordEntity> latestByTaskId = new LinkedHashMap<Long, RunRecordEntity>();
        for (Long taskId : taskIds) {
            LambdaQueryWrapper<RunRecordEntity> query = new LambdaQueryWrapper<RunRecordEntity>()
                    .select(RunRecordEntity::getId,
                            RunRecordEntity::getCollectionTaskId,
                            RunRecordEntity::getStatus,
                            RunRecordEntity::getStartedAt,
                            RunRecordEntity::getEndedAt,
                            RunRecordEntity::getCreatedAt)
                    .eq(RunRecordEntity::getTenantId, securityService.currentTenantId())
                    .eq(RunRecordEntity::getCollectionTaskId, taskId);
            Set<Long> projectIds = projectIdsByTaskId.get(taskId);
            if (projectIds != null && !projectIds.isEmpty()) {
                query.in(RunRecordEntity::getProjectId, projectIds);
            }
            query.in(RunRecordEntity::getStatus, "RUNNING", "SUCCESS", "FAILED")
                    .orderByDesc(RunRecordEntity::getEndedAt)
                    .orderByDesc(RunRecordEntity::getStartedAt)
                    .orderByDesc(RunRecordEntity::getCreatedAt)
                    .orderByDesc(RunRecordEntity::getId);
            Page<RunRecordEntity> page = new Page<RunRecordEntity>(1, 1, false);
            Page<RunRecordEntity> result = runRecordMapper.selectPage(page, query);
            if (!result.getRecords().isEmpty()) {
                latestByTaskId.put(taskId, result.getRecords().get(0));
            }
        }
        return latestByTaskId;
    }

    private Set<Long> visibleModelIds() {
        Set<Long> ids = new LinkedHashSet<Long>();
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        List<DataModelEntity> models = dataModelMapper.selectList(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getTenantId, securityService.currentTenantId())
                .eq(DataModelEntity::getProjectId, currentProjectId));
        for (DataModelEntity model : models) {
            ids.add(model.getId());
        }
        ids.addAll(projectResourceAccessService.sharedResourceIds(StudioConstants.RESOURCE_TYPE_DATA_MODEL));
        return ids;
    }

    private Set<Long> visibleDatasourceIds() {
        Set<Long> ids = new LinkedHashSet<Long>();
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        List<DatasourceEntity> datasources = datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getTenantId, securityService.currentTenantId())
                .eq(DatasourceEntity::getProjectId, currentProjectId));
        for (DatasourceEntity datasource : datasources) {
            ids.add(datasource.getId());
        }
        ids.addAll(projectResourceAccessService.sharedResourceIds(StudioConstants.RESOURCE_TYPE_DATASOURCE));
        return ids;
    }

    private boolean isVisibleRelation(DataModelLineageRelationEntity relation,
                                      Set<Long> visibleModelIds,
                                      Set<Long> visibleDatasourceIds) {
        if (relation == null) {
            return false;
        }
        if (relation.getSourceModelId() != null && !visibleModelIds.contains(relation.getSourceModelId())) {
            return false;
        }
        if (relation.getTargetModelId() != null && !visibleModelIds.contains(relation.getTargetModelId())) {
            return false;
        }
        if (relation.getSourceDatasourceId() != null && !visibleDatasourceIds.contains(relation.getSourceDatasourceId())) {
            return false;
        }
        if (relation.getTargetDatasourceId() != null && !visibleDatasourceIds.contains(relation.getTargetDatasourceId())) {
            return false;
        }
        return true;
    }

    private void scheduleAfterCommit(final Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            runnable.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }

    private <T> List<T> convertList(Object source, Class<T> type) {
        if (source == null) {
            return new ArrayList<T>();
        }
        return objectMapper.convertValue(source,
                objectMapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    private <T> T convertMap(Object source, Class<T> type) {
        if (source == null) {
            return null;
        }
        return objectMapper.convertValue(source, type);
    }
}
