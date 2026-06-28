package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskListView;
import com.jdragon.studio.dto.model.CollectionTaskOptionView;
import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.CollectionTaskWorkflowOptionView;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.CollectionTaskSaveRequest;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.CollectionTaskMetricBindingEntity;
import com.jdragon.studio.infra.entity.CollectionTaskScheduleEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskMetricBindingMapper;
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
    private static final String[] FILE_MODEL_READER_OPTION_KEYS = new String[]{
            "rootPath", "partitionType", "partition", "pattern", "fileType", "encoding", "delimiter", "dataTag"
    };
    private static final String[] FILE_MODEL_WRITER_OPTION_KEYS = new String[]{
            "rootPath", "fileName", "fileType", "encoding", "delimiter", "efile",
            "efile.entity", "efile.type", "efile.dataTime", "efile.tableName", "efile.tableCode", "efile.planDate"
    };
    private static final String METRIC_BINDING_ROLE_SOURCE = "SOURCE";
    private static final String METRIC_BINDING_ROLE_TARGET = "TARGET";

    private final CollectionTaskDefinitionMapper definitionMapper;
    private final CollectionTaskMetricBindingMapper metricBindingMapper;
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
    private final CollectionTaskIncrementalCursorSupport incrementalCursorSupport;

    public CollectionTaskService(CollectionTaskDefinitionMapper definitionMapper,
                                 CollectionTaskMetricBindingMapper metricBindingMapper,
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
        this.metricBindingMapper = metricBindingMapper;
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
        this.incrementalCursorSupport = new CollectionTaskIncrementalCursorSupport(objectMapper);
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

    public List<CollectionTaskListView> listSummaries(String nameKeyword, String targetDatasourceKeyword, String targetModelKeyword) {
        int pageNo = 1;
        int pageSize = 500;
        List<CollectionTaskListView> result = new ArrayList<CollectionTaskListView>();
        PageView<CollectionTaskListView> page;
        do {
            page = listSummaryPage(pageNo, pageSize, nameKeyword, targetDatasourceKeyword, targetModelKeyword);
            result.addAll(page.getItems());
            pageNo++;
        } while (result.size() < page.getTotal());
        return result;
    }

    public PageView<CollectionTaskListView> listSummaryPage(Integer pageNo,
                                                            Integer pageSize,
                                                            String nameKeyword,
                                                            String targetDatasourceKeyword,
                                                            String targetModelKeyword) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        String normalizedName = normalizeNullableText(nameKeyword);
        String normalizedTargetDatasource = normalizeNullableText(targetDatasourceKeyword);
        String normalizedTargetModel = normalizeNullableText(targetModelKeyword);
        Page<CollectionTaskDefinitionEntity> page = new Page<CollectionTaskDefinitionEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<CollectionTaskDefinitionEntity> queryWrapper = selectListColumns(buildAccessibleQuery())
                .like(hasText(normalizedName), CollectionTaskDefinitionEntity::getName, normalizedName)
                .and(hasText(normalizedTargetDatasource), wrapper -> wrapper
                        .like(CollectionTaskDefinitionEntity::getTargetDatasourceNameSnapshot, normalizedTargetDatasource)
                        .or()
                        .like(CollectionTaskDefinitionEntity::getTargetDatasourceTypeCodeSnapshot, normalizedTargetDatasource))
                .and(hasText(normalizedTargetModel), wrapper -> wrapper
                        .like(CollectionTaskDefinitionEntity::getTargetModelNameSnapshot, normalizedTargetModel)
                        .or()
                        .like(CollectionTaskDefinitionEntity::getTargetModelPhysicalLocatorSnapshot, normalizedTargetModel))
                .orderByDesc(CollectionTaskDefinitionEntity::getUpdatedAt)
                .orderByDesc(CollectionTaskDefinitionEntity::getId);
        Page<CollectionTaskDefinitionEntity> entityPage = definitionMapper.selectPage(page, queryWrapper);
        Map<Long, CollectionTaskScheduleDefinition> schedules = loadScheduleMap(entityPage.getRecords());
        List<CollectionTaskListView> result = new ArrayList<CollectionTaskListView>();
        for (CollectionTaskDefinitionEntity entity : entityPage.getRecords()) {
            result.add(toListView(entity, schedules.get(entity.getId())));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), result);
    }

    public List<CollectionTaskDefinitionView> listMetricBindings() {
        List<CollectionTaskMetricBindingEntity> entities = metricBindingMapper.selectList(buildAccessibleMetricBindingQuery()
                .select(CollectionTaskMetricBindingEntity::getId,
                        CollectionTaskMetricBindingEntity::getTenantId,
                        CollectionTaskMetricBindingEntity::getProjectId,
                        CollectionTaskMetricBindingEntity::getDeleted,
                        CollectionTaskMetricBindingEntity::getCreatedAt,
                        CollectionTaskMetricBindingEntity::getUpdatedAt,
                        CollectionTaskMetricBindingEntity::getCollectionTaskId,
                        CollectionTaskMetricBindingEntity::getTaskNameSnapshot,
                        CollectionTaskMetricBindingEntity::getTaskType,
                        CollectionTaskMetricBindingEntity::getTaskStatus,
                        CollectionTaskMetricBindingEntity::getSourceCount,
                        CollectionTaskMetricBindingEntity::getBindingRole,
                        CollectionTaskMetricBindingEntity::getSourceAlias,
                        CollectionTaskMetricBindingEntity::getDatasourceId,
                        CollectionTaskMetricBindingEntity::getDatasourceName,
                        CollectionTaskMetricBindingEntity::getDatasourceTypeCode,
                        CollectionTaskMetricBindingEntity::getModelId,
                        CollectionTaskMetricBindingEntity::getModelName,
                        CollectionTaskMetricBindingEntity::getModelPhysicalLocator)
                .orderByDesc(CollectionTaskMetricBindingEntity::getUpdatedAt)
                .orderByAsc(CollectionTaskMetricBindingEntity::getId));
        Map<Long, CollectionTaskDefinitionView> grouped = new LinkedHashMap<Long, CollectionTaskDefinitionView>();
        for (CollectionTaskMetricBindingEntity entity : entities) {
            if (entity == null || entity.getCollectionTaskId() == null) {
                continue;
            }
            CollectionTaskDefinitionView view = grouped.get(entity.getCollectionTaskId());
            if (view == null) {
                view = toMetricTaskView(entity);
                grouped.put(entity.getCollectionTaskId(), view);
            }
            if (METRIC_BINDING_ROLE_SOURCE.equalsIgnoreCase(entity.getBindingRole())) {
                view.getSourceBindings().add(toMetricSourceBinding(entity));
            } else if (METRIC_BINDING_ROLE_TARGET.equalsIgnoreCase(entity.getBindingRole())) {
                view.setTargetBinding(toMetricTargetBinding(entity));
            }
        }
        return new ArrayList<CollectionTaskDefinitionView>(grouped.values());
    }

    public Map<Long, String> listAccessibleNames() {
        List<CollectionTaskDefinitionEntity> entities = definitionMapper.selectList(buildAccessibleQuery()
                .select(CollectionTaskDefinitionEntity::getId,
                        CollectionTaskDefinitionEntity::getName)
                .orderByAsc(CollectionTaskDefinitionEntity::getName)
                .orderByAsc(CollectionTaskDefinitionEntity::getId));
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        for (CollectionTaskDefinitionEntity entity : entities) {
            if (entity.getId() != null) {
                result.put(entity.getId(), entity.getName());
            }
        }
        return result;
    }

    public List<CollectionTaskOptionView> listOptions() {
        List<CollectionTaskDefinitionEntity> entities = definitionMapper.selectList(buildAccessibleQuery()
                .select(CollectionTaskDefinitionEntity::getId,
                        CollectionTaskDefinitionEntity::getProjectId,
                        CollectionTaskDefinitionEntity::getName)
                .orderByAsc(CollectionTaskDefinitionEntity::getName)
                .orderByAsc(CollectionTaskDefinitionEntity::getId));
        List<CollectionTaskOptionView> result = new ArrayList<CollectionTaskOptionView>();
        for (CollectionTaskDefinitionEntity entity : entities) {
            CollectionTaskOptionView view = new CollectionTaskOptionView();
            view.setId(entity.getId());
            view.setProjectId(entity.getProjectId());
            view.setName(entity.getName());
            result.add(view);
        }
        return result;
    }

    public Long countOnlineSummaries() {
        Long count = definitionMapper.selectCount(buildAccessibleQuery()
                .eq(CollectionTaskDefinitionEntity::getStatus, CollectionTaskStatus.ONLINE.name()));
        return count == null ? 0L : count;
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

    public List<CollectionTaskListView> listOnlineSummaries() {
        List<CollectionTaskDefinitionEntity> entities = definitionMapper.selectList(selectListColumns(buildAccessibleQuery())
                .eq(CollectionTaskDefinitionEntity::getStatus, CollectionTaskStatus.ONLINE.name())
                .orderByAsc(CollectionTaskDefinitionEntity::getName));
        Map<Long, CollectionTaskScheduleDefinition> schedules = loadScheduleMap(entities);
        List<CollectionTaskListView> result = new ArrayList<CollectionTaskListView>();
        for (CollectionTaskDefinitionEntity entity : entities) {
            result.add(toListView(entity, schedules.get(entity.getId())));
        }
        return result;
    }

    public PageView<CollectionTaskWorkflowOptionView> listWorkflowOptions(Integer pageNo,
                                                                          Integer pageSize,
                                                                          String keyword) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        if (projectResourceAccessService.currentProjectId() == null) {
            return PageView.of(safePageNo, safePageSize, 0L, new ArrayList<CollectionTaskWorkflowOptionView>());
        }
        String normalizedKeyword = normalizeNullableText(keyword);
        Long keywordId = parseLongOrNull(normalizedKeyword);
        Page<CollectionTaskDefinitionEntity> page = new Page<CollectionTaskDefinitionEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<CollectionTaskDefinitionEntity> queryWrapper = selectWorkflowOptionColumns(buildAccessibleQuery())
                .eq(CollectionTaskDefinitionEntity::getStatus, CollectionTaskStatus.ONLINE.name())
                .and(hasText(normalizedKeyword), wrapper -> {
                    wrapper.like(CollectionTaskDefinitionEntity::getName, normalizedKeyword)
                            .or()
                            .like(CollectionTaskDefinitionEntity::getTaskType, normalizedKeyword);
                    if (keywordId != null) {
                        wrapper.or().eq(CollectionTaskDefinitionEntity::getId, keywordId);
                    }
                })
                .orderByDesc(CollectionTaskDefinitionEntity::getUpdatedAt)
                .orderByDesc(CollectionTaskDefinitionEntity::getId);
        Page<CollectionTaskDefinitionEntity> entityPage = definitionMapper.selectPage(page, queryWrapper);
        List<CollectionTaskWorkflowOptionView> result = new ArrayList<CollectionTaskWorkflowOptionView>();
        for (CollectionTaskDefinitionEntity entity : entityPage.getRecords()) {
            result.add(toWorkflowOptionView(entity));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), result);
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
        incrementalCursorSupport.protectSystemIncrementalCursors(entity.getId() == null ? null : entity, sourceBindings);
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
        applyTargetSnapshots(entity, targetBinding);
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
        rebuildMetricBindings(entity, sourceBindings, targetBinding);
        saveSchedule(entity.getId(), entity.getProjectId(), request.getSchedule());
        dataModelLineageService.scheduleTaskRebuildAfterCommit(entity.getId());
        return get(entity.getId());
    }

    @Transactional
    public CollectionTaskDefinitionView publish(Long id) {
        CollectionTaskDefinitionEntity entity = requireWritableEntity(id);
        entity.setStatus(CollectionTaskStatus.ONLINE.name());
        definitionMapper.updateById(entity);
        metricBindingMapper.update(null, new LambdaUpdateWrapper<CollectionTaskMetricBindingEntity>()
                .set(CollectionTaskMetricBindingEntity::getTaskStatus, CollectionTaskStatus.ONLINE.name())
                .eq(CollectionTaskMetricBindingEntity::getCollectionTaskId, id));
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
        CollectionTaskIncrementalCursorSupport.ResetResult resetResult = incrementalCursorSupport
                .resetSystemIncrementalCursorFields(entity.getSourceBindingsJson(), sourceAlias, incrColumn, incrModel);
        if (resetResult.isSourceAliasMissing()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Source alias not found: " + sourceAlias);
        }
        if (resetResult.isChanged()) {
            entity.setSourceBindingsJson(resetResult.getSourceBindings());
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
        metricBindingMapper.delete(new LambdaQueryWrapper<CollectionTaskMetricBindingEntity>()
                .eq(CollectionTaskMetricBindingEntity::getCollectionTaskId, id));
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
        sanitizeSourceReaderOptionsForView(sourceBindings);
        incrementalCursorSupport.normalizeSourceBindingsForView(sourceBindings);
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

    private CollectionTaskListView toListView(CollectionTaskDefinitionEntity entity, CollectionTaskScheduleDefinition schedule) {
        CollectionTaskListView view = new CollectionTaskListView();
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
        view.setTargetDatasourceName(entity.getTargetDatasourceNameSnapshot());
        view.setTargetDatasourceTypeCode(entity.getTargetDatasourceTypeCodeSnapshot());
        view.setTargetModelName(entity.getTargetModelNameSnapshot());
        view.setTargetModelPhysicalLocator(entity.getTargetModelPhysicalLocatorSnapshot());
        view.setSchedule(schedule);
        return view;
    }

    private CollectionTaskWorkflowOptionView toWorkflowOptionView(CollectionTaskDefinitionEntity entity) {
        CollectionTaskWorkflowOptionView view = new CollectionTaskWorkflowOptionView();
        view.setId(entity.getId());
        view.setProjectId(entity.getProjectId());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setTaskType(entity.getTaskType() == null ? null : CollectionTaskType.valueOf(entity.getTaskType()));
        view.setSourceCount(entity.getSourceCount());
        return view;
    }

    private LambdaQueryWrapper<CollectionTaskDefinitionEntity> selectListColumns(LambdaQueryWrapper<CollectionTaskDefinitionEntity> queryWrapper) {
        return queryWrapper.select(CollectionTaskDefinitionEntity::getId,
                CollectionTaskDefinitionEntity::getTenantId,
                CollectionTaskDefinitionEntity::getProjectId,
                CollectionTaskDefinitionEntity::getDeleted,
                CollectionTaskDefinitionEntity::getCreatedAt,
                CollectionTaskDefinitionEntity::getUpdatedAt,
                CollectionTaskDefinitionEntity::getName,
                CollectionTaskDefinitionEntity::getTaskType,
                CollectionTaskDefinitionEntity::getStatus,
                CollectionTaskDefinitionEntity::getSourceCount,
                CollectionTaskDefinitionEntity::getTargetDatasourceNameSnapshot,
                CollectionTaskDefinitionEntity::getTargetDatasourceTypeCodeSnapshot,
                CollectionTaskDefinitionEntity::getTargetModelNameSnapshot,
                CollectionTaskDefinitionEntity::getTargetModelPhysicalLocatorSnapshot);
    }

    private LambdaQueryWrapper<CollectionTaskDefinitionEntity> selectWorkflowOptionColumns(LambdaQueryWrapper<CollectionTaskDefinitionEntity> queryWrapper) {
        return queryWrapper.select(CollectionTaskDefinitionEntity::getId,
                CollectionTaskDefinitionEntity::getProjectId,
                CollectionTaskDefinitionEntity::getUpdatedAt,
                CollectionTaskDefinitionEntity::getName,
                CollectionTaskDefinitionEntity::getTaskType,
                CollectionTaskDefinitionEntity::getSourceCount);
    }

    private Map<Long, CollectionTaskScheduleDefinition> loadScheduleMap(List<CollectionTaskDefinitionEntity> entities) {
        Set<Long> taskIds = new HashSet<Long>();
        for (CollectionTaskDefinitionEntity entity : entities) {
            if (entity != null && entity.getId() != null) {
                taskIds.add(entity.getId());
            }
        }
        if (taskIds.isEmpty()) {
            return new LinkedHashMap<Long, CollectionTaskScheduleDefinition>();
        }
        List<CollectionTaskScheduleEntity> schedules = scheduleMapper.selectList(new LambdaQueryWrapper<CollectionTaskScheduleEntity>()
                .select(CollectionTaskScheduleEntity::getCollectionTaskId,
                        CollectionTaskScheduleEntity::getCronExpression,
                        CollectionTaskScheduleEntity::getEnabled,
                        CollectionTaskScheduleEntity::getTimezone)
                .in(CollectionTaskScheduleEntity::getCollectionTaskId, taskIds));
        Map<Long, CollectionTaskScheduleDefinition> result = new LinkedHashMap<Long, CollectionTaskScheduleDefinition>();
        for (CollectionTaskScheduleEntity entity : schedules) {
            if (entity == null || entity.getCollectionTaskId() == null) {
                continue;
            }
            CollectionTaskScheduleDefinition schedule = new CollectionTaskScheduleDefinition();
            schedule.setCronExpression(entity.getCronExpression());
            schedule.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
            schedule.setTimezone(entity.getTimezone());
            result.put(entity.getCollectionTaskId(), schedule);
        }
        return result;
    }

    private CollectionTaskDefinitionView toMetricTaskView(CollectionTaskMetricBindingEntity entity) {
        CollectionTaskDefinitionView view = new CollectionTaskDefinitionView();
        view.setId(entity.getCollectionTaskId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getTaskNameSnapshot());
        view.setTaskType(entity.getTaskType() == null ? null : CollectionTaskType.valueOf(entity.getTaskType()));
        view.setStatus(entity.getTaskStatus() == null ? null : CollectionTaskStatus.valueOf(entity.getTaskStatus()));
        view.setSourceCount(entity.getSourceCount());
        view.setSourceBindings(new ArrayList<CollectionTaskSourceBinding>());
        return view;
    }

    private CollectionTaskSourceBinding toMetricSourceBinding(CollectionTaskMetricBindingEntity entity) {
        CollectionTaskSourceBinding sourceBinding = new CollectionTaskSourceBinding();
        sourceBinding.setSourceAlias(entity.getSourceAlias());
        sourceBinding.setDatasourceId(entity.getDatasourceId());
        sourceBinding.setDatasourceName(entity.getDatasourceName());
        sourceBinding.setDatasourceTypeCode(entity.getDatasourceTypeCode());
        sourceBinding.setModelId(entity.getModelId());
        sourceBinding.setModelName(entity.getModelName());
        sourceBinding.setModelPhysicalLocator(entity.getModelPhysicalLocator());
        return sourceBinding;
    }

    private CollectionTaskTargetBinding toMetricTargetBinding(CollectionTaskMetricBindingEntity entity) {
        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(entity.getDatasourceId());
        targetBinding.setDatasourceName(entity.getDatasourceName());
        targetBinding.setDatasourceTypeCode(entity.getDatasourceTypeCode());
        targetBinding.setModelId(entity.getModelId());
        targetBinding.setModelName(entity.getModelName());
        targetBinding.setModelPhysicalLocator(entity.getModelPhysicalLocator());
        return targetBinding;
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

    private LambdaQueryWrapper<CollectionTaskMetricBindingEntity> buildAccessibleMetricBindingQuery() {
        LambdaQueryWrapper<CollectionTaskMetricBindingEntity> queryWrapper = new LambdaQueryWrapper<CollectionTaskMetricBindingEntity>()
                .eq(CollectionTaskMetricBindingEntity::getTenantId, securityService.currentTenantId());
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return queryWrapper;
        }
        List<Long> sharedIds = projectResourceAccessService.sharedResourceIdList(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK);
        if (sharedIds.isEmpty()) {
            queryWrapper.eq(CollectionTaskMetricBindingEntity::getProjectId, currentProjectId);
            return queryWrapper;
        }
        queryWrapper.and(wrapper -> wrapper.eq(CollectionTaskMetricBindingEntity::getProjectId, currentProjectId)
                .or()
                .in(CollectionTaskMetricBindingEntity::getCollectionTaskId, sharedIds));
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
        incrementalCursorSupport.protectSystemIncrementalCursors(existingEntity, sourceBindings);
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

    private boolean matchesSummaryKeywords(CollectionTaskListView view,
                                           String nameKeyword,
                                           String targetDatasourceKeyword,
                                           String targetModelKeyword) {
        if (!containsIgnoreCase(view.getName(), nameKeyword)) {
            return false;
        }
        if (!containsIgnoreCase(view.getTargetDatasourceName(), targetDatasourceKeyword)
                && !containsIgnoreCase(view.getTargetDatasourceTypeCode(), targetDatasourceKeyword)) {
            return false;
        }
        if (!containsIgnoreCase(view.getTargetModelName(), targetModelKeyword)
                && !containsIgnoreCase(view.getTargetModelPhysicalLocator(), targetModelKeyword)) {
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

    private void applyTargetSnapshots(CollectionTaskDefinitionEntity entity, CollectionTaskTargetBinding targetBinding) {
        if (targetBinding == null) {
            entity.setTargetDatasourceNameSnapshot(null);
            entity.setTargetDatasourceTypeCodeSnapshot(null);
            entity.setTargetModelNameSnapshot(null);
            entity.setTargetModelPhysicalLocatorSnapshot(null);
            return;
        }
        entity.setTargetDatasourceNameSnapshot(normalizeNullableText(targetBinding.getDatasourceName()));
        entity.setTargetDatasourceTypeCodeSnapshot(normalizeNullableText(targetBinding.getDatasourceTypeCode()));
        entity.setTargetModelNameSnapshot(normalizeNullableText(targetBinding.getModelName()));
        entity.setTargetModelPhysicalLocatorSnapshot(normalizeNullableText(targetBinding.getModelPhysicalLocator()));
    }

    private void rebuildMetricBindings(CollectionTaskDefinitionEntity taskEntity,
                                       List<CollectionTaskSourceBinding> sourceBindings,
                                       CollectionTaskTargetBinding targetBinding) {
        if (taskEntity == null || taskEntity.getId() == null) {
            return;
        }
        metricBindingMapper.delete(new LambdaQueryWrapper<CollectionTaskMetricBindingEntity>()
                .eq(CollectionTaskMetricBindingEntity::getCollectionTaskId, taskEntity.getId()));
        if (sourceBindings != null) {
            for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
                if (sourceBinding != null) {
                    metricBindingMapper.insert(toMetricBindingEntity(taskEntity, METRIC_BINDING_ROLE_SOURCE, sourceBinding, null));
                }
            }
        }
        if (targetBinding != null) {
            metricBindingMapper.insert(toMetricBindingEntity(taskEntity, METRIC_BINDING_ROLE_TARGET, null, targetBinding));
        }
    }

    private CollectionTaskMetricBindingEntity toMetricBindingEntity(CollectionTaskDefinitionEntity taskEntity,
                                                                    String bindingRole,
                                                                    CollectionTaskSourceBinding sourceBinding,
                                                                    CollectionTaskTargetBinding targetBinding) {
        CollectionTaskMetricBindingEntity entity = new CollectionTaskMetricBindingEntity();
        entity.setTenantId(taskEntity.getTenantId());
        entity.setProjectId(taskEntity.getProjectId());
        entity.setCollectionTaskId(taskEntity.getId());
        entity.setTaskNameSnapshot(taskEntity.getName());
        entity.setTaskType(taskEntity.getTaskType());
        entity.setTaskStatus(taskEntity.getStatus());
        entity.setSourceCount(taskEntity.getSourceCount());
        entity.setBindingRole(bindingRole);
        if (METRIC_BINDING_ROLE_SOURCE.equals(bindingRole) && sourceBinding != null) {
            entity.setSourceAlias(sourceBinding.getSourceAlias());
            entity.setDatasourceId(sourceBinding.getDatasourceId());
            entity.setDatasourceName(sourceBinding.getDatasourceName());
            entity.setDatasourceTypeCode(sourceBinding.getDatasourceTypeCode());
            entity.setModelId(sourceBinding.getModelId());
            entity.setModelName(sourceBinding.getModelName());
            entity.setModelPhysicalLocator(sourceBinding.getModelPhysicalLocator());
        }
        if (METRIC_BINDING_ROLE_TARGET.equals(bindingRole) && targetBinding != null) {
            entity.setDatasourceId(targetBinding.getDatasourceId());
            entity.setDatasourceName(targetBinding.getDatasourceName());
            entity.setDatasourceTypeCode(targetBinding.getDatasourceTypeCode());
            entity.setModelId(targetBinding.getModelId());
            entity.setModelName(targetBinding.getModelName());
            entity.setModelPhysicalLocator(targetBinding.getModelPhysicalLocator());
        }
        return entity;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalizeNullableText(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo.intValue() <= 0 ? 1 : pageNo.intValue();
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize.intValue() <= 0) {
            return 20;
        }
        return Math.min(pageSize.intValue(), 200);
    }

    private Long parseLongOrNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
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
            enriched.setReaderOptions(sanitizeFileReaderOptions(datasource.getTypeCode(), binding.getReaderOptions()));
            enriched.setIncremental(binding.getIncremental());
            result.add(enriched);
        }
        return result;
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
        enriched.setWriterOptions(sanitizeFileWriterOptions(datasource.getTypeCode(), binding.getWriterOptions()));
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

    private Map<String, Object> sanitizeFileReaderOptions(String datasourceTypeCode, Map<String, Object> options) {
        Map<String, Object> result = copyOptionMap(options);
        if (!isFileDatasourceType(datasourceTypeCode)) {
            return result;
        }
        for (String key : FILE_MODEL_READER_OPTION_KEYS) {
            result.remove(key);
        }
        return result;
    }

    private void sanitizeSourceReaderOptionsForView(List<CollectionTaskSourceBinding> sourceBindings) {
        if (sourceBindings == null) {
            return;
        }
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            if (sourceBinding == null) {
                continue;
            }
            sourceBinding.setReaderOptions(sanitizeFileReaderOptions(sourceBinding.getDatasourceTypeCode(), sourceBinding.getReaderOptions()));
        }
    }

    private Map<String, Object> sanitizeFileWriterOptions(String datasourceTypeCode, Map<String, Object> options) {
        Map<String, Object> result = copyOptionMap(options);
        if (!isFileDatasourceType(datasourceTypeCode)) {
            return result;
        }
        for (String key : FILE_MODEL_WRITER_OPTION_KEYS) {
            result.remove(key);
        }
        return result;
    }

    private boolean isFileDatasourceType(String datasourceTypeCode) {
        return "ftp".equalsIgnoreCase(datasourceTypeCode)
                || "sftp".equalsIgnoreCase(datasourceTypeCode)
                || "minio".equalsIgnoreCase(datasourceTypeCode);
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

}
