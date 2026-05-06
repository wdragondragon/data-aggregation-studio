package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionIncrementalCursorState;
import com.jdragon.studio.dto.model.CollectionIncrementalDefinition;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.dto.model.request.CollectionTaskSaveRequest;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.CollectionTaskScheduleEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskScheduleMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

@Service
public class CollectionTaskService {

    private static final DateTimeFormatter JSON_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CollectionTaskDefinitionMapper definitionMapper;
    private final CollectionTaskScheduleMapper scheduleMapper;
    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final CollectionTaskAssemblerService collectionTaskAssemblerService;
    private final ObjectMapper objectMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final DataModelLineageService dataModelLineageService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;

    public CollectionTaskService(CollectionTaskDefinitionMapper definitionMapper,
                                 CollectionTaskScheduleMapper scheduleMapper,
                                 DispatchTaskMapper dispatchTaskMapper,
                                 RunRecordMapper runRecordMapper,
                                 DataSourceService dataSourceService,
                                 DataModelService dataModelService,
                                 CollectionTaskAssemblerService collectionTaskAssemblerService,
                                 ObjectMapper objectMapper,
                                 StudioSecurityService securityService,
                                 ProjectResourceAccessService projectResourceAccessService,
                                 DataModelLineageService dataModelLineageService,
                                 DatasourceTypeCapabilityService datasourceTypeCapabilityService) {
        this.definitionMapper = definitionMapper;
        this.scheduleMapper = scheduleMapper;
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.collectionTaskAssemblerService = collectionTaskAssemblerService;
        this.objectMapper = objectMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.dataModelLineageService = dataModelLineageService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
    }

    public List<CollectionTaskDefinitionView> list(String nameKeyword, String targetDatasourceKeyword, String targetModelKeyword) {
        List<CollectionTaskDefinitionEntity> entities = definitionMapper.selectList(buildAccessibleQuery()
                .orderByDesc(CollectionTaskDefinitionEntity::getUpdatedAt));
        List<CollectionTaskDefinitionView> result = new ArrayList<CollectionTaskDefinitionView>();
        for (CollectionTaskDefinitionEntity entity : entities) {
            CollectionTaskDefinitionView view = toView(entity);
            if (matchesKeywords(view, nameKeyword, targetDatasourceKeyword, targetModelKeyword)) {
                result.add(view);
            }
        }
        return result;
    }

    public List<CollectionTaskDefinitionView> listOnline() {
        List<CollectionTaskDefinitionEntity> entities = definitionMapper.selectList(buildAccessibleQuery()
                .eq(CollectionTaskDefinitionEntity::getStatus, CollectionTaskStatus.ONLINE.name())
                .orderByAsc(CollectionTaskDefinitionEntity::getName));
        List<CollectionTaskDefinitionView> result = new ArrayList<CollectionTaskDefinitionView>();
        for (CollectionTaskDefinitionEntity entity : entities) {
            result.add(toView(entity));
        }
        return result;
    }

