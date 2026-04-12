package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DataModelLineageService {

    private static final String SOURCE_TYPE_COLLECTION_TASK = "COLLECTION_TASK";
    private static final String SOURCE_TYPE_MANUAL = "MANUAL";
    private static final String MAPPING_MODE_DIRECT = "DIRECT";
    private static final String MAPPING_MODE_EXPRESSION = "EXPRESSION";
    private static final String MAPPING_MODE_UNRESOLVED_EXPRESSION = "UNRESOLVED_EXPRESSION";
    private static final String RUN_STATUS_NOT_RUN = "NOT_RUN";
    private static final String DISPLAY_STATUS_NOT_RUN = "NOT_RUN";
    private static final String DISPLAY_STATUS_RUNNING = "RUNNING";
    private static final String DISPLAY_STATUS_NORMAL = "NORMAL";
    private static final String DISPLAY_STATUS_EXCEPTION = "EXCEPTION";
    private static final String SOURCE_TYPE_LABEL_AUTOMATIC = "AUTOMATIC";
    private static final String SOURCE_TYPE_LABEL_MANUAL = "MANUAL";
    private static final String SOURCE_TYPE_LABEL_MIXED = "MIXED";
    private static final String VISUAL_PLATFORM_MODEL = "PLATFORM_MODEL";
    private static final String VISUAL_EXTERNAL_ACCESS = "EXTERNAL_ACCESS";
    private static final Pattern QUALIFIED_FIELD_PATTERN = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("(?<![A-Za-z0-9_\\.])([A-Za-z_][A-Za-z0-9_]*)(?![A-Za-z0-9_])");
    private static final Set<String> EXPRESSION_KEYWORDS = new LinkedHashSet<String>();

    static {
        Collections.addAll(EXPRESSION_KEYWORDS,
                "case", "when", "then", "else", "end", "null", "and", "or", "not", "as",
                "if", "cast", "concat", "sum", "max", "min", "avg", "count", "coalesce",
                "substr", "substring", "trim", "replace", "lower", "upper", "round", "floor",
                "ceil", "ceiling", "abs", "year", "month", "day", "hour", "minute", "second",
                "date", "now", "current_date", "current_timestamp", "true", "false", "distinct");
    }

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
        Map<String, Set<String>> aliasFields = buildAliasFields(sourceBindings, modelMap);
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
        String status = normalizeStatus(event.getEventType());
        if (isBlank(status)) {
            return;
        }
        LocalDateTime eventTime = resolveEventTime(event);
        LambdaQueryWrapper<DataModelLineageRelationEntity> query = new LambdaQueryWrapper<DataModelLineageRelationEntity>()
                .eq(DataModelLineageRelationEntity::getCollectionTaskId, event.getCollectionTaskId());
        if (event.getProjectId() != null) {
            query.eq(DataModelLineageRelationEntity::getProjectId, event.getProjectId());
        }
        List<DataModelLineageRelationEntity> relations = relationMapper.selectList(query);
        for (DataModelLineageRelationEntity relation : relations) {
            if (shouldUpdateRunStatus(relation, event, eventTime)) {
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
        LineageQueryContext context = buildLineageContext(focusModel, relations, level);
        DataModelLineageView view = new DataModelLineageView();
        view.setEditable(Boolean.valueOf(isLineageEditable(focusModel, level)));
        view.setSummary(buildSummary(context));
        view.setNodes(context.nodes);
        view.setEdges(context.edges);
        view.setUnresolvedExpressions(context.unresolvedExpressions);
        return view;
    }

    public DataModelLineageEdgeDetailView getEdgeDetail(Long modelId, LineageLevel level, String edgeId) {
        DataModelEntity focusModel = requireReadableModel(modelId);
        EdgeKey edgeKey = decodeEdgeKey(edgeId);
        if (edgeKey == null || !Objects.equals(edgeKey.level, level.name())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Lineage edge not found: " + edgeId);
        }
        DatasourceEntity focusDatasource = focusModel.getDatasourceId() == null ? null : datasourceMapper.selectById(focusModel.getDatasourceId());
        String focusNodeId = resolveFocusNodeId(level, focusModel, focusDatasource);
        List<DataModelLineageRelationEntity> relations = filterVisibleRelations(loadAccessibleRelations(level));
        enrichLatestRunStatus(relations);
        Set<String> reachableNodeIds = traverseReachableNodeIds(relations, focusNodeId, level);
        List<DataModelLineageRelationEntity> matched = new ArrayList<DataModelLineageRelationEntity>();
        for (DataModelLineageRelationEntity relation : relations) {
            if (!reachableNodeIds.contains(relationSourceNodeId(relation, level))
                    || !reachableNodeIds.contains(relationTargetNodeId(relation, level))) {
                continue;
            }
            if (matchesEdgeKey(edgeKey, relation, level)) {
                matched.add(relation);
            }
        }
        if (matched.isEmpty()) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Lineage edge not found: " + edgeId);
        }
        return buildEdgeDetail(edgeId, level, matched);
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
            CollectionTaskSourceBinding source = findSourceBinding(sourceBindings, mapping.getSourceAlias());
            if (source != null) {
                appendDirectFieldRelation(relations, uniqueKeys, task, source, targetBinding, datasourceMap, modelMap,
                        mapping.getSourceField(), mapping.getTargetField(), null, MAPPING_MODE_DIRECT);
            }
            return;
        }
        if (isBlank(mapping.getExpression())) {
            return;
        }
        ExpressionResolution resolution = resolveExpressionMappings(mapping, sourceBindings, aliasFields);
        if (resolution.references.isEmpty()) {
            CollectionTaskSourceBinding source = sourceBindings.get(0);
            appendDirectFieldRelation(relations, uniqueKeys, task, source, targetBinding, datasourceMap, modelMap,
                    null, mapping.getTargetField(), mapping.getExpression(), MAPPING_MODE_UNRESOLVED_EXPRESSION);
            return;
        }
        for (FieldReference reference : resolution.references) {
            CollectionTaskSourceBinding source = findSourceBinding(sourceBindings, reference.sourceAlias);
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
        relation.setLatestRunStatus(RUN_STATUS_NOT_RUN);
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

    private Map<String, Set<String>> buildAliasFields(List<CollectionTaskSourceBinding> sourceBindings,
                                                      Map<Long, DataModelEntity> modelMap) {
        Map<String, Set<String>> result = new LinkedHashMap<String, Set<String>>();
        for (CollectionTaskSourceBinding binding : sourceBindings) {
            String alias = firstNonBlank(binding.getSourceAlias(), binding.getDatasourceName(), String.valueOf(binding.getModelId()));
            result.put(alias, extractModelFields(modelMap.get(binding.getModelId())));
        }
        return result;
    }

    private Set<String> extractModelFields(DataModelEntity model) {
        Set<String> fields = new LinkedHashSet<String>();
        if (model == null || model.getTechnicalMetadata() == null) {
            return fields;
        }
        Object rawColumns = model.getTechnicalMetadata().get("columns");
        if (!(rawColumns instanceof List)) {
            return fields;
        }
        for (Object candidate : (List<?>) rawColumns) {
            if (!(candidate instanceof Map)) {
                continue;
            }
            Object name = ((Map<?, ?>) candidate).get("name");
            if (name != null && !String.valueOf(name).trim().isEmpty()) {
                fields.add(String.valueOf(name));
            }
        }
        return fields;
    }

    private ExpressionResolution resolveExpressionMappings(FieldMappingDefinition mapping,
                                                           List<CollectionTaskSourceBinding> sourceBindings,
                                                           Map<String, Set<String>> aliasFields) {
        ExpressionResolution resolution = new ExpressionResolution();
        if (mapping == null || isBlank(mapping.getExpression())) {
            return resolution;
        }
        Set<String> seen = new LinkedHashSet<String>();
        Matcher qualifiedMatcher = QUALIFIED_FIELD_PATTERN.matcher(mapping.getExpression());
        while (qualifiedMatcher.find()) {
            String alias = qualifiedMatcher.group(1);
            String field = qualifiedMatcher.group(2);
            Set<String> fields = aliasFields.get(alias);
            if (fields != null && containsIgnoreCase(fields, field) && seen.add(alias + "." + field.toLowerCase(Locale.ENGLISH))) {
                resolution.references.add(new FieldReference(alias, findOriginalField(fields, field)));
            }
        }
        if (!resolution.references.isEmpty()) {
            return resolution;
        }
        Matcher identifierMatcher = IDENTIFIER_PATTERN.matcher(mapping.getExpression());
        while (identifierMatcher.find()) {
            String identifier = identifierMatcher.group(1);
            if (EXPRESSION_KEYWORDS.contains(identifier.toLowerCase(Locale.ENGLISH))) {
                continue;
            }
            FieldReference uniqueMatch = resolveUniqueBareField(identifier, sourceBindings, aliasFields);
            if (uniqueMatch != null && seen.add(uniqueMatch.sourceAlias + "." + uniqueMatch.fieldName.toLowerCase(Locale.ENGLISH))) {
                resolution.references.add(uniqueMatch);
            }
        }
        return resolution;
    }

    private FieldReference resolveUniqueBareField(String identifier,
                                                  List<CollectionTaskSourceBinding> sourceBindings,
                                                  Map<String, Set<String>> aliasFields) {
        FieldReference result = null;
        for (CollectionTaskSourceBinding binding : sourceBindings) {
            String alias = firstNonBlank(binding.getSourceAlias(), binding.getDatasourceName(), String.valueOf(binding.getModelId()));
            Set<String> fields = aliasFields.get(alias);
            if (fields == null || !containsIgnoreCase(fields, identifier)) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = new FieldReference(alias, findOriginalField(fields, identifier));
        }
        return result;
    }

    private boolean containsIgnoreCase(Collection<String> fields, String expected) {
        if (fields == null || expected == null) {
            return false;
        }
        for (String field : fields) {
            if (field != null && field.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    private String findOriginalField(Collection<String> fields, String expected) {
        if (fields == null) {
            return expected;
        }
        for (String field : fields) {
            if (field != null && field.equalsIgnoreCase(expected)) {
                return field;
            }
        }
        return expected;
    }

    private CollectionTaskSourceBinding findSourceBinding(List<CollectionTaskSourceBinding> sourceBindings, String sourceAlias) {
        if (sourceBindings == null || sourceBindings.isEmpty()) {
            return null;
        }
        if (isBlank(sourceAlias)) {
            return sourceBindings.get(0);
        }
        for (CollectionTaskSourceBinding binding : sourceBindings) {
            if (sourceAlias.equalsIgnoreCase(firstNonBlank(binding.getSourceAlias(), binding.getDatasourceName(), String.valueOf(binding.getModelId())))) {
                return binding;
            }
        }
        return sourceBindings.get(0);
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
            LocalDateTime latestRunTime = resolveRunTime(latestRun);
            if (!shouldApplyLatestRun(relation, latestRun, latestRunTime)) {
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
        for (DataModelLineageRelationEntity relation : relations) {
            if (relation != null && relation.getCollectionTaskId() != null) {
                taskIds.add(relation.getCollectionTaskId());
            }
        }
        if (taskIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<RunRecordEntity> runs = runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getTenantId, securityService.currentTenantId())
                .in(RunRecordEntity::getCollectionTaskId, taskIds)
                .in(RunRecordEntity::getStatus, "RUNNING", "SUCCESS", "FAILED")
                .orderByDesc(RunRecordEntity::getEndedAt)
                .orderByDesc(RunRecordEntity::getStartedAt)
                .orderByDesc(RunRecordEntity::getCreatedAt)
                .orderByDesc(RunRecordEntity::getId));
        if (runs.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, RunRecordEntity> latestByTaskId = new LinkedHashMap<Long, RunRecordEntity>();
        for (RunRecordEntity run : runs) {
            if (run.getCollectionTaskId() != null && !latestByTaskId.containsKey(run.getCollectionTaskId())) {
                latestByTaskId.put(run.getCollectionTaskId(), run);
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

    private LineageQueryContext buildLineageContext(DataModelEntity focusModel,
                                                    List<DataModelLineageRelationEntity> relations,
                                                    LineageLevel level) {
        LineageQueryContext context = new LineageQueryContext();
        DatasourceEntity focusDatasource = focusModel.getDatasourceId() == null ? null : datasourceMapper.selectById(focusModel.getDatasourceId());
        String focusNodeId = resolveFocusNodeId(level, focusModel, focusDatasource);
        Set<String> reachableNodeIds = traverseReachableNodeIds(relations, focusNodeId, level);
        reachableNodeIds.add(focusNodeId);
        Map<String, List<DataModelLineageRelationEntity>> groupedEdges = new LinkedHashMap<String, List<DataModelLineageRelationEntity>>();
        for (DataModelLineageRelationEntity relation : relations) {
            String sourceNodeId = relationSourceNodeId(relation, level);
            String targetNodeId = relationTargetNodeId(relation, level);
            if (!reachableNodeIds.contains(sourceNodeId) || !reachableNodeIds.contains(targetNodeId)) {
                continue;
            }
            String edgeId = encodeEdgeKey(buildEdgeKey(level, relation));
            groupedEdges.computeIfAbsent(edgeId, key -> new ArrayList<DataModelLineageRelationEntity>()).add(relation);
        }

        Map<String, DataModelLineageNodeView> nodes = new LinkedHashMap<String, DataModelLineageNodeView>();
        ensureFocusNode(nodes, focusModel, focusDatasource, focusNodeId, level);
        Map<String, NodeRelationRole> roles = classifyNodeRoles(groupedEdges, focusNodeId, level);

        Set<String> unresolvedKeys = new LinkedHashSet<String>();
        for (Map.Entry<String, List<DataModelLineageRelationEntity>> entry : groupedEdges.entrySet()) {
            List<DataModelLineageRelationEntity> contributors = entry.getValue();
            contributors.sort(runStatusComparator());
            DataModelLineageRelationEntity latest = contributors.get(0);
            DataModelLineageRelationEntity preferred = resolvePreferredEdgeRelation(contributors);
            String sourceNodeId = relationSourceNodeId(latest, level);
            String targetNodeId = relationTargetNodeId(latest, level);
            nodes.putIfAbsent(sourceNodeId, buildNodeView(latest, true, level, roles.get(sourceNodeId), sourceNodeId.equals(focusNodeId)));
            nodes.putIfAbsent(targetNodeId, buildNodeView(latest, false, level, roles.get(targetNodeId), targetNodeId.equals(focusNodeId)));
            if (level == LineageLevel.FIELD) {
                appendFieldToNode(nodes.get(sourceNodeId), latest.getSourceFieldKey());
                appendFieldToNode(nodes.get(targetNodeId), latest.getTargetFieldKey());
            }

            DataModelLineageEdgeView edge = new DataModelLineageEdgeView();
            edge.setEdgeId(entry.getKey());
            edge.setSourceNodeId(sourceNodeId);
            edge.setTargetNodeId(targetNodeId);
            edge.setSelfLoop(Boolean.valueOf(Objects.equals(sourceNodeId, targetNodeId)));
            edge.setSourceField(level == LineageLevel.FIELD ? latest.getSourceFieldKey() : null);
            edge.setTargetField(level == LineageLevel.FIELD ? latest.getTargetFieldKey() : null);
            edge.setLabel(level == LineageLevel.FIELD
                    ? safeText(latest.getSourceFieldKey()) + " -> " + safeText(latest.getTargetFieldKey())
                    : null);
            edge.setSourceType(preferred == null ? null : preferred.getSourceType());
            edge.setSourceTypeLabel(resolveAggregatedSourceTypeLabel(contributors));
            edge.setLatestRunId(preferred == null ? null : preferred.getLatestRunId());
            edge.setLatestRunStatus(preferred == null ? RUN_STATUS_NOT_RUN : defaultRunStatus(preferred.getLatestRunStatus()));
            edge.setDisplayStatus(resolveDisplayStatus(preferred));
            edge.setLatestRunAt(preferred == null ? null : preferred.getLatestRunAt());
            edge.setContributorCount(Integer.valueOf(contributors.size()));
            context.edges.add(edge);

            if (level == LineageLevel.FIELD) {
                for (DataModelLineageRelationEntity relation : contributors) {
                    if (!MAPPING_MODE_UNRESOLVED_EXPRESSION.equalsIgnoreCase(relation.getMappingMode())) {
                        continue;
                    }
                    String unresolvedKey = relation.getCollectionTaskId() + "|" + safeText(relation.getTargetFieldKey()) + "|" + safeText(relation.getExpressionSnapshot());
                    if (!unresolvedKeys.add(unresolvedKey)) {
                        continue;
                    }
                    DataModelLineageUnresolvedExpressionView unresolved = new DataModelLineageUnresolvedExpressionView();
                    unresolved.setCollectionTaskId(relation.getCollectionTaskId());
                    unresolved.setCollectionTaskName(relation.getCollectionTaskNameSnapshot());
                    unresolved.setSourceAlias(null);
                    unresolved.setTargetField(relation.getTargetFieldKey());
                    unresolved.setExpression(relation.getExpressionSnapshot());
                    unresolved.setLatestRunAt(relation.getLatestRunAt());
                    unresolved.setLatestRunStatus(defaultRunStatus(relation.getLatestRunStatus()));
                    context.unresolvedExpressions.add(unresolved);
                }
            }
        }

        context.level = level == null ? null : level.name();
        context.focusNodeId = focusNodeId;
        context.nodes.addAll(nodes.values());
        return context;
    }

    private DataModelLineageEdgeDetailView buildEdgeDetail(String edgeId,
                                                           LineageLevel level,
                                                           List<DataModelLineageRelationEntity> contributors) {
        contributors.sort(runStatusComparator());
        DataModelLineageRelationEntity latest = contributors.get(0);
        DataModelLineageRelationEntity preferred = resolvePreferredEdgeRelation(contributors);
        DataModelLineageEdgeDetailView detail = new DataModelLineageEdgeDetailView();
        detail.setEdgeId(edgeId);
        detail.setSourceNodeTitle(resolveNodeTitle(latest, true, level));
        detail.setTargetNodeTitle(resolveNodeTitle(latest, false, level));
        detail.setSourceField(level == LineageLevel.FIELD ? latest.getSourceFieldKey() : null);
        detail.setTargetField(level == LineageLevel.FIELD ? latest.getTargetFieldKey() : null);
        detail.setSourceType(preferred == null ? null : preferred.getSourceType());
        detail.setSourceTypeLabel(resolveAggregatedSourceTypeLabel(contributors));
        detail.setLatestRunId(preferred == null ? null : preferred.getLatestRunId());
        detail.setLatestRunStatus(preferred == null ? RUN_STATUS_NOT_RUN : defaultRunStatus(preferred.getLatestRunStatus()));
        detail.setDisplayStatus(resolveDisplayStatus(preferred));
        detail.setLatestRunAt(preferred == null ? null : preferred.getLatestRunAt());
        for (DataModelLineageRelationEntity relation : contributors) {
            DataModelLineageContributorView contributor = new DataModelLineageContributorView();
            contributor.setRelationId(relation.getId());
            contributor.setSourceType(relation.getSourceType());
            contributor.setSourceTypeLabel(resolveSourceTypeLabel(relation.getSourceType()));
            contributor.setDisplayStatus(resolveDisplayStatus(relation));
            contributor.setSourceModelId(relation.getSourceModelId());
            contributor.setTargetModelId(relation.getTargetModelId());
            contributor.setSourceField(relation.getSourceFieldKey());
            contributor.setTargetField(relation.getTargetFieldKey());
            contributor.setCollectionTaskId(relation.getCollectionTaskId());
            contributor.setCollectionTaskName(relation.getCollectionTaskNameSnapshot());
            contributor.setLatestRunId(relation.getLatestRunId());
            contributor.setLatestRunStatus(defaultRunStatus(relation.getLatestRunStatus()));
            contributor.setLatestRunAt(relation.getLatestRunAt());
            contributor.setTaskPath(relation.getCollectionTaskId() == null ? null : "/collection-tasks/" + relation.getCollectionTaskId() + "/edit");
            contributor.setRunPath(relation.getCollectionTaskId() == null || relation.getLatestRunId() == null
                    ? null
                    : "/collection-task-runs?collectionTaskId=" + relation.getCollectionTaskId() + "&runRecordId=" + relation.getLatestRunId());
            contributor.setMappingMode(relation.getMappingMode());
            contributor.setExpression(relation.getExpressionSnapshot());
            contributor.setMaintainerUserId(relation.getManualMaintainerUserId());
            contributor.setMaintainer(relation.getManualMaintainerNameSnapshot());
            contributor.setUpdatedAt(relation.getUpdatedAt());
            contributor.setEditable(Boolean.valueOf(SOURCE_TYPE_MANUAL.equalsIgnoreCase(relation.getSourceType())));
            detail.getContributors().add(contributor);
        }
        return detail;
    }

    private Map<String, NodeRelationRole> classifyNodeRoles(Map<String, List<DataModelLineageRelationEntity>> groupedEdges,
                                                            String focusNodeId,
                                                            LineageLevel level) {
        Map<String, Set<String>> incoming = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<String, Set<String>>();
        for (List<DataModelLineageRelationEntity> contributors : groupedEdges.values()) {
            if (contributors.isEmpty()) {
                continue;
            }
            DataModelLineageRelationEntity latest = contributors.get(0);
            String sourceNodeId = relationSourceNodeId(latest, level);
            String targetNodeId = relationTargetNodeId(latest, level);
            incoming.computeIfAbsent(targetNodeId, key -> new LinkedHashSet<String>()).add(sourceNodeId);
            outgoing.computeIfAbsent(sourceNodeId, key -> new LinkedHashSet<String>()).add(targetNodeId);
        }
        Set<String> upstream = traverse(incoming, focusNodeId);
        Set<String> downstream = traverse(outgoing, focusNodeId);
        Set<String> allNodes = new LinkedHashSet<String>();
        allNodes.addAll(incoming.keySet());
        allNodes.addAll(outgoing.keySet());
        Map<String, NodeRelationRole> result = new LinkedHashMap<String, NodeRelationRole>();
        for (String nodeId : allNodes) {
            if (Objects.equals(nodeId, focusNodeId)) {
                result.put(nodeId, NodeRelationRole.FOCUS);
            } else if (upstream.contains(nodeId)) {
                result.put(nodeId, NodeRelationRole.UPSTREAM);
            } else if (downstream.contains(nodeId)) {
                result.put(nodeId, NodeRelationRole.DOWNSTREAM);
            } else {
                result.put(nodeId, NodeRelationRole.UNRELATED);
            }
        }
        return result;
    }

    private DataModelLineageSummaryView buildSummary(LineageQueryContext context) {
        DataModelLineageSummaryView summary = new DataModelLineageSummaryView();
        if (context == null) {
            return summary;
        }
        if (LineageLevel.FIELD.name().equalsIgnoreCase(context.level)) {
            buildFieldLevelSummary(summary, context);
            return summary;
        }
        Map<String, Set<String>> incoming = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<String, Set<String>>();
        for (DataModelLineageEdgeView edge : context.edges) {
            incoming.computeIfAbsent(edge.getTargetNodeId(), key -> new LinkedHashSet<String>()).add(edge.getSourceNodeId());
            outgoing.computeIfAbsent(edge.getSourceNodeId(), key -> new LinkedHashSet<String>()).add(edge.getTargetNodeId());
        }
        Set<String> directUpstream = new LinkedHashSet<String>(incoming.getOrDefault(context.focusNodeId, Collections.<String>emptySet()));
        directUpstream.remove(context.focusNodeId);
        summary.setDirectUpstreamCount(Integer.valueOf(directUpstream.size()));
        Set<String> allUpstream = traverse(incoming, context.focusNodeId);
        summary.setTotalUpstreamCount(Integer.valueOf(allUpstream.size()));
        summary.setUpstreamDepth(Integer.valueOf(maxDepth(incoming, context.focusNodeId)));
        Set<String> directDownstream = new LinkedHashSet<String>(outgoing.getOrDefault(context.focusNodeId, Collections.<String>emptySet()));
        directDownstream.remove(context.focusNodeId);
        summary.setDirectDownstreamCount(Integer.valueOf(directDownstream.size()));
        Set<String> allDownstream = traverse(outgoing, context.focusNodeId);
        summary.setTotalDownstreamCount(Integer.valueOf(allDownstream.size()));
        summary.setDownstreamDepth(Integer.valueOf(maxDepth(outgoing, context.focusNodeId)));
        return summary;
    }

    private void buildFieldLevelSummary(DataModelLineageSummaryView summary, LineageQueryContext context) {
        Map<String, Set<String>> incoming = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<String, Set<String>>();
        Set<String> focusFieldNodeIds = new LinkedHashSet<String>();
        for (DataModelLineageEdgeView edge : context.edges) {
            if (isBlank(edge.getSourceField()) || isBlank(edge.getTargetField())) {
                continue;
            }
            String sourceFieldNodeId = fieldVertexId(edge.getSourceNodeId(), edge.getSourceField());
            String targetFieldNodeId = fieldVertexId(edge.getTargetNodeId(), edge.getTargetField());
            incoming.computeIfAbsent(targetFieldNodeId, key -> new LinkedHashSet<String>()).add(sourceFieldNodeId);
            outgoing.computeIfAbsent(sourceFieldNodeId, key -> new LinkedHashSet<String>()).add(targetFieldNodeId);
            if (Objects.equals(edge.getSourceNodeId(), context.focusNodeId)) {
                focusFieldNodeIds.add(sourceFieldNodeId);
            }
            if (Objects.equals(edge.getTargetNodeId(), context.focusNodeId)) {
                focusFieldNodeIds.add(targetFieldNodeId);
            }
        }
        if (focusFieldNodeIds.isEmpty()) {
            summary.setUpstreamDepth(Integer.valueOf(0));
            summary.setTotalUpstreamCount(Integer.valueOf(0));
            summary.setDirectUpstreamCount(Integer.valueOf(0));
            summary.setDownstreamDepth(Integer.valueOf(0));
            summary.setTotalDownstreamCount(Integer.valueOf(0));
            summary.setDirectDownstreamCount(Integer.valueOf(0));
            return;
        }
        Set<String> directUpstream = new LinkedHashSet<String>();
        Set<String> directDownstream = new LinkedHashSet<String>();
        for (String focusFieldNodeId : focusFieldNodeIds) {
            directUpstream.addAll(incoming.getOrDefault(focusFieldNodeId, Collections.<String>emptySet()));
            directDownstream.addAll(outgoing.getOrDefault(focusFieldNodeId, Collections.<String>emptySet()));
        }
        directUpstream.removeAll(focusFieldNodeIds);
        directDownstream.removeAll(focusFieldNodeIds);
        summary.setDirectUpstreamCount(Integer.valueOf(directUpstream.size()));
        summary.setDirectDownstreamCount(Integer.valueOf(directDownstream.size()));
        summary.setTotalUpstreamCount(Integer.valueOf(traverseAll(incoming, focusFieldNodeIds).size()));
        summary.setTotalDownstreamCount(Integer.valueOf(traverseAll(outgoing, focusFieldNodeIds).size()));
        summary.setUpstreamDepth(Integer.valueOf(maxDepth(incoming, focusFieldNodeIds)));
        summary.setDownstreamDepth(Integer.valueOf(maxDepth(outgoing, focusFieldNodeIds)));
    }

    private void ensureFocusNode(Map<String, DataModelLineageNodeView> nodes,
                                 DataModelEntity focusModel,
                                 DatasourceEntity focusDatasource,
                                 String focusNodeId,
                                 LineageLevel level) {
        if (nodes.containsKey(focusNodeId)) {
            return;
        }
        DataModelLineageNodeView node = new DataModelLineageNodeView();
        node.setNodeId(focusNodeId);
        node.setFocus(Boolean.TRUE);
        node.setVisualType(VISUAL_PLATFORM_MODEL);
        node.setDatasourceId(focusDatasource == null ? null : focusDatasource.getId());
        node.setModelId(focusModel.getId());
        node.setDatasourceName(focusDatasource == null ? null : focusDatasource.getName());
        node.setDatasourceType(focusDatasource == null ? null : focusDatasource.getTypeCode());
        node.setDatabaseName(resolveDatabaseName(focusDatasource));
        node.setPhysicalLocator(focusModel.getPhysicalLocator());
        node.setHost(resolveStringMetadata(focusDatasource, "host", "endpoint"));
        node.setPort(resolveStringMetadata(focusDatasource, "port"));
        node.setDailyIncrement("--");
        node.setTotalCount("--");
        node.setTitle(level == LineageLevel.DATABASE
                ? firstNonBlank(node.getDatasourceName(), node.getDatabaseName(), focusModel.getName())
                : focusModel.getName());
        node.setSubtitle(level == LineageLevel.DATABASE ? node.getDatabaseName() : node.getPhysicalLocator());
        nodes.put(focusNodeId, node);
    }

    private DataModelLineageNodeView buildNodeView(DataModelLineageRelationEntity relation,
                                                   boolean sourceSide,
                                                   LineageLevel level,
                                                   NodeRelationRole role,
                                                   boolean focus) {
        DataModelLineageNodeView node = new DataModelLineageNodeView();
        node.setNodeId(sourceSide ? relationSourceNodeId(relation, level) : relationTargetNodeId(relation, level));
        node.setFocus(Boolean.valueOf(focus));
        node.setVisualType(resolveVisualType(level, role, focus));
        node.setDatasourceId(sourceSide ? relation.getSourceDatasourceId() : relation.getTargetDatasourceId());
        node.setModelId(sourceSide ? relation.getSourceModelId() : relation.getTargetModelId());
        node.setDatasourceName(sourceSide ? relation.getSourceDatasourceNameSnapshot() : relation.getTargetDatasourceNameSnapshot());
        node.setDatasourceType(sourceSide ? relation.getSourceDatasourceTypeSnapshot() : relation.getTargetDatasourceTypeSnapshot());
        node.setDatabaseName(sourceSide ? relation.getSourceDatabaseNameSnapshot() : relation.getTargetDatabaseNameSnapshot());
        node.setPhysicalLocator(sourceSide ? relation.getSourceModelLocatorSnapshot() : relation.getTargetModelLocatorSnapshot());
        node.setHost(sourceSide ? relation.getSourceHostSnapshot() : relation.getTargetHostSnapshot());
        node.setPort(sourceSide ? relation.getSourcePortSnapshot() : relation.getTargetPortSnapshot());
        node.setDailyIncrement("--");
        node.setTotalCount("--");
        node.setTitle(level == LineageLevel.DATABASE
                ? firstNonBlank(node.getDatasourceName(), node.getDatabaseName(), sourceSide ? relation.getSourceModelNameSnapshot() : relation.getTargetModelNameSnapshot())
                : sourceSide ? relation.getSourceModelNameSnapshot() : relation.getTargetModelNameSnapshot());
        node.setSubtitle(level == LineageLevel.DATABASE ? node.getDatabaseName() : node.getPhysicalLocator());
        return node;
    }

    private void appendFieldToNode(DataModelLineageNodeView node, String fieldName) {
        if (node == null || isBlank(fieldName)) {
            return;
        }
        for (DataModelLineageNodeFieldView field : node.getFields()) {
            if (fieldName.equalsIgnoreCase(field.getFieldKey())) {
                return;
            }
        }
        DataModelLineageNodeFieldView field = new DataModelLineageNodeFieldView();
        field.setFieldKey(fieldName);
        field.setFieldName(fieldName);
        node.getFields().add(field);
    }

    private Set<String> traverseReachableNodeIds(List<DataModelLineageRelationEntity> relations,
                                                 String focusNodeId,
                                                 LineageLevel level) {
        Map<String, Set<String>> adjacency = new LinkedHashMap<String, Set<String>>();
        for (DataModelLineageRelationEntity relation : relations) {
            String sourceNodeId = relationSourceNodeId(relation, level);
            String targetNodeId = relationTargetNodeId(relation, level);
            adjacency.computeIfAbsent(sourceNodeId, key -> new LinkedHashSet<String>()).add(targetNodeId);
            adjacency.computeIfAbsent(targetNodeId, key -> new LinkedHashSet<String>()).add(sourceNodeId);
        }
        Set<String> visited = new LinkedHashSet<String>();
        Deque<String> queue = new ArrayDeque<String>();
        queue.add(focusNodeId);
        visited.add(focusNodeId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacency.getOrDefault(current, Collections.<String>emptySet())) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return visited;
    }

    private Set<String> traverse(Map<String, Set<String>> adjacency, String startNodeId) {
        Set<String> visited = new LinkedHashSet<String>();
        Deque<String> queue = new ArrayDeque<String>();
        queue.add(startNodeId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacency.getOrDefault(current, Collections.<String>emptySet())) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        visited.remove(startNodeId);
        return visited;
    }

    private Set<String> traverseAll(Map<String, Set<String>> adjacency, Set<String> startNodeIds) {
        Set<String> visited = new LinkedHashSet<String>();
        if (startNodeIds == null || startNodeIds.isEmpty()) {
            return visited;
        }
        Deque<String> queue = new ArrayDeque<String>(startNodeIds);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacency.getOrDefault(current, Collections.<String>emptySet())) {
                if (startNodeIds.contains(next)) {
                    continue;
                }
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return visited;
    }

    private int maxDepth(Map<String, Set<String>> incoming, String nodeId) {
        return maxDepth(incoming, nodeId, new LinkedHashSet<String>());
    }

    private int maxDepth(Map<String, Set<String>> incoming, Set<String> nodeIds) {
        int maxDepth = 0;
        if (nodeIds == null) {
            return maxDepth;
        }
        for (String nodeId : nodeIds) {
            if (nodeId == null) {
                continue;
            }
            maxDepth = Math.max(maxDepth, maxDepth(incoming, nodeId, new LinkedHashSet<String>()));
        }
        return maxDepth;
    }

    private int maxDepth(Map<String, Set<String>> incoming, String nodeId, Set<String> path) {
        if (!path.add(nodeId)) {
            return 0;
        }
        Set<String> direct = incoming.getOrDefault(nodeId, Collections.<String>emptySet());
        if (direct.isEmpty()) {
            path.remove(nodeId);
            return 0;
        }
        int maxDepth = 0;
        for (String parentNodeId : direct) {
            if (Objects.equals(parentNodeId, nodeId)) {
                continue;
            }
            maxDepth = Math.max(maxDepth, 1 + maxDepth(incoming, parentNodeId, path));
        }
        path.remove(nodeId);
        return maxDepth;
    }

    private String relationSourceNodeId(DataModelLineageRelationEntity relation, LineageLevel level) {
        if (level == LineageLevel.DATABASE) {
            return datasourceNodeId(relation.getSourceDatasourceId(), relation.getSourceDatasourceNameSnapshot(), relation.getSourceDatabaseNameSnapshot());
        }
        return modelNodeId(relation.getSourceModelId(), relation.getSourceModelLocatorSnapshot(), relation.getSourceModelNameSnapshot());
    }

    private String relationTargetNodeId(DataModelLineageRelationEntity relation, LineageLevel level) {
        if (level == LineageLevel.DATABASE) {
            return datasourceNodeId(relation.getTargetDatasourceId(), relation.getTargetDatasourceNameSnapshot(), relation.getTargetDatabaseNameSnapshot());
        }
        return modelNodeId(relation.getTargetModelId(), relation.getTargetModelLocatorSnapshot(), relation.getTargetModelNameSnapshot());
    }

    private String resolveFocusNodeId(LineageLevel level, DataModelEntity focusModel, DatasourceEntity focusDatasource) {
        if (level == LineageLevel.DATABASE) {
            return datasourceNodeId(focusDatasource == null ? null : focusDatasource.getId(),
                    focusDatasource == null ? null : focusDatasource.getName(),
                    resolveDatabaseName(focusDatasource));
        }
        return modelNodeId(focusModel.getId(), focusModel.getPhysicalLocator(), focusModel.getName());
    }

    private String datasourceNodeId(Long datasourceId, String datasourceName, String databaseName) {
        if (datasourceId != null) {
            return "datasource:" + datasourceId + ":" + safeText(normalizeText(firstNonBlank(databaseName, "_")));
        }
        return "datasource-snapshot:" + safeText(normalizeText(firstNonBlank(databaseName, datasourceName, "unknown")));
    }

    private String modelNodeId(Long modelId, String physicalLocator, String modelName) {
        if (modelId != null) {
            return "model:" + modelId;
        }
        return "model-snapshot:" + firstNonBlank(physicalLocator, modelName, "unknown");
    }

    private String fieldVertexId(String nodeId, String fieldKey) {
        return safeText(nodeId) + "#" + normalizeText(fieldKey);
    }

    private EdgeKey buildEdgeKey(LineageLevel level, DataModelLineageRelationEntity relation) {
        EdgeKey edgeKey = new EdgeKey();
        edgeKey.level = level.name();
        edgeKey.sourceNodeId = relationSourceNodeId(relation, level);
        edgeKey.targetNodeId = relationTargetNodeId(relation, level);
        edgeKey.sourceField = level == LineageLevel.FIELD ? normalizeText(relation.getSourceFieldKey()) : null;
        edgeKey.targetField = level == LineageLevel.FIELD ? normalizeText(relation.getTargetFieldKey()) : null;
        return edgeKey;
    }

    private boolean matchesEdgeKey(EdgeKey edgeKey, DataModelLineageRelationEntity relation, LineageLevel level) {
        EdgeKey candidate = buildEdgeKey(level, relation);
        return Objects.equals(edgeKey.level, candidate.level)
                && Objects.equals(edgeKey.sourceNodeId, candidate.sourceNodeId)
                && Objects.equals(edgeKey.targetNodeId, candidate.targetNodeId)
                && Objects.equals(normalizeText(edgeKey.sourceField), normalizeText(candidate.sourceField))
                && Objects.equals(normalizeText(edgeKey.targetField), normalizeText(candidate.targetField));
    }

    private String encodeEdgeKey(EdgeKey edgeKey) {
        String raw = edgeKey.level + "|" + edgeKey.sourceNodeId + "|" + edgeKey.targetNodeId + "|" + safeText(edgeKey.sourceField) + "|" + safeText(edgeKey.targetField);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private EdgeKey decodeEdgeKey(String edgeId) {
        if (isBlank(edgeId)) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(edgeId), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length < 5) {
                return null;
            }
            EdgeKey edgeKey = new EdgeKey();
            edgeKey.level = parts[0];
            edgeKey.sourceNodeId = parts[1];
            edgeKey.targetNodeId = parts[2];
            edgeKey.sourceField = blankToNull(parts[3]);
            edgeKey.targetField = blankToNull(parts[4]);
            return edgeKey;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Comparator<DataModelLineageRelationEntity> runStatusComparator() {
        return new Comparator<DataModelLineageRelationEntity>() {
            @Override
            public int compare(DataModelLineageRelationEntity left, DataModelLineageRelationEntity right) {
                LocalDateTime leftTime = left == null ? null : left.getLatestRunAt();
                LocalDateTime rightTime = right == null ? null : right.getLatestRunAt();
                if (leftTime == null && rightTime == null) {
                    return compareNullableLong(right == null ? null : right.getLatestRunId(),
                            left == null ? null : left.getLatestRunId());
                }
                if (leftTime == null) {
                    return 1;
                }
                if (rightTime == null) {
                    return -1;
                }
                int compared = rightTime.compareTo(leftTime);
                if (compared != 0) {
                    return compared;
                }
                return compareNullableLong(right == null ? null : right.getLatestRunId(),
                        left == null ? null : left.getLatestRunId());
            }
        };
    }

    private int compareNullableLong(Long left, Long right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private String resolveNodeTitle(DataModelLineageRelationEntity relation, boolean sourceSide, LineageLevel level) {
        if (level == LineageLevel.DATABASE) {
            return sourceSide
                    ? firstNonBlank(relation.getSourceDatasourceNameSnapshot(), relation.getSourceDatabaseNameSnapshot(), relation.getSourceModelNameSnapshot())
                    : firstNonBlank(relation.getTargetDatasourceNameSnapshot(), relation.getTargetDatabaseNameSnapshot(), relation.getTargetModelNameSnapshot());
        }
        return sourceSide ? relation.getSourceModelNameSnapshot() : relation.getTargetModelNameSnapshot();
    }

    private String resolveVisualType(LineageLevel level, NodeRelationRole role, boolean focus) {
        if (focus) {
            return VISUAL_PLATFORM_MODEL;
        }
        if (role == NodeRelationRole.UPSTREAM) {
            return VISUAL_EXTERNAL_ACCESS;
        }
        return VISUAL_PLATFORM_MODEL;
    }

    private DataModelLineageRelationEntity resolvePreferredEdgeRelation(List<DataModelLineageRelationEntity> contributors) {
        if (contributors == null || contributors.isEmpty()) {
            return null;
        }
        List<DataModelLineageRelationEntity> automatic = new ArrayList<DataModelLineageRelationEntity>();
        for (DataModelLineageRelationEntity contributor : contributors) {
            if (contributor != null && SOURCE_TYPE_COLLECTION_TASK.equalsIgnoreCase(contributor.getSourceType())) {
                automatic.add(contributor);
            }
        }
        if (!automatic.isEmpty()) {
            automatic.sort(runStatusComparator());
            return automatic.get(0);
        }
        return contributors.get(0);
    }

    private String resolveSourceTypeLabel(String sourceType) {
        if (SOURCE_TYPE_MANUAL.equalsIgnoreCase(sourceType)) {
            return SOURCE_TYPE_LABEL_MANUAL;
        }
        return SOURCE_TYPE_LABEL_AUTOMATIC;
    }

    private String resolveAggregatedSourceTypeLabel(List<DataModelLineageRelationEntity> contributors) {
        if (contributors == null || contributors.isEmpty()) {
            return SOURCE_TYPE_LABEL_AUTOMATIC;
        }
        boolean hasAutomatic = false;
        boolean hasManual = false;
        for (DataModelLineageRelationEntity contributor : contributors) {
            if (contributor == null) {
                continue;
            }
            if (SOURCE_TYPE_MANUAL.equalsIgnoreCase(contributor.getSourceType())) {
                hasManual = true;
            } else {
                hasAutomatic = true;
            }
        }
        if (hasAutomatic && hasManual) {
            return SOURCE_TYPE_LABEL_MIXED;
        }
        if (hasManual) {
            return SOURCE_TYPE_LABEL_MANUAL;
        }
        return SOURCE_TYPE_LABEL_AUTOMATIC;
    }

    private String resolveDisplayStatus(DataModelLineageRelationEntity relation) {
        if (relation == null) {
            return DISPLAY_STATUS_NOT_RUN;
        }
        if (SOURCE_TYPE_MANUAL.equalsIgnoreCase(relation.getSourceType())) {
            return DISPLAY_STATUS_NORMAL;
        }
        String status = defaultRunStatus(relation.getLatestRunStatus());
        if ("RUNNING".equalsIgnoreCase(status)) {
            return DISPLAY_STATUS_RUNNING;
        }
        if ("SUCCESS".equalsIgnoreCase(status)) {
            return DISPLAY_STATUS_NORMAL;
        }
        if ("FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)) {
            return DISPLAY_STATUS_EXCEPTION;
        }
        return DISPLAY_STATUS_NOT_RUN;
    }

    private boolean shouldUpdateRunStatus(DataModelLineageRelationEntity relation,
                                          ExecutionEvent event,
                                          LocalDateTime eventTime) {
        if (relation == null || event == null) {
            return false;
        }
        if (Objects.equals(relation.getLatestRunId(), event.getRunRecordId())) {
            return true;
        }
        if (relation.getLatestRunAt() == null) {
            return true;
        }
        return eventTime != null && !eventTime.isBefore(relation.getLatestRunAt());
    }

    private boolean shouldApplyLatestRun(DataModelLineageRelationEntity relation,
                                         RunRecordEntity run,
                                         LocalDateTime runTime) {
        if (relation == null || run == null) {
            return false;
        }
        if (Objects.equals(relation.getLatestRunId(), run.getId())
                && Objects.equals(defaultRunStatus(relation.getLatestRunStatus()), defaultRunStatus(run.getStatus()))
                && Objects.equals(relation.getLatestRunAt(), runTime)) {
            return false;
        }
        if (relation.getLatestRunAt() == null) {
            return true;
        }
        if (runTime == null) {
            return false;
        }
        return !runTime.isBefore(relation.getLatestRunAt());
    }

    private LocalDateTime resolveEventTime(ExecutionEvent event) {
        if (event == null) {
            return null;
        }
        if (event.getEndedAt() != null) {
            return event.getEndedAt();
        }
        if (event.getOccurredAt() != null) {
            return event.getOccurredAt();
        }
        return event.getStartedAt();
    }

    private LocalDateTime resolveRunTime(RunRecordEntity run) {
        if (run == null) {
            return null;
        }
        if (run.getEndedAt() != null) {
            return run.getEndedAt();
        }
        if (run.getStartedAt() != null) {
            return run.getStartedAt();
        }
        return run.getCreatedAt();
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

    private String resolveDatabaseName(DatasourceEntity datasource) {
        return firstNonBlank(resolveStringMetadata(datasource, "database", "schema", "catalog"),
                resolveStringMetadata(datasource, "dbName"));
    }

    private String resolveStringMetadata(DatasourceEntity datasource, String... keys) {
        if (datasource == null || datasource.getTechnicalMetadata() == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = datasource.getTechnicalMetadata().get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String normalizeStatus(String status) {
        return isBlank(status) ? null : status.trim().toUpperCase(Locale.ENGLISH);
    }

    private String defaultRunStatus(String status) {
        return isBlank(status) ? RUN_STATUS_NOT_RUN : status;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private String normalizeText(String value) {
        return blankToNull(value == null ? null : value.trim().toLowerCase(Locale.ENGLISH));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private enum NodeRelationRole {
        FOCUS,
        UPSTREAM,
        DOWNSTREAM,
        UNRELATED
    }

    private static final class FieldReference {
        private final String sourceAlias;
        private final String fieldName;

        private FieldReference(String sourceAlias, String fieldName) {
            this.sourceAlias = sourceAlias;
            this.fieldName = fieldName;
        }
    }

    private static final class ExpressionResolution {
        private final List<FieldReference> references = new ArrayList<FieldReference>();
    }

    private static final class EdgeKey {
        private String level;
        private String sourceNodeId;
        private String targetNodeId;
        private String sourceField;
        private String targetField;
    }

    private static final class LineageQueryContext {
        private String level;
        private String focusNodeId;
        private List<DataModelLineageNodeView> nodes = new ArrayList<DataModelLineageNodeView>();
        private List<DataModelLineageEdgeView> edges = new ArrayList<DataModelLineageEdgeView>();
        private List<DataModelLineageUnresolvedExpressionView> unresolvedExpressions = new ArrayList<DataModelLineageUnresolvedExpressionView>();
    }
}
