package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityRuleOutputType;
import com.jdragon.studio.dto.enums.QualityTaskAlertOperator;
import com.jdragon.studio.dto.enums.QualityTaskStatus;
import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.QualityRuleInputParamView;
import com.jdragon.studio.dto.model.QualityRuleOutputParamView;
import com.jdragon.studio.dto.model.QualityRuleView;
import com.jdragon.studio.dto.model.QualityTaskAlertConfig;
import com.jdragon.studio.dto.model.QualityTaskDefinitionView;
import com.jdragon.studio.dto.model.QualityTaskListView;
import com.jdragon.studio.dto.model.QualityTaskOptionView;
import com.jdragon.studio.dto.model.QualityTaskParamBinding;
import com.jdragon.studio.dto.model.QualityTaskPreviewView;
import com.jdragon.studio.dto.model.QualityTaskValidationView;
import com.jdragon.studio.dto.model.QualityTaskWorkflowOptionView;
import com.jdragon.studio.dto.model.request.QualityTaskSaveRequest;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.QualityTaskAlertEntity;
import com.jdragon.studio.infra.entity.QualityTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.QualityTaskScheduleEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.QualityTaskAlertMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.QualityTaskScheduleMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class QualityTaskService {

    private final QualityTaskDefinitionMapper definitionMapper;
    private final QualityTaskScheduleMapper scheduleMapper;
    private final QualityTaskAlertMapper alertMapper;
    private final DispatchTaskMapper dispatchTaskMapper;
    private final RunRecordMapper runRecordMapper;
    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final QualityRuleService qualityRuleService;
    private final QualityTaskExecutionPlanService qualityTaskExecutionPlanService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final ObjectMapper objectMapper;
    private RuntimeClusterSelectionService runtimeClusterSelectionService;

    public QualityTaskService(QualityTaskDefinitionMapper definitionMapper,
                              QualityTaskScheduleMapper scheduleMapper,
                              QualityTaskAlertMapper alertMapper,
                              DispatchTaskMapper dispatchTaskMapper,
                              RunRecordMapper runRecordMapper,
                              DataSourceService dataSourceService,
                              DataModelService dataModelService,
                              QualityRuleService qualityRuleService,
                              QualityTaskExecutionPlanService qualityTaskExecutionPlanService,
                              DatasourceTypeCapabilityService datasourceTypeCapabilityService,
                              StudioSecurityService securityService,
                              ProjectResourceAccessService projectResourceAccessService,
                              ObjectMapper objectMapper) {
        this.definitionMapper = definitionMapper;
        this.scheduleMapper = scheduleMapper;
        this.alertMapper = alertMapper;
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.runRecordMapper = runRecordMapper;
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.qualityRuleService = qualityRuleService;
        this.qualityTaskExecutionPlanService = qualityTaskExecutionPlanService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.objectMapper = objectMapper;
    }

    public PageView<QualityTaskListView> list(Integer pageNo,
                                              Integer pageSize,
                                              String keyword,
                                              String status,
                                              String ruleDimension,
                                              String granularity) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return PageView.of(safePageNo, safePageSize, 0L, new ArrayList<QualityTaskListView>());
        }
        String normalizedKeyword = normalizeText(keyword);
        String normalizedStatus = normalizeText(status);
        String normalizedDimension = normalizeText(ruleDimension);
        String normalizedGranularity = normalizeText(granularity);
        Page<QualityTaskDefinitionEntity> page = new Page<QualityTaskDefinitionEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<QualityTaskDefinitionEntity> queryWrapper = selectTaskListColumns(new LambdaQueryWrapper<QualityTaskDefinitionEntity>())
                .eq(QualityTaskDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityTaskDefinitionEntity::getProjectId, currentProjectId)
                .and(hasText(normalizedKeyword), wrapper -> wrapper.like(QualityTaskDefinitionEntity::getTaskName, normalizedKeyword)
                        .or()
                        .like(QualityTaskDefinitionEntity::getTaskCode, normalizedKeyword)
                        .or()
                        .like(QualityTaskDefinitionEntity::getRuleNameSnapshot, normalizedKeyword)
                        .or()
                        .like(QualityTaskDefinitionEntity::getDatasourceNameSnapshot, normalizedKeyword)
                        .or()
                        .like(QualityTaskDefinitionEntity::getModelNameSnapshot, normalizedKeyword))
                .eq(hasText(normalizedStatus), QualityTaskDefinitionEntity::getStatus,
                        normalizedStatus == null ? null : normalizedStatus.toUpperCase(Locale.ROOT))
                .eq(hasText(normalizedDimension), QualityTaskDefinitionEntity::getRuleDimension,
                        normalizedDimension == null ? null : normalizedDimension.toUpperCase(Locale.ROOT))
                .eq(hasText(normalizedGranularity), QualityTaskDefinitionEntity::getGranularity,
                        normalizedGranularity == null ? null : normalizedGranularity.toUpperCase(Locale.ROOT))
                .orderByDesc(QualityTaskDefinitionEntity::getUpdatedAt)
                .orderByDesc(QualityTaskDefinitionEntity::getId);
        Page<QualityTaskDefinitionEntity> entityPage = definitionMapper.selectPage(page, queryWrapper);
        List<QualityTaskListView> result = new ArrayList<QualityTaskListView>();
        for (QualityTaskDefinitionEntity entity : entityPage.getRecords()) {
            result.add(toListView(entity));
        }
        if (runtimeClusterSelectionService != null) {
            runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_QUALITY_TASK, result);
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), result);
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeClusterSelectionService(RuntimeClusterSelectionService runtimeClusterSelectionService) { this.runtimeClusterSelectionService = runtimeClusterSelectionService; }

    public List<QualityTaskListView> list(String keyword,
                                          String status,
                                          String ruleDimension,
                                          String granularity) {
        int pageNo = 1;
        int pageSize = 200;
        List<QualityTaskListView> result = new ArrayList<QualityTaskListView>();
        PageView<QualityTaskListView> page;
        do {
            page = list(pageNo, pageSize, keyword, status, ruleDimension, granularity);
            result.addAll(page.getItems());
            pageNo++;
        } while (result.size() < page.getTotal());
        return result;
    }

    public List<QualityTaskListView> listOnline() {
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return new ArrayList<QualityTaskListView>();
        }
        List<QualityTaskDefinitionEntity> entities = definitionMapper.selectList(selectTaskListColumns(new LambdaQueryWrapper<QualityTaskDefinitionEntity>())
                .eq(QualityTaskDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityTaskDefinitionEntity::getProjectId, currentProjectId)
                .eq(QualityTaskDefinitionEntity::getStatus, QualityTaskStatus.ONLINE.name())
                .orderByAsc(QualityTaskDefinitionEntity::getTaskName)
                .orderByAsc(QualityTaskDefinitionEntity::getId));
        List<QualityTaskListView> result = new ArrayList<QualityTaskListView>();
        for (QualityTaskDefinitionEntity entity : entities) {
            result.add(toListView(entity));
        }
        if (runtimeClusterSelectionService != null) {
            runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_QUALITY_TASK, result);
        }
        return result;
    }

    public PageView<QualityTaskWorkflowOptionView> listWorkflowOptions(Integer pageNo,
                                                                       Integer pageSize,
                                                                       String keyword) {
        return listWorkflowOptions(pageNo, pageSize, keyword, null);
    }

    public PageView<QualityTaskWorkflowOptionView> listWorkflowOptions(Integer pageNo,
                                                                       Integer pageSize,
                                                                       String keyword,
                                                                       Long runtimeClusterId) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return PageView.of(safePageNo, safePageSize, 0L, new ArrayList<QualityTaskWorkflowOptionView>());
        }
        String normalizedKeyword = normalizeText(keyword);
        Page<QualityTaskDefinitionEntity> page = new Page<QualityTaskDefinitionEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<QualityTaskDefinitionEntity> queryWrapper = selectWorkflowOptionColumns(new LambdaQueryWrapper<QualityTaskDefinitionEntity>())
                .eq(QualityTaskDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityTaskDefinitionEntity::getProjectId, currentProjectId)
                .eq(QualityTaskDefinitionEntity::getStatus, QualityTaskStatus.ONLINE.name())
                .eq(runtimeClusterId != null, QualityTaskDefinitionEntity::getRuntimeClusterId, runtimeClusterId)
                .and(hasText(normalizedKeyword), wrapper -> wrapper.like(QualityTaskDefinitionEntity::getTaskName, normalizedKeyword)
                        .or()
                        .like(QualityTaskDefinitionEntity::getTaskCode, normalizedKeyword)
                        .or()
                        .like(QualityTaskDefinitionEntity::getRuleNameSnapshot, normalizedKeyword)
                        .or()
                        .like(QualityTaskDefinitionEntity::getDatasourceNameSnapshot, normalizedKeyword)
                        .or()
                        .like(QualityTaskDefinitionEntity::getModelNameSnapshot, normalizedKeyword)
                        .or()
                        .like(QualityTaskDefinitionEntity::getColumnName, normalizedKeyword))
                .orderByDesc(QualityTaskDefinitionEntity::getUpdatedAt)
                .orderByDesc(QualityTaskDefinitionEntity::getId);
        Page<QualityTaskDefinitionEntity> entityPage = definitionMapper.selectPage(page, queryWrapper);
        List<QualityTaskWorkflowOptionView> result = new ArrayList<QualityTaskWorkflowOptionView>();
        for (QualityTaskDefinitionEntity entity : entityPage.getRecords()) {
            result.add(toWorkflowOptionView(entity));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), result);
    }

    public List<QualityTaskOptionView> listOptions() {
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return new ArrayList<QualityTaskOptionView>();
        }
        List<QualityTaskDefinitionEntity> entities = definitionMapper.selectList(new LambdaQueryWrapper<QualityTaskDefinitionEntity>()
                .select(QualityTaskDefinitionEntity::getId,
                        QualityTaskDefinitionEntity::getProjectId,
                        QualityTaskDefinitionEntity::getTaskName,
                        QualityTaskDefinitionEntity::getRuleNameSnapshot,
                        QualityTaskDefinitionEntity::getRuleDimension,
                        QualityTaskDefinitionEntity::getGranularity)
                .eq(QualityTaskDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityTaskDefinitionEntity::getProjectId, currentProjectId)
                .orderByAsc(QualityTaskDefinitionEntity::getTaskName)
                .orderByAsc(QualityTaskDefinitionEntity::getId));
        List<QualityTaskOptionView> result = new ArrayList<QualityTaskOptionView>();
        for (QualityTaskDefinitionEntity entity : entities) {
            result.add(toOptionView(entity));
        }
        return result;
    }

    public Map<Long, String> listAccessibleNames() {
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        if (currentProjectId == null) {
            return result;
        }
        List<QualityTaskDefinitionEntity> entities = definitionMapper.selectList(new LambdaQueryWrapper<QualityTaskDefinitionEntity>()
                .select(QualityTaskDefinitionEntity::getId,
                        QualityTaskDefinitionEntity::getTaskName)
                .eq(QualityTaskDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityTaskDefinitionEntity::getProjectId, currentProjectId)
                .orderByAsc(QualityTaskDefinitionEntity::getTaskName)
                .orderByAsc(QualityTaskDefinitionEntity::getId));
        for (QualityTaskDefinitionEntity entity : entities) {
            if (entity.getId() != null) {
                result.put(entity.getId(), entity.getTaskName());
            }
        }
        return result;
    }

    public Map<Long, String> listAccessibleNamesByIds(Collection<Long> ids) {
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        Set<Long> taskIds = normalizeIds(ids);
        if (currentProjectId == null || taskIds.isEmpty()) {
            return result;
        }
        List<QualityTaskDefinitionEntity> entities = definitionMapper.selectList(new LambdaQueryWrapper<QualityTaskDefinitionEntity>()
                .select(QualityTaskDefinitionEntity::getId,
                        QualityTaskDefinitionEntity::getTaskName)
                .eq(QualityTaskDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityTaskDefinitionEntity::getProjectId, currentProjectId)
                .in(QualityTaskDefinitionEntity::getId, taskIds)
                .orderByAsc(QualityTaskDefinitionEntity::getTaskName)
                .orderByAsc(QualityTaskDefinitionEntity::getId));
        for (QualityTaskDefinitionEntity entity : entities) {
            if (entity.getId() != null) {
                result.put(entity.getId(), entity.getTaskName());
            }
        }
        return result;
    }

    public String getAccessibleName(Long id) {
        if (id == null) {
            return null;
        }
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return null;
        }
        QualityTaskDefinitionEntity entity = definitionMapper.selectOne(new LambdaQueryWrapper<QualityTaskDefinitionEntity>()
                .select(QualityTaskDefinitionEntity::getId,
                        QualityTaskDefinitionEntity::getTaskName)
                .eq(QualityTaskDefinitionEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityTaskDefinitionEntity::getProjectId, currentProjectId)
                .eq(QualityTaskDefinitionEntity::getId, id)
                .last("limit 1"));
        return entity == null ? null : entity.getTaskName();
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

    public QualityTaskDefinitionView get(Long id) {
        QualityTaskDefinitionEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Quality task not found: " + id);
        }
        QualityTaskDefinitionView view = toView(entity);
        return runtimeClusterSelectionService == null ? view
                : runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_QUALITY_TASK, view);
    }

    public QualityTaskDefinitionView requireOnline(Long id) {
        QualityTaskDefinitionView definition = get(id);
        if (definition.getStatus() != QualityTaskStatus.ONLINE) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Quality task is not online");
        }
        runtimeClusterSelectionService.assertResourceValid(StudioConstants.RESOURCE_TYPE_QUALITY_TASK, definition.getId());
        runtimeClusterSelectionService.assertExistingResourceRunnable(definition.getProjectId(),
                definition.getRuntimeClusterId(), java.util.Collections.singletonList(definition.getDatasourceId()));
        return definition;
    }

    public QualityTaskDefinitionView requireOnlineForExecution(Long id) {
        QualityTaskDefinitionView definition = get(id);
        if (definition.getStatus() != QualityTaskStatus.ONLINE) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Quality task is not online");
        }
        return definition;
    }

    public QualityTaskDefinitionView requireRunnableOnCluster(Long id, Long runtimeClusterId) {
        QualityTaskDefinitionView definition = get(id);
        if (definition.getStatus() != QualityTaskStatus.ONLINE) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Quality task is not online");
        }
        List<Long> datasourceIds = new ArrayList<Long>();
        if (definition.getDatasourceId() != null) {
            datasourceIds.add(definition.getDatasourceId());
        }
        runtimeClusterSelectionService.validateManualOverride(definition.getProjectId(), runtimeClusterId, datasourceIds);
        definition.setRuntimeClusterId(runtimeClusterId);
        definition.setRuntimeClusterName(runtimeClusterSelectionService.runtimeClusterName(definition.getProjectId(), runtimeClusterId));
        definition.setRuntimeValid(Boolean.TRUE);
        definition.setRuntimeValidationMessage(null);
        return definition;
    }

    public QualityTaskPreviewView preview(QualityTaskSaveRequest request) {
        runtimeClusterSelectionService.assertExplicitSelection(request == null ? null : request.getRuntimeClusterId());
        return qualityTaskExecutionPlanService.preview(toTransientDefinition(request));
    }

    public QualityTaskValidationView validate(QualityTaskSaveRequest request) {
        runtimeClusterSelectionService.assertExplicitSelection(request == null ? null : request.getRuntimeClusterId());
        return qualityTaskExecutionPlanService.validate(toTransientDefinition(request));
    }

    @Transactional
    public QualityTaskDefinitionView save(QualityTaskSaveRequest request) {
        validateSaveRequest(request);
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        QualityTaskDefinitionEntity entity = request.getId() == null
                ? new QualityTaskDefinitionEntity()
                : requireWritableEntity(request.getId());
        QualityTaskDefinitionView definition = toTransientDefinition(request, entity);
        Long runtimeClusterId = definition.getRuntimeClusterId();
        ensureUniqueTaskName(currentProjectId, request.getTaskName(), entity.getId());
        ensureUniqueTaskCode(currentProjectId, request.getTaskCode(), entity.getId());

        entity.setTenantId(securityService.currentTenantId());
        entity.setProjectId(currentProjectId);
        entity.setRuntimeClusterId(runtimeClusterId);
        entity.setTaskName(definition.getTaskName());
        entity.setTaskCode(definition.getTaskCode());
        entity.setStatus(entity.getId() != null && QualityTaskStatus.ONLINE.name().equalsIgnoreCase(entity.getStatus())
                ? QualityTaskStatus.ONLINE.name()
                : QualityTaskStatus.DRAFT.name());
        entity.setRuleId(definition.getRuleId());
        entity.setRuleNameSnapshot(definition.getRuleName());
        entity.setRuleDimension(definition.getRuleDimension() == null ? null : definition.getRuleDimension().name());
        entity.setGranularity(definition.getGranularity() == null ? null : definition.getGranularity().name());
        entity.setDatasourceId(definition.getDatasourceId());
        entity.setDatasourceNameSnapshot(definition.getDatasourceName());
        entity.setDatasourceTypeCode(definition.getDatasourceTypeCode());
        entity.setModelId(definition.getModelId());
        entity.setModelNameSnapshot(definition.getModelName());
        entity.setModelPhysicalLocator(definition.getModelPhysicalLocator());
        entity.setColumnName(definition.getColumnName());
        entity.setWhereClause(definition.getWhereClause());
        entity.setResolvedSqlPreview(qualityTaskExecutionPlanService.preview(definition).getResolvedSql());
        entity.setParameterBindingsJson(toListOfMaps(definition.getParameterBindings()));
        entity.setRuleSnapshotJson(toMap(definition.getRuleSnapshot()));
        if (entity.getId() == null) {
            entity.setCreatedBy(securityService.currentUserId());
            definitionMapper.insert(entity);
        } else {
            definitionMapper.updateById(entity);
            deleteTaskChildren(entity.getId());
        }
        saveSchedule(entity.getId(), currentProjectId, request.getSchedule());
        saveAlerts(entity.getId(), definition.getAlertConfigs());
        runtimeClusterSelectionService.markResourceValid(StudioConstants.RESOURCE_TYPE_QUALITY_TASK, entity.getId());
        return get(entity.getId());
    }

    @Transactional
    public QualityTaskDefinitionView publish(Long id) {
        QualityTaskDefinitionEntity entity = requireWritableEntity(id);
        QualityTaskDefinitionView definition = toView(entity);
        runtimeClusterSelectionService.assertResourceValid(StudioConstants.RESOURCE_TYPE_QUALITY_TASK, entity.getId());
        runtimeClusterSelectionService.assertExistingResourceRunnable(entity.getProjectId(), entity.getRuntimeClusterId(),
                java.util.Collections.singletonList(entity.getDatasourceId()));
        entity.setResolvedSqlPreview(qualityTaskExecutionPlanService.preview(definition).getResolvedSql());
        entity.setStatus(QualityTaskStatus.ONLINE.name());
        definitionMapper.updateById(entity);
        return get(id);
    }

    @Transactional
    public QualityTaskDefinitionView updateSchedule(Long id, CollectionTaskScheduleDefinition schedule) {
        QualityTaskDefinitionEntity entity = requireWritableEntity(id);
        if (schedule != null && Boolean.TRUE.equals(schedule.getEnabled()) && runtimeClusterSelectionService != null) {
            runtimeClusterSelectionService.assertResourceValid(StudioConstants.RESOURCE_TYPE_QUALITY_TASK, id);
            runtimeClusterSelectionService.assertExistingResourceRunnable(entity.getProjectId(), entity.getRuntimeClusterId(),
                    java.util.Collections.singletonList(entity.getDatasourceId()));
        }
        saveSchedule(id, entity.getProjectId(), schedule);
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        requireWritableEntity(id);
        deleteTaskChildren(id);
        scheduleMapper.delete(new LambdaQueryWrapper<QualityTaskScheduleEntity>()
                .eq(QualityTaskScheduleEntity::getQualityTaskId, id));
        dispatchTaskMapper.delete(new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getQualityTaskId, id));
        runRecordMapper.delete(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getQualityTaskId, id));
        definitionMapper.deleteById(id);
    }

    public List<QualityTaskScheduleEntity> findEnabledSchedules() {
        return scheduleMapper.selectList(new LambdaQueryWrapper<QualityTaskScheduleEntity>()
                .eq(QualityTaskScheduleEntity::getEnabled, Integer.valueOf(1)));
    }

    @Transactional
    public void markScheduleTriggered(Long qualityTaskId, LocalDateTime triggeredAt) {
        QualityTaskScheduleEntity scheduleEntity = scheduleMapper.selectOne(new LambdaQueryWrapper<QualityTaskScheduleEntity>()
                .eq(QualityTaskScheduleEntity::getQualityTaskId, qualityTaskId)
                .last("limit 1"));
        if (scheduleEntity == null) {
            return;
        }
        scheduleEntity.setLastTriggeredAt(triggeredAt);
        scheduleMapper.updateById(scheduleEntity);
    }

    private QualityTaskDefinitionView toTransientDefinition(QualityTaskSaveRequest request) {
        QualityTaskDefinitionEntity existingEntity = request == null || request.getId() == null
                ? null : findAccessibleEntity(request.getId());
        return toTransientDefinition(request, existingEntity);
    }

    private QualityTaskDefinitionView toTransientDefinition(QualityTaskSaveRequest request,
                                                             QualityTaskDefinitionEntity existingEntity) {
        validateSaveRequest(request);
        QualityRuleView rule = qualityRuleService.requireAccessibleRule(request.getRuleId());
        DataSourceDefinition datasource = requireSqlDatasource(request.getDatasourceId());
        DataModelDefinition model = dataModelService.get(request.getModelId());
        ensureModelBelongsToDatasource(model, datasource);
        if (rule.getGranularity() != request.getGranularity()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Task granularity must match the selected quality rule");
        }
        String columnName = normalizeColumnName(request.getGranularity(), request.getColumnName(), model);
        QualityTaskDefinitionView definition = new QualityTaskDefinitionView();
        definition.setId(request.getId());
        definition.setTenantId(securityService.currentTenantId());
        definition.setProjectId(projectResourceAccessService.requireCurrentProjectId());
        Long runtimeClusterId = runtimeClusterSelectionService == null
                ? request.getRuntimeClusterId()
                : runtimeClusterSelectionService.validateDatasourceSelectionForResourceSave(
                definition.getProjectId(), request.getRuntimeClusterId(),
                existingEntity == null ? null : existingEntity.getRuntimeClusterId(),
                existingEntity != null && existingEntity.getId() != null,
                java.util.Collections.singletonList(datasource.getId()));
        definition.setRuntimeClusterId(runtimeClusterId);
        definition.setRuntimeClusterName(runtimeClusterSelectionService == null ? null
                : runtimeClusterSelectionService.runtimeClusterName(definition.getProjectId(), runtimeClusterId));
        definition.setTaskName(normalizeText(request.getTaskName()));
        definition.setTaskCode(normalizeText(request.getTaskCode()));
        definition.setRuleId(rule.getId());
        definition.setRuleName(rule.getRuleName());
        definition.setRuleDimension(rule.getRuleDimension());
        definition.setGranularity(rule.getGranularity());
        definition.setDatasourceId(datasource.getId());
        definition.setDatasourceName(datasource.getName());
        definition.setDatasourceTypeCode(datasource.getTypeCode());
        definition.setModelId(model.getId());
        definition.setModelName(model.getName());
        definition.setModelPhysicalLocator(model.getPhysicalLocator());
        definition.setColumnName(columnName);
        definition.setWhereClause(normalizeNullableText(request.getWhereClause()));
        definition.setRuleSnapshot(toMap(rule));
        definition.setOutputParams(rule.getOutputParams() == null
                ? new ArrayList<QualityRuleOutputParamView>()
                : new ArrayList<QualityRuleOutputParamView>(rule.getOutputParams()));
        definition.setParameterBindings(buildParameterBindings(rule, request.getParameterBindings(), model.getPhysicalLocator(), columnName));
        definition.setAlertConfigs(normalizeAlertConfigs(request.getAlertConfigs(), definition.getOutputParams()));
        return definition;
    }

    private List<QualityTaskParamBinding> buildParameterBindings(QualityRuleView rule,
                                                                 List<QualityTaskParamBinding> requestBindings,
                                                                 String modelPhysicalLocator,
                                                                 String columnName) {
        Map<String, QualityTaskParamBinding> bindingMap = new LinkedHashMap<String, QualityTaskParamBinding>();
        if (requestBindings != null) {
            for (QualityTaskParamBinding binding : requestBindings) {
                if (binding == null || !hasText(binding.getParamName())) {
                    continue;
                }
                bindingMap.put(normalizeText(binding.getParamName()), binding);
            }
        }
        List<QualityTaskParamBinding> result = new ArrayList<QualityTaskParamBinding>();
        if (rule.getInputParams() == null) {
            return result;
        }
        for (QualityRuleInputParamView inputParam : rule.getInputParams()) {
            QualityTaskParamBinding item = new QualityTaskParamBinding();
            item.setParamOrder(inputParam.getParamOrder());
            item.setParamName(inputParam.getParamName());
            item.setParamType(inputParam.getParamType());
            item.setParamMeaning(inputParam.getParamMeaning());
            if (inputParam.getParamType() == com.jdragon.studio.dto.enums.QualityRuleParamType.TABLE) {
                item.setParamValue(modelPhysicalLocator);
                item.setEditable(Boolean.FALSE);
            } else if (inputParam.getParamType() == com.jdragon.studio.dto.enums.QualityRuleParamType.COLUMN) {
                item.setParamValue(columnName);
                item.setEditable(Boolean.FALSE);
            } else {
                QualityTaskParamBinding requestBinding = bindingMap.get(inputParam.getParamName());
                item.setParamValue(requestBinding == null ? null : normalizeNullableText(requestBinding.getParamValue()));
                item.setEditable(Boolean.TRUE);
            }
            result.add(item);
        }
        return result;
    }

    private List<QualityTaskAlertConfig> normalizeAlertConfigs(List<QualityTaskAlertConfig> requestAlerts,
                                                               List<QualityRuleOutputParamView> outputParams) {
        List<QualityTaskAlertConfig> result = new ArrayList<QualityTaskAlertConfig>();
        if (requestAlerts == null) {
            return result;
        }
        Map<Integer, QualityRuleOutputParamView> outputByOrder = new LinkedHashMap<Integer, QualityRuleOutputParamView>();
        Map<String, QualityRuleOutputParamView> outputByField = new LinkedHashMap<String, QualityRuleOutputParamView>();
        if (outputParams != null) {
            for (QualityRuleOutputParamView outputParam : outputParams) {
                if (outputParam.getOutputOrder() != null) {
                    outputByOrder.put(outputParam.getOutputOrder(), outputParam);
                }
                if (hasText(outputParam.getResultField())) {
                    outputByField.put(outputParam.getResultField(), outputParam);
                }
            }
        }
        for (QualityTaskAlertConfig requestAlert : requestAlerts) {
            if (requestAlert == null) {
                continue;
            }
            QualityRuleOutputParamView matchedOutput = requestAlert.getOutputOrder() == null
                    ? null
                    : outputByOrder.get(requestAlert.getOutputOrder());
            if (matchedOutput == null && hasText(requestAlert.getResultField())) {
                matchedOutput = outputByField.get(normalizeText(requestAlert.getResultField()));
            }
            QualityTaskAlertConfig item = new QualityTaskAlertConfig();
            item.setOutputOrder(requestAlert.getOutputOrder() == null && matchedOutput != null
                    ? matchedOutput.getOutputOrder()
                    : requestAlert.getOutputOrder());
            item.setResultField(hasText(requestAlert.getResultField())
                    ? normalizeText(requestAlert.getResultField())
                    : (matchedOutput == null ? null : matchedOutput.getResultField()));
            item.setOutputType(requestAlert.getOutputType() == null
                    ? (matchedOutput == null ? null : matchedOutput.getOutputType())
                    : requestAlert.getOutputType());
            item.setEnabled(Boolean.TRUE.equals(requestAlert.getEnabled()));
            item.setOperator(requestAlert.getOperator());
            item.setExpectedValue(normalizeNullableText(requestAlert.getExpectedValue()));
            item.setMinValue(normalizeNullableText(requestAlert.getMinValue()));
            item.setMaxValue(normalizeNullableText(requestAlert.getMaxValue()));
            validateAlertConfig(item);
            result.add(item);
        }
        return result;
    }

    private void validateAlertConfig(QualityTaskAlertConfig alertConfig) {
        if (alertConfig == null || !Boolean.TRUE.equals(alertConfig.getEnabled())) {
            return;
        }
        if (!hasText(alertConfig.getResultField())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Enabled alert must bind a result field");
        }
        if (alertConfig.getOperator() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Enabled alert must define a compare operator");
        }
        if (alertConfig.getOutputType() == QualityRuleOutputType.STRING
                && alertConfig.getOperator() != QualityTaskAlertOperator.EQ
                && alertConfig.getOperator() != QualityTaskAlertOperator.NE) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "String outputs only support EQ and NE alert operators");
        }
        if (isRangeOperator(alertConfig.getOperator())) {
            if (!hasText(alertConfig.getMinValue()) || !hasText(alertConfig.getMaxValue())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Range alert operator requires min and max values");
            }
        } else if (!hasText(alertConfig.getExpectedValue())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Single-value alert operator requires an expected value");
        }
    }

    private boolean isRangeOperator(QualityTaskAlertOperator operator) {
        return operator == QualityTaskAlertOperator.LT_R_LT
                || operator == QualityTaskAlertOperator.LT_R_LE
                || operator == QualityTaskAlertOperator.LE_R_LT
                || operator == QualityTaskAlertOperator.LE_R_LE;
    }

    private void saveAlerts(Long qualityTaskId, List<QualityTaskAlertConfig> alertConfigs) {
        if (alertConfigs == null) {
            return;
        }
        for (QualityTaskAlertConfig alertConfig : alertConfigs) {
            QualityTaskAlertEntity entity = new QualityTaskAlertEntity();
            entity.setQualityTaskId(qualityTaskId);
            entity.setOutputOrder(alertConfig.getOutputOrder());
            entity.setResultField(alertConfig.getResultField());
            entity.setOutputType(alertConfig.getOutputType() == null ? null : alertConfig.getOutputType().name());
            entity.setEnabled(Boolean.TRUE.equals(alertConfig.getEnabled()) ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setOperator(alertConfig.getOperator() == null ? null : alertConfig.getOperator().name());
            entity.setExpectedValue(alertConfig.getExpectedValue());
            entity.setMinValue(alertConfig.getMinValue());
            entity.setMaxValue(alertConfig.getMaxValue());
            alertMapper.insert(entity);
        }
    }

    private void saveSchedule(Long qualityTaskId, Long projectId, CollectionTaskScheduleDefinition schedule) {
        QualityTaskScheduleEntity entity = scheduleMapper.selectOne(new LambdaQueryWrapper<QualityTaskScheduleEntity>()
                .eq(QualityTaskScheduleEntity::getQualityTaskId, qualityTaskId)
                .last("limit 1"));
        if (entity == null) {
            entity = new QualityTaskScheduleEntity();
            entity.setQualityTaskId(qualityTaskId);
        }
        entity.setTenantId(securityService.currentTenantId());
        entity.setProjectId(projectId);
        if (schedule == null) {
            entity.setCronExpression(null);
            entity.setEnabled(Integer.valueOf(0));
            entity.setTimezone(null);
        } else {
            entity.setCronExpression(normalizeNullableText(schedule.getCronExpression()));
            entity.setEnabled(Boolean.TRUE.equals(schedule.getEnabled()) ? Integer.valueOf(1) : Integer.valueOf(0));
            entity.setTimezone(normalizeNullableText(schedule.getTimezone()));
        }
        if (entity.getId() == null) {
            scheduleMapper.insert(entity);
        } else {
            scheduleMapper.updateById(entity);
        }
    }

    private void deleteTaskChildren(Long qualityTaskId) {
        alertMapper.delete(new LambdaQueryWrapper<QualityTaskAlertEntity>()
                .eq(QualityTaskAlertEntity::getQualityTaskId, qualityTaskId));
    }

    private QualityTaskDefinitionView toView(QualityTaskDefinitionEntity entity) {
        QualityTaskDefinitionView view = new QualityTaskDefinitionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null
                : runtimeClusterSelectionService.runtimeClusterName(entity.getProjectId(), entity.getRuntimeClusterId()));
        view.setCreatedBy(entity.getCreatedBy());
        view.setTaskName(entity.getTaskName());
        view.setTaskCode(entity.getTaskCode());
        view.setStatus(entity.getStatus() == null ? null : QualityTaskStatus.valueOf(entity.getStatus()));
        view.setRuleId(entity.getRuleId());
        view.setRuleName(entity.getRuleNameSnapshot());
        view.setRuleDimension(entity.getRuleDimension() == null ? null : com.jdragon.studio.dto.enums.QualityRuleDimension.valueOf(entity.getRuleDimension()));
        view.setGranularity(entity.getGranularity() == null ? null : QualityRuleGranularity.valueOf(entity.getGranularity()));
        view.setDatasourceId(entity.getDatasourceId());
        view.setDatasourceName(entity.getDatasourceNameSnapshot());
        view.setDatasourceTypeCode(entity.getDatasourceTypeCode());
        view.setModelId(entity.getModelId());
        view.setModelName(entity.getModelNameSnapshot());
        view.setModelPhysicalLocator(entity.getModelPhysicalLocator());
        view.setColumnName(entity.getColumnName());
        view.setWhereClause(entity.getWhereClause());
        view.setResolvedSqlPreview(entity.getResolvedSqlPreview());
        view.setParameterBindings(loadParameterBindings(entity.getParameterBindingsJson()));
        view.setRuleSnapshot(entity.getRuleSnapshotJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(entity.getRuleSnapshotJson()));
        QualityRuleView snapshot = view.getRuleSnapshot().isEmpty()
                ? null
                : objectMapper.convertValue(view.getRuleSnapshot(), QualityRuleView.class);
        view.setOutputParams(snapshot == null || snapshot.getOutputParams() == null
                ? new ArrayList<QualityRuleOutputParamView>()
                : new ArrayList<QualityRuleOutputParamView>(snapshot.getOutputParams()));
        view.setAlertConfigs(loadAlertConfigs(entity.getId()));
        view.setSchedule(loadSchedule(entity.getId()));
        return view;
    }

    private QualityTaskListView toListView(QualityTaskDefinitionEntity entity) {
        QualityTaskListView view = new QualityTaskListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null : runtimeClusterSelectionService.runtimeClusterName(entity.getProjectId(), entity.getRuntimeClusterId()));
        view.setCreatedBy(entity.getCreatedBy());
        view.setTaskName(entity.getTaskName());
        view.setTaskCode(entity.getTaskCode());
        view.setStatus(entity.getStatus() == null ? null : QualityTaskStatus.valueOf(entity.getStatus()));
        view.setRuleId(entity.getRuleId());
        view.setRuleName(entity.getRuleNameSnapshot());
        view.setRuleDimension(entity.getRuleDimension() == null ? null : com.jdragon.studio.dto.enums.QualityRuleDimension.valueOf(entity.getRuleDimension()));
        view.setGranularity(entity.getGranularity() == null ? null : QualityRuleGranularity.valueOf(entity.getGranularity()));
        view.setDatasourceId(entity.getDatasourceId());
        view.setDatasourceName(entity.getDatasourceNameSnapshot());
        view.setDatasourceTypeCode(entity.getDatasourceTypeCode());
        view.setModelId(entity.getModelId());
        view.setModelName(entity.getModelNameSnapshot());
        view.setModelPhysicalLocator(entity.getModelPhysicalLocator());
        view.setColumnName(entity.getColumnName());
        return view;
    }

    private QualityTaskOptionView toOptionView(QualityTaskDefinitionEntity entity) {
        QualityTaskOptionView view = new QualityTaskOptionView();
        view.setId(entity.getId());
        view.setProjectId(entity.getProjectId());
        view.setTaskName(entity.getTaskName());
        view.setRuleName(entity.getRuleNameSnapshot());
        view.setRuleDimension(entity.getRuleDimension());
        view.setGranularity(entity.getGranularity());
        return view;
    }

    private QualityTaskWorkflowOptionView toWorkflowOptionView(QualityTaskDefinitionEntity entity) {
        QualityTaskWorkflowOptionView view = new QualityTaskWorkflowOptionView();
        view.setId(entity.getId());
        view.setProjectId(entity.getProjectId());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null : runtimeClusterSelectionService.runtimeClusterName(entity.getProjectId(), entity.getRuntimeClusterId()));
        view.setTaskName(entity.getTaskName());
        view.setTaskCode(entity.getTaskCode());
        view.setRuleId(entity.getRuleId());
        view.setRuleName(entity.getRuleNameSnapshot());
        view.setRuleDimension(entity.getRuleDimension());
        view.setGranularity(entity.getGranularity());
        view.setDatasourceId(entity.getDatasourceId());
        view.setDatasourceName(entity.getDatasourceNameSnapshot());
        view.setModelId(entity.getModelId());
        view.setModelName(entity.getModelNameSnapshot());
        view.setColumnName(entity.getColumnName());
        return view;
    }

    private LambdaQueryWrapper<QualityTaskDefinitionEntity> selectTaskListColumns(LambdaQueryWrapper<QualityTaskDefinitionEntity> queryWrapper) {
        return queryWrapper.select(QualityTaskDefinitionEntity::getId,
                QualityTaskDefinitionEntity::getTenantId,
                QualityTaskDefinitionEntity::getProjectId,
                QualityTaskDefinitionEntity::getDeleted,
                QualityTaskDefinitionEntity::getCreatedAt,
                QualityTaskDefinitionEntity::getUpdatedAt,
                QualityTaskDefinitionEntity::getRuntimeClusterId,
                QualityTaskDefinitionEntity::getCreatedBy,
                QualityTaskDefinitionEntity::getTaskName,
                QualityTaskDefinitionEntity::getTaskCode,
                QualityTaskDefinitionEntity::getStatus,
                QualityTaskDefinitionEntity::getRuleId,
                QualityTaskDefinitionEntity::getRuleNameSnapshot,
                QualityTaskDefinitionEntity::getRuleDimension,
                QualityTaskDefinitionEntity::getGranularity,
                QualityTaskDefinitionEntity::getDatasourceId,
                QualityTaskDefinitionEntity::getDatasourceNameSnapshot,
                QualityTaskDefinitionEntity::getDatasourceTypeCode,
                QualityTaskDefinitionEntity::getModelId,
                QualityTaskDefinitionEntity::getModelNameSnapshot,
                QualityTaskDefinitionEntity::getModelPhysicalLocator,
                QualityTaskDefinitionEntity::getColumnName);
    }

    private LambdaQueryWrapper<QualityTaskDefinitionEntity> selectWorkflowOptionColumns(LambdaQueryWrapper<QualityTaskDefinitionEntity> queryWrapper) {
        return queryWrapper.select(QualityTaskDefinitionEntity::getId,
                QualityTaskDefinitionEntity::getProjectId,
                QualityTaskDefinitionEntity::getRuntimeClusterId,
                QualityTaskDefinitionEntity::getUpdatedAt,
                QualityTaskDefinitionEntity::getTaskName,
                QualityTaskDefinitionEntity::getTaskCode,
                QualityTaskDefinitionEntity::getRuleId,
                QualityTaskDefinitionEntity::getRuleNameSnapshot,
                QualityTaskDefinitionEntity::getRuleDimension,
                QualityTaskDefinitionEntity::getGranularity,
                QualityTaskDefinitionEntity::getDatasourceId,
                QualityTaskDefinitionEntity::getDatasourceNameSnapshot,
                QualityTaskDefinitionEntity::getModelId,
                QualityTaskDefinitionEntity::getModelNameSnapshot,
                QualityTaskDefinitionEntity::getColumnName);
    }

    private List<QualityTaskParamBinding> loadParameterBindings(List<Map<String, Object>> items) {
        List<QualityTaskParamBinding> result = new ArrayList<QualityTaskParamBinding>();
        if (items == null) {
            return result;
        }
        for (Map<String, Object> item : items) {
            result.add(objectMapper.convertValue(item, QualityTaskParamBinding.class));
        }
        return result;
    }

    private List<QualityTaskAlertConfig> loadAlertConfigs(Long qualityTaskId) {
        List<QualityTaskAlertEntity> entities = alertMapper.selectList(new LambdaQueryWrapper<QualityTaskAlertEntity>()
                .eq(QualityTaskAlertEntity::getQualityTaskId, qualityTaskId)
                .orderByAsc(QualityTaskAlertEntity::getOutputOrder)
                .orderByAsc(QualityTaskAlertEntity::getId));
        List<QualityTaskAlertConfig> result = new ArrayList<QualityTaskAlertConfig>();
        for (QualityTaskAlertEntity entity : entities) {
            QualityTaskAlertConfig item = new QualityTaskAlertConfig();
            item.setOutputOrder(entity.getOutputOrder());
            item.setResultField(entity.getResultField());
            item.setOutputType(entity.getOutputType() == null ? null : QualityRuleOutputType.valueOf(entity.getOutputType()));
            item.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
            item.setOperator(entity.getOperator() == null ? null : QualityTaskAlertOperator.valueOf(entity.getOperator()));
            item.setExpectedValue(entity.getExpectedValue());
            item.setMinValue(entity.getMinValue());
            item.setMaxValue(entity.getMaxValue());
            result.add(item);
        }
        return result;
    }

    private CollectionTaskScheduleDefinition loadSchedule(Long qualityTaskId) {
        QualityTaskScheduleEntity entity = scheduleMapper.selectOne(new LambdaQueryWrapper<QualityTaskScheduleEntity>()
                .eq(QualityTaskScheduleEntity::getQualityTaskId, qualityTaskId)
                .last("limit 1"));
        if (entity == null) {
            return null;
        }
        CollectionTaskScheduleDefinition schedule = new CollectionTaskScheduleDefinition();
        schedule.setCronExpression(entity.getCronExpression());
        schedule.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        schedule.setTimezone(entity.getTimezone());
        return schedule;
    }

    private QualityTaskDefinitionEntity findAccessibleEntity(Long id) {
        if (id == null) {
            return null;
        }
        QualityTaskDefinitionEntity entity = definitionMapper.selectById(id);
        if (entity == null) {
            return null;
        }
        if (!securityService.currentTenantId().equals(entity.getTenantId())) {
            return null;
        }
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId != null
                && (entity.getProjectId() == null || currentProjectId.longValue() != entity.getProjectId().longValue())) {
            return null;
        }
        return entity;
    }

    private QualityTaskDefinitionEntity requireWritableEntity(Long id) {
        QualityTaskDefinitionEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Quality task not found: " + id);
        }
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private void validateSaveRequest(QualityTaskSaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Quality task request is required");
        }
        if (!hasText(request.getTaskName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Task name is required");
        }
        if (!hasText(request.getTaskCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Task code is required");
        }
        if (request.getRuleId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Rule id is required");
        }
        if (request.getGranularity() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Granularity is required");
        }
        if (request.getDatasourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource is required");
        }
        if (request.getModelId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model is required");
        }
    }

    private DataSourceDefinition requireSqlDatasource(Long datasourceId) {
        DataSourceDefinition datasource = dataSourceService.get(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        if (!datasourceTypeCapabilityService.isSqlExecutable(datasource.getTypeCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Selected datasource does not support SQL quality checks");
        }
        return datasource;
    }

    private void ensureModelBelongsToDatasource(DataModelDefinition model, DataSourceDefinition datasource) {
        if (model.getDatasourceId() == null || !String.valueOf(model.getDatasourceId()).equals(String.valueOf(datasource.getId()))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model does not belong to the selected datasource");
        }
    }

    private String normalizeColumnName(QualityRuleGranularity granularity, String columnName, DataModelDefinition model) {
        if (granularity != QualityRuleGranularity.COLUMN) {
            return null;
        }
        String normalized = normalizeNullableText(columnName);
        if (!hasText(normalized)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Column is required for column-level quality tasks");
        }
        List<String> availableColumns = resolveModelColumns(model);
        if (!availableColumns.isEmpty() && !availableColumns.contains(normalized)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Selected column does not exist in the target model");
        }
        return normalized;
    }

    private List<String> resolveModelColumns(DataModelDefinition model) {
        List<String> result = new ArrayList<String>();
        if (model == null || model.getTechnicalMetadata() == null) {
            return result;
        }
        Object columns = model.getTechnicalMetadata().get("columns");
        if (!(columns instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) columns) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> column = (Map<?, ?>) item;
            String[] candidateKeys = new String[]{"name", "columnName", "fieldName", "physicalName"};
            for (String candidateKey : candidateKeys) {
                Object value = column.get(candidateKey);
                if (value != null && hasText(String.valueOf(value))) {
                    String normalized = normalizeText(String.valueOf(value));
                    if (!result.contains(normalized)) {
                        result.add(normalized);
                    }
                    break;
                }
            }
        }
        return result;
    }

    private void ensureUniqueTaskName(Long projectId, String taskName, Long selfId) {
        ensureUnique(projectId, taskName, selfId, true);
    }

    private void ensureUniqueTaskCode(Long projectId, String taskCode, Long selfId) {
        ensureUnique(projectId, taskCode, selfId, false);
    }

    private void ensureUnique(Long projectId, String value, Long selfId, boolean byName) {
        if (projectId == null || !hasText(value)) {
            return;
        }
        List<QualityTaskDefinitionEntity> duplicates = definitionMapper.selectList(new LambdaQueryWrapper<QualityTaskDefinitionEntity>()
                .eq(QualityTaskDefinitionEntity::getProjectId, projectId)
                .eq(byName, QualityTaskDefinitionEntity::getTaskName, normalizeText(value))
                .eq(!byName, QualityTaskDefinitionEntity::getTaskCode, normalizeText(value)));
        for (QualityTaskDefinitionEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    byName ? "Quality task name already exists in the current project"
                            : "Quality task code already exists in the current project");
        }
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toListOfMaps(List<QualityTaskParamBinding> items) {
        return items == null ? new ArrayList<Map<String, Object>>() : objectMapper.convertValue(items, ArrayList.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return value == null ? new LinkedHashMap<String, Object>() : objectMapper.convertValue(value, LinkedHashMap.class);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalizeText(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeNullableText(String value) {
        String normalized = normalizeText(value);
        return hasText(normalized) ? normalized : null;
    }
}
