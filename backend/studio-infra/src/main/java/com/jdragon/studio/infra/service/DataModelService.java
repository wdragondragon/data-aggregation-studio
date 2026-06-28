package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.ModelKind;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataModelDatasourceOptionView;
import com.jdragon.studio.dto.model.DataModelListView;
import com.jdragon.studio.dto.model.DataModelOptionView;
import com.jdragon.studio.dto.model.DataModelSqlHintView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.RunMetricFilterOptionView;
import com.jdragon.studio.dto.model.request.DataModelQueryCondition;
import com.jdragon.studio.dto.model.request.DataModelQueryGroup;
import com.jdragon.studio.dto.model.request.DataModelQueryRequest;
import com.jdragon.studio.dto.model.request.DataModelSaveRequest;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DataModelService {

    private static final Logger log = LoggerFactory.getLogger(DataModelService.class);
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 5000;
    private static final int MAX_OPTION_PAGE_SIZE = 100;

    private final DataModelMapper dataModelMapper;
    private final DataSourceService dataSourceService;
    private final AggregationSourceCapabilityProvider modelDiscoveryProvider;
    private final MetadataSchemaService metadataSchemaService;
    private final DataModelSearchIndexService dataModelSearchIndexService;
    private final DataModelIndexRebuildQueueService dataModelIndexRebuildQueueService;
    private final BusinessMetaModelMetadataService businessMetaModelMetadataService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final DataModelAccessScopeService dataModelAccessScopeService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final DataModelDefaultValueSupport defaultValueSupport = new DataModelDefaultValueSupport();

    public DataModelService(DataModelMapper dataModelMapper,
                            DataSourceService dataSourceService,
                            AggregationSourceCapabilityProvider modelDiscoveryProvider,
                            MetadataSchemaService metadataSchemaService,
                            DataModelSearchIndexService dataModelSearchIndexService,
                            DataModelIndexRebuildQueueService dataModelIndexRebuildQueueService,
                            BusinessMetaModelMetadataService businessMetaModelMetadataService,
                            StudioSecurityService securityService,
                            ProjectResourceAccessService projectResourceAccessService,
                            DataModelAccessScopeService dataModelAccessScopeService,
                            DatasourceTypeCapabilityService datasourceTypeCapabilityService) {
        this.dataModelMapper = dataModelMapper;
        this.dataSourceService = dataSourceService;
        this.modelDiscoveryProvider = modelDiscoveryProvider;
        this.metadataSchemaService = metadataSchemaService;
        this.dataModelSearchIndexService = dataModelSearchIndexService;
        this.dataModelIndexRebuildQueueService = dataModelIndexRebuildQueueService;
        this.businessMetaModelMetadataService = businessMetaModelMetadataService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.dataModelAccessScopeService = dataModelAccessScopeService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
    }

    public List<DataModelDefinition> list() {
        return listPage(null, DEFAULT_PAGE_NO, MAX_PAGE_SIZE).getItems();
    }

    public List<DataModelDefinition> listByDatasource(Long datasourceId) {
        return listByDatasourcePage(datasourceId, DEFAULT_PAGE_NO, MAX_PAGE_SIZE).getItems();
    }

    public DataModelDefinition get(Long modelId) {
        DataModelEntity entity = findAccessibleEntity(modelId);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Model not found: " + modelId);
        }
        return toDefinition(entity);
    }

    public List<DataModelDefinition> query(DataModelQueryRequest request) {
        return queryPage(request, request == null ? null : request.getPageNo(), request == null ? null : request.getPageSize()).getItems();
    }

    public PageView<DataModelDefinition> listPage(String datasourceType, Integer pageNo, Integer pageSize) {
        return listPage(datasourceType, pageNo, pageSize, null, null);
    }

    public PageView<DataModelDefinition> listPage(String datasourceType, Integer pageNo, Integer pageSize,
                                                  String sortField, String sortOrder) {
        return pageQuery(buildBaseQuery(null, datasourceType, null, sortField, sortOrder), pageNo, pageSize);
    }

    public PageView<DataModelDefinition> listByDatasourcePage(Long datasourceId, Integer pageNo, Integer pageSize) {
        return listByDatasourcePage(datasourceId, pageNo, pageSize, null, null);
    }

    public PageView<DataModelDefinition> listByDatasourcePage(Long datasourceId, Integer pageNo, Integer pageSize,
                                                              String sortField, String sortOrder) {
        dataSourceService.get(datasourceId);
        return pageQuery(buildBaseQuery(datasourceId, null, null, sortField, sortOrder), pageNo, pageSize);
    }

    public PageView<DataModelDefinition> queryPage(DataModelQueryRequest request, Integer pageNo, Integer pageSize) {
        if (request == null) {
            return pageQuery(buildBaseQuery(null, null, null), pageNo, pageSize);
        }
        List<DataModelQueryGroup> groups = normalizeQueryGroups(request.getGroups());
        if (groups.isEmpty()) {
            return pageQuery(buildBaseQuery(request.getDatasourceId(), request.getDatasourceType(), request.getModelKind(), request.getSortField(), request.getSortOrder()), pageNo, pageSize);
        }
        DataModelQueryRequest normalizedRequest = new DataModelQueryRequest();
        normalizedRequest.setDatasourceId(request.getDatasourceId());
        normalizedRequest.setDatasourceType(request.getDatasourceType());
        normalizedRequest.setModelKind(request.getModelKind());
        normalizedRequest.setGroups(groups);
        Set<Long> matchedIds = dataModelSearchIndexService.queryModelIds(normalizedRequest);
        if (matchedIds.isEmpty()) {
            return PageView.of(normalizePageNo(pageNo), normalizePageSize(pageSize), 0L, new ArrayList<DataModelDefinition>());
        }
        LambdaQueryWrapper<DataModelEntity> queryWrapper = buildBaseQuery(request.getDatasourceId(), request.getDatasourceType(), request.getModelKind(), request.getSortField(), request.getSortOrder())
                .in(DataModelEntity::getId, matchedIds);
        return pageQuery(queryWrapper, pageNo, pageSize);
    }

    public PageView<DataModelListView> listSummaryPage(String datasourceType, Integer pageNo, Integer pageSize,
                                                       String sortField, String sortOrder) {
        return summaryPageQuery(buildBaseQuery(null, datasourceType, null, sortField, sortOrder), pageNo, pageSize);
    }

    public PageView<DataModelOptionView> listOptions(String keyword, Integer pageNo, Integer pageSize) {
        LambdaQueryWrapper<DataModelEntity> queryWrapper = buildBaseQuery(null, null, null, "name", "asc");
        if (keyword != null && !keyword.trim().isEmpty()) {
            queryWrapper.like(DataModelEntity::getName, keyword.trim());
        }
        return optionPageQuery(queryWrapper, pageNo, pageSize);
    }

    public PageView<RunMetricFilterOptionView> listMetricFilterOptionPage(Long datasourceId,
                                                                          String keyword,
                                                                          Integer pageNo,
                                                                          Integer pageSize) {
        if (datasourceId != null) {
            dataSourceService.get(datasourceId);
        }
        LambdaQueryWrapper<DataModelEntity> queryWrapper = buildBaseQuery(datasourceId, null, null, "name", "asc");
        if (keyword != null && !keyword.trim().isEmpty()) {
            String normalizedKeyword = keyword.trim();
            queryWrapper.and(query -> query.like(DataModelEntity::getName, normalizedKeyword)
                    .or()
                    .like(DataModelEntity::getPhysicalLocator, normalizedKeyword));
        }
        return metricFilterOptionPageQuery(queryWrapper, pageNo, pageSize);
    }

    public PageView<DataModelListView> listByDatasourceSummaryPage(Long datasourceId, Integer pageNo, Integer pageSize,
                                                                   String sortField, String sortOrder) {
        dataSourceService.get(datasourceId);
        return summaryPageQuery(buildBaseQuery(datasourceId, null, null, sortField, sortOrder), pageNo, pageSize);
    }

    public PageView<DataModelDatasourceOptionView> listDatasourceOptions(Long datasourceId,
                                                                         String keyword,
                                                                         Integer pageNo,
                                                                         Integer pageSize) {
        dataSourceService.get(datasourceId);
        LambdaQueryWrapper<DataModelEntity> queryWrapper = buildBaseQuery(datasourceId, null, null, "name", "asc");
        if (keyword != null && !keyword.trim().isEmpty()) {
            String normalizedKeyword = keyword.trim();
            queryWrapper.and(query -> query.like(DataModelEntity::getName, normalizedKeyword)
                    .or()
                    .like(DataModelEntity::getPhysicalLocator, normalizedKeyword));
        }
        return datasourceOptionPageQuery(queryWrapper, pageNo, pageSize);
    }

    public List<DataModelSqlHintView> listSqlHintsByDatasource(Long datasourceId) {
        dataSourceService.get(datasourceId);
        List<DataModelEntity> entities = dataModelMapper.selectList(buildBaseQuery(datasourceId, null, null, "name", "asc")
                .select(DataModelEntity::getId,
                        DataModelEntity::getDatasourceId,
                        DataModelEntity::getName,
                        DataModelEntity::getPhysicalLocator,
                        DataModelEntity::getTechnicalMetadata)
                .last("limit " + MAX_PAGE_SIZE));
        return toSqlHintViews(entities);
    }

    public PageView<DataModelListView> querySummaryPage(DataModelQueryRequest request, Integer pageNo, Integer pageSize) {
        if (request == null) {
            return summaryPageQuery(buildBaseQuery(null, null, null), pageNo, pageSize);
        }
        List<DataModelQueryGroup> groups = normalizeQueryGroups(request.getGroups());
        if (groups.isEmpty()) {
            return summaryPageQuery(buildBaseQuery(request.getDatasourceId(), request.getDatasourceType(), request.getModelKind(), request.getSortField(), request.getSortOrder()), pageNo, pageSize);
        }
        DataModelQueryRequest normalizedRequest = new DataModelQueryRequest();
        normalizedRequest.setDatasourceId(request.getDatasourceId());
        normalizedRequest.setDatasourceType(request.getDatasourceType());
        normalizedRequest.setModelKind(request.getModelKind());
        normalizedRequest.setGroups(groups);
        Set<Long> matchedIds = dataModelSearchIndexService.queryModelIds(normalizedRequest);
        if (matchedIds.isEmpty()) {
            return PageView.of(normalizePageNo(pageNo), normalizePageSize(pageSize), 0L, new ArrayList<DataModelListView>());
        }
        LambdaQueryWrapper<DataModelEntity> queryWrapper = buildBaseQuery(request.getDatasourceId(), request.getDatasourceType(), request.getModelKind(), request.getSortField(), request.getSortOrder())
                .in(DataModelEntity::getId, matchedIds);
        return summaryPageQuery(queryWrapper, pageNo, pageSize);
    }

    @Transactional
    public List<DataModelDefinition> syncFromDatasource(Long datasourceId) {
        syncFromDatasourceAtomically(datasourceId, null);
        return listByDatasource(datasourceId);
    }

    @Transactional
    public List<DataModelDefinition> syncFromDatasource(Long datasourceId, List<String> physicalLocators) {
        syncFromDatasourceAtomically(datasourceId, physicalLocators);
        return listByDatasource(datasourceId);
    }

    private void syncFromDatasourceAtomically(Long datasourceId, List<String> physicalLocators) {
        DataSourceDefinition datasource = dataSourceService.getInternal(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        datasourceTypeCapabilityService.ensureReadable(datasource.getTypeCode());
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        String currentTenantId = securityService.currentTenantId();
        Set<String> selectedLocators = normalizeLocators(physicalLocators);
        List<DataModelDefinition> discovered = buildSyncCandidates(datasource, selectedLocators);
        List<AggregationSourceCapabilityProvider.HydrationResult> hydratedModels = modelDiscoveryProvider
                .hydrateDiscoveredModels(datasource, discovered);
        List<DataModelEntity> savedEntities = new ArrayList<DataModelEntity>();
        for (AggregationSourceCapabilityProvider.HydrationResult hydrated : hydratedModels) {
            if (!hydrated.isSuccess() || hydrated.getDefinition() == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, resolveHydrationFailureMessage(hydrated));
            }
            savedEntities.add(upsertHydratedModel(datasourceId, datasource, currentProjectId, currentTenantId, hydrated.getDefinition()));
        }
        dataModelIndexRebuildQueueService.enqueueModelRebuilds(extractModelIds(savedEntities));
    }

    @Transactional
    public DataModelSyncBatchResult syncBatchFromDatasource(Long datasourceId, List<String> physicalLocators) {
        DataSourceDefinition datasource = dataSourceService.getInternal(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        datasourceTypeCapabilityService.ensureReadable(datasource.getTypeCode());
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        String currentTenantId = securityService.currentTenantId();
        Set<String> selectedLocators = normalizeLocators(physicalLocators);
        List<DataModelDefinition> discovered = buildSyncCandidates(datasource, selectedLocators);
        List<AggregationSourceCapabilityProvider.HydrationResult> hydratedModels = modelDiscoveryProvider
                .hydrateDiscoveredModels(datasource, discovered);
        DataModelSyncBatchResult result = new DataModelSyncBatchResult();
        List<DataModelEntity> savedEntities = new ArrayList<DataModelEntity>();
        for (AggregationSourceCapabilityProvider.HydrationResult hydrated : hydratedModels) {
            DataModelSyncItemResult itemResult = new DataModelSyncItemResult();
            itemResult.setPhysicalLocator(hydrated.getPhysicalLocator());
            LocalDateTime startedAt = LocalDateTime.now();
            itemResult.setStartedAt(startedAt);
            try {
                if (!hydrated.isSuccess() || hydrated.getDefinition() == null) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST, resolveHydrationFailureMessage(hydrated));
                }
                DataModelEntity entity = upsertHydratedModel(datasourceId, datasource, currentProjectId, currentTenantId, hydrated.getDefinition());
                savedEntities.add(entity);
                itemResult.setModelName(entity.getName());
                itemResult.setSuccess(true);
            } catch (Exception ex) {
                itemResult.setSuccess(false);
                itemResult.setMessage(ex.getMessage());
                log.warn("Failed to sync model. datasourceId={}, physicalLocator={}, reason={}",
                        datasourceId, hydrated.getPhysicalLocator(), ex.getMessage());
            }
            LocalDateTime finishedAt = LocalDateTime.now();
            itemResult.setFinishedAt(finishedAt);
            itemResult.setDurationMs(Long.valueOf(Duration.between(startedAt, finishedAt).toMillis()));
            result.addItem(itemResult);
        }
        dataModelIndexRebuildQueueService.enqueueModelRebuilds(extractModelIds(savedEntities));
        return result;
    }

    @Transactional
    public DataModelDefinition save(DataModelSaveRequest request) {
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        DataModelEntity entity = request.getId() == null ? new DataModelEntity() : requireWritableEntity(request.getId());
        if (entity == null) {
            entity = new DataModelEntity();
        }
        DataSourceDefinition datasource = dataSourceService.getInternal(request.getDatasourceId());
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + request.getDatasourceId());
        }
        ensureUniqueName(currentProjectId, request.getDatasourceId(), request.getName(), entity.getId());
        entity.setTenantId(securityService.currentTenantId());
        entity.setProjectId(currentProjectId);
        entity.setDatasourceId(request.getDatasourceId());
        entity.setName(request.getName());
        entity.setPhysicalLocator(request.getPhysicalLocator());
        entity.setModelKind(resolveModelKind(request, entity));
        Long schemaVersionId = resolveSchemaVersionId(request, datasource, entity);
        entity.setSchemaVersionId(schemaVersionId);
        MetadataSchemaDefinition technicalSchema = metadataSchemaService.findSchemaByVersionId(schemaVersionId);
        Map<String, Object> technicalMetadata = enrichTechnicalMetadata(entity.getTechnicalMetadata(), request, datasource);
        entity.setTechnicalMetadata(normalizeTechnicalMetadata(
                applyDefaults(technicalMetadata, technicalSchema, MetadataScope.TECHNICAL),
                datasource == null ? null : datasource.getTypeCode(),
                schemaVersionId));
        Map<String, Object> businessMetadata = request.getBusinessMetadata() == null
                ? entity.getBusinessMetadata()
                : request.getBusinessMetadata();
        entity.setBusinessMetadata(businessMetaModelMetadataService.normalizeForModel(
                businessMetadata,
                resolveAllowedBusinessMetaModelCodes(datasource == null ? null : datasource.getTypeCode(), schemaVersionId)));
        if (entity.getId() == null) {
            dataModelMapper.insert(entity);
        } else {
            dataModelMapper.updateById(entity);
        }
        dataModelIndexRebuildQueueService.enqueueModelRebuild(entity.getId());
        return toDefinition(entity);
    }

    public List<Map<String, Object>> preview(Long modelId, int limit) {
        DataModelEntity model = findAccessibleEntity(modelId);
        if (model == null) {
            return new ArrayList<Map<String, Object>>();
        }
        DataSourceDefinition datasource = dataSourceService.getInternal(model.getDatasourceId());
        datasourceTypeCapabilityService.ensureReadable(datasource.getTypeCode());
        return modelDiscoveryProvider.preview(datasource, toDefinition(model), limit);
    }

    @Transactional
    public void delete(Long modelId) {
        requireWritableEntity(modelId);
        dataModelMapper.deleteById(modelId);
        dataModelIndexRebuildQueueService.enqueueModelDelete(modelId);
    }

    @Transactional
    public int rebuildSearchIndex(Long datasourceId) {
        LambdaQueryWrapper<DataModelEntity> queryWrapper = buildAccessibleQuery()
                .orderByAsc(DataModelEntity::getId);
        if (datasourceId != null) {
            queryWrapper.eq(DataModelEntity::getDatasourceId, datasourceId);
        }
        List<DataModelEntity> entities = dataModelMapper.selectList(queryWrapper);
        dataModelIndexRebuildQueueService.enqueueModelRebuilds(extractModelIds(entities));
        return entities.size();
    }

    private List<DataModelDefinition> buildSyncCandidates(DataSourceDefinition datasource,
                                                          Set<String> selectedLocators) {
        if (selectedLocators == null || selectedLocators.isEmpty()) {
            return modelDiscoveryProvider.discoverModels(datasource).getModels();
        }
        List<DataModelDefinition> candidates = new ArrayList<DataModelDefinition>();
        for (String locator : selectedLocators) {
            DataModelDefinition definition = new DataModelDefinition();
            definition.setDatasourceId(datasource.getId());
            definition.setName(locator);
            definition.setModelKind(ModelKind.TABLE);
            definition.setPhysicalLocator(locator);
            Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
            technicalMetadata.put("sourceType", datasource.getTypeCode());
            technicalMetadata.put("discoveryMode", "AUTO");
            technicalMetadata.put("physicalName", locator);
            definition.setTechnicalMetadata(technicalMetadata);
            definition.setBusinessMetadata(new LinkedHashMap<String, Object>());
            candidates.add(definition);
        }
        return candidates;
    }

    private String resolveHydrationFailureMessage(AggregationSourceCapabilityProvider.HydrationResult hydrated) {
        if (hydrated == null) {
            return "Failed to load model metadata";
        }
        String physicalLocator = hydrated.getPhysicalLocator();
        String message = hydrated.getErrorMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "Failed to load model metadata";
        }
        if (physicalLocator == null || physicalLocator.trim().isEmpty()) {
            return message;
        }
        return "Failed to load model metadata for " + physicalLocator + ": " + message;
    }

    private DataModelEntity upsertHydratedModel(Long datasourceId,
                                                DataSourceDefinition datasource,
                                                Long currentProjectId,
                                                String currentTenantId,
                                                DataModelDefinition hydratedDefinition) {
        DataModelEntity existing = dataModelMapper.selectOne(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getDatasourceId, datasourceId)
                .eq(DataModelEntity::getProjectId, currentProjectId)
                .eq(DataModelEntity::getPhysicalLocator, hydratedDefinition.getPhysicalLocator())
                .last("limit 1"));
        ensureUniqueName(currentProjectId, datasourceId, hydratedDefinition.getName(),
                existing == null ? null : existing.getId());
        DataModelEntity entity = existing == null ? new DataModelEntity() : existing;
        entity.setTenantId(currentTenantId);
        entity.setProjectId(currentProjectId);
        entity.setDatasourceId(datasourceId);
        entity.setName(hydratedDefinition.getName());
        entity.setModelKind(hydratedDefinition.getModelKind() == null ? ModelKind.DATASET.name() : hydratedDefinition.getModelKind().name());
        entity.setPhysicalLocator(hydratedDefinition.getPhysicalLocator());
        Long schemaVersionId = resolveSchemaVersionId(hydratedDefinition, datasource.getTypeCode(), existing);
        entity.setSchemaVersionId(schemaVersionId);
        Map<String, Object> technicalMetadata = mergeTechnicalMetadata(existing == null ? null : existing.getTechnicalMetadata(),
                hydratedDefinition.getTechnicalMetadata());
        entity.setTechnicalMetadata(normalizeTechnicalMetadata(technicalMetadata, datasource.getTypeCode(), schemaVersionId));
        entity.setBusinessMetadata(businessMetaModelMetadataService.normalizeForModel(
                existing == null ? null : existing.getBusinessMetadata(),
                resolveAllowedBusinessMetaModelCodes(datasource.getTypeCode(), schemaVersionId)));
        if (entity.getId() == null) {
            dataModelMapper.insert(entity);
        } else {
            dataModelMapper.updateById(entity);
        }
        return entity;
    }

    private DataModelDefinition toDefinition(DataModelEntity entity) {
        DataModelDefinition definition = new DataModelDefinition();
        definition.setId(entity.getId());
        definition.setTenantId(entity.getTenantId());
        definition.setProjectId(entity.getProjectId());
        definition.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        definition.setCreatedAt(entity.getCreatedAt());
        definition.setUpdatedAt(entity.getUpdatedAt());
        definition.setDatasourceId(entity.getDatasourceId());
        definition.setName(entity.getName());
        definition.setPhysicalLocator(entity.getPhysicalLocator());
        definition.setSchemaVersionId(entity.getSchemaVersionId());
        definition.setTechnicalMetadata(entity.getTechnicalMetadata() == null ? new LinkedHashMap<String, Object>() : entity.getTechnicalMetadata());
        definition.setBusinessMetadata(entity.getBusinessMetadata() == null ? new LinkedHashMap<String, Object>() : entity.getBusinessMetadata());
        if (entity.getModelKind() != null) {
            definition.setModelKind(ModelKind.valueOf(entity.getModelKind()));
        }
        return definition;
    }

    private DataModelListView toListView(DataModelEntity entity) {
        DataModelListView view = new DataModelListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setDatasourceId(entity.getDatasourceId());
        view.setName(entity.getName());
        view.setPhysicalLocator(entity.getPhysicalLocator());
        view.setSchemaVersionId(entity.getSchemaVersionId());
        if (entity.getModelKind() != null) {
            view.setModelKind(ModelKind.valueOf(entity.getModelKind()));
        }
        return view;
    }

    private PageView<DataModelDefinition> pageQuery(LambdaQueryWrapper<DataModelEntity> queryWrapper) {
        return pageQuery(queryWrapper, null, null);
    }

    private PageView<DataModelDefinition> pageQuery(LambdaQueryWrapper<DataModelEntity> queryWrapper,
                                                    Integer pageNo,
                                                    Integer pageSize) {
        int resolvedPageNo = normalizePageNo(pageNo);
        int resolvedPageSize = normalizePageSize(pageSize);
        long total = safeCount(queryWrapper);
        if (total <= 0L) {
            return PageView.of(resolvedPageNo, resolvedPageSize, 0L, Collections.<DataModelDefinition>emptyList());
        }
        long offset = (long) (resolvedPageNo - 1) * resolvedPageSize;
        List<DataModelEntity> entities = dataModelMapper.selectList(cloneQuery(queryWrapper)
                .last("limit " + resolvedPageSize + " offset " + offset));
        return PageView.of(resolvedPageNo, resolvedPageSize, total, toDefinitions(entities));
    }

    private PageView<DataModelListView> summaryPageQuery(LambdaQueryWrapper<DataModelEntity> queryWrapper,
                                                         Integer pageNo,
                                                         Integer pageSize) {
        int resolvedPageNo = normalizePageNo(pageNo);
        int resolvedPageSize = normalizePageSize(pageSize);
        long total = safeCount(queryWrapper);
        if (total <= 0L) {
            return PageView.of(resolvedPageNo, resolvedPageSize, 0L, Collections.<DataModelListView>emptyList());
        }
        long offset = (long) (resolvedPageNo - 1) * resolvedPageSize;
        List<DataModelEntity> entities = dataModelMapper.selectList(cloneQuery(queryWrapper)
                .select(DataModelEntity::getId,
                        DataModelEntity::getTenantId,
                        DataModelEntity::getProjectId,
                        DataModelEntity::getDeleted,
                        DataModelEntity::getCreatedAt,
                        DataModelEntity::getUpdatedAt,
                        DataModelEntity::getDatasourceId,
                        DataModelEntity::getName,
                        DataModelEntity::getModelKind,
                        DataModelEntity::getPhysicalLocator,
                        DataModelEntity::getSchemaVersionId)
                .last("limit " + resolvedPageSize + " offset " + offset));
        return PageView.of(resolvedPageNo, resolvedPageSize, total, toListViews(entities));
    }

    private PageView<DataModelOptionView> optionPageQuery(LambdaQueryWrapper<DataModelEntity> queryWrapper,
                                                          Integer pageNo,
                                                          Integer pageSize) {
        int resolvedPageNo = normalizePageNo(pageNo);
        int resolvedPageSize = normalizePageSize(pageSize);
        long total = safeCount(queryWrapper);
        if (total <= 0L) {
            return PageView.of(resolvedPageNo, resolvedPageSize, 0L, Collections.<DataModelOptionView>emptyList());
        }
        long offset = (long) (resolvedPageNo - 1) * resolvedPageSize;
        List<DataModelEntity> entities = dataModelMapper.selectList(cloneQuery(queryWrapper)
                .select(DataModelEntity::getId,
                        DataModelEntity::getTenantId,
                        DataModelEntity::getProjectId,
                        DataModelEntity::getDeleted,
                        DataModelEntity::getCreatedAt,
                        DataModelEntity::getUpdatedAt,
                        DataModelEntity::getName)
                .last("limit " + resolvedPageSize + " offset " + offset));
        return PageView.of(resolvedPageNo, resolvedPageSize, total, toOptionViews(entities));
    }

    private PageView<DataModelDatasourceOptionView> datasourceOptionPageQuery(LambdaQueryWrapper<DataModelEntity> queryWrapper,
                                                                              Integer pageNo,
                                                                              Integer pageSize) {
        int resolvedPageNo = normalizePageNo(pageNo);
        int resolvedPageSize = Math.min(normalizePageSize(pageSize), MAX_OPTION_PAGE_SIZE);
        long total = safeCount(queryWrapper);
        if (total <= 0L) {
            return PageView.of(resolvedPageNo, resolvedPageSize, 0L, Collections.<DataModelDatasourceOptionView>emptyList());
        }
        long offset = (long) (resolvedPageNo - 1) * resolvedPageSize;
        List<DataModelEntity> entities = dataModelMapper.selectList(cloneQuery(queryWrapper)
                .select(DataModelEntity::getId,
                        DataModelEntity::getDatasourceId,
                        DataModelEntity::getName,
                        DataModelEntity::getModelKind,
                        DataModelEntity::getPhysicalLocator)
                .last("limit " + resolvedPageSize + " offset " + offset));
        return PageView.of(resolvedPageNo, resolvedPageSize, total, toDatasourceOptionViews(entities));
    }

    private PageView<RunMetricFilterOptionView> metricFilterOptionPageQuery(LambdaQueryWrapper<DataModelEntity> queryWrapper,
                                                                            Integer pageNo,
                                                                            Integer pageSize) {
        int resolvedPageNo = normalizePageNo(pageNo);
        int resolvedPageSize = Math.min(normalizePageSize(pageSize), 100);
        long total = safeCount(queryWrapper);
        if (total <= 0L) {
            return PageView.of(resolvedPageNo, resolvedPageSize, 0L, Collections.<RunMetricFilterOptionView>emptyList());
        }
        long offset = (long) (resolvedPageNo - 1) * resolvedPageSize;
        List<DataModelEntity> entities = dataModelMapper.selectList(cloneQuery(queryWrapper)
                .select(DataModelEntity::getId,
                        DataModelEntity::getName,
                        DataModelEntity::getPhysicalLocator)
                .last("limit " + resolvedPageSize + " offset " + offset));
        return PageView.of(resolvedPageNo, resolvedPageSize, total, toMetricFilterOptionViews(entities));
    }

    private long safeCount(LambdaQueryWrapper<DataModelEntity> queryWrapper) {
        Long total = dataModelMapper.selectCount(cloneQuery(queryWrapper));
        return total == null ? 0L : total.longValue();
    }

    private LambdaQueryWrapper<DataModelEntity> buildBaseQuery(Long datasourceId,
                                                               String datasourceType,
                                                               String modelKind) {
        return buildBaseQuery(datasourceId, datasourceType, modelKind, null, null);
    }

    private LambdaQueryWrapper<DataModelEntity> buildBaseQuery(Long datasourceId,
                                                               String datasourceType,
                                                               String modelKind,
                                                               String sortField,
                                                               String sortOrder) {
        LambdaQueryWrapper<DataModelEntity> queryWrapper = buildAccessibleQuery();
        applySort(queryWrapper, sortField, sortOrder);
        if (datasourceId != null) {
            queryWrapper.eq(DataModelEntity::getDatasourceId, datasourceId);
        } else if (datasourceType != null && !datasourceType.trim().isEmpty()) {
            Set<Long> datasourceIds = resolveDatasourceIdsByType(datasourceType.trim());
            if (datasourceIds.isEmpty()) {
                queryWrapper.in(DataModelEntity::getId, Collections.singleton(Long.valueOf(-1L)));
                return queryWrapper;
            }
            queryWrapper.in(DataModelEntity::getDatasourceId, datasourceIds);
        }
        if (modelKind != null && !modelKind.trim().isEmpty()) {
            queryWrapper.eq(DataModelEntity::getModelKind, modelKind.trim().toUpperCase());
        }
        return queryWrapper;
    }

    private void applySort(LambdaQueryWrapper<DataModelEntity> queryWrapper,
                           String sortField,
                           String sortOrder) {
        String normalizedSortField = normalizeSortField(sortField);
        SFunction<DataModelEntity, ?> column = resolveSortColumn(normalizedSortField);
        boolean ascending = "asc".equalsIgnoreCase(sortOrder) || "ascending".equalsIgnoreCase(sortOrder);
        queryWrapper.orderBy(true, ascending, column);
        if (!"projectId".equals(normalizedSortField)) {
            queryWrapper.orderByAsc(DataModelEntity::getProjectId);
        }
        if (!"name".equals(normalizedSortField)) {
            queryWrapper.orderByAsc(DataModelEntity::getName);
        }
        if (!"id".equals(normalizedSortField)) {
            queryWrapper.orderByDesc(DataModelEntity::getId);
        }
    }

    private String normalizeSortField(String sortField) {
        if (sortField == null || sortField.trim().isEmpty()) {
            return "updatedAt";
        }
        String normalized = sortField.trim();
        if ("name".equals(normalized)
                || "datasourceId".equals(normalized)
                || "projectId".equals(normalized)
                || "createdAt".equals(normalized)
                || "id".equals(normalized)) {
            return normalized;
        }
        return "updatedAt";
    }

    private SFunction<DataModelEntity, ?> resolveSortColumn(String normalizedSortField) {
        if ("name".equals(normalizedSortField)) {
            return DataModelEntity::getName;
        }
        if ("datasourceId".equals(normalizedSortField)) {
            return DataModelEntity::getDatasourceId;
        }
        if ("projectId".equals(normalizedSortField)) {
            return DataModelEntity::getProjectId;
        }
        if ("createdAt".equals(normalizedSortField)) {
            return DataModelEntity::getCreatedAt;
        }
        if ("id".equals(normalizedSortField)) {
            return DataModelEntity::getId;
        }
        return DataModelEntity::getUpdatedAt;
    }

    private Set<Long> resolveDatasourceIdsByType(String datasourceType) {
        if (datasourceType == null || datasourceType.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> datasourceIds = new HashSet<Long>();
        for (DataSourceDefinition datasource : dataSourceService.list()) {
            if (datasource != null
                    && datasource.getId() != null
                    && datasource.getTypeCode() != null
                    && datasourceType.equalsIgnoreCase(datasource.getTypeCode())) {
                datasourceIds.add(Long.valueOf(String.valueOf(datasource.getId())));
            }
        }
        return datasourceIds;
    }

    private LambdaQueryWrapper<DataModelEntity> cloneQuery(LambdaQueryWrapper<DataModelEntity> queryWrapper) {
        return queryWrapper == null ? new LambdaQueryWrapper<DataModelEntity>() : queryWrapper.clone();
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo.intValue() < 1 ? DEFAULT_PAGE_NO : pageNo.intValue();
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize.intValue() < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize.intValue(), MAX_PAGE_SIZE);
    }

    private LambdaQueryWrapper<DataModelEntity> buildAccessibleQuery() {
        return dataModelAccessScopeService.buildAccessibleQuery();
    }

    private DataModelEntity findAccessibleEntity(Long modelId) {
        DataModelEntity entity = dataModelMapper.selectById(modelId);
        if (entity == null) {
            return null;
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATA_MODEL,
                entity.getProjectId(), entity.getId(), "Model not found: " + modelId);
        return entity;
    }

    private DataModelEntity requireWritableEntity(Long modelId) {
        DataModelEntity entity = dataModelMapper.selectById(modelId);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Model not found: " + modelId);
        }
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private void ensureUniqueName(Long projectId, Long datasourceId, String name, Long selfId) {
        if (projectId == null || datasourceId == null || name == null || name.trim().isEmpty()) {
            return;
        }
        List<DataModelEntity> duplicates = dataModelMapper.selectList(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getProjectId, projectId)
                .eq(DataModelEntity::getDatasourceId, datasourceId)
                .eq(DataModelEntity::getName, name.trim()));
        for (DataModelEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Model name already exists in the current datasource");
        }
    }

    private List<DataModelDefinition> toDefinitions(List<DataModelEntity> entities) {
        List<DataModelDefinition> result = new ArrayList<DataModelDefinition>();
        for (DataModelEntity entity : entities) {
            result.add(toDefinition(entity));
        }
        return result;
    }

    private List<DataModelListView> toListViews(List<DataModelEntity> entities) {
        List<DataModelListView> result = new ArrayList<DataModelListView>();
        for (DataModelEntity entity : entities) {
            result.add(toListView(entity));
        }
        return result;
    }

    private List<DataModelOptionView> toOptionViews(List<DataModelEntity> entities) {
        List<DataModelOptionView> result = new ArrayList<DataModelOptionView>();
        for (DataModelEntity entity : entities) {
            result.add(toOptionView(entity));
        }
        return result;
    }

    private List<DataModelDatasourceOptionView> toDatasourceOptionViews(List<DataModelEntity> entities) {
        List<DataModelDatasourceOptionView> result = new ArrayList<DataModelDatasourceOptionView>();
        for (DataModelEntity entity : entities) {
            result.add(toDatasourceOptionView(entity));
        }
        return result;
    }

    private List<RunMetricFilterOptionView> toMetricFilterOptionViews(List<DataModelEntity> entities) {
        List<RunMetricFilterOptionView> result = new ArrayList<RunMetricFilterOptionView>();
        for (DataModelEntity entity : entities) {
            RunMetricFilterOptionView view = new RunMetricFilterOptionView();
            view.setId(entity.getId());
            view.setName(entity.getName());
            view.setLabel(buildMetricFilterOptionLabel(entity));
            result.add(view);
        }
        return result;
    }

    private String buildMetricFilterOptionLabel(DataModelEntity entity) {
        if (entity.getPhysicalLocator() == null || entity.getPhysicalLocator().trim().isEmpty()) {
            return entity.getName();
        }
        return entity.getName() + " / " + entity.getPhysicalLocator();
    }

    private DataModelOptionView toOptionView(DataModelEntity entity) {
        DataModelOptionView view = new DataModelOptionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        return view;
    }

    private DataModelDatasourceOptionView toDatasourceOptionView(DataModelEntity entity) {
        DataModelDatasourceOptionView view = new DataModelDatasourceOptionView();
        view.setId(entity.getId());
        view.setDatasourceId(entity.getDatasourceId());
        view.setName(entity.getName());
        view.setPhysicalLocator(entity.getPhysicalLocator());
        if (entity.getModelKind() != null) {
            view.setModelKind(ModelKind.valueOf(entity.getModelKind()));
        }
        return view;
    }

    private List<DataModelSqlHintView> toSqlHintViews(List<DataModelEntity> entities) {
        List<DataModelSqlHintView> result = new ArrayList<DataModelSqlHintView>();
        for (DataModelEntity entity : entities) {
            result.add(toSqlHintView(entity));
        }
        return result;
    }

    private DataModelSqlHintView toSqlHintView(DataModelEntity entity) {
        DataModelSqlHintView view = new DataModelSqlHintView();
        view.setId(entity.getId());
        view.setDatasourceId(entity.getDatasourceId());
        view.setName(entity.getName());
        view.setPhysicalLocator(entity.getPhysicalLocator());
        view.setColumns(extractSqlHintColumns(entity.getTechnicalMetadata()));
        return view;
    }

    private List<String> extractSqlHintColumns(Map<String, Object> technicalMetadata) {
        Object columns = technicalMetadata == null ? null : technicalMetadata.get("columns");
        if (!(columns instanceof List<?>)) {
            return Collections.emptyList();
        }
        Set<String> result = new LinkedHashSet<String>();
        for (Object item : (List<?>) columns) {
            String name = "";
            if (item instanceof Map<?, ?>) {
                Object value = ((Map<?, ?>) item).get("name");
                name = value == null ? "" : String.valueOf(value).trim();
            } else if (item != null) {
                name = String.valueOf(item).trim();
            }
            if (!name.isEmpty()) {
                result.add(name);
            }
        }
        return new ArrayList<String>(result);
    }

    private List<Long> extractModelIds(List<DataModelEntity> entities) {
        List<Long> modelIds = new ArrayList<Long>();
        if (entities == null) {
            return modelIds;
        }
        for (DataModelEntity entity : entities) {
            if (entity != null && entity.getId() != null) {
                modelIds.add(entity.getId());
            }
        }
        return modelIds;
    }

    private List<DataModelQueryGroup> normalizeQueryGroups(List<DataModelQueryGroup> groups) {
        List<DataModelQueryGroup> normalized = new ArrayList<DataModelQueryGroup>();
        if (groups == null) {
            return normalized;
        }
        for (DataModelQueryGroup group : groups) {
            if (group == null || group.getMetaSchemaCode() == null || group.getMetaSchemaCode().trim().isEmpty()) {
                continue;
            }
            DataModelQueryGroup copied = new DataModelQueryGroup();
            copied.setScope(group.getScope());
            copied.setMetaSchemaCode(group.getMetaSchemaCode().trim());
            copied.setRowMatchMode(group.getRowMatchMode());
            List<DataModelQueryCondition> conditions = new ArrayList<DataModelQueryCondition>();
            if (group.getConditions() != null) {
                for (DataModelQueryCondition condition : group.getConditions()) {
                    if (condition == null || condition.getFieldKey() == null || condition.getFieldKey().trim().isEmpty()) {
                        continue;
                    }
                    if ((condition.getValue() == null || String.valueOf(condition.getValue()).trim().isEmpty())
                            && (condition.getValues() == null || condition.getValues().isEmpty())) {
                        continue;
                    }
                    DataModelQueryCondition copiedCondition = new DataModelQueryCondition();
                    copiedCondition.setFieldKey(condition.getFieldKey().trim());
                    copiedCondition.setOperator(condition.getOperator());
                    copiedCondition.setValue(condition.getValue());
                    copiedCondition.setValues(condition.getValues());
                    conditions.add(copiedCondition);
                }
            }
            if (!conditions.isEmpty()) {
                copied.setConditions(conditions);
                normalized.add(copied);
            }
        }
        return normalized;
    }

    private Map<String, Object> mergeTechnicalMetadata(Map<String, Object> existing, Map<String, Object> discovered) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>();
        if (existing != null) {
            merged.putAll(existing);
        }
        if (discovered != null) {
            merged.putAll(discovered);
        }
        return merged;
    }

    private Set<String> normalizeLocators(List<String> physicalLocators) {
        Set<String> selected = new HashSet<String>();
        if (physicalLocators == null) {
            return selected;
        }
        for (String physicalLocator : physicalLocators) {
            if (physicalLocator != null && !physicalLocator.trim().isEmpty()) {
                selected.add(physicalLocator.trim());
            }
        }
        return selected;
    }

    private String resolveModelKind(DataModelSaveRequest request, DataModelEntity existing) {
        if (request.getModelKind() != null) {
            return request.getModelKind().name();
        }
        if (existing != null && existing.getModelKind() != null) {
            return existing.getModelKind();
        }
        return ModelKind.DATASET.name();
    }

    private Long resolveSchemaVersionId(DataModelSaveRequest request,
                                        DataSourceDefinition datasource,
                                        DataModelEntity existing) {
        if (request.getSchemaVersionId() != null) {
            return request.getSchemaVersionId();
        }
        DataModelDefinition temp = new DataModelDefinition();
        if (request.getModelKind() != null) {
            temp.setModelKind(request.getModelKind());
        } else if (existing != null && existing.getModelKind() != null) {
            temp.setModelKind(ModelKind.valueOf(existing.getModelKind()));
        }
        return resolveSchemaVersionId(temp, datasource == null ? null : datasource.getTypeCode(), existing);
    }

    private Map<String, Object> enrichTechnicalMetadata(Map<String, Object> existing,
                                                        DataModelSaveRequest request,
                                                        DataSourceDefinition datasource) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        if (existing != null) {
            metadata.putAll(existing);
        }
        if (request.getTechnicalMetadata() != null) {
            metadata.putAll(request.getTechnicalMetadata());
        }
        putIfAbsent(metadata, "sourceType", datasource == null ? null : datasource.getTypeCode());
        putIfAbsent(metadata, "discoveryMode", "MANUAL");
        putIfAbsent(metadata, "physicalName", request.getPhysicalLocator());
        if (request.getModelKind() != null) {
            putIfAbsent(metadata, "tableType", request.getModelKind().name());
        }
        return metadata;
    }

    private void putIfAbsent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (target.containsKey(key)) {
            Object existing = target.get(key);
            if (existing != null && (!(existing instanceof String) || !((String) existing).trim().isEmpty())) {
                return;
            }
        }
        target.put(key, value);
    }

    private Long resolveSchemaVersionId(DataModelDefinition definition, String datasourceTypeCode, DataModelEntity existing) {
        if (definition.getSchemaVersionId() != null) {
            return definition.getSchemaVersionId();
        }
        if (existing != null && existing.getSchemaVersionId() != null) {
            return existing.getSchemaVersionId();
        }
        if (datasourceTypeCode == null || datasourceTypeCode.trim().isEmpty()) {
            return null;
        }
        String modelKind = definition.getModelKind() == null ? null : definition.getModelKind().name().toLowerCase();
        if (modelKind != null) {
            MetadataSchemaDefinition exactSchema = metadataSchemaService.findTechnicalMetaModel(datasourceTypeCode, modelKind);
            if (exactSchema != null && exactSchema.getCurrentVersionId() != null) {
                return exactSchema.getCurrentVersionId();
            }
        }
        MetadataSchemaDefinition tableSchema = metadataSchemaService.findTechnicalMetaModel(datasourceTypeCode, "table");
        if (tableSchema != null && tableSchema.getCurrentVersionId() != null) {
            return tableSchema.getCurrentVersionId();
        }
        List<MetadataSchemaDefinition> schemas = metadataSchemaService.listSchemas();
        for (MetadataSchemaDefinition schema : schemas) {
            if (!"model".equalsIgnoreCase(schema.getObjectType())) {
                continue;
            }
            if (schema.getCurrentVersionId() == null) {
                continue;
            }
            String typeCode = schema.getTypeCode();
            if (typeCode == null) {
                continue;
            }
            if (typeCode.equalsIgnoreCase(datasourceTypeCode + "." + modelKind)
                    || typeCode.equalsIgnoreCase(datasourceTypeCode)
                    || (modelKind != null && typeCode.equalsIgnoreCase(modelKind))) {
                return schema.getCurrentVersionId();
            }
        }
        return null;
    }

    private Map<String, Object> normalizeTechnicalMetadata(Map<String, Object> metadata,
                                                           String datasourceTypeCode,
                                                           Long schemaVersionId) {
        MetadataSchemaDefinition tableSchema = metadataSchemaService.findSchemaByVersionId(schemaVersionId);
        Map<String, Object> normalized = applyDefaults(metadata, tableSchema, MetadataScope.TECHNICAL);
        MetadataSchemaDefinition fieldSchema = metadataSchemaService.findTechnicalMetaModel(datasourceTypeCode, "field");
        Object columns = normalized.get("columns");
        if (columns instanceof List) {
            normalized.put("columns", normalizeColumnMetadata((List<?>) columns, fieldSchema));
        }
        return normalized;
    }

    private List<Map<String, Object>> normalizeColumnMetadata(List<?> columns,
                                                              MetadataSchemaDefinition fieldSchema) {
        List<Map<String, Object>> normalized = new ArrayList<Map<String, Object>>();
        if (columns == null) {
            return normalized;
        }
        for (Object column : columns) {
            Map<String, Object> item = copyObjectMap(column);
            if (item == null) {
                continue;
            }
            normalized.add(applyDefaults(item, fieldSchema, MetadataScope.TECHNICAL));
        }
        return normalized;
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

    private Set<String> resolveAllowedBusinessMetaModelCodes(String datasourceTypeCode,
                                                             Long activeSchemaVersionId) {
        Set<String> allowedCodes = new HashSet<String>();
        String normalizedDatasourceType = datasourceTypeCode == null ? "" : datasourceTypeCode.trim().toLowerCase();
        if (normalizedDatasourceType.isEmpty()) {
            return allowedCodes;
        }
        for (MetadataSchemaDefinition schema : metadataSchemaService.listSchemas()) {
            if (!"model".equalsIgnoreCase(schema.getObjectType())) {
                continue;
            }
            if (!"TECHNICAL".equalsIgnoreCase(metadataSchemaService.getSchemaDomain(schema))) {
                continue;
            }
            if (!normalizedDatasourceType.equals(normalize(metadataSchemaService.getSchemaDatasourceType(schema)))) {
                continue;
            }
            String metaModelCode = metadataSchemaService.getSchemaMetaModelCode(schema);
            if ("source".equalsIgnoreCase(metaModelCode)) {
                continue;
            }
            boolean activeSchema = sameId(schema.getId(), activeSchemaVersionId)
                    || sameId(schema.getCurrentVersionId(), activeSchemaVersionId);
            boolean multipleSchema = "MULTIPLE".equalsIgnoreCase(metadataSchemaService.getSchemaDisplayMode(schema));
            if (activeSchema || multipleSchema || (activeSchemaVersionId == null && "table".equalsIgnoreCase(metaModelCode))) {
                allowedCodes.add(metaModelCode);
            }
        }
        return allowedCodes;
    }

    private boolean sameId(Long left, Long right) {
        if (left == null || right == null) {
            return false;
        }
        return left.longValue() == right.longValue();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private Map<String, Object> applyDefaults(Map<String, Object> input,
                                              MetadataSchemaDefinition schema,
                                              MetadataScope scope) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (input != null) {
            output.putAll(input);
        }
        if (schema == null || schema.getFields() == null) {
            return output;
        }
        for (MetadataFieldDefinition field : schema.getFields()) {
            if (field.getScope() != scope) {
                continue;
            }
            if (output.containsKey(field.getFieldKey())) {
                continue;
            }
            Object defaultValue = defaultValueSupport.parseDefaultValue(field);
            if (defaultValue != null) {
                output.put(field.getFieldKey(), defaultValue);
            }
        }
        return output;
    }

}

