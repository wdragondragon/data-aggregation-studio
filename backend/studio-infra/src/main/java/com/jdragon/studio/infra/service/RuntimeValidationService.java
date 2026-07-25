package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DatasourceClusterBindingImpactView;
import com.jdragon.studio.dto.model.BaseDefinition;
import com.jdragon.studio.dto.model.RuntimeValidationView;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.DatasourceClusterBindingEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
import com.jdragon.studio.infra.entity.ProjectRuntimeClusterEntity;
import com.jdragon.studio.infra.entity.QualityTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeValidationEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.entity.WorkflowVersionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.ProjectRuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RuntimeValidationMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkflowVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RuntimeValidationService {
    public static final String ISSUE_DATASOURCE_CLUSTER_REMOVED = "DATASOURCE_CLUSTER_BINDING_REMOVED";
    public static final String ISSUE_RUNTIME_CLUSTER_UNAVAILABLE = "RUNTIME_CLUSTER_UNAVAILABLE";
    public static final String ISSUE_PROJECT_CLUSTER_UNAUTHORIZED = "PROJECT_RUNTIME_CLUSTER_UNAUTHORIZED";

    private final RuntimeValidationMapper validationMapper;
    private final DatasourceClusterBindingMapper bindingMapper;
    private final CollectionTaskDefinitionMapper collectionTaskMapper;
    private final QualityTaskDefinitionMapper qualityTaskMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final DataDevelopmentScriptMapper scriptMapper;
    private final DataServiceDefinitionMapper dataServiceMapper;
    private final DataIngestionServiceMapper ingestionMapper;
    private final ProtocolConversionServiceMapper protocolMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private DataModelMapper dataModelMapper;
    private RuntimeClusterMapper runtimeClusterMapper;
    private ProjectRuntimeClusterMapper projectRuntimeClusterMapper;

    public RuntimeValidationService(RuntimeValidationMapper validationMapper,
                                    DatasourceClusterBindingMapper bindingMapper,
                                    CollectionTaskDefinitionMapper collectionTaskMapper,
                                    QualityTaskDefinitionMapper qualityTaskMapper,
                                    WorkflowDefinitionMapper workflowDefinitionMapper,
                                    WorkflowVersionMapper workflowVersionMapper,
                                    DataDevelopmentScriptMapper scriptMapper,
                                    DataServiceDefinitionMapper dataServiceMapper,
                                    DataIngestionServiceMapper ingestionMapper,
                                    ProtocolConversionServiceMapper protocolMapper,
                                    StudioSecurityService securityService,
                                    ProjectResourceAccessService projectResourceAccessService) {
        this.validationMapper = validationMapper;
        this.bindingMapper = bindingMapper;
        this.collectionTaskMapper = collectionTaskMapper;
        this.qualityTaskMapper = qualityTaskMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.workflowVersionMapper = workflowVersionMapper;
        this.scriptMapper = scriptMapper;
        this.dataServiceMapper = dataServiceMapper;
        this.ingestionMapper = ingestionMapper;
        this.protocolMapper = protocolMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    @Autowired
    void setDataModelMapper(DataModelMapper dataModelMapper) {
        this.dataModelMapper = dataModelMapper;
    }

    @Autowired
    void setRuntimePlacementMappers(RuntimeClusterMapper runtimeClusterMapper,
                                    ProjectRuntimeClusterMapper projectRuntimeClusterMapper) {
        this.runtimeClusterMapper = runtimeClusterMapper;
        this.projectRuntimeClusterMapper = projectRuntimeClusterMapper;
    }

    public DatasourceClusterBindingImpactView previewDatasourceBindingImpact(Long datasourceId,
                                                                             Collection<Long> desiredClusterIds) {
        projectResourceAccessService.requireCurrentProjectId();
        String tenantId = securityService.currentTenantId();
        Set<Long> removedClusterIds = activeClusterIds(tenantId, datasourceId);
        removedClusterIds.removeAll(normalize(desiredClusterIds));
        DatasourceClusterBindingImpactView impact = new DatasourceClusterBindingImpactView();
        impact.setDatasourceId(datasourceId);
        impact.setRemovedClusterIds(new ArrayList<Long>(removedClusterIds));
        if (datasourceId == null || removedClusterIds.isEmpty()) {
            return impact;
        }
        collectCollectionTasks(impact, tenantId, datasourceId, removedClusterIds);
        collectQualityTasks(impact, tenantId, datasourceId, removedClusterIds);
        collectWorkflows(impact, tenantId, datasourceId, removedClusterIds);
        collectScripts(impact, tenantId, datasourceId, removedClusterIds);
        collectDataServices(impact, tenantId, datasourceId, removedClusterIds);
        collectIngestionServices(impact, tenantId, datasourceId, removedClusterIds);
        collectProtocolServices(impact, tenantId, datasourceId, removedClusterIds);
        return impact;
    }

    public List<RuntimeValidationView> queryInvalid(String resourceType, Collection<Long> resourceIds) {
        LambdaQueryWrapper<RuntimeValidationEntity> query = new LambdaQueryWrapper<RuntimeValidationEntity>()
                .eq(RuntimeValidationEntity::getTenantId, securityService.currentTenantId())
                .eq(RuntimeValidationEntity::getProjectId, projectResourceAccessService.requireCurrentProjectId())
                .eq(RuntimeValidationEntity::getValid, 0)
                .eq(resourceType != null && !resourceType.trim().isEmpty(),
                        RuntimeValidationEntity::getResourceType,
                        resourceType == null ? null : resourceType.trim().toUpperCase(Locale.ROOT))
                .orderByAsc(RuntimeValidationEntity::getResourceType)
                .orderByAsc(RuntimeValidationEntity::getResourceId);
        Set<Long> normalizedResourceIds = normalize(resourceIds);
        if (!normalizedResourceIds.isEmpty()) {
            query.in(RuntimeValidationEntity::getResourceId, normalizedResourceIds);
        }
        List<RuntimeValidationView> result = new ArrayList<RuntimeValidationView>();
        for (RuntimeValidationEntity entity : validationMapper.selectList(query)) {
            result.add(toView(entity));
        }
        return result;
    }

    public <T extends BaseDefinition> T hydrate(String resourceType, T view) {
        if (view == null) {
            return null;
        }
        List<T> views = new ArrayList<T>();
        views.add(view);
        hydrate(resourceType, views);
        return view;
    }

    public <T extends BaseDefinition> List<T> hydrate(String resourceType, List<T> views) {
        if (views == null || views.isEmpty()) {
            return views;
        }
        List<Long> resourceIds = new ArrayList<Long>();
        for (T view : views) {
            if (view != null) {
                view.setRuntimeValid(Boolean.TRUE);
                view.setRuntimeValidationMessage(null);
                if (view.getId() != null) {
                    resourceIds.add(view.getId());
                }
            }
        }
        if (resourceIds.isEmpty()) {
            return views;
        }
        Map<String, RuntimeValidationEntity> invalidByResource = new LinkedHashMap<String, RuntimeValidationEntity>();
        List<RuntimeValidationEntity> invalidRows = validationMapper.selectList(new LambdaQueryWrapper<RuntimeValidationEntity>()
                .eq(RuntimeValidationEntity::getTenantId, securityService.currentTenantId())
                .eq(RuntimeValidationEntity::getResourceType, resourceType)
                .in(RuntimeValidationEntity::getResourceId, resourceIds)
                .eq(RuntimeValidationEntity::getValid, 0));
        for (RuntimeValidationEntity invalid : invalidRows) {
            invalidByResource.put(key(invalid.getProjectId(), invalid.getResourceType(), invalid.getResourceId()), invalid);
        }
        for (T view : views) {
            RuntimeValidationEntity invalid = view == null ? null
                    : invalidByResource.get(key(view.getProjectId(), resourceType, view.getId()));
            if (invalid != null) {
                view.setRuntimeValid(Boolean.FALSE);
                view.setRuntimeValidationMessage(invalid.getIssueMessage());
            }
        }
        return views;
    }

    @Transactional
    public void applyDatasourceBindingImpact(DatasourceClusterBindingImpactView impact) {
        if (impact == null || impact.getDatasourceId() == null) {
            return;
        }
        String tenantId = securityService.currentTenantId();
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        Map<String, RuntimeValidationEntity> candidates = new LinkedHashMap<String, RuntimeValidationEntity>();
        for (RuntimeValidationView affected : impact.getAffectedResources()) {
            Long projectId = affected.getProjectId() == null ? currentProjectId : affected.getProjectId();
            RuntimeValidationEntity entity = validationMapper.selectOne(new LambdaQueryWrapper<RuntimeValidationEntity>()
                    .eq(RuntimeValidationEntity::getTenantId, tenantId)
                    .eq(RuntimeValidationEntity::getProjectId, projectId)
                    .eq(RuntimeValidationEntity::getResourceType, affected.getResourceType())
                    .eq(RuntimeValidationEntity::getResourceId, affected.getResourceId())
                    .last("limit 1"));
            if (entity == null) {
                entity = new RuntimeValidationEntity();
                entity.setTenantId(tenantId);
                entity.setProjectId(projectId);
                entity.setResourceType(affected.getResourceType());
                entity.setResourceId(affected.getResourceId());
            }
            entity.setRuntimeClusterId(affected.getRuntimeClusterId());
            candidates.put(key(projectId, affected.getResourceType(), affected.getResourceId()), entity);
        }
        List<RuntimeValidationEntity> previous = validationMapper.selectList(new LambdaQueryWrapper<RuntimeValidationEntity>()
                .eq(RuntimeValidationEntity::getTenantId, tenantId)
                .eq(RuntimeValidationEntity::getIssueCode, ISSUE_DATASOURCE_CLUSTER_REMOVED));
        for (RuntimeValidationEntity entity : previous) {
            if (referencesDatasource(entity, impact.getDatasourceId())) {
                candidates.put(key(entity.getProjectId(), entity.getResourceType(), entity.getResourceId()), entity);
            }
        }
        for (RuntimeValidationEntity entity : candidates.values()) {
            recomputeValidation(tenantId, entity);
        }
    }

    public void assertResourceValid(String resourceType, Long resourceId) {
        if (resourceType == null || resourceId == null) {
            return;
        }
        RuntimeValidationEntity validation = validationMapper.selectOne(new LambdaQueryWrapper<RuntimeValidationEntity>()
                .eq(RuntimeValidationEntity::getTenantId, securityService.currentTenantId())
                .eq(RuntimeValidationEntity::getResourceType, resourceType)
                .eq(RuntimeValidationEntity::getResourceId, resourceId)
                .eq(RuntimeValidationEntity::getValid, 0)
                .last("limit 1"));
        if (validation != null) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    validation.getIssueMessage() == null ? "Runtime configuration is invalid" : validation.getIssueMessage());
        }
    }

    @Transactional
    public void markResourceValid(String resourceType, Long resourceId) {
        if (resourceType == null || resourceId == null) {
            return;
        }
        List<RuntimeValidationEntity> validations = validationMapper.selectList(new LambdaQueryWrapper<RuntimeValidationEntity>()
                .eq(RuntimeValidationEntity::getTenantId, securityService.currentTenantId())
                .eq(RuntimeValidationEntity::getProjectId, projectResourceAccessService.requireCurrentProjectId())
                .eq(RuntimeValidationEntity::getResourceType, resourceType)
                .eq(RuntimeValidationEntity::getResourceId, resourceId));
        for (RuntimeValidationEntity validation : validations) {
            validation.setValid(1);
            validation.setIssueCode(null);
            validation.setIssueMessage(null);
            validation.setDetailsJson(new LinkedHashMap<String, Object>());
            validation.setValidatedAt(LocalDateTime.now());
            validationMapper.updateById(validation);
        }
    }

    @Transactional
    public void revalidatePlacementScope(String tenantId, Long projectId, Long runtimeClusterId) {
        if (tenantId == null || runtimeClusterId == null) return;
        revalidateCollectionTasks(tenantId, projectId, runtimeClusterId);
        revalidateQualityTasks(tenantId, projectId, runtimeClusterId);
        revalidateWorkflows(tenantId, projectId, runtimeClusterId);
        revalidateScripts(tenantId, projectId, runtimeClusterId);
        revalidateDataServices(tenantId, projectId, runtimeClusterId);
        revalidateIngestionServices(tenantId, projectId, runtimeClusterId);
        revalidateProtocolServices(tenantId, projectId, runtimeClusterId);
    }

    private void revalidateCollectionTasks(String tenantId, Long projectId, Long clusterId) {
        LambdaQueryWrapper<CollectionTaskDefinitionEntity> query = new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
                .eq(CollectionTaskDefinitionEntity::getTenantId, tenantId)
                .eq(CollectionTaskDefinitionEntity::getRuntimeClusterId, clusterId)
                .eq(projectId != null, CollectionTaskDefinitionEntity::getProjectId, projectId);
        for (CollectionTaskDefinitionEntity entity : collectionTaskMapper.selectList(query)) {
            revalidateResource(tenantId, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_COLLECTION_TASK,
                    entity.getId(), clusterId);
        }
    }

    private void revalidateQualityTasks(String tenantId, Long projectId, Long clusterId) {
        LambdaQueryWrapper<QualityTaskDefinitionEntity> query = new LambdaQueryWrapper<QualityTaskDefinitionEntity>()
                .eq(QualityTaskDefinitionEntity::getTenantId, tenantId)
                .eq(QualityTaskDefinitionEntity::getRuntimeClusterId, clusterId)
                .eq(projectId != null, QualityTaskDefinitionEntity::getProjectId, projectId);
        for (QualityTaskDefinitionEntity entity : qualityTaskMapper.selectList(query)) {
            revalidateResource(tenantId, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_QUALITY_TASK,
                    entity.getId(), clusterId);
        }
    }

    private void revalidateWorkflows(String tenantId, Long projectId, Long clusterId) {
        LambdaQueryWrapper<WorkflowDefinitionEntity> query = new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .eq(WorkflowDefinitionEntity::getTenantId, tenantId)
                .eq(WorkflowDefinitionEntity::getRuntimeClusterId, clusterId)
                .eq(projectId != null, WorkflowDefinitionEntity::getProjectId, projectId);
        for (WorkflowDefinitionEntity entity : workflowDefinitionMapper.selectList(query)) {
            revalidateResource(tenantId, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_WORKFLOW,
                    entity.getId(), clusterId);
        }
    }

    private void revalidateScripts(String tenantId, Long projectId, Long clusterId) {
        LambdaQueryWrapper<DataDevelopmentScriptEntity> query = new LambdaQueryWrapper<DataDevelopmentScriptEntity>()
                .eq(DataDevelopmentScriptEntity::getTenantId, tenantId)
                .eq(DataDevelopmentScriptEntity::getRuntimeClusterId, clusterId)
                .eq(projectId != null, DataDevelopmentScriptEntity::getProjectId, projectId);
        for (DataDevelopmentScriptEntity entity : scriptMapper.selectList(query)) {
            revalidateResource(tenantId, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT,
                    entity.getId(), clusterId);
        }
    }

    private void revalidateDataServices(String tenantId, Long projectId, Long clusterId) {
        LambdaQueryWrapper<DataServiceDefinitionEntity> query = new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                .eq(DataServiceDefinitionEntity::getTenantId, tenantId)
                .eq(DataServiceDefinitionEntity::getRuntimeClusterId, clusterId)
                .eq(projectId != null, DataServiceDefinitionEntity::getProjectId, projectId);
        for (DataServiceDefinitionEntity entity : dataServiceMapper.selectList(query)) {
            revalidateResource(tenantId, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_DATA_SERVICE,
                    entity.getId(), clusterId);
        }
    }

    private void revalidateIngestionServices(String tenantId, Long projectId, Long clusterId) {
        LambdaQueryWrapper<DataIngestionServiceEntity> query = new LambdaQueryWrapper<DataIngestionServiceEntity>()
                .eq(DataIngestionServiceEntity::getTenantId, tenantId)
                .eq(DataIngestionServiceEntity::getRuntimeClusterId, clusterId)
                .eq(projectId != null, DataIngestionServiceEntity::getProjectId, projectId);
        for (DataIngestionServiceEntity entity : ingestionMapper.selectList(query)) {
            revalidateResource(tenantId, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE,
                    entity.getId(), clusterId);
        }
    }

    private void revalidateProtocolServices(String tenantId, Long projectId, Long clusterId) {
        LambdaQueryWrapper<ProtocolConversionServiceEntity> query = new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                .eq(ProtocolConversionServiceEntity::getTenantId, tenantId)
                .eq(ProtocolConversionServiceEntity::getRuntimeClusterId, clusterId)
                .eq(projectId != null, ProtocolConversionServiceEntity::getProjectId, projectId);
        for (ProtocolConversionServiceEntity entity : protocolMapper.selectList(query)) {
            revalidateResource(tenantId, entity.getProjectId(),
                    StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE, entity.getId(), clusterId);
        }
    }

    private void revalidateResource(String tenantId, Long projectId, String resourceType,
                                    Long resourceId, Long runtimeClusterId) {
        RuntimeValidationEntity validation = validationMapper.selectOne(new LambdaQueryWrapper<RuntimeValidationEntity>()
                .eq(RuntimeValidationEntity::getTenantId, tenantId)
                .eq(RuntimeValidationEntity::getProjectId, projectId)
                .eq(RuntimeValidationEntity::getResourceType, resourceType)
                .eq(RuntimeValidationEntity::getResourceId, resourceId)
                .last("limit 1"));
        if (validation == null) {
            validation = new RuntimeValidationEntity();
            validation.setTenantId(tenantId);
            validation.setProjectId(projectId);
            validation.setResourceType(resourceType);
            validation.setResourceId(resourceId);
            validation.setRuntimeClusterId(runtimeClusterId);
        }
        recomputeValidation(tenantId, validation);
    }

    private void collectCollectionTasks(DatasourceClusterBindingImpactView impact, String tenantId,
                                        Long datasourceId, Set<Long> removedClusterIds) {
        List<CollectionTaskDefinitionEntity> entities = collectionTaskMapper.selectList(new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
                .eq(CollectionTaskDefinitionEntity::getTenantId, tenantId)
                .in(CollectionTaskDefinitionEntity::getRuntimeClusterId, removedClusterIds));
        for (CollectionTaskDefinitionEntity entity : entities) {
            if (containsDatasource(entity.getSourceBindingsJson(), datasourceId) || containsDatasource(entity.getTargetBindingJson(), datasourceId)) {
                add(impact, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, entity.getId(), entity.getName(), entity.getRuntimeClusterId());
            }
        }
    }

    private void collectQualityTasks(DatasourceClusterBindingImpactView impact, String tenantId,
                                     Long datasourceId, Set<Long> removedClusterIds) {
        for (QualityTaskDefinitionEntity entity : qualityTaskMapper.selectList(new LambdaQueryWrapper<QualityTaskDefinitionEntity>()
                .eq(QualityTaskDefinitionEntity::getTenantId, tenantId)
                .eq(QualityTaskDefinitionEntity::getDatasourceId, datasourceId)
                .in(QualityTaskDefinitionEntity::getRuntimeClusterId, removedClusterIds))) {
            add(impact, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_QUALITY_TASK, entity.getId(), entity.getTaskName(), entity.getRuntimeClusterId());
        }
    }

    private void collectWorkflows(DatasourceClusterBindingImpactView impact, String tenantId,
                                  Long datasourceId, Set<Long> removedClusterIds) {
        List<WorkflowDefinitionEntity> definitions = workflowDefinitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .eq(WorkflowDefinitionEntity::getTenantId, tenantId)
                .in(WorkflowDefinitionEntity::getRuntimeClusterId, removedClusterIds));
        for (WorkflowDefinitionEntity definition : definitions) {
            if (definition.getCurrentVersionId() == null) {
                continue;
            }
            WorkflowVersionEntity version = workflowVersionMapper.selectById(definition.getCurrentVersionId());
            if (version != null && workflowDatasourceIds(tenantId, version.getGraphJson()).contains(datasourceId)) {
                add(impact, definition.getProjectId(), StudioConstants.RESOURCE_TYPE_WORKFLOW, definition.getId(), definition.getName(), definition.getRuntimeClusterId());
            }
        }
    }

    private void collectScripts(DatasourceClusterBindingImpactView impact, String tenantId,
                                Long datasourceId, Set<Long> removedClusterIds) {
        for (DataDevelopmentScriptEntity entity : scriptMapper.selectList(new LambdaQueryWrapper<DataDevelopmentScriptEntity>()
                .eq(DataDevelopmentScriptEntity::getTenantId, tenantId)
                .in(DataDevelopmentScriptEntity::getRuntimeClusterId, removedClusterIds))) {
            if (scriptDatasourceIds(tenantId, entity).contains(datasourceId)) {
                add(impact, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT,
                        entity.getId(), entity.getFileName(), entity.getRuntimeClusterId());
            }
        }
    }

    private void collectDataServices(DatasourceClusterBindingImpactView impact, String tenantId,
                                     Long datasourceId, Set<Long> removedClusterIds) {
        for (DataServiceDefinitionEntity entity : dataServiceMapper.selectList(new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                .eq(DataServiceDefinitionEntity::getTenantId, tenantId)
                .eq(DataServiceDefinitionEntity::getDatasourceId, datasourceId)
                .in(DataServiceDefinitionEntity::getRuntimeClusterId, removedClusterIds))) {
            add(impact, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_DATA_SERVICE, entity.getId(), entity.getServiceName(), entity.getRuntimeClusterId());
        }
    }

    private void collectIngestionServices(DatasourceClusterBindingImpactView impact, String tenantId,
                                          Long datasourceId, Set<Long> removedClusterIds) {
        List<DataIngestionServiceEntity> entities = ingestionMapper.selectList(new LambdaQueryWrapper<DataIngestionServiceEntity>()
                .eq(DataIngestionServiceEntity::getTenantId, tenantId)
                .in(DataIngestionServiceEntity::getRuntimeClusterId, removedClusterIds));
        for (DataIngestionServiceEntity entity : entities) {
            if (datasourceId.equals(entity.getDatasourceId()) || containsDatasource(entity.getSourceBindingsJson(), datasourceId)) {
                add(impact, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE, entity.getId(), entity.getServiceName(), entity.getRuntimeClusterId());
            }
        }
    }

    private void collectProtocolServices(DatasourceClusterBindingImpactView impact, String tenantId,
                                         Long datasourceId, Set<Long> removedClusterIds) {
        for (ProtocolConversionServiceEntity entity : protocolMapper.selectList(new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                .eq(ProtocolConversionServiceEntity::getTenantId, tenantId)
                .eq(ProtocolConversionServiceEntity::getTargetDatasourceId, datasourceId)
                .in(ProtocolConversionServiceEntity::getRuntimeClusterId, removedClusterIds))) {
            add(impact, entity.getProjectId(), StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE, entity.getId(), entity.getServiceName(), entity.getRuntimeClusterId());
        }
    }

    private void add(DatasourceClusterBindingImpactView impact, Long projectId, String type, Long id, String name, Long clusterId) {
        RuntimeValidationView item = new RuntimeValidationView();
        item.setProjectId(projectId);
        item.setResourceType(type);
        item.setResourceId(id);
        item.setResourceName(name);
        item.setRuntimeClusterId(clusterId);
        item.setIssueCode(ISSUE_DATASOURCE_CLUSTER_REMOVED);
        item.setIssueMessage("Datasource is no longer applicable to the resource runtime cluster");
        impact.getAffectedResources().add(item);
    }

    private void recomputeValidation(String tenantId, RuntimeValidationEntity validation) {
        ResourceRuntimeState state = resolveResourceRuntimeState(tenantId, validation.getResourceType(), validation.getResourceId());
        if (state == null || state.runtimeClusterId == null) {
            markValidationValid(validation);
            return;
        }
        String placementIssue = placementIssue(tenantId, state.projectId, state.runtimeClusterId);
        if (placementIssue != null) {
            validation.setProjectId(state.projectId);
            validation.setRuntimeClusterId(state.runtimeClusterId);
            validation.setValid(0);
            validation.setIssueCode(placementIssue.contains("not authorized")
                    ? ISSUE_PROJECT_CLUSTER_UNAUTHORIZED : ISSUE_RUNTIME_CLUSTER_UNAVAILABLE);
            validation.setIssueMessage(placementIssue);
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("runtimeClusterId", state.runtimeClusterId);
            details.put("projectId", state.projectId);
            validation.setDetailsJson(details);
            validation.setValidatedAt(LocalDateTime.now());
            persistValidation(validation);
            return;
        }
        List<Long> missingDatasourceIds = new ArrayList<Long>();
        for (Long datasourceId : state.datasourceIds) {
            Long count = bindingMapper.selectCount(new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                    .eq(DatasourceClusterBindingEntity::getTenantId, tenantId)
                    .eq(DatasourceClusterBindingEntity::getDatasourceId, datasourceId)
                    .eq(DatasourceClusterBindingEntity::getRuntimeClusterId, state.runtimeClusterId)
                    .eq(DatasourceClusterBindingEntity::getEnabled, 1));
            if (count == null || count.longValue() == 0L) {
                missingDatasourceIds.add(datasourceId);
            }
        }
        validation.setProjectId(state.projectId);
        validation.setRuntimeClusterId(state.runtimeClusterId);
        validation.setValidatedAt(LocalDateTime.now());
        if (missingDatasourceIds.isEmpty()) {
            markValidationValid(validation);
            return;
        }
        validation.setValid(0);
        validation.setIssueCode(ISSUE_DATASOURCE_CLUSTER_REMOVED);
        validation.setIssueMessage("Datasource(s) " + missingDatasourceIds
                + " are not applicable to runtime cluster " + state.runtimeClusterId);
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("datasourceIds", new ArrayList<Long>(state.datasourceIds));
        details.put("missingDatasourceIds", missingDatasourceIds);
        if (missingDatasourceIds.size() == 1) {
            details.put("datasourceId", missingDatasourceIds.get(0));
        }
        validation.setDetailsJson(details);
        persistValidation(validation);
    }

    private String placementIssue(String tenantId, Long projectId, Long runtimeClusterId) {
        RuntimeClusterEntity cluster = runtimeClusterMapper.selectOne(new LambdaQueryWrapper<RuntimeClusterEntity>()
                .eq(RuntimeClusterEntity::getTenantId, tenantId)
                .eq(RuntimeClusterEntity::getId, runtimeClusterId)
                .last("limit 1"));
        if (cluster == null || !Integer.valueOf(1).equals(cluster.getEnabled())) {
            return "Runtime cluster is disabled or missing";
        }
        Long selectedCount = projectRuntimeClusterMapper.selectCount(
                new LambdaQueryWrapper<ProjectRuntimeClusterEntity>()
                        .eq(ProjectRuntimeClusterEntity::getTenantId, tenantId)
                        .eq(ProjectRuntimeClusterEntity::getProjectId, projectId)
                        .eq(ProjectRuntimeClusterEntity::getRuntimeClusterId, runtimeClusterId)
                        .eq(ProjectRuntimeClusterEntity::getEnabled, 1));
        if (selectedCount != null && selectedCount.longValue() > 0L) return null;

        return "Runtime cluster is not authorized for this project";
    }

    private void markValidationValid(RuntimeValidationEntity validation) {
        if (validation.getId() == null) {
            return;
        }
        validation.setValid(1);
        validation.setIssueCode(null);
        validation.setIssueMessage(null);
        validation.setDetailsJson(new LinkedHashMap<String, Object>());
        validation.setValidatedAt(LocalDateTime.now());
        validationMapper.updateById(validation);
    }

    private void persistValidation(RuntimeValidationEntity validation) {
        if (validation.getId() == null) {
            validationMapper.insert(validation);
        } else {
            validationMapper.updateById(validation);
        }
    }

    private ResourceRuntimeState resolveResourceRuntimeState(String tenantId, String resourceType, Long resourceId) {
        if (resourceType == null || resourceId == null) {
            return null;
        }
        ResourceRuntimeState state = new ResourceRuntimeState();
        if (StudioConstants.RESOURCE_TYPE_COLLECTION_TASK.equals(resourceType)) {
            CollectionTaskDefinitionEntity entity = collectionTaskMapper.selectById(resourceId);
            if (!sameTenant(tenantId, entity == null ? null : entity.getTenantId())) return null;
            state.projectId = entity.getProjectId(); state.runtimeClusterId = entity.getRuntimeClusterId();
            collectDatasourceIds(entity.getSourceBindingsJson(), state.datasourceIds);
            collectDatasourceIds(entity.getTargetBindingJson(), state.datasourceIds);
        } else if (StudioConstants.RESOURCE_TYPE_QUALITY_TASK.equals(resourceType)) {
            QualityTaskDefinitionEntity entity = qualityTaskMapper.selectById(resourceId);
            if (!sameTenant(tenantId, entity == null ? null : entity.getTenantId())) return null;
            state.projectId = entity.getProjectId(); state.runtimeClusterId = entity.getRuntimeClusterId();
            addDatasourceId(state.datasourceIds, entity.getDatasourceId());
        } else if (StudioConstants.RESOURCE_TYPE_WORKFLOW.equals(resourceType)) {
            WorkflowDefinitionEntity entity = workflowDefinitionMapper.selectById(resourceId);
            if (!sameTenant(tenantId, entity == null ? null : entity.getTenantId())) return null;
            state.projectId = entity.getProjectId(); state.runtimeClusterId = entity.getRuntimeClusterId();
            WorkflowVersionEntity version = entity.getCurrentVersionId() == null ? null
                    : workflowVersionMapper.selectById(entity.getCurrentVersionId());
            if (version != null) state.datasourceIds.addAll(workflowDatasourceIds(tenantId, version.getGraphJson()));
        } else if (StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT.equals(resourceType)) {
            DataDevelopmentScriptEntity entity = scriptMapper.selectById(resourceId);
            if (!sameTenant(tenantId, entity == null ? null : entity.getTenantId())) return null;
            state.projectId = entity.getProjectId(); state.runtimeClusterId = entity.getRuntimeClusterId();
            state.datasourceIds.addAll(scriptDatasourceIds(tenantId, entity));
        } else if (StudioConstants.RESOURCE_TYPE_DATA_SERVICE.equals(resourceType)) {
            DataServiceDefinitionEntity entity = dataServiceMapper.selectById(resourceId);
            if (!sameTenant(tenantId, entity == null ? null : entity.getTenantId())) return null;
            state.projectId = entity.getProjectId(); state.runtimeClusterId = entity.getRuntimeClusterId();
            addDatasourceId(state.datasourceIds, entity.getDatasourceId());
        } else if (StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE.equals(resourceType)) {
            DataIngestionServiceEntity entity = ingestionMapper.selectById(resourceId);
            if (!sameTenant(tenantId, entity == null ? null : entity.getTenantId())) return null;
            state.projectId = entity.getProjectId(); state.runtimeClusterId = entity.getRuntimeClusterId();
            addDatasourceId(state.datasourceIds, entity.getDatasourceId());
            collectDatasourceIds(entity.getSourceBindingsJson(), state.datasourceIds);
        } else if (StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE.equals(resourceType)) {
            ProtocolConversionServiceEntity entity = protocolMapper.selectById(resourceId);
            if (!sameTenant(tenantId, entity == null ? null : entity.getTenantId())) return null;
            state.projectId = entity.getProjectId(); state.runtimeClusterId = entity.getRuntimeClusterId();
            addDatasourceId(state.datasourceIds, entity.getTargetDatasourceId());
        } else {
            return null;
        }
        return state;
    }

    private Set<Long> workflowDatasourceIds(String tenantId, Object graph) {
        Set<Long> datasourceIds = new LinkedHashSet<Long>();
        collectDatasourceIds(graph, datasourceIds);

        Set<Long> collectionTaskIds = new LinkedHashSet<Long>();
        collectIdsByKey(graph, "collectionTaskId", collectionTaskIds);
        for (Long taskId : collectionTaskIds) {
            CollectionTaskDefinitionEntity task = collectionTaskMapper.selectById(taskId);
            if (!sameTenant(tenantId, task == null ? null : task.getTenantId())) continue;
            collectDatasourceIds(task.getSourceBindingsJson(), datasourceIds);
            collectDatasourceIds(task.getTargetBindingJson(), datasourceIds);
        }

        Set<Long> qualityTaskIds = new LinkedHashSet<Long>();
        collectIdsByKey(graph, "qualityTaskId", qualityTaskIds);
        for (Long taskId : qualityTaskIds) {
            QualityTaskDefinitionEntity task = qualityTaskMapper.selectById(taskId);
            if (sameTenant(tenantId, task == null ? null : task.getTenantId())) {
                addDatasourceId(datasourceIds, task.getDatasourceId());
            }
        }

        Set<Long> scriptIds = new LinkedHashSet<Long>();
        collectIdsByKey(graph, "scriptId", scriptIds);
        for (Long scriptId : scriptIds) {
            DataDevelopmentScriptEntity script = scriptMapper.selectById(scriptId);
            if (sameTenant(tenantId, script == null ? null : script.getTenantId())) {
                datasourceIds.addAll(scriptDatasourceIds(tenantId, script));
            }
        }
        return datasourceIds;
    }

    private Set<Long> scriptDatasourceIds(String tenantId, DataDevelopmentScriptEntity script) {
        Set<Long> datasourceIds = new LinkedHashSet<Long>();
        if (script == null) return datasourceIds;
        addDatasourceId(datasourceIds, script.getDatasourceId());
        if (dataModelMapper == null || script.getExecutionConfigJson() == null) return datasourceIds;
        Set<Long> modelIds = new LinkedHashSet<Long>();
        collectIdsByKey(script.getExecutionConfigJson(), "modelIds", modelIds);
        for (Long modelId : modelIds) {
            DataModelEntity model = dataModelMapper.selectById(modelId);
            if (sameTenant(tenantId, model == null ? null : model.getTenantId())) {
                addDatasourceId(datasourceIds, model.getDatasourceId());
            }
        }
        return datasourceIds;
    }

    private void collectIdsByKey(Object value, String expectedKey, Set<Long> target) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (expectedKey.equals(String.valueOf(entry.getKey()))) {
                    addDatasourceIds(target, entry.getValue());
                }
                collectIdsByKey(entry.getValue(), expectedKey, target);
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                collectIdsByKey(item, expectedKey, target);
            }
        }
    }

    private void collectDatasourceIds(Object value, Set<Long> result) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (key.contains("datasource")) {
                    addDatasourceIds(result, entry.getValue());
                }
                collectDatasourceIds(entry.getValue(), result);
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                collectDatasourceIds(item, result);
            }
        }
    }

    private void addDatasourceIds(Set<Long> result, Object value) {
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) addDatasourceIds(result, item);
            return;
        }
        if (value instanceof Map) {
            Object id = ((Map<?, ?>) value).get("id");
            addDatasourceId(result, asLong(id));
            return;
        }
        addDatasourceId(result, asLong(value));
    }

    private Long asLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void addDatasourceId(Set<Long> result, Long datasourceId) {
        if (datasourceId != null) result.add(datasourceId);
    }

    private boolean sameTenant(String expected, String actual) {
        return expected != null && expected.equals(actual);
    }

    private Set<Long> activeClusterIds(String tenantId, Long datasourceId) {
        Set<Long> result = new LinkedHashSet<Long>();
        if (datasourceId == null) {
            return result;
        }
        for (DatasourceClusterBindingEntity binding : bindingMapper.selectList(new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                .eq(DatasourceClusterBindingEntity::getTenantId, tenantId)
                .eq(DatasourceClusterBindingEntity::getDatasourceId, datasourceId)
                .eq(DatasourceClusterBindingEntity::getEnabled, 1))) {
            if (binding.getRuntimeClusterId() != null) {
                result.add(binding.getRuntimeClusterId());
            }
        }
        return result;
    }

    private Set<Long> normalize(Collection<Long> values) {
        Set<Long> result = new LinkedHashSet<Long>();
        if (values != null) {
            for (Long value : values) {
                if (value != null) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean containsDatasource(Object value, Long datasourceId) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (key.contains("datasource") && matchesId(entry.getValue(), datasourceId)) {
                    return true;
                }
                if (containsDatasource(entry.getValue(), datasourceId)) {
                    return true;
                }
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<Object>) value) {
                if (containsDatasource(item, datasourceId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesId(Object value, Long expected) {
        if (value instanceof Number) {
            return expected.equals(Long.valueOf(((Number) value).longValue()));
        }
        if (value instanceof String) {
            try {
                return expected.equals(Long.valueOf(((String) value).trim()));
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                if (matchesId(item, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean referencesDatasource(RuntimeValidationEntity entity, Long datasourceId) {
        if (entity.getDetailsJson() == null) {
            return false;
        }
        return matchesId(entity.getDetailsJson().get("datasourceId"), datasourceId)
                || matchesId(entity.getDetailsJson().get("datasourceIds"), datasourceId)
                || matchesId(entity.getDetailsJson().get("missingDatasourceIds"), datasourceId);
    }

    private String key(Long projectId, String resourceType, Long resourceId) {
        return projectId + ":" + resourceType + ":" + resourceId;
    }

    private RuntimeValidationView toView(RuntimeValidationEntity entity) {
        RuntimeValidationView view = new RuntimeValidationView();
        view.setProjectId(entity.getProjectId());
        view.setResourceType(entity.getResourceType());
        view.setResourceId(entity.getResourceId());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setValid(Integer.valueOf(1).equals(entity.getValid()));
        view.setIssueCode(entity.getIssueCode());
        view.setIssueMessage(entity.getIssueMessage());
        view.setDetails(entity.getDetailsJson() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(entity.getDetailsJson()));
        view.setValidatedAt(entity.getValidatedAt());
        return view;
    }

    private static final class ResourceRuntimeState {
        private Long projectId;
        private Long runtimeClusterId;
        private final Set<Long> datasourceIds = new LinkedHashSet<Long>();
    }
}
