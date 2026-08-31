package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskExecutionMode;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskListView;
import com.jdragon.studio.dto.model.CollectionTaskOptionView;
import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskStreamingOptions;
import com.jdragon.studio.dto.model.CollectionTaskStreamingRuntimeView;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.CollectionTaskWorkflowOptionView;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.RunLogChunkView;
import com.jdragon.studio.dto.model.StreamingMetricBucketView;
import com.jdragon.studio.dto.model.StreamingTaskEventView;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final Set<String> LEGACY_STREAMING_READER_OPTION_KEYS = Set.of(
            "groupId", "offsetReset", "resetOffset", "pollTimeoutMs");

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
    private RuntimeClusterSelectionService runtimeClusterSelectionService;
    private StreamingTaskRuntimeService streamingTaskRuntimeService;

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
        if (runtimeClusterSelectionService != null) {
            runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, result);
        }
        return result;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeClusterSelectionService(RuntimeClusterSelectionService runtimeClusterSelectionService) { this.runtimeClusterSelectionService = runtimeClusterSelectionService; }

    @org.springframework.beans.factory.annotation.Autowired
    void setStreamingTaskRuntimeService(StreamingTaskRuntimeService streamingTaskRuntimeService) {
        this.streamingTaskRuntimeService = streamingTaskRuntimeService;
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
        if (runtimeClusterSelectionService != null) {
            runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, result);
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

    public Map<Long, String> listAccessibleNamesByIds(Collection<Long> ids) {
        Set<Long> taskIds = normalizeIds(ids);
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        if (taskIds.isEmpty()) {
            return result;
        }
        List<CollectionTaskDefinitionEntity> entities = definitionMapper.selectList(buildAccessibleQuery()
                .select(CollectionTaskDefinitionEntity::getId,
                        CollectionTaskDefinitionEntity::getName)
                .in(CollectionTaskDefinitionEntity::getId, taskIds)
                .orderByAsc(CollectionTaskDefinitionEntity::getName)
                .orderByAsc(CollectionTaskDefinitionEntity::getId));
        for (CollectionTaskDefinitionEntity entity : entities) {
            if (entity.getId() != null) {
                result.put(entity.getId(), entity.getName());
            }
        }
        return result;
    }

    public String getAccessibleName(Long id) {
        if (id == null) {
            return null;
        }
        CollectionTaskDefinitionEntity entity = definitionMapper.selectOne(buildAccessibleQuery()
                .select(CollectionTaskDefinitionEntity::getId,
                        CollectionTaskDefinitionEntity::getName)
                .eq(CollectionTaskDefinitionEntity::getId, id)
                .last("limit 1"));
        return entity == null ? null : entity.getName();
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
        return listWorkflowOptions(pageNo, pageSize, keyword, null);
    }

    public PageView<CollectionTaskWorkflowOptionView> listWorkflowOptions(Integer pageNo,
                                                                          Integer pageSize,
                                                                          String keyword,
                                                                          Long runtimeClusterId) {
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
                .and(wrapper -> wrapper.isNull(CollectionTaskDefinitionEntity::getExecutionMode)
                        .or()
                        .eq(CollectionTaskDefinitionEntity::getExecutionMode, CollectionTaskExecutionMode.BATCH.name()))
                .eq(runtimeClusterId != null, CollectionTaskDefinitionEntity::getRuntimeClusterId, runtimeClusterId)
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
        CollectionTaskDefinitionView view = toView(entity, true);
        return runtimeClusterSelectionService == null ? view
                : runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, view);
    }

    public CollectionTaskDefinitionView requireOnline(Long id) {
        CollectionTaskDefinitionView view = get(id);
        if (view.getStatus() != CollectionTaskStatus.ONLINE) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Collection task is not online");
        }
        runtimeClusterSelectionService.assertResourceValid(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, view.getId());
        runtimeClusterSelectionService.assertExistingResourceRunnable(view.getProjectId(), view.getRuntimeClusterId(),
                datasourceIds(view));
        return view;
    }

    public CollectionTaskDefinitionView requireOnlineForExecution(Long id) {
        CollectionTaskDefinitionEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Collection task not found: " + id);
        }
        CollectionTaskDefinitionView view = toView(entity, false);
        if (view.getStatus() != CollectionTaskStatus.ONLINE) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Collection task is not online");
        }
        return view;
    }

    public CollectionTaskDefinitionView requireRunnableOnCluster(Long id, Long runtimeClusterId) {
        CollectionTaskDefinitionView view = get(id);
        if (view.getStatus() != CollectionTaskStatus.ONLINE) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Collection task is not online");
        }
        runtimeClusterSelectionService.validateManualOverride(view.getProjectId(), runtimeClusterId, datasourceIds(view));
        view.setRuntimeClusterId(runtimeClusterId);
        view.setRuntimeClusterName(runtimeClusterSelectionService.runtimeClusterName(view.getProjectId(), runtimeClusterId));
        view.setRuntimeValid(Boolean.TRUE);
        view.setRuntimeValidationMessage(null);
        return view;
    }

    public Map<String, Object> preview(CollectionTaskSaveRequest request) {
        return previewForView(request);
    }

    public Map<String, Object> previewForView(CollectionTaskSaveRequest request) {
        runtimeClusterSelectionService.assertExplicitSelection(request == null ? null : request.getRuntimeClusterId());
        return collectionTaskAssemblerService.assemblePreview(toDefinitionView(request));
    }

    @Transactional
    public CollectionTaskDefinitionView save(CollectionTaskSaveRequest request) {
        validateRequest(request);
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        boolean creating = request.getId() == null;
        CollectionTaskDefinitionEntity entity = creating
                ? new CollectionTaskDefinitionEntity()
                : requireWritableEntity(request.getId());
        if (entity == null) {
            entity = new CollectionTaskDefinitionEntity();
        }
        if (creating) {
            entity.setId(IdWorker.getId());
        } else if (executionMode(entity) == CollectionTaskExecutionMode.STREAMING) {
            requireStreamingRuntimeService().assertEditable(entity);
        }
        CollectionTaskExecutionMode executionMode = resolveExecutionMode(request.getExecutionMode(), entity, creating);
        Map<String, Object> executionOptions = copyExecutionOptions(request.getExecutionOptions());
        List<CollectionTaskSourceBinding> sourceBindings = enrichSourceBindings(
                request.getSourceBindings(), storedSourceBindings(entity));
        migrateLegacyStreamingReaderOptions(executionMode, sourceBindings, request.getStreamingOptions(), entity);
        persistKafkaReaderDefaults(sourceBindings, entity.getId());
        incrementalCursorSupport.protectSystemIncrementalCursors(entity.getId() == null ? null : entity, sourceBindings);
        CollectionTaskTargetBinding targetBinding = enrichTargetBinding(request.getTargetBinding(), executionOptions);
        List<Long> datasourceIds = new ArrayList<Long>();
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            datasourceIds.add(sourceBinding.getDatasourceId());
        }
        datasourceIds.add(targetBinding == null ? null : targetBinding.getDatasourceId());
        Long runtimeClusterId = runtimeClusterSelectionService.validateDatasourceSelectionForResourceSave(
                currentProjectId, request.getRuntimeClusterId(), entity.getRuntimeClusterId(),
                entity.getId() != null, datasourceIds);
        List<FieldMappingDefinition> fieldMappings = request.getFieldMappings() == null
                ? new ArrayList<FieldMappingDefinition>()
                : request.getFieldMappings();
        CollectionTaskStreamingOptions streamingOptions = normalizeStreamingOptions(
                executionMode, request.getStreamingOptions(), entity, creating);
        persistStreamingReaderDefaults(executionMode, sourceBindings, streamingOptions);
        validateExecutionMode(executionMode, sourceBindings, request.getSchedule(), streamingOptions);

        ensureUniqueName(currentProjectId, request.getName(), entity.getId());
        entity.setTenantId(securityService.currentTenantId());
        entity.setProjectId(currentProjectId);
        entity.setRuntimeClusterId(runtimeClusterId);
        entity.setName(request.getName());
        entity.setTaskType(sourceBindings.size() > 1 ? CollectionTaskType.FUSION.name() : CollectionTaskType.SINGLE_TABLE.name());
        entity.setSourceCount(sourceBindings.size());
        entity.setStatus(resolveStatusForSave(entity.getStatus(), creating));
        entity.setExecutionMode(executionMode.name());
        applyTargetSnapshots(entity, targetBinding);
        entity.setSourceBindingsJson(toListOfMaps(sourceBindings));
        entity.setTargetBindingJson(toMap(targetBinding));
        entity.setFieldMappingsJson(toListOfMaps(fieldMappings));
        entity.setExecutionOptionsJson(executionOptions);
        entity.setStreamingOptionsJson(stripLegacyStreamingOptions(streamingOptions));
        if (creating && entity.getCreatedBy() == null) {
            entity.setCreatedBy(securityService.currentUserId());
        }

        if (creating) {
            definitionMapper.insert(entity);
        } else {
            definitionMapper.updateById(entity);
        }
        rebuildMetricBindings(entity, sourceBindings, targetBinding);
        saveSchedule(entity.getId(), entity.getProjectId(), executionMode == CollectionTaskExecutionMode.STREAMING
                ? null : request.getSchedule());
        if (executionMode == CollectionTaskExecutionMode.STREAMING) {
            requireStreamingRuntimeService().ensureDeployment(entity);
        }
        dataModelLineageService.scheduleTaskRebuildAfterCommit(entity.getId());
        runtimeClusterSelectionService.markResourceValid(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, entity.getId());
        return get(entity.getId());
    }

    @Transactional
    public CollectionTaskListView publish(Long id) {
        CollectionTaskDefinitionEntity entity = requireWritableEntity(id);
        runtimeClusterSelectionService.assertResourceValid(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, entity.getId());
        runtimeClusterSelectionService.assertExistingResourceRunnable(entity.getProjectId(), entity.getRuntimeClusterId(),
                datasourceIds(entity));
        if (executionMode(entity) == CollectionTaskExecutionMode.STREAMING) {
            requireStreamingRuntimeService().online(entity);
        }
        definitionMapper.update(null, new LambdaUpdateWrapper<CollectionTaskDefinitionEntity>()
                .set(CollectionTaskDefinitionEntity::getStatus, CollectionTaskStatus.ONLINE.name())
                .eq(CollectionTaskDefinitionEntity::getId, entity.getId()));
        metricBindingMapper.update(null, new LambdaUpdateWrapper<CollectionTaskMetricBindingEntity>()
                .set(CollectionTaskMetricBindingEntity::getTaskStatus, CollectionTaskStatus.ONLINE.name())
                .eq(CollectionTaskMetricBindingEntity::getCollectionTaskId, id));
        return getListView(id);
    }

    @Transactional
    public CollectionTaskListView offline(Long id) {
        CollectionTaskDefinitionEntity entity = requireWritableEntity(id);
        requireStreamingRuntimeService().offline(entity);
        definitionMapper.update(null, new LambdaUpdateWrapper<CollectionTaskDefinitionEntity>()
                .set(CollectionTaskDefinitionEntity::getStatus, CollectionTaskStatus.OFFLINE.name())
                .eq(CollectionTaskDefinitionEntity::getId, entity.getId()));
        metricBindingMapper.update(null, new LambdaUpdateWrapper<CollectionTaskMetricBindingEntity>()
                .set(CollectionTaskMetricBindingEntity::getTaskStatus, CollectionTaskStatus.OFFLINE.name())
                .eq(CollectionTaskMetricBindingEntity::getCollectionTaskId, id));
        return getListView(id);
    }

    @Transactional
    public CollectionTaskListView recover(Long id) {
        CollectionTaskDefinitionEntity entity = requireWritableEntity(id);
        requireStreamingRuntimeService().recover(entity);
        return getListView(id);
    }

    public CollectionTaskStreamingRuntimeView streamingRuntime(Long id) {
        return requireStreamingRuntimeService().runtime(requireAccessibleStreamingEntity(id));
    }

    public List<StreamingMetricBucketView> streamingMetrics(Long id,
                                                            LocalDateTime startTime,
                                                            LocalDateTime endTime) {
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Streaming metric startTime must not be after endTime");
        }
        return requireStreamingRuntimeService().metrics(requireAccessibleStreamingEntity(id), startTime, endTime);
    }

    public PageView<StreamingMetricBucketView> streamingMetricsPage(Long id,
                                                                     LocalDateTime startTime,
                                                                     LocalDateTime endTime,
                                                                     Integer pageNo,
                                                                     Integer pageSize) {
        return streamingMetricsPage(id, startTime, endTime, pageNo, pageSize, false);
    }

    public PageView<StreamingMetricBucketView> streamingMetricsPage(Long id,
                                                                     LocalDateTime startTime,
                                                                     LocalDateTime endTime,
                                                                     Integer pageNo,
                                                                     Integer pageSize,
                                                                     boolean onlyWithRecords) {
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Streaming metric startTime must not be after endTime");
        }
        return requireStreamingRuntimeService().metricsPage(requireAccessibleStreamingEntity(id),
                startTime, endTime, pageNo, pageSize, onlyWithRecords);
    }

    public PageView<StreamingTaskEventView> streamingEvents(Long id, Integer pageNo, Integer pageSize) {
        return requireStreamingRuntimeService().events(requireAccessibleStreamingEntity(id), pageNo, pageSize);
    }

    public PageView<RunLogChunkView> streamingLogChunks(Long id, Integer pageNo, Integer pageSize) {
        return requireStreamingRuntimeService().logChunks(requireAccessibleStreamingEntity(id), pageNo, pageSize);
    }

    private Set<Long> datasourceIds(CollectionTaskDefinitionView view) {
        Set<Long> datasourceIds = new HashSet<Long>();
        if (view == null) {
            return datasourceIds;
        }
        for (CollectionTaskSourceBinding source : view.getSourceBindings()) {
            if (source != null && source.getDatasourceId() != null) {
                datasourceIds.add(source.getDatasourceId());
            }
        }
        if (view.getTargetBinding() != null && view.getTargetBinding().getDatasourceId() != null) {
            datasourceIds.add(view.getTargetBinding().getDatasourceId());
        }
        return datasourceIds;
    }

    private Set<Long> datasourceIds(CollectionTaskDefinitionEntity entity) {
        Set<Long> datasourceIds = new HashSet<Long>();
        if (entity == null) {
            return datasourceIds;
        }
        for (CollectionTaskSourceBinding source : convertList(
                entity.getSourceBindingsJson(), CollectionTaskSourceBinding.class)) {
            if (source != null && source.getDatasourceId() != null) {
                datasourceIds.add(source.getDatasourceId());
            }
        }
        CollectionTaskTargetBinding target = convertMap(
                entity.getTargetBindingJson(), CollectionTaskTargetBinding.class);
        if (target != null && target.getDatasourceId() != null) {
            datasourceIds.add(target.getDatasourceId());
        }
        return datasourceIds;
    }

    @Transactional
    public CollectionTaskDefinitionView updateSchedule(Long id, CollectionTaskScheduleDefinition schedule) {
        CollectionTaskDefinitionEntity entity = requireWritableEntity(id);
        assertBatchExecution(entity, "Streaming collection tasks do not support schedules");
        if (schedule != null && Boolean.TRUE.equals(schedule.getEnabled()) && runtimeClusterSelectionService != null) {
            CollectionTaskDefinitionView view = toView(entity, false);
            runtimeClusterSelectionService.assertResourceValid(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, id);
            runtimeClusterSelectionService.assertExistingResourceRunnable(entity.getProjectId(), entity.getRuntimeClusterId(),
                    datasourceIds(view));
        }
        saveSchedule(id, entity.getProjectId(), schedule);
        return get(id);
    }

    @Transactional
    public CollectionTaskDefinitionView resetIncrementalCursor(Long id, String sourceAlias, String incrColumn, String incrModel) {
        CollectionTaskDefinitionEntity entity = requireWritableEntity(id);
        if (executionMode(entity) == CollectionTaskExecutionMode.STREAMING
                && requireStreamingRuntimeService().isRunning(entity.getId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Running streaming collection task must be offline before resetting cursors");
        }
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
        CollectionTaskDefinitionEntity entity = requireWritableEntity(id);
        if (executionMode(entity) == CollectionTaskExecutionMode.STREAMING
                && requireStreamingRuntimeService().isRunning(entity.getId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Running streaming collection task must be offline before it can be deleted");
        }
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
        List<CollectionTaskScheduleEntity> schedules = scheduleMapper.selectList(new LambdaQueryWrapper<CollectionTaskScheduleEntity>()
                .eq(CollectionTaskScheduleEntity::getEnabled, 1));
        if (schedules.isEmpty()) {
            return schedules;
        }
        Set<Long> taskIds = new HashSet<Long>();
        for (CollectionTaskScheduleEntity schedule : schedules) {
            if (schedule != null && schedule.getCollectionTaskId() != null) {
                taskIds.add(schedule.getCollectionTaskId());
            }
        }
        if (taskIds.isEmpty()) {
            return new ArrayList<CollectionTaskScheduleEntity>();
        }
        List<CollectionTaskDefinitionEntity> tasks = definitionMapper.selectList(
                new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
                        .select(CollectionTaskDefinitionEntity::getId,
                                CollectionTaskDefinitionEntity::getExecutionMode)
                        .in(CollectionTaskDefinitionEntity::getId, taskIds));
        Set<Long> streamingTaskIds = new HashSet<Long>();
        for (CollectionTaskDefinitionEntity task : tasks) {
            if (executionMode(task) == CollectionTaskExecutionMode.STREAMING) {
                streamingTaskIds.add(task.getId());
            }
        }
        if (streamingTaskIds.isEmpty()) {
            return schedules;
        }
        List<CollectionTaskScheduleEntity> result = new ArrayList<CollectionTaskScheduleEntity>();
        for (CollectionTaskScheduleEntity schedule : schedules) {
            if (!streamingTaskIds.contains(schedule.getCollectionTaskId())) {
                result.add(schedule);
            }
        }
        return result;
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
        return toView(entity, true);
    }

    private CollectionTaskDefinitionView toView(CollectionTaskDefinitionEntity entity, boolean maskSensitiveOptions) {
        CollectionTaskDefinitionView view = new CollectionTaskDefinitionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null
                : runtimeClusterSelectionService.runtimeClusterName(entity.getProjectId(), entity.getRuntimeClusterId()));
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setTaskType(entity.getTaskType() == null ? null : CollectionTaskType.valueOf(entity.getTaskType()));
        view.setStatus(entity.getStatus() == null ? null : CollectionTaskStatus.valueOf(entity.getStatus()));
        view.setExecutionMode(executionMode(entity));
        view.setSourceCount(entity.getSourceCount());
        List<CollectionTaskSourceBinding> sourceBindings = convertList(entity.getSourceBindingsJson(), CollectionTaskSourceBinding.class);
        sanitizeSourceReaderOptions(sourceBindings, maskSensitiveOptions);
        migrateLegacyStreamingReaderOptions(executionMode(entity), sourceBindings, null, entity);
        incrementalCursorSupport.normalizeSourceBindingsForView(sourceBindings);
        view.setSourceBindings(sourceBindings);
        view.setStreamingOptions(toStreamingOptions(entity));
        CollectionTaskTargetBinding targetBinding = convertMap(entity.getTargetBindingJson(), CollectionTaskTargetBinding.class);
        if (maskSensitiveOptions && targetBinding != null) {
            targetBinding.setModelPhysicalLocator(collectionTaskAssemblerService.maskHttpPhysicalLocator(
                    targetBinding.getDatasourceTypeCode(), targetBinding.getModelPhysicalLocator()));
        }
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
        if (streamingTaskRuntimeService != null) {
            streamingTaskRuntimeService.hydrate(view, entity.getId());
        }
        return view;
    }

    private CollectionTaskListView toListView(CollectionTaskDefinitionEntity entity, CollectionTaskScheduleDefinition schedule) {
        CollectionTaskListView view = new CollectionTaskListView();
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null : runtimeClusterSelectionService.runtimeClusterName(entity.getProjectId(), entity.getRuntimeClusterId()));
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null : runtimeClusterSelectionService.runtimeClusterName(entity.getProjectId(), entity.getRuntimeClusterId()));
        view.setName(entity.getName());
        view.setTaskType(entity.getTaskType() == null ? null : CollectionTaskType.valueOf(entity.getTaskType()));
        view.setStatus(entity.getStatus() == null ? null : CollectionTaskStatus.valueOf(entity.getStatus()));
        view.setExecutionMode(executionMode(entity));
        view.setSourceCount(entity.getSourceCount());
        view.setTargetDatasourceName(entity.getTargetDatasourceNameSnapshot());
        view.setTargetDatasourceTypeCode(entity.getTargetDatasourceTypeCodeSnapshot());
        view.setTargetModelName(entity.getTargetModelNameSnapshot());
        view.setTargetModelPhysicalLocator(collectionTaskAssemblerService.maskHttpPhysicalLocator(
                entity.getTargetDatasourceTypeCodeSnapshot(), entity.getTargetModelPhysicalLocatorSnapshot()));
        view.setSchedule(schedule);
        if (streamingTaskRuntimeService != null) {
            streamingTaskRuntimeService.hydrate(view, entity.getId());
        }
        return view;
    }

    private CollectionTaskListView getListView(Long id) {
        CollectionTaskDefinitionEntity entity = definitionMapper.selectOne(selectListColumns(buildAccessibleQuery())
                .eq(CollectionTaskDefinitionEntity::getId, id)
                .last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Collection task not found: " + id);
        }
        Map<Long, CollectionTaskScheduleDefinition> schedules = loadScheduleMap(Collections.singletonList(entity));
        return toListView(entity, schedules.get(entity.getId()));
    }

    private CollectionTaskWorkflowOptionView toWorkflowOptionView(CollectionTaskDefinitionEntity entity) {
        CollectionTaskWorkflowOptionView view = new CollectionTaskWorkflowOptionView();
        view.setId(entity.getId());
        view.setProjectId(entity.getProjectId());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null
                : runtimeClusterSelectionService.runtimeClusterName(entity.getProjectId(), entity.getRuntimeClusterId()));
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
                CollectionTaskDefinitionEntity::getRuntimeClusterId,
                CollectionTaskDefinitionEntity::getName,
                CollectionTaskDefinitionEntity::getTaskType,
                CollectionTaskDefinitionEntity::getStatus,
                CollectionTaskDefinitionEntity::getExecutionMode,
                CollectionTaskDefinitionEntity::getSourceCount,
                CollectionTaskDefinitionEntity::getTargetDatasourceNameSnapshot,
                CollectionTaskDefinitionEntity::getTargetDatasourceTypeCodeSnapshot,
                CollectionTaskDefinitionEntity::getTargetModelNameSnapshot,
                CollectionTaskDefinitionEntity::getTargetModelPhysicalLocatorSnapshot);
    }

    private LambdaQueryWrapper<CollectionTaskDefinitionEntity> selectWorkflowOptionColumns(LambdaQueryWrapper<CollectionTaskDefinitionEntity> queryWrapper) {
        return queryWrapper.select(CollectionTaskDefinitionEntity::getId,
                CollectionTaskDefinitionEntity::getProjectId,
                CollectionTaskDefinitionEntity::getRuntimeClusterId,
                CollectionTaskDefinitionEntity::getUpdatedAt,
                CollectionTaskDefinitionEntity::getName,
                CollectionTaskDefinitionEntity::getTaskType,
                CollectionTaskDefinitionEntity::getExecutionMode,
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

    private Set<Long> normalizeIds(Collection<Long> ids) {
        Set<Long> result = new HashSet<Long>();
        if (ids == null) {
            return result;
        }
        for (Long id : ids) {
            if (id != null) {
                result.add(id);
            }
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
        sourceBinding.setModelPhysicalLocator(collectionTaskAssemblerService.maskHttpPhysicalLocator(
                entity.getDatasourceTypeCode(), entity.getModelPhysicalLocator()));
        return sourceBinding;
    }

    private CollectionTaskTargetBinding toMetricTargetBinding(CollectionTaskMetricBindingEntity entity) {
        CollectionTaskTargetBinding targetBinding = new CollectionTaskTargetBinding();
        targetBinding.setDatasourceId(entity.getDatasourceId());
        targetBinding.setDatasourceName(entity.getDatasourceName());
        targetBinding.setDatasourceTypeCode(entity.getDatasourceTypeCode());
        targetBinding.setModelId(entity.getModelId());
        targetBinding.setModelName(entity.getModelName());
        targetBinding.setModelPhysicalLocator(collectionTaskAssemblerService.maskHttpPhysicalLocator(
                entity.getDatasourceTypeCode(), entity.getModelPhysicalLocator()));
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

    private CollectionTaskDefinitionEntity requireWritableListEntity(Long id) {
        CollectionTaskDefinitionEntity entity = definitionMapper.selectOne(selectListColumns(buildAccessibleQuery())
                .eq(CollectionTaskDefinitionEntity::getId, id)
                .last("limit 1"));
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
        validateFieldMappings(request.getFieldMappings(), aliases);
    }

    private CollectionTaskExecutionMode resolveExecutionMode(CollectionTaskExecutionMode requestedMode,
                                                              CollectionTaskDefinitionEntity entity,
                                                              boolean creating) {
        if (requestedMode != null) {
            return requestedMode;
        }
        return creating ? CollectionTaskExecutionMode.BATCH : executionMode(entity);
    }

    private CollectionTaskExecutionMode executionMode(CollectionTaskDefinitionEntity entity) {
        if (entity == null || !hasText(entity.getExecutionMode())) {
            return CollectionTaskExecutionMode.BATCH;
        }
        try {
            return CollectionTaskExecutionMode.valueOf(entity.getExecutionMode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Unsupported collection task execution mode: " + entity.getExecutionMode());
        }
    }

    private String resolveStatusForSave(String existingStatus, boolean creating) {
        if (creating || !hasText(existingStatus)) {
            return CollectionTaskStatus.DRAFT.name();
        }
        if (CollectionTaskStatus.ONLINE.name().equalsIgnoreCase(existingStatus)) {
            return CollectionTaskStatus.ONLINE.name();
        }
        if (CollectionTaskStatus.OFFLINE.name().equalsIgnoreCase(existingStatus)) {
            return CollectionTaskStatus.OFFLINE.name();
        }
        return CollectionTaskStatus.DRAFT.name();
    }

    private CollectionTaskStreamingOptions normalizeStreamingOptions(CollectionTaskExecutionMode mode,
                                                                      CollectionTaskStreamingOptions requested,
                                                                      CollectionTaskDefinitionEntity entity,
                                                                      boolean creating) {
        if (mode != CollectionTaskExecutionMode.STREAMING) {
            return null;
        }
        CollectionTaskStreamingOptions result;
        if (requested != null) {
            result = objectMapper.convertValue(requested, CollectionTaskStreamingOptions.class);
        } else if (!creating && entity != null && entity.getStreamingOptionsJson() != null
                && !entity.getStreamingOptionsJson().isEmpty()) {
            result = objectMapper.convertValue(entity.getStreamingOptionsJson(), CollectionTaskStreamingOptions.class);
        } else {
            result = new CollectionTaskStreamingOptions();
        }
        if (hasText(result.getGroupId())) {
            result.setGroupId(result.getGroupId().trim());
        }
        if (hasText(result.getOffsetReset())) {
            result.setOffsetReset(result.getOffsetReset().trim().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private void migrateLegacyStreamingReaderOptions(CollectionTaskExecutionMode mode,
                                                      List<CollectionTaskSourceBinding> sourceBindings,
                                                      CollectionTaskStreamingOptions requested,
                                                      CollectionTaskDefinitionEntity entity) {
        if (mode != CollectionTaskExecutionMode.STREAMING || sourceBindings == null || sourceBindings.isEmpty()) {
            return;
        }
        Map<String, Object> legacy = new LinkedHashMap<String, Object>();
        if (entity != null && entity.getStreamingOptionsJson() != null) {
            legacy.putAll(entity.getStreamingOptionsJson());
        }
        if (requested != null) {
            legacy.putAll(toMap(requested));
        }
        if (legacy.isEmpty()) {
            return;
        }
        CollectionTaskSourceBinding source = sourceBindings.get(0);
        if (source == null) {
            return;
        }
        Map<String, Object> readerOptions = source.getReaderOptions() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(source.getReaderOptions());
        for (String key : LEGACY_STREAMING_READER_OPTION_KEYS) {
            Object value = legacy.get(key);
            if (value == null || !hasText(String.valueOf(value))
                    || (readerOptions.containsKey(key) && hasText(String.valueOf(readerOptions.get(key))))) {
                continue;
            }
            readerOptions.put(key, value);
        }
        source.setReaderOptions(readerOptions);
    }

    private void persistStreamingReaderDefaults(CollectionTaskExecutionMode mode,
                                                 List<CollectionTaskSourceBinding> sourceBindings,
                                                 CollectionTaskStreamingOptions options) {
        if (mode != CollectionTaskExecutionMode.STREAMING || sourceBindings == null || sourceBindings.isEmpty()
                || options == null) {
            return;
        }
        CollectionTaskSourceBinding source = sourceBindings.get(0);
        if (source == null) {
            return;
        }
        Map<String, Object> readerOptions = source.getReaderOptions() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(source.getReaderOptions());
        putIfMissing(readerOptions, "groupId", options.getGroupId());
        putIfMissing(readerOptions, "offsetReset", "latest");
        putIfMissing(readerOptions, "resetOffset", Boolean.FALSE);
        putIfMissing(readerOptions, "pollTimeoutMs", Integer.valueOf(1000));
        source.setReaderOptions(readerOptions);
    }

    private void persistKafkaReaderDefaults(List<CollectionTaskSourceBinding> sourceBindings, Long taskId) {
        if (sourceBindings == null || sourceBindings.isEmpty() || taskId == null) {
            return;
        }
        boolean multipleSources = sourceBindings.size() > 1;
        for (CollectionTaskSourceBinding source : sourceBindings) {
            if (source == null || !"kafka".equalsIgnoreCase(source.getDatasourceTypeCode())) {
                continue;
            }
            Map<String, Object> readerOptions = source.getReaderOptions() == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(source.getReaderOptions());
            String groupId = "studio." + securityService.currentTenantId() + "." + taskId;
            if (multipleSources && hasText(source.getSourceAlias())) {
                groupId += "." + source.getSourceAlias().trim().replaceAll("[^A-Za-z0-9._-]", "_");
            }
            putIfMissing(readerOptions, "groupId", groupId);
            source.setReaderOptions(readerOptions);
        }
    }

    private void putIfMissing(Map<String, Object> target, String key, Object value) {
        Object current = target.get(key);
        if ((!target.containsKey(key) || current == null || !hasText(String.valueOf(current))) && value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> stripLegacyStreamingOptions(CollectionTaskStreamingOptions options) {
        if (options == null) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> result = toMap(options);
        for (String key : LEGACY_STREAMING_READER_OPTION_KEYS) {
            result.remove(key);
        }
        return result;
    }

    private void clearLegacyStreamingOptions(CollectionTaskStreamingOptions options) {
        if (options == null) {
            return;
        }
        options.setGroupId(null);
        options.setOffsetReset(null);
        options.setResetOffset(null);
        options.setPollTimeoutMs(null);
    }

    private CollectionTaskStreamingOptions toStreamingOptions(CollectionTaskDefinitionEntity entity) {
        if (executionMode(entity) != CollectionTaskExecutionMode.STREAMING) {
            return null;
        }
        CollectionTaskStreamingOptions result = normalizeStreamingOptions(CollectionTaskExecutionMode.STREAMING, null, entity, false);
        clearLegacyStreamingOptions(result);
        return result;
    }

    private void validateExecutionMode(CollectionTaskExecutionMode mode,
                                       List<CollectionTaskSourceBinding> sourceBindings,
                                       CollectionTaskScheduleDefinition schedule,
                                       CollectionTaskStreamingOptions options) {
        if (mode != CollectionTaskExecutionMode.STREAMING) {
            return;
        }
        if (sourceBindings == null || sourceBindings.size() != 1) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "STREAMING collection task requires exactly one source binding");
        }
        CollectionTaskSourceBinding source = sourceBindings.get(0);
        if (source == null || !"kafka".equalsIgnoreCase(source.getDatasourceTypeCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "STREAMING collection task currently supports only a Kafka source");
        }
        if (!hasText(source.getModelPhysicalLocator())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Kafka source model physicalLocator must contain the Topic name");
        }
        if (schedule != null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "STREAMING collection tasks do not support schedules");
        }
        if (options == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Streaming options are required");
        }
        Map<String, Object> readerOptions = source.getReaderOptions() == null
                ? Collections.<String, Object>emptyMap() : source.getReaderOptions();
        String offsetReset = optionText(readerOptions.get("offsetReset"), "latest").toLowerCase(Locale.ROOT);
        if (!"earliest".equals(offsetReset) && !"latest".equals(offsetReset)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Kafka reader offsetReset must be earliest or latest");
        }
        requirePositive(optionNumber(readerOptions.get("pollTimeoutMs"), 1000L, "pollTimeoutMs"), "pollTimeoutMs");
        requirePositive(options.getMaxBatchRecords(), "maxBatchRecords");
        requirePositive(options.getMaxBatchBytes(), "maxBatchBytes");
        requireNonNegative(options.getBatchRetryCount(), "batchRetryCount");
        requirePositive(options.getStopTimeoutMs(), "stopTimeoutMs");
        requirePositive(options.getMaxConsecutiveFailures(), "maxConsecutiveFailures");
        requirePositive(options.getRetryInitialDelayMs(), "retryInitialDelayMs");
        requirePositive(options.getRetryMaxDelayMs(), "retryMaxDelayMs");
        if (options.getRetryMaxDelayMs().longValue() < options.getRetryInitialDelayMs().longValue()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "retryMaxDelayMs must not be less than retryInitialDelayMs");
        }
    }

    private void requirePositive(Number value, String field) {
        if (value == null || value.longValue() <= 0L) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, field + " must be greater than 0");
        }
    }

    private void requireNonNegative(Number value, String field) {
        if (value == null || value.longValue() < 0L) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, field + " must not be negative");
        }
    }

    private String optionText(Object value, String defaultValue) {
        return value == null || String.valueOf(value).trim().isEmpty()
                ? defaultValue : String.valueOf(value).trim();
    }

    private Number optionNumber(Object value, long defaultValue, String field) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return Long.valueOf(defaultValue);
        }
        if (value instanceof Number) {
            return (Number) value;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, field + " must be a number");
        }
    }

    private void assertBatchExecution(CollectionTaskDefinitionEntity entity, String message) {
        if (executionMode(entity) == CollectionTaskExecutionMode.STREAMING) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
    }

    private CollectionTaskDefinitionEntity requireAccessibleStreamingEntity(Long id) {
        CollectionTaskDefinitionEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Collection task not found: " + id);
        }
        if (executionMode(entity) != CollectionTaskExecutionMode.STREAMING) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Collection task is not configured for STREAMING execution");
        }
        return entity;
    }

    private StreamingTaskRuntimeService requireStreamingRuntimeService() {
        if (streamingTaskRuntimeService == null) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Streaming task runtime service is unavailable");
        }
        return streamingTaskRuntimeService;
    }

    private void validateFieldMappings(List<FieldMappingDefinition> mappings, Set<String> sourceAliases) {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        Set<String> targetFields = new HashSet<String>();
        for (int index = 0; index < mappings.size(); index++) {
            FieldMappingDefinition mapping = mappings.get(index);
            int displayIndex = index + 1;
            if (mapping == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Field mapping " + displayIndex + " is required");
            }
            String targetField = normalizeNullableText(mapping.getTargetField());
            String sourceField = normalizeNullableText(mapping.getSourceField());
            String sourceAlias = normalizeNullableText(mapping.getSourceAlias());
            String expression = normalizeNullableText(mapping.getExpression());
            if (!hasText(targetField)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Field mapping " + displayIndex + " requires a target field");
            }
            if (!targetFields.add(targetField.toLowerCase(Locale.ROOT))) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Field mapping target field must be unique: " + targetField);
            }
            if (!hasText(sourceField) && !hasText(expression)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Field mapping " + displayIndex + " requires a source field or expression");
            }
            if (hasText(sourceField)) {
                if (!hasText(sourceAlias)) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Field mapping " + displayIndex + " requires a source alias");
                }
                if (!sourceAliases.contains(sourceAlias.toLowerCase(Locale.ROOT))) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Field mapping " + displayIndex + " references an unknown source alias: " + sourceAlias);
                }
            }
        }
    }

    private CollectionTaskDefinitionView toDefinitionView(CollectionTaskSaveRequest request) {
        validateRequest(request);
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        Map<String, Object> executionOptions = copyExecutionOptions(request.getExecutionOptions());
        CollectionTaskDefinitionEntity existingEntity = request.getId() == null ? null : findAccessibleEntity(request.getId());
        boolean creating = existingEntity == null;
        CollectionTaskExecutionMode executionMode = resolveExecutionMode(
                request.getExecutionMode(), existingEntity, creating);
        List<CollectionTaskSourceBinding> sourceBindings = enrichSourceBindings(
                request.getSourceBindings(), storedSourceBindings(existingEntity));
        incrementalCursorSupport.protectSystemIncrementalCursors(existingEntity, sourceBindings);
        CollectionTaskTargetBinding targetBinding = enrichTargetBinding(request.getTargetBinding(), executionOptions);
        List<Long> datasourceIds = new ArrayList<Long>();
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            datasourceIds.add(sourceBinding.getDatasourceId());
        }
        datasourceIds.add(targetBinding == null ? null : targetBinding.getDatasourceId());
        Long runtimeClusterId = runtimeClusterSelectionService.validateDatasourceSelectionForResourceSave(
                currentProjectId, request.getRuntimeClusterId(),
                existingEntity == null ? null : existingEntity.getRuntimeClusterId(),
                existingEntity != null && existingEntity.getId() != null, datasourceIds);
        CollectionTaskStreamingOptions streamingOptions = normalizeStreamingOptions(
                executionMode, request.getStreamingOptions(), existingEntity, creating);
        migrateLegacyStreamingReaderOptions(executionMode, sourceBindings, request.getStreamingOptions(), existingEntity);
        persistStreamingReaderDefaults(executionMode, sourceBindings, streamingOptions);
        validateExecutionMode(executionMode, sourceBindings, request.getSchedule(), streamingOptions);
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setId(request.getId());
        definition.setTenantId(securityService.currentTenantId());
        definition.setProjectId(currentProjectId);
        definition.setRuntimeClusterId(runtimeClusterId);
        definition.setName(request.getName());
        definition.setExecutionMode(executionMode);
        clearLegacyStreamingOptions(streamingOptions);
        definition.setStreamingOptions(streamingOptions);
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

    private List<CollectionTaskSourceBinding> enrichSourceBindings(List<CollectionTaskSourceBinding> bindings,
                                                                    List<CollectionTaskSourceBinding> existingBindings) {
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
            Map<String, Object> readerOptions = sanitizeFileReaderOptions(datasource.getTypeCode(), binding.getReaderOptions());
            if ("kafka".equalsIgnoreCase(datasource.getTypeCode())) {
                readerOptions = KafkaConfigurationSupport.normalizeTaskRuntimeOptions(readerOptions);
            }
            CollectionTaskSourceBinding existingBinding = findExistingSourceBinding(
                    binding, existingBindings, datasource.getTypeCode());
            enriched.setReaderOptions(collectionTaskAssemblerService.prepareReaderOptionOverrides(
                    datasource.getTypeCode(), model, readerOptions,
                    existingBinding == null ? null : existingBinding.getReaderOptions()));
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
        Map<String, Object> writerOptions = sanitizeFileWriterOptions(
                datasource.getTypeCode(), binding.getWriterOptions());
        if ("kafka".equalsIgnoreCase(datasource.getTypeCode())) {
            writerOptions = KafkaConfigurationSupport.normalizeTaskRuntimeOptions(writerOptions);
        }
        enriched.setWriterOptions(writerOptions);
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

    private void sanitizeSourceReaderOptions(List<CollectionTaskSourceBinding> sourceBindings,
                                             boolean maskSensitiveOptions) {
        if (sourceBindings == null) {
            return;
        }
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            if (sourceBinding == null) {
                continue;
            }
            Map<String, Object> readerOptions = sanitizeFileReaderOptions(
                    sourceBinding.getDatasourceTypeCode(), sourceBinding.getReaderOptions());
            if (maskSensitiveOptions
                    && "http".equalsIgnoreCase(sourceBinding.getDatasourceTypeCode())
                    && sourceBinding.getModelId() != null) {
                DataModelDefinition model = null;
                try {
                    model = dataModelService.get(sourceBinding.getModelId());
                } catch (StudioException exception) {
                    if (!StudioErrorCode.NOT_FOUND.equals(exception.getCode())) {
                        throw exception;
                    }
                }
                readerOptions = collectionTaskAssemblerService.maskReaderOptionOverridesForView(
                        sourceBinding.getDatasourceTypeCode(), model, readerOptions);
            }
            if (maskSensitiveOptions) {
                sourceBinding.setModelPhysicalLocator(collectionTaskAssemblerService.maskHttpPhysicalLocator(
                        sourceBinding.getDatasourceTypeCode(), sourceBinding.getModelPhysicalLocator()));
            }
            sourceBinding.setReaderOptions(readerOptions);
        }
    }

    private List<CollectionTaskSourceBinding> storedSourceBindings(CollectionTaskDefinitionEntity entity) {
        if (entity == null) {
            return new ArrayList<CollectionTaskSourceBinding>();
        }
        return convertList(entity.getSourceBindingsJson(), CollectionTaskSourceBinding.class);
    }

    private CollectionTaskSourceBinding findExistingSourceBinding(CollectionTaskSourceBinding binding,
                                                                  List<CollectionTaskSourceBinding> existingBindings,
                                                                  String datasourceTypeCode) {
        if (binding == null || existingBindings == null || existingBindings.isEmpty()) {
            return null;
        }
        boolean existingAliasMatched = false;
        if (hasText(binding.getSourceAlias())) {
            for (CollectionTaskSourceBinding existing : existingBindings) {
                if (existing != null && hasText(existing.getSourceAlias())
                        && binding.getSourceAlias().trim().equalsIgnoreCase(existing.getSourceAlias().trim())) {
                    existingAliasMatched = true;
                    if (Objects.equals(binding.getDatasourceId(), existing.getDatasourceId())
                            && Objects.equals(binding.getModelId(), existing.getModelId())) {
                        return existing;
                    }
                }
            }
        }
        if (existingAliasMatched) {
            return null;
        }
        CollectionTaskSourceBinding matched = null;
        for (CollectionTaskSourceBinding existing : existingBindings) {
            if (existing == null
                    || !Objects.equals(binding.getDatasourceId(), existing.getDatasourceId())
                    || !Objects.equals(binding.getModelId(), existing.getModelId())) {
                continue;
            }
            if (matched != null) {
                if ("http".equalsIgnoreCase(datasourceTypeCode)
                        && containsMaskedHttpReaderOption(binding.getReaderOptions())) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Cannot preserve masked HTTP reader credentials for source alias '"
                                    + binding.getSourceAlias()
                                    + "': multiple existing sources use datasourceId "
                                    + binding.getDatasourceId() + " and modelId " + binding.getModelId()
                                    + ". Restore the original alias or re-enter the sensitive HTTP options.");
                }
                return null;
            }
            matched = existing;
        }
        return matched;
    }

    private boolean containsMaskedHttpReaderOption(Map<String, Object> readerOptions) {
        if (readerOptions == null || readerOptions.isEmpty()) {
            return false;
        }
        for (String key : new String[]{"header", "params", "requestBody"}) {
            Object value = readerOptions.get(key);
            if (value != null && String.valueOf(value).contains("****")) {
                return true;
            }
        }
        return false;
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
