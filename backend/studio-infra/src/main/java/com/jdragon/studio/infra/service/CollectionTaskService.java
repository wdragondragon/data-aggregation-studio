package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

@Service
public class CollectionTaskService {

    private final CollectionTaskDefinitionMapper definitionMapper;
    private final CollectionTaskScheduleMapper scheduleMapper;
    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final ObjectMapper objectMapper;

    public CollectionTaskService(CollectionTaskDefinitionMapper definitionMapper,
                                 CollectionTaskScheduleMapper scheduleMapper,
                                 DispatchTaskMapper dispatchTaskMapper,
                                 RunRecordMapper runRecordMapper,
                                 DataSourceService dataSourceService,
                                 DataModelService dataModelService,
                                 ObjectMapper objectMapper) {
        this.definitionMapper = definitionMapper;
        this.scheduleMapper = scheduleMapper;
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.objectMapper = objectMapper;
    }

    public List<CollectionTaskDefinitionView> list(String nameKeyword, String targetDatasourceKeyword, String targetModelKeyword) {
        List<CollectionTaskDefinitionEntity> entities = definitionMapper.selectList(new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
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
        List<CollectionTaskDefinitionEntity> entities = definitionMapper.selectList(new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
                .eq(CollectionTaskDefinitionEntity::getStatus, CollectionTaskStatus.ONLINE.name())
                .orderByAsc(CollectionTaskDefinitionEntity::getName));
        List<CollectionTaskDefinitionView> result = new ArrayList<CollectionTaskDefinitionView>();
        for (CollectionTaskDefinitionEntity entity : entities) {
            result.add(toView(entity));
        }
        return result;
    }

    public CollectionTaskDefinitionView get(Long id) {
        CollectionTaskDefinitionEntity entity = definitionMapper.selectById(id);
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

    @Transactional
    public CollectionTaskDefinitionView save(CollectionTaskSaveRequest request) {
        validateRequest(request);
        CollectionTaskDefinitionEntity entity = request.getId() == null
                ? new CollectionTaskDefinitionEntity()
                : definitionMapper.selectById(request.getId());
        if (entity == null) {
            entity = new CollectionTaskDefinitionEntity();
        }
        List<CollectionTaskSourceBinding> sourceBindings = enrichSourceBindings(request.getSourceBindings());
        CollectionTaskTargetBinding targetBinding = enrichTargetBinding(request.getTargetBinding());
        List<FieldMappingDefinition> fieldMappings = request.getFieldMappings() == null
                ? new ArrayList<FieldMappingDefinition>()
                : request.getFieldMappings();

        entity.setName(request.getName());
        entity.setTaskType(sourceBindings.size() > 1 ? CollectionTaskType.FUSION.name() : CollectionTaskType.SINGLE_TABLE.name());
        entity.setSourceCount(sourceBindings.size());
        entity.setStatus(entity.getId() != null && CollectionTaskStatus.ONLINE.name().equalsIgnoreCase(entity.getStatus())
                ? CollectionTaskStatus.ONLINE.name()
                : CollectionTaskStatus.DRAFT.name());
        entity.setSourceBindingsJson(toListOfMaps(sourceBindings));
        entity.setTargetBindingJson(toMap(targetBinding));
        entity.setFieldMappingsJson(toListOfMaps(fieldMappings));
        entity.setExecutionOptionsJson(request.getExecutionOptions() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(request.getExecutionOptions()));

        if (entity.getId() == null) {
            definitionMapper.insert(entity);
        } else {
            definitionMapper.updateById(entity);
        }
        saveSchedule(entity.getId(), request.getSchedule());
        return get(entity.getId());
    }

    @Transactional
    public CollectionTaskDefinitionView publish(Long id) {
        CollectionTaskDefinitionEntity entity = definitionMapper.selectById(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Collection task not found: " + id);
        }
        entity.setStatus(CollectionTaskStatus.ONLINE.name());
        definitionMapper.updateById(entity);
        return get(id);
    }

    @Transactional
    public CollectionTaskDefinitionView updateSchedule(Long id, CollectionTaskScheduleDefinition schedule) {
        if (definitionMapper.selectById(id) == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Collection task not found: " + id);
        }
        saveSchedule(id, schedule);
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        scheduleMapper.delete(new LambdaQueryWrapper<CollectionTaskScheduleEntity>()
                .eq(CollectionTaskScheduleEntity::getCollectionTaskId, id));
        dispatchTaskMapper.delete(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getCollectionTaskId, id));
        runRecordMapper.delete(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getCollectionTaskId, id));
        definitionMapper.deleteById(id);
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

    private void saveSchedule(Long collectionTaskId, CollectionTaskScheduleDefinition schedule) {
        CollectionTaskScheduleEntity scheduleEntity = scheduleMapper.selectOne(new LambdaQueryWrapper<CollectionTaskScheduleEntity>()
                .eq(CollectionTaskScheduleEntity::getCollectionTaskId, collectionTaskId)
                .last("limit 1"));
        if (scheduleEntity == null) {
            scheduleEntity = new CollectionTaskScheduleEntity();
            scheduleEntity.setCollectionTaskId(collectionTaskId);
        }
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
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setTaskType(entity.getTaskType() == null ? null : CollectionTaskType.valueOf(entity.getTaskType()));
        view.setStatus(entity.getStatus() == null ? null : CollectionTaskStatus.valueOf(entity.getStatus()));
        view.setSourceCount(entity.getSourceCount());
        view.setSourceBindings(convertList(entity.getSourceBindingsJson(), CollectionTaskSourceBinding.class));
        view.setTargetBinding(convertMap(entity.getTargetBindingJson(), CollectionTaskTargetBinding.class));
        view.setFieldMappings(convertList(entity.getFieldMappingsJson(), FieldMappingDefinition.class));
        view.setExecutionOptions(entity.getExecutionOptionsJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(entity.getExecutionOptionsJson()));
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

    private void validateRequest(CollectionTaskSaveRequest request) {
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
            result.add(enriched);
        }
        return result;
    }

    private CollectionTaskTargetBinding enrichTargetBinding(CollectionTaskTargetBinding binding) {
        DataSourceDefinition datasource = dataSourceService.get(binding.getDatasourceId());
        DataModelDefinition model = dataModelService.get(binding.getModelId());
        ensureModelBelongsToDatasource(model, datasource);
        CollectionTaskTargetBinding enriched = new CollectionTaskTargetBinding();
        enriched.setDatasourceId(datasource.getId());
        enriched.setDatasourceName(datasource.getName());
        enriched.setDatasourceTypeCode(datasource.getTypeCode());
        enriched.setModelId(model.getId());
        enriched.setModelName(model.getName());
        enriched.setModelPhysicalLocator(model.getPhysicalLocator());
        return enriched;
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
            result.add(toMap(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> Map<String, Object> toMap(T item) {
        if (item == null) {
            return new LinkedHashMap<String, Object>();
        }
        return objectMapper.convertValue(item, LinkedHashMap.class);
    }
}