    public CollectionTaskDefinitionView get(Long id) {
        CollectionTaskDefinitionEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Collection task not found: " + id);
        }
        return toView(entity);
    }

    public CollectionTaskDefinitionView requireOnline(Long id) {
        CollectionTaskDefinitionView view = get(id);
        if (view.getStatus() != CollectionTaskStatus.ONLINE) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Collection task is not online");
        }
        return view;
    }

    public Map<String, Object> preview(CollectionTaskSaveRequest request) {
        return collectionTaskAssemblerService.assemble(toDefinitionView(request));
    }

    @Transactional
    public CollectionTaskDefinitionView save(CollectionTaskSaveRequest request) {
        validateRequest(request);
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        CollectionTaskDefinitionEntity entity = request.getId() == null
                ? new CollectionTaskDefinitionEntity()
                : requireWritableEntity(request.getId());
        if (entity == null) {
            entity = new CollectionTaskDefinitionEntity();
        }
        Map<String, Object> executionOptions = copyExecutionOptions(request.getExecutionOptions());
        List<CollectionTaskSourceBinding> sourceBindings = enrichSourceBindings(request.getSourceBindings());
        protectSystemIncrementalCursors(entity.getId() == null ? null : entity, sourceBindings);
        CollectionTaskTargetBinding targetBinding = enrichTargetBinding(request.getTargetBinding(), executionOptions);
        List<FieldMappingDefinition> fieldMappings = request.getFieldMappings() == null
                ? new ArrayList<FieldMappingDefinition>()
                : request.getFieldMappings();

        ensureUniqueName(currentProjectId, request.getName(), entity.getId());
        entity.setTenantId(securityService.currentTenantId());
        entity.setProjectId(currentProjectId);
        entity.setName(request.getName());
        entity.setTaskType(sourceBindings.size() > 1 ? CollectionTaskType.FUSION.name() : CollectionTaskType.SINGLE_TABLE.name());
        entity.setSourceCount(sourceBindings.size());
        entity.setStatus(entity.getId() != null && CollectionTaskStatus.ONLINE.name().equalsIgnoreCase(entity.getStatus())
                ? CollectionTaskStatus.ONLINE.name()
                : CollectionTaskStatus.DRAFT.name());
        entity.setSourceBindingsJson(toListOfMaps(sourceBindings));
        entity.setTargetBindingJson(toMap(targetBinding));
        entity.setFieldMappingsJson(toListOfMaps(fieldMappings));
        entity.setExecutionOptionsJson(executionOptions);
        if (entity.getId() == null && entity.getCreatedBy() == null) {
            entity.setCreatedBy(securityService.currentUserId());
        }

        if (entity.getId() == null) {
            definitionMapper.insert(entity);
        } else {
            definitionMapper.updateById(entity);
        }
        saveSchedule(entity.getId(), entity.getProjectId(), request.getSchedule());
        dataModelLineageService.scheduleTaskRebuildAfterCommit(entity.getId());
        return get(entity.getId());
    }

    @Transactional
    public CollectionTaskDefinitionView publish(Long id) {
        CollectionTaskDefinitionEntity entity = requireWritableEntity(id);
        entity.setStatus(CollectionTaskStatus.ONLINE.name());
        definitionMapper.updateById(entity);
        return get(id);
    }

    @Transactional
    public CollectionTaskDefinitionView updateSchedule(Long id, CollectionTaskScheduleDefinition schedule) {
        CollectionTaskDefinitionEntity entity = requireWritableEntity(id);
        saveSchedule(id, entity.getProjectId(), schedule);
        return get(id);
    }

    @Transactional
    public CollectionTaskDefinitionView resetIncrementalCursor(Long id, String sourceAlias, String incrColumn, String incrModel) {
        CollectionTaskDefinitionEntity entity = requireWritableEntity(id);
        List<Map<String, Object>> sourceBindings = valueAsMutableMapList(entity.getSourceBindingsJson());
        boolean resetAll = sourceAlias == null || sourceAlias.trim().isEmpty();
        boolean matched = false;
        boolean changed = false;
        for (Map<String, Object> sourceBinding : sourceBindings) {
            if (!resetAll && !sameAlias(sourceBinding.get("sourceAlias"), sourceAlias)) {
                continue;
            }
            matched = true;
            Map<String, Object> incremental = valueAsMap(sourceBinding.get("incremental"));
            if (removeSystemIncrementalCursorFields(incremental, incrColumn, incrModel)) {
                sourceBinding.put("incremental", incremental);
                changed = true;
            }
        }
        if (!resetAll && !matched) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Source alias not found: " + sourceAlias);
        }
        if (changed) {
            entity.setSourceBindingsJson(sourceBindings);
            definitionMapper.updateById(entity);
        }
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        requireWritableEntity(id);
        scheduleMapper.delete(new LambdaQueryWrapper<CollectionTaskScheduleEntity>()
                .eq(CollectionTaskScheduleEntity::getCollectionTaskId, id));
        dispatchTaskMapper.delete(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getCollectionTaskId, id));
        runRecordMapper.delete(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getCollectionTaskId, id));
        definitionMapper.deleteById(id);
        dataModelLineageService.scheduleTaskDeleteAfterCommit(id);
    }

    public List<CollectionTaskScheduleEntity> findEnabledSchedules() {
        return scheduleMapper.selectList(new LambdaQueryWrapper<CollectionTaskScheduleEntity>()
                .eq(CollectionTaskScheduleEntity::getEnabled, 1));
    }

    @Transactional
    public void markScheduleTriggered(Long collectionTaskId, LocalDateTime triggeredAt) {
        CollectionTaskScheduleEntity scheduleEntity = scheduleMapper.selectOne(new LambdaQueryWrapper<CollectionTaskScheduleEntity>()
                .eq(CollectionTaskScheduleEntity::getCollectionTaskId, collectionTaskId)
                .last("limit 1"));
        if (scheduleEntity == null) {
            return;
        }
        scheduleEntity.setLastTriggeredAt(triggeredAt);
        scheduleMapper.updateById(scheduleEntity);
    }

    private void saveSchedule(Long collectionTaskId, Long projectId, CollectionTaskScheduleDefinition schedule) {
        CollectionTaskScheduleEntity scheduleEntity = scheduleMapper.selectOne(new LambdaQueryWrapper<CollectionTaskScheduleEntity>()
                .eq(CollectionTaskScheduleEntity::getCollectionTaskId, collectionTaskId)
                .last("limit 1"));
        if (scheduleEntity == null) {
            scheduleEntity = new CollectionTaskScheduleEntity();
            scheduleEntity.setCollectionTaskId(collectionTaskId);
        }
        scheduleEntity.setTenantId(securityService.currentTenantId());
        scheduleEntity.setProjectId(projectId);
        if (schedule == null) {
            scheduleEntity.setCronExpression(null);
            scheduleEntity.setEnabled(0);
            scheduleEntity.setTimezone(null);
        } else {
            scheduleEntity.setCronExpression(schedule.getCronExpression());
            scheduleEntity.setEnabled(Boolean.TRUE.equals(schedule.getEnabled()) ? 1 : 0);
            scheduleEntity.setTimezone(schedule.getTimezone());
        }
        if (scheduleEntity.getId() == null) {
            scheduleMapper.insert(scheduleEntity);
        } else {
            scheduleMapper.updateById(scheduleEntity);
        }
    }

    private CollectionTaskDefinitionView toView(CollectionTaskDefinitionEntity entity) {
        CollectionTaskDefinitionView view = new CollectionTaskDefinitionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setTaskType(entity.getTaskType() == null ? null : CollectionTaskType.valueOf(entity.getTaskType()));
        view.setStatus(entity.getStatus() == null ? null : CollectionTaskStatus.valueOf(entity.getStatus()));
        view.setSourceCount(entity.getSourceCount());
        List<CollectionTaskSourceBinding> sourceBindings = convertList(entity.getSourceBindingsJson(), CollectionTaskSourceBinding.class);
        normalizeSourceBindingsForView(sourceBindings);
        view.setSourceBindings(sourceBindings);
        CollectionTaskTargetBinding targetBinding = convertMap(entity.getTargetBindingJson(), CollectionTaskTargetBinding.class);
        Map<String, Object> executionOptions = entity.getExecutionOptionsJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(entity.getExecutionOptionsJson());
        migrateLegacyWriteMode(targetBinding, executionOptions);
        view.setTargetBinding(targetBinding);
        view.setFieldMappings(convertList(entity.getFieldMappingsJson(), FieldMappingDefinition.class));
        view.setExecutionOptions(executionOptions);
        CollectionTaskScheduleEntity scheduleEntity = scheduleMapper.selectOne(new LambdaQueryWrapper<CollectionTaskScheduleEntity>()
                .eq(CollectionTaskScheduleEntity::getCollectionTaskId, entity.getId())
                .last("limit 1"));
        if (scheduleEntity != null) {
            CollectionTaskScheduleDefinition schedule = new CollectionTaskScheduleDefinition();
            schedule.setCronExpression(scheduleEntity.getCronExpression());
            schedule.setEnabled(scheduleEntity.getEnabled() != null && scheduleEntity.getEnabled() == 1);
            schedule.setTimezone(scheduleEntity.getTimezone());
            view.setSchedule(schedule);
        }
        return view;
    }

    private LambdaQueryWrapper<CollectionTaskDefinitionEntity> buildAccessibleQuery() {
        LambdaQueryWrapper<CollectionTaskDefinitionEntity> queryWrapper = new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
                .eq(CollectionTaskDefinitionEntity::getTenantId, securityService.currentTenantId());
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return queryWrapper;
        }
        List<Long> sharedIds = projectResourceAccessService.sharedResourceIdList(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK);
        if (sharedIds.isEmpty()) {
            queryWrapper.eq(CollectionTaskDefinitionEntity::getProjectId, currentProjectId);
            return queryWrapper;
        }
        queryWrapper.and(wrapper -> wrapper.eq(CollectionTaskDefinitionEntity::getProjectId, currentProjectId)
                .or()
                .in(CollectionTaskDefinitionEntity::getId, sharedIds));
        return queryWrapper;
    }

    private CollectionTaskDefinitionEntity findAccessibleEntity(Long id) {
        CollectionTaskDefinitionEntity entity = definitionMapper.selectById(id);
        if (entity == null) {
            return null;
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK,
                entity.getProjectId(), entity.getId(), "Collection task not found: " + id);
        return entity;
    }

    private CollectionTaskDefinitionEntity requireWritableEntity(Long id) {
        CollectionTaskDefinitionEntity entity = definitionMapper.selectById(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Collection task not found: " + id);
        }
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private void ensureUniqueName(Long projectId, String name, Long selfId) {
        if (projectId == null || name == null || name.trim().isEmpty()) {
            return;
        }
        List<CollectionTaskDefinitionEntity> duplicates = definitionMapper.selectList(new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
                .eq(CollectionTaskDefinitionEntity::getProjectId, projectId)
                .eq(CollectionTaskDefinitionEntity::getName, name.trim()));
        for (CollectionTaskDefinitionEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Collection task name already exists in the current project");
        }
    }

    private void validateRequest(CollectionTaskSaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Collection task request is required");
        }
        if (request.getSourceBindings() == null || request.getSourceBindings().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "At least one source binding is required");
        }
        if (request.getTargetBinding() == null || request.getTargetBinding().getDatasourceId() == null || request.getTargetBinding().getModelId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Target binding is required");
        }
        Set<String> aliases = new HashSet<String>();
        for (CollectionTaskSourceBinding sourceBinding : request.getSourceBindings()) {
            if (sourceBinding.getDatasourceId() == null || sourceBinding.getModelId() == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Source datasource and model are required");
            }
            if (sourceBinding.getSourceAlias() == null || sourceBinding.getSourceAlias().trim().isEmpty()) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Source alias is required");
            }
            if (!aliases.add(sourceBinding.getSourceAlias().trim().toLowerCase())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Source alias must be unique");
            }
        }
    }

    private CollectionTaskDefinitionView toDefinitionView(CollectionTaskSaveRequest request) {
        validateRequest(request);
        Map<String, Object> executionOptions = copyExecutionOptions(request.getExecutionOptions());
        List<CollectionTaskSourceBinding> sourceBindings = enrichSourceBindings(request.getSourceBindings());
        CollectionTaskDefinitionEntity existingEntity = request.getId() == null ? null : findAccessibleEntity(request.getId());
        protectSystemIncrementalCursors(existingEntity, sourceBindings);
        CollectionTaskTargetBinding targetBinding = enrichTargetBinding(request.getTargetBinding(), executionOptions);
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setId(request.getId());
        definition.setName(request.getName());
        definition.setTaskType(sourceBindings.size() > 1 ? CollectionTaskType.FUSION : CollectionTaskType.SINGLE_TABLE);
        definition.setSourceCount(sourceBindings.size());
        definition.setSourceBindings(sourceBindings);
        definition.setTargetBinding(targetBinding);
        definition.setFieldMappings(request.getFieldMappings() == null
                ? new ArrayList<FieldMappingDefinition>()
                : new ArrayList<FieldMappingDefinition>(request.getFieldMappings()));
        definition.setExecutionOptions(executionOptions);
        definition.setSchedule(request.getSchedule());
        return definition;
    }

    private boolean matchesKeywords(CollectionTaskDefinitionView view,
                                    String nameKeyword,
                                    String targetDatasourceKeyword,
                                    String targetModelKeyword) {
        if (!containsIgnoreCase(view.getName(), nameKeyword)) {
            return false;
        }
        CollectionTaskTargetBinding targetBinding = view.getTargetBinding();
        if (targetBinding == null) {
            return targetDatasourceKeyword == null && targetModelKeyword == null;
        }
        if (!containsIgnoreCase(targetBinding.getDatasourceName(), targetDatasourceKeyword)
                && !containsIgnoreCase(targetBinding.getDatasourceTypeCode(), targetDatasourceKeyword)) {
            return false;
        }
        if (!containsIgnoreCase(targetBinding.getModelName(), targetModelKeyword)
                && !containsIgnoreCase(targetBinding.getModelPhysicalLocator(), targetModelKeyword)) {
            return false;
        }
        return true;
    }

    private boolean containsIgnoreCase(String candidate, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return true;
        }
        if (candidate == null) {
            return false;
        }
        return candidate.toLowerCase(Locale.ROOT).contains(keyword.trim().toLowerCase(Locale.ROOT));
    }

    private List<CollectionTaskSourceBinding> enrichSourceBindings(List<CollectionTaskSourceBinding> bindings) {
        List<CollectionTaskSourceBinding> result = new ArrayList<CollectionTaskSourceBinding>();
        for (CollectionTaskSourceBinding binding : bindings) {
            DataSourceDefinition datasource = dataSourceService.get(binding.getDatasourceId());
            datasourceTypeCapabilityService.ensureReadable(datasource.getTypeCode());
            DataModelDefinition model = dataModelService.get(binding.getModelId());
            ensureModelBelongsToDatasource(model, datasource);
            CollectionTaskSourceBinding enriched = new CollectionTaskSourceBinding();
            enriched.setSourceAlias(binding.getSourceAlias().trim());
            enriched.setDatasourceId(datasource.getId());
            enriched.setDatasourceName(datasource.getName());
            enriched.setDatasourceTypeCode(datasource.getTypeCode());
            enriched.setModelId(model.getId());
            enriched.setModelName(model.getName());
            enriched.setModelPhysicalLocator(model.getPhysicalLocator());
            enriched.setReaderOptions(copyOptionMap(binding.getReaderOptions()));
            enriched.setIncremental(binding.getIncremental());
            result.add(enriched);
        }
        return result;
    }

    private void protectSystemIncrementalCursors(CollectionTaskDefinitionEntity existingEntity,
                                                 List<CollectionTaskSourceBinding> sourceBindings) {
        clearIncomingSystemIncrementalCursorFields(sourceBindings);
        if (existingEntity == null || sourceBindings == null || sourceBindings.isEmpty()) {
            return;
        }
        Map<String, CollectionIncrementalDefinition> existingIncrementals = existingIncrementalsByAlias(existingEntity);
        if (existingIncrementals.isEmpty()) {
            return;
        }
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            if (sourceBinding == null || sourceBinding.getIncremental() == null) {
                continue;
            }
            CollectionIncrementalDefinition existing = existingIncrementals.get(normalizeAlias(sourceBinding.getSourceAlias()));
            if (existing == null) {
                continue;
            }
            copySystemIncrementalCursorFields(existing, sourceBinding.getIncremental());
        }
    }

    private void clearIncomingSystemIncrementalCursorFields(List<CollectionTaskSourceBinding> sourceBindings) {
        if (sourceBindings == null) {
            return;
        }
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            if (sourceBinding == null || sourceBinding.getIncremental() == null) {
                continue;
            }
            clearSystemIncrementalCursorFields(sourceBinding.getIncremental());
        }
    }

    private void clearSystemIncrementalCursorFields(CollectionIncrementalDefinition incremental) {
        clearIncrementalCursorProjection(incremental);
        incremental.setCursorStates(new ArrayList<CollectionIncrementalCursorState>());
    }

    private void normalizeSourceBindingsForView(List<CollectionTaskSourceBinding> sourceBindings) {
        if (sourceBindings == null) {
            return;
        }
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            if (sourceBinding == null || sourceBinding.getIncremental() == null) {
                continue;
            }
            CollectionIncrementalDefinition incremental = sourceBinding.getIncremental();
            normalizeCursorStates(incremental);
            refreshIncrementalCursorProjection(incremental);
        }
    }

    private void refreshIncrementalCursorProjection(CollectionIncrementalDefinition incremental) {
        CollectionIncrementalCursorState currentState = findCursorState(incremental.getCursorStates(),
                incremental.getIncrColumn(),
                normalizeIncrModel(incremental.getIncrModel()));
        clearIncrementalCursorProjection(incremental);
        if (currentState == null) {
            return;
        }
        incremental.setPkValue(currentState.getPkValue());
        incremental.setValueType(currentState.getValueType());
        incremental.setLastRunRecordId(currentState.getLastRunRecordId());
        incremental.setLastUpdatedAt(currentState.getLastUpdatedAt());
    }

    private void clearIncrementalCursorProjection(CollectionIncrementalDefinition incremental) {
        incremental.setPkValue(null);
        incremental.setValueType(null);
        incremental.setLastRunRecordId(null);
        incremental.setLastUpdatedAt(null);
    }

    private Map<String, CollectionIncrementalDefinition> existingIncrementalsByAlias(CollectionTaskDefinitionEntity entity) {
        Map<String, CollectionIncrementalDefinition> result = new LinkedHashMap<String, CollectionIncrementalDefinition>();
        for (Map<String, Object> sourceBinding : valueAsMutableMapList(entity.getSourceBindingsJson())) {
            String sourceAlias = normalizeAlias(sourceBinding.get("sourceAlias"));
            if (sourceAlias.isEmpty()) {
                continue;
            }
            Map<String, Object> incremental = valueAsMap(sourceBinding.get("incremental"));
            if (incremental.isEmpty()) {
                continue;
            }
            CollectionIncrementalDefinition definition = toIncrementalDefinition(incremental);
            if (definition != null) {
                normalizeCursorStates(definition);
                result.put(sourceAlias, definition);
            }
        }
        return result;
    }

    private CollectionIncrementalDefinition toIncrementalDefinition(Map<String, Object> incremental) {
        try {
            return objectMapper.convertValue(incremental, CollectionIncrementalDefinition.class);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean sameIncrementalScope(CollectionIncrementalDefinition existing,
                                         CollectionIncrementalDefinition incoming) {
        return sameOption(existing.getIncrColumn(), incoming.getIncrColumn())
                && sameOption(normalizeIncrModel(existing.getIncrModel()), normalizeIncrModel(incoming.getIncrModel()));
    }

    private String normalizeIncrModel(String incrModel) {
        return incrModel == null || incrModel.trim().isEmpty() ? ">" : incrModel.trim();
    }

    private String normalizeIncrModel(Object incrModel) {
        return incrModel == null || String.valueOf(incrModel).trim().isEmpty() ? ">" : String.valueOf(incrModel).trim();
    }

    private void copySystemIncrementalCursorFields(CollectionIncrementalDefinition existing,
                                                   CollectionIncrementalDefinition incoming) {
        List<CollectionIncrementalCursorState> cursorStates = copyCursorStates(existing.getCursorStates());
        incoming.setCursorStates(cursorStates);
        CollectionIncrementalCursorState currentState = findCursorState(cursorStates,
                incoming.getIncrColumn(),
                normalizeIncrModel(incoming.getIncrModel()));
        if (currentState == null) {
            return;
        }
        incoming.setPkValue(currentState.getPkValue());
        incoming.setValueType(currentState.getValueType());
        incoming.setLastRunRecordId(currentState.getLastRunRecordId());
        incoming.setLastUpdatedAt(currentState.getLastUpdatedAt());
    }

    private void normalizeCursorStates(CollectionIncrementalDefinition incremental) {
        List<CollectionIncrementalCursorState> cursorStates = copyCursorStates(incremental.getCursorStates());
        if ((cursorStates == null || cursorStates.isEmpty())
                && !isBlankOption(incremental.getPkValue())
                && !isBlankOption(incremental.getIncrColumn())) {
            CollectionIncrementalCursorState state = new CollectionIncrementalCursorState();
            state.setIncrColumn(incremental.getIncrColumn());
            state.setIncrModel(normalizeIncrModel(incremental.getIncrModel()));
            state.setPkValue(incremental.getPkValue());
            state.setValueType(incremental.getValueType());
            state.setLastRunRecordId(incremental.getLastRunRecordId());
            state.setLastUpdatedAt(incremental.getLastUpdatedAt());
            cursorStates.add(state);
        }
        incremental.setCursorStates(cursorStates);
    }

    private List<CollectionIncrementalCursorState> copyCursorStates(List<CollectionIncrementalCursorState> cursorStates) {
        List<CollectionIncrementalCursorState> result = new ArrayList<CollectionIncrementalCursorState>();
        if (cursorStates == null) {
            return result;
        }
        for (CollectionIncrementalCursorState cursorState : cursorStates) {
            if (cursorState == null || isBlankOption(cursorState.getIncrColumn())) {
                continue;
            }
            CollectionIncrementalCursorState copy = new CollectionIncrementalCursorState();
            copy.setIncrColumn(cursorState.getIncrColumn());
            copy.setIncrModel(normalizeIncrModel(cursorState.getIncrModel()));
            copy.setPkValue(cursorState.getPkValue());
            copy.setValueType(cursorState.getValueType());
            copy.setLastRunRecordId(cursorState.getLastRunRecordId());
            copy.setLastUpdatedAt(cursorState.getLastUpdatedAt());
            result.add(copy);
        }
        return result;
    }

    private CollectionIncrementalCursorState findCursorState(List<CollectionIncrementalCursorState> cursorStates,
                                                             String incrColumn,
                                                             String incrModel) {
        if (cursorStates == null || isBlankOption(incrColumn)) {
            return null;
        }
        for (CollectionIncrementalCursorState cursorState : cursorStates) {
            if (cursorState == null) {
                continue;
            }
            if (sameIncrementalScope(cursorState.getIncrColumn(), cursorState.getIncrModel(), incrColumn, incrModel)) {
                return cursorState;
            }
        }
        return null;
    }

    private boolean sameIncrementalScope(Object leftColumn, Object leftModel, Object rightColumn, Object rightModel) {
        return sameOption(leftColumn, rightColumn)
                && sameOption(normalizeIncrModel(leftModel), normalizeIncrModel(rightModel));
    }

    private boolean sameOption(Object left, Object right) {
        String leftText = left == null ? "" : String.valueOf(left).trim();
        String rightText = right == null ? "" : String.valueOf(right).trim();
        return leftText.equals(rightText);
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (!isBlankOption(value)) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private boolean sameAlias(Object left, Object right) {
        return normalizeAlias(left).equals(normalizeAlias(right));
    }

    private String normalizeAlias(Object alias) {
        return alias == null ? "" : String.valueOf(alias).trim().toLowerCase(Locale.ROOT);
    }

    private boolean removeSystemIncrementalCursorFields(Map<String, Object> incremental,
                                                        String incrColumn,
                                                        String incrModel) {
        boolean changed = false;
        String targetColumn = firstNonBlank(incrColumn, incremental.get("incrColumn"));
        String targetModel = normalizeIncrModel(firstNonBlank(incrModel, incremental.get("incrModel")));
        if (!targetColumn.isEmpty()) {
            List<Map<String, Object>> cursorStates = valueAsMutableMapList(incremental.get("cursorStates"));
            List<Map<String, Object>> retained = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> cursorState : cursorStates) {
                if (sameIncrementalScope(cursorState.get("incrColumn"), cursorState.get("incrModel"), targetColumn, targetModel)) {
                    changed = true;
                    continue;
                }
                retained.add(cursorState);
            }
            if (changed || incremental.containsKey("cursorStates")) {
                incremental.put("cursorStates", retained);
            }
        }
        if (targetColumn.isEmpty() || sameIncrementalScope(incremental.get("incrColumn"), incremental.get("incrModel"), targetColumn, targetModel)) {
            changed = incremental.remove("pkValue") != null || changed;
            changed = incremental.remove("valueType") != null || changed;
            changed = incremental.remove("lastRunRecordId") != null || changed;
            changed = incremental.remove("lastUpdatedAt") != null || changed;
        }
        return changed;
    }

    private CollectionTaskTargetBinding enrichTargetBinding(CollectionTaskTargetBinding binding, Map<String, Object> executionOptions) {
        DataSourceDefinition datasource = dataSourceService.get(binding.getDatasourceId());
        datasourceTypeCapabilityService.ensureWritable(datasource.getTypeCode());
        DataModelDefinition model = dataModelService.get(binding.getModelId());
        ensureModelBelongsToDatasource(model, datasource);
        CollectionTaskTargetBinding enriched = new CollectionTaskTargetBinding();
        enriched.setDatasourceId(datasource.getId());
        enriched.setDatasourceName(datasource.getName());
        enriched.setDatasourceTypeCode(datasource.getTypeCode());
        enriched.setModelId(model.getId());
        enriched.setModelName(model.getName());
        enriched.setModelPhysicalLocator(model.getPhysicalLocator());
        enriched.setWriterOptions(copyOptionMap(binding.getWriterOptions()));
        migrateLegacyWriteMode(enriched, executionOptions);
        return enriched;
    }

    private Map<String, Object> copyExecutionOptions(Map<String, Object> executionOptions) {
        if (executionOptions == null || executionOptions.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return new LinkedHashMap<String, Object>(executionOptions);
    }

    private void migrateLegacyWriteMode(CollectionTaskTargetBinding targetBinding, Map<String, Object> executionOptions) {
        if (targetBinding == null || executionOptions == null) {
            return;
        }
        Map<String, Object> writerOptions = copyOptionMap(targetBinding.getWriterOptions());
        Object legacyWriteMode = executionOptions.remove("writeMode");
        if (isBlankOption(writerOptions.get("writeMode")) && !isBlankOption(legacyWriteMode)) {
            writerOptions.put("writeMode", legacyWriteMode);
        }
        targetBinding.setWriterOptions(writerOptions);
    }

    private boolean isBlankOption(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }

    private Map<String, Object> copyOptionMap(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return new LinkedHashMap<String, Object>(options);
    }

    private void ensureModelBelongsToDatasource(DataModelDefinition model, DataSourceDefinition datasource) {
        if (model.getDatasourceId() == null || !String.valueOf(model.getDatasourceId()).equals(String.valueOf(datasource.getId()))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model does not belong to the selected datasource");
        }
    }

    private <T> List<T> convertList(List<Map<String, Object>> items, Class<T> type) {
        List<T> result = new ArrayList<T>();
        if (items == null) {
            return result;
        }
        for (Map<String, Object> item : items) {
            result.add(objectMapper.convertValue(item, type));
        }
        return result;
    }

    private <T> T convertMap(Map<String, Object> item, Class<T> type) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(item, type);
    }

    private <T> List<Map<String, Object>> toListOfMaps(List<T> items) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (items == null) {
            return result;
        }
        for (T item : items) {
            result.add(sanitizeJsonMap(toMap(item)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> Map<String, Object> toMap(T item) {
        if (item == null) {
            return new LinkedHashMap<String, Object>();
        }
        return sanitizeJsonMap(objectMapper.convertValue(item, LinkedHashMap.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeJsonMap(Map<String, Object> map) {
        Object sanitized = sanitizeJsonValue(map);
        if (sanitized instanceof Map<?, ?>) {
            return (Map<String, Object>) sanitized;
        }
        return new LinkedHashMap<String, Object>();
    }

    private Object sanitizeJsonValue(Object value) {
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(JSON_TIME_FORMATTER);
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> sanitized = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    sanitized.put(String.valueOf(entry.getKey()), sanitizeJsonValue(entry.getValue()));
                }
            }
            return sanitized;
        }
        if (value instanceof List<?>) {
            List<Object> sanitized = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                sanitized.add(sanitizeJsonValue(item));
            }
            return sanitized;
        }
        return value;
    }

    private List<Map<String, Object>> valueAsMutableMapList(Object value) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (!(value instanceof List<?>)) {
            return result;
        }
        for (Object item : (List<?>) value) {
            Map<String, Object> map = valueAsMap(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    private Map<String, Object> valueAsMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map<?, ?>)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }
}
