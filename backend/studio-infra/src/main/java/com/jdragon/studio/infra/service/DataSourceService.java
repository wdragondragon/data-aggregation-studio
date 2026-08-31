package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.enums.RuntimeDatasourceProbeMode;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataSourceListView;
import com.jdragon.studio.dto.model.DataSourceOptionView;
import com.jdragon.studio.dto.model.DatasourceClusterBindingImpactView;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.RunMetricFilterOptionView;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.DatasourceConnectionTestRecordView;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryOptionResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

@Service
public class DataSourceService {
    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);
    private static final int MAX_CONNECTION_TEST_MESSAGE_LENGTH = 1000;
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 500;

    private final DatasourceMapper datasourceMapper;
    private final DataModelMapper dataModelMapper;
    private final EncryptionService encryptionService;
    private final MetadataSchemaService metadataSchemaService;
    private final DataModelIndexRebuildQueueService dataModelIndexRebuildQueueService;
    private final BusinessMetaModelMetadataService businessMetaModelMetadataService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final DatasourceConnectionFingerprintService datasourceConnectionFingerprintService;
    private final DatasourceConnectionHealthService datasourceConnectionHealthService;
    private DatasourceClusterBindingService datasourceClusterBindingService;
    private RuntimeValidationService runtimeValidationService;
    private RuntimeDatasourceProbeRouter runtimeDatasourceProbeRouter;

    public DataSourceService(DatasourceMapper datasourceMapper,
                             DataModelMapper dataModelMapper,
                             EncryptionService encryptionService,
                             MetadataSchemaService metadataSchemaService,
                             DataModelIndexRebuildQueueService dataModelIndexRebuildQueueService,
                             BusinessMetaModelMetadataService businessMetaModelMetadataService,
                             StudioSecurityService securityService,
                             ProjectResourceAccessService projectResourceAccessService,
                             DatasourceTypeCapabilityService datasourceTypeCapabilityService,
                             DatasourceConnectionFingerprintService datasourceConnectionFingerprintService,
                             DatasourceConnectionHealthService datasourceConnectionHealthService) {
        this.datasourceMapper = datasourceMapper;
        this.dataModelMapper = dataModelMapper;
        this.encryptionService = encryptionService;
        this.metadataSchemaService = metadataSchemaService;
        this.dataModelIndexRebuildQueueService = dataModelIndexRebuildQueueService;
        this.businessMetaModelMetadataService = businessMetaModelMetadataService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.datasourceConnectionFingerprintService = datasourceConnectionFingerprintService;
        this.datasourceConnectionHealthService = datasourceConnectionHealthService;
    }

    /** Kept as method injection so existing focused service tests remain source-compatible. */
    @Autowired
    public void setDatasourceClusterBindingService(DatasourceClusterBindingService datasourceClusterBindingService) {
        this.datasourceClusterBindingService = datasourceClusterBindingService;
    }

    @Autowired
    public void setRuntimeValidationService(RuntimeValidationService runtimeValidationService) {
        this.runtimeValidationService = runtimeValidationService;
    }

    @Autowired
    public void setRuntimeDatasourceProbeRouter(RuntimeDatasourceProbeRouter runtimeDatasourceProbeRouter) {
        this.runtimeDatasourceProbeRouter = runtimeDatasourceProbeRouter;
    }

    public List<DataSourceDefinition> list() {
        List<DatasourceEntity> entities = datasourceMapper.selectList(buildAccessibleQuery()
                .orderByAsc(DatasourceEntity::getProjectId)
                .orderByAsc(DatasourceEntity::getName));
        List<DataSourceDefinition> result = new ArrayList<DataSourceDefinition>();
        for (DatasourceEntity entity : entities) {
            result.add(toDefinition(entity, true));
        }
        datasourceConnectionHealthService.hydrateDefinitions(result);
        hydrateApplicableClusters(result);
        return result;
    }

    /**
     * Worker-side scripts execute without an HTTP project context. Keep their
     * datasource visibility tied to the dispatched resource project instead
     * of allowing a tenant-wide fallback.
     */
    public List<DataSourceDefinition> listForProject(Long projectId) {
        List<DatasourceEntity> entities = datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getTenantId, securityService.currentTenantId())
                .orderByAsc(DatasourceEntity::getProjectId)
                .orderByAsc(DatasourceEntity::getName));
        List<DataSourceDefinition> result = new ArrayList<DataSourceDefinition>();
        for (DatasourceEntity entity : entities) {
            if (projectResourceAccessService.canReadFromProject(projectId,
                    StudioConstants.RESOURCE_TYPE_DATASOURCE, entity.getProjectId(), entity.getId())) {
                result.add(toDefinition(entity, true));
            }
        }
        datasourceConnectionHealthService.hydrateDefinitions(result);
        hydrateApplicableClusters(result);
        return result;
    }

    public Long countSummaries() {
        Long count = datasourceMapper.selectCount(buildAccessibleQuery());
        return count == null ? 0L : count;
    }

    public List<DataSourceListView> listSummaries() {
        List<DatasourceEntity> entities = datasourceMapper.selectList(selectSummaryColumns(buildAccessibleQuery())
                .orderByAsc(DatasourceEntity::getProjectId)
                .orderByAsc(DatasourceEntity::getName));
        return toSummaryViews(entities);
    }

    public List<DataSourceListView> listBasicSummaries() {
        List<DatasourceEntity> entities = datasourceMapper.selectList(selectOptionColumns(buildAccessibleQuery())
                .orderByAsc(DatasourceEntity::getProjectId)
                .orderByAsc(DatasourceEntity::getName));
        List<DataSourceListView> result = new ArrayList<DataSourceListView>();
        for (DatasourceEntity entity : entities) {
            result.add(toBasicListView(entity));
        }
        hydrateApplicableClustersForListViews(result);
        return result;
    }

    public PageView<DataSourceListView> listSummaryPage(Integer pageNo, Integer pageSize) {
        int current = normalizePageNo(pageNo);
        int size = normalizePageSize(pageSize);
        Page<DatasourceEntity> page = new Page<DatasourceEntity>(current, size);
        Page<DatasourceEntity> entityPage = datasourceMapper.selectPage(page, selectSummaryColumns(buildAccessibleQuery())
                .orderByAsc(DatasourceEntity::getProjectId)
                .orderByAsc(DatasourceEntity::getName));
        return PageView.of(current, size, entityPage.getTotal(), toSummaryViews(entityPage.getRecords()));
    }

    private List<DataSourceListView> toSummaryViews(List<DatasourceEntity> entities) {
        List<DataSourceListView> result = new ArrayList<DataSourceListView>();
        if (entities == null || entities.isEmpty()) {
            return result;
        }
        for (DatasourceEntity entity : entities) {
            result.add(toListView(entity));
        }
        hydrateApplicableClustersForListViews(result);
        datasourceConnectionHealthService.hydrateListViews(result);
        datasourceConnectionHealthService.hydrateClusterHealth(result);
        return result;
    }

    public List<DataSourceOptionView> listBasicOptions() {
        return listBasicOptions(null);
    }

    public List<DataSourceOptionView> listBasicOptions(Long runtimeClusterId) {
        List<DatasourceEntity> entities = datasourceMapper.selectList(selectOptionColumns(buildAccessibleQuery())
                .eq(DatasourceEntity::getEnabled, 1)
                .orderByAsc(DatasourceEntity::getProjectId)
                .orderByAsc(DatasourceEntity::getName));
        entities = filterByRuntimeCluster(entities, runtimeClusterId);
        List<DataSourceOptionView> result = new ArrayList<DataSourceOptionView>();
        for (DatasourceEntity entity : entities) {
            result.add(toOptionView(entity));
        }
        return result;
    }

    public Set<Long> listAccessibleIdsByType(String typeCode) {
        Set<Long> result = new LinkedHashSet<Long>();
        if (typeCode == null || typeCode.trim().isEmpty()) {
            return result;
        }
        String normalizedTypeCode = typeCode.trim();
        List<String> typeCodes = new ArrayList<String>();
        typeCodes.add(normalizedTypeCode);
        String lowerTypeCode = normalizedTypeCode.toLowerCase(Locale.ROOT);
        if (!typeCodes.contains(lowerTypeCode)) {
            typeCodes.add(lowerTypeCode);
        }
        String upperTypeCode = normalizedTypeCode.toUpperCase(Locale.ROOT);
        if (!typeCodes.contains(upperTypeCode)) {
            typeCodes.add(upperTypeCode);
        }
        List<DatasourceEntity> entities = datasourceMapper.selectList(buildAccessibleQuery()
                .select(DatasourceEntity::getId, DatasourceEntity::getTypeCode)
                .in(DatasourceEntity::getTypeCode, typeCodes));
        for (DatasourceEntity entity : entities) {
            if (entity.getId() != null) {
                result.add(entity.getId());
            }
        }
        return result;
    }

    public List<RunMetricFilterOptionView> listMetricFilterOptions() {
        List<DatasourceEntity> entities = datasourceMapper.selectList(selectMetricFilterOptionColumns(buildAccessibleQuery())
                .orderByAsc(DatasourceEntity::getProjectId)
                .orderByAsc(DatasourceEntity::getName));
        List<RunMetricFilterOptionView> result = new ArrayList<RunMetricFilterOptionView>();
        for (DatasourceEntity entity : entities) {
            RunMetricFilterOptionView option = new RunMetricFilterOptionView();
            option.setId(entity.getId());
            option.setName(entity.getName());
            option.setLabel(entity.getName() + " / " + entity.getTypeCode());
            option.setTypeCode(entity.getTypeCode());
            result.add(option);
        }
        return result;
    }

    public List<DataSourceOptionView> listBasicOptionsByTypes(Set<String> typeCodes) {
        return listBasicOptionsByTypes(typeCodes, null);
    }

    public List<DataSourceOptionView> listBasicOptionsByTypes(Set<String> typeCodes, Long runtimeClusterId) {
        if (typeCodes == null || typeCodes.isEmpty()) {
            return new ArrayList<DataSourceOptionView>();
        }
        List<DatasourceEntity> entities = datasourceMapper.selectList(selectOptionColumns(buildAccessibleQuery())
                .in(DatasourceEntity::getTypeCode, typeCodes)
                .eq(DatasourceEntity::getEnabled, 1)
                .orderByAsc(DatasourceEntity::getProjectId)
                .orderByAsc(DatasourceEntity::getName));
        entities = filterByRuntimeCluster(entities, runtimeClusterId);
        List<DataSourceOptionView> result = new ArrayList<DataSourceOptionView>();
        for (DatasourceEntity entity : entities) {
            result.add(toOptionView(entity));
        }
        return result;
    }

    public Map<Long, DataSourceListView> listBasicSummaryMap(Set<Long> datasourceIds) {
        Map<Long, DataSourceListView> result = new LinkedHashMap<Long, DataSourceListView>();
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return result;
        }
        List<DatasourceEntity> entities = datasourceMapper.selectList(buildAccessibleQuery()
                .select(DatasourceEntity::getId,
                        DatasourceEntity::getTenantId,
                        DatasourceEntity::getProjectId,
                        DatasourceEntity::getDeleted,
                        DatasourceEntity::getCreatedAt,
                        DatasourceEntity::getUpdatedAt,
                        DatasourceEntity::getName,
                        DatasourceEntity::getTypeCode,
                        DatasourceEntity::getSchemaVersionId,
                        DatasourceEntity::getEnabled,
                        DatasourceEntity::getExecutable)
                .in(DatasourceEntity::getId, datasourceIds));
        for (DatasourceEntity entity : entities) {
            DataSourceListView view = toBasicListView(entity);
            result.put(view.getId(), view);
        }
        hydrateApplicableClustersForListViews(new ArrayList<DataSourceListView>(result.values()));
        return result;
    }

    public Map<Long, String> listBasicNameMap(Set<Long> datasourceIds) {
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return result;
        }
        List<DatasourceEntity> entities = datasourceMapper.selectList(buildAccessibleQuery()
                .select(DatasourceEntity::getId, DatasourceEntity::getName)
                .in(DatasourceEntity::getId, datasourceIds));
        for (DatasourceEntity entity : entities) {
            result.put(entity.getId(), entity.getName());
        }
        return result;
    }

    public DataSourceDefinition get(Long id) {
        DatasourceEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            return null;
        }
        DataSourceDefinition definition = toDefinition(entity, true);
        List<DataSourceDefinition> definitions = new ArrayList<DataSourceDefinition>();
        definitions.add(definition);
        datasourceConnectionHealthService.hydrateDefinitions(definitions);
        hydrateApplicableClusters(definitions);
        return definition;
    }

    public DataSourceDefinition getForProject(Long projectId, Long id) {
        DatasourceEntity entity = findReadableEntityForProject(projectId, id);
        if (entity == null) {
            return null;
        }
        DataSourceDefinition definition = toDefinition(entity, true);
        List<DataSourceDefinition> definitions = new ArrayList<DataSourceDefinition>();
        definitions.add(definition);
        datasourceConnectionHealthService.hydrateDefinitions(definitions);
        hydrateApplicableClusters(definitions);
        return definition;
    }

    public DataSourceDefinition getInternal(Long id) {
        DatasourceEntity entity = findAccessibleEntity(id);
        return entity == null ? null : toDefinition(entity, false);
    }

    /**
     * Guards a persisted datasource at the point a new execution is accepted.
     * Editing a resource that refers to a disabled datasource remains allowed,
     * but no new run may use that datasource until it is enabled again.
     */
    public DataSourceDefinition requireRunnableForExecution(Long id) {
        DataSourceDefinition definition = getInternal(id);
        if (definition == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + id);
        }
        if (!Boolean.TRUE.equals(definition.getEnabled()) || !Boolean.TRUE.equals(definition.getExecutable())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Datasource must be enabled and executable before running");
        }
        return definition;
    }

    public DataSourceDefinition getInternalForProject(Long projectId, Long id) {
        DatasourceEntity entity = findReadableEntityForProject(projectId, id);
        return entity == null ? null : toDefinition(entity, false);
    }

    public void assertReadableIfPresent(Long id) {
        if (id == null) {
            return;
        }
        DatasourceEntity entity = datasourceMapper.selectOne(new LambdaQueryWrapper<DatasourceEntity>()
                .select(DatasourceEntity::getId,
                        DatasourceEntity::getProjectId)
                .eq(DatasourceEntity::getTenantId, securityService.currentTenantId())
                .eq(DatasourceEntity::getId, id)
                .last("limit 1"));
        if (entity == null) {
            return;
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATASOURCE,
                entity.getProjectId(), entity.getId(), "Datasource not found: " + id);
    }

    @Transactional
    public DataSourceDefinition save(DataSourceSaveRequest request) {
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        String currentTenantId = securityService.currentTenantId();
        DatasourceEntity entity = request.getId() == null ? new DatasourceEntity() : requireWritableEntity(request.getId());
        if (entity == null) {
            entity = new DatasourceEntity();
        }
        List<Long> applicableClusterIds = normalizeApplicableClusterIds(
                currentProjectId, entity.getId(), request.getApplicableClusterIds());
        datasourceTypeCapabilityService.ensureEnabled(request.getTypeCode());
        boolean newEntity = entity.getId() == null;
        DatasourceClusterBindingImpactView bindingImpact = newEntity || runtimeValidationService == null
                ? new DatasourceClusterBindingImpactView()
                : runtimeValidationService.previewDatasourceBindingImpact(entity.getId(), applicableClusterIds);
        if (!bindingImpact.getAffectedResources().isEmpty() && !Boolean.TRUE.equals(request.getConfirmClusterBindingImpact())) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Removing datasource cluster applicability affects " + bindingImpact.getAffectedResources().size()
                            + " resource(s); confirmClusterBindingImpact is required");
        }
        String originalConnectionFingerprint = entity.getConnectionFingerprint();
        MetadataSchemaDefinition schema = findDatasourceSchema(request.getSchemaVersionId(), request.getTypeCode());
        Map<String, Object> technicalMetadata = applyDefaults(request.getTechnicalMetadata(), schema, MetadataScope.TECHNICAL);
        technicalMetadata = preserveSensitiveValues(entity.getTechnicalMetadata(), technicalMetadata);
        technicalMetadata = normalizeDatasourceConnectionMetadata(request.getTypeCode(), technicalMetadata);
        Long resolvedSchemaVersionId = resolveSchemaVersionId(request, schema);
        Map<String, Object> encryptedTechnicalMetadata = encryptSensitive(technicalMetadata);
        String connectionFingerprint = datasourceConnectionFingerprintService.fingerprint(currentTenantId,
                request.getTypeCode(),
                encryptedTechnicalMetadata);
        boolean connectionDefinitionChanged = newEntity || !Objects.equals(originalConnectionFingerprint, connectionFingerprint);
        ensureUniqueName(currentProjectId, request.getName(), entity.getId());
        entity.setTenantId(currentTenantId);
        entity.setProjectId(currentProjectId);
        if (newEntity) {
            entity.setCreatedBy(securityService.currentUserId());
        }
        entity.setName(request.getName());
        entity.setTypeCode(request.getTypeCode());
        entity.setSchemaVersionId(resolvedSchemaVersionId);
        entity.setEnabled(Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);
        entity.setExecutable(Boolean.TRUE.equals(request.getExecutable()) ? 1 : 0);
        entity.setConnectionFingerprint(connectionFingerprint);
        entity.setManualConnectionTestTimeoutSeconds(normalizeTimeoutOverride(request.getManualConnectionTestTimeoutSeconds()));
        entity.setScheduledConnectionTestTimeoutSeconds(normalizeTimeoutOverride(request.getScheduledConnectionTestTimeoutSeconds()));
        entity.setTechnicalMetadata(encryptedTechnicalMetadata);
        entity.setBusinessMetadata(businessMetaModelMetadataService.normalizeForDatasource(request.getBusinessMetadata()));
        if (connectionDefinitionChanged) {
            applyUnknownConnectionSnapshot(entity);
        }
        if (entity.getId() == null) {
            datasourceMapper.insert(entity);
        } else {
            datasourceMapper.updateById(entity);
            if (connectionDefinitionChanged) {
                clearConnectionSnapshot(entity.getId());
            }
        }
        datasourceConnectionHealthService.ensureHealthRow(currentTenantId, connectionFingerprint);
        if (datasourceClusterBindingService != null) {
            datasourceClusterBindingService.replaceBindings(currentTenantId, entity.getId(), applicableClusterIds);
        }
        if (runtimeValidationService != null) {
            bindingImpact.setDatasourceId(entity.getId());
            runtimeValidationService.applyDatasourceBindingImpact(bindingImpact);
        }
        DataSourceDefinition saved = toDefinition(entity, true);
        List<DataSourceDefinition> definitions = new ArrayList<DataSourceDefinition>();
        definitions.add(saved);
        datasourceConnectionHealthService.hydrateDefinitions(definitions);
        hydrateApplicableClusters(definitions);
        return saved;
    }

    public DatasourceClusterBindingImpactView previewClusterBindingImpact(Long datasourceId, List<Long> applicableClusterIds) {
        requireWritableEntity(datasourceId);
        if (runtimeValidationService == null) {
            DatasourceClusterBindingImpactView empty = new DatasourceClusterBindingImpactView();
            empty.setDatasourceId(datasourceId);
            return empty;
        }
        return runtimeValidationService.previewDatasourceBindingImpact(datasourceId, applicableClusterIds);
    }

    public ConnectionTestResult testConnection(Long id) {
        return testConnection(id, null);
    }

    public ConnectionTestResult testConnection(Long id, Long runtimeClusterId) {
        requireExplicitRuntimeCluster(runtimeClusterId);
        DatasourceEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + id);
        }
        assertApplicableToRuntimeCluster(entity, runtimeClusterId);
        ensureConnectionFingerprint(entity);
        DataSourceDefinition definition = toDefinition(entity, true);
        ensureDatasourceCanTest(definition);
        final DataSourceDefinition probeDefinition = definition;
        int timeoutSeconds = datasourceConnectionHealthService.effectiveManualTimeout(definition);
        ConnectionTestResult result = datasourceConnectionHealthService.runManualProbe(definition, runtimeClusterId, new Callable<ConnectionTestResult>() {
            @Override
            public ConnectionTestResult call() {
                return executeConnectionTest(probeDefinition, runtimeClusterId,
                        RuntimeDatasourceProbeMode.STORED);
            }
        }, timeoutSeconds);
        persistConnectionSnapshot(id, result);
        return result;
    }

    public ConnectionTestResult testConnection(DataSourceSaveRequest request) {
        return testConnection(request, null);
    }

    public ConnectionTestResult testConnection(DataSourceSaveRequest request, Long runtimeClusterId) {
        requireExplicitRuntimeCluster(runtimeClusterId);
        List<Long> applicableClusterIds = request.getApplicableClusterIds();
        if (datasourceClusterBindingService != null && runtimeClusterId != null) {
            Long projectId = projectResourceAccessService.requireCurrentProjectId();
            applicableClusterIds = datasourceClusterBindingService.normalizeForSave(
                    projectId, request.getId(), request.getApplicableClusterIds());
            if (!applicableClusterIds.contains(runtimeClusterId)) {
                throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                        "Datasource is not applicable to the selected runtime cluster");
            }
        }
        datasourceTypeCapabilityService.ensureEnabled(request.getTypeCode());
        final DataSourceDefinition definition = buildDefinitionForTest(request);
        definition.setApplicableClusterIds(applicableClusterIds == null
                ? new ArrayList<Long>() : new ArrayList<Long>(applicableClusterIds));
        ensureDatasourceCanTest(definition);
        int timeoutSeconds = datasourceConnectionHealthService.effectiveManualTimeout(definition);
        return datasourceConnectionHealthService.runCurrentFormProbe(new Callable<ConnectionTestResult>() {
            @Override
            public ConnectionTestResult call() {
                return executeConnectionTest(definition, runtimeClusterId,
                        RuntimeDatasourceProbeMode.DRAFT_FORM);
            }
        }, timeoutSeconds);
    }

    public ModelDiscoveryResult discoverModels(Long id) {
        return discoverModels(id, null);
    }

    public ModelDiscoveryResult discoverModels(Long id, String keyword) {
        return discoverModels(id, keyword, null, null);
    }

    public ModelDiscoveryResult discoverModels(Long id, String keyword, Integer pageNo, Integer pageSize) {
        return discoverModels(id, keyword, pageNo, pageSize, null);
    }

    public ModelDiscoveryResult discoverModels(Long id, String keyword, Integer pageNo, Integer pageSize, Long runtimeClusterId) {
        requireExplicitRuntimeCluster(runtimeClusterId);
        DatasourceEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + id);
        }
        assertApplicableToRuntimeCluster(entity, runtimeClusterId);
        DataSourceDefinition definition = get(id);
        datasourceTypeCapabilityService.ensureReadable(definition.getTypeCode());
        return runtimeDatasourceProbeRouter().discover(definition, runtimeClusterId, keyword, pageNo, pageSize);
    }

    public ModelDiscoveryOptionResult discoverModelOptions(Long id, String keyword, Integer pageNo, Integer pageSize) {
        return discoverModelOptions(id, keyword, pageNo, pageSize, null);
    }

    public ModelDiscoveryOptionResult discoverModelOptions(Long id, String keyword, Integer pageNo, Integer pageSize, Long runtimeClusterId) {
        requireExplicitRuntimeCluster(runtimeClusterId);
        DatasourceEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + id);
        }
        assertApplicableToRuntimeCluster(entity, runtimeClusterId);
        DataSourceDefinition definition = get(id);
        datasourceTypeCapabilityService.ensureReadable(definition.getTypeCode());
        return runtimeDatasourceProbeRouter().discoverOptions(definition, runtimeClusterId, keyword, pageNo, pageSize);
    }

    public List<DatasourceConnectionTestRecordView> connectionHistory(Long id, Integer days, Integer limit) {
        return connectionHistory(id, days, limit, null);
    }

    public List<DatasourceConnectionTestRecordView> connectionHistory(Long id, Integer days, Integer limit, Long runtimeClusterId) {
        requireExplicitRuntimeCluster(runtimeClusterId);
        DatasourceEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + id);
        }
        assertApplicableToRuntimeCluster(entity, runtimeClusterId);
        ensureConnectionFingerprint(entity);
        return datasourceConnectionHealthService.history(entity.getTenantId(), runtimeClusterId, entity.getConnectionFingerprint(), days, limit);
    }

    public void dispatchDueScheduledConnectionTests() {
        if (!datasourceConnectionHealthService.enabled()) {
            return;
        }
        List<DatasourceEntity> entities = datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getEnabled, 1)
                .eq(DatasourceEntity::getExecutable, 1)
                .orderByAsc(DatasourceEntity::getTenantId)
                .orderByAsc(DatasourceEntity::getConnectionFingerprint)
                .orderByAsc(DatasourceEntity::getId));
        Map<String, ScheduledProbeCandidate> candidates = new LinkedHashMap<String, ScheduledProbeCandidate>();
        Map<Long, List<Long>> applicableClusters = new LinkedHashMap<Long, List<Long>>();
        if (datasourceClusterBindingService != null) {
            Map<String, List<Long>> datasourceIdsByTenant = new LinkedHashMap<String, List<Long>>();
            for (DatasourceEntity entity : entities) {
                List<Long> tenantDatasourceIds = datasourceIdsByTenant.get(entity.getTenantId());
                if (tenantDatasourceIds == null) {
                    tenantDatasourceIds = new ArrayList<Long>();
                    datasourceIdsByTenant.put(entity.getTenantId(), tenantDatasourceIds);
                }
                tenantDatasourceIds.add(entity.getId());
            }
            for (Map.Entry<String, List<Long>> entry : datasourceIdsByTenant.entrySet()) {
                applicableClusters.putAll(datasourceClusterBindingService.listApplicableClusterIds(
                        entry.getKey(), entry.getValue()));
            }
        }
        for (DatasourceEntity entity : entities) {
            try {
                ensureConnectionFingerprint(entity);
                if (!hasText(entity.getConnectionFingerprint())) {
                    continue;
                }
                DataSourceDefinition definition = toDefinition(entity, true);
                int timeoutSeconds = datasourceConnectionHealthService.effectiveScheduledTimeout(definition);
                List<Long> clusterIds = applicableClusters.get(entity.getId());
                if (clusterIds == null || clusterIds.isEmpty()) {
                    continue;
                }
                for (Long runtimeClusterId : clusterIds) {
                    String key = entity.getTenantId() + "\n" + runtimeClusterId + "\n" + entity.getConnectionFingerprint();
                    ScheduledProbeCandidate candidate = candidates.get(key);
                    if (candidate == null) candidates.put(key, new ScheduledProbeCandidate(definition, runtimeClusterId, timeoutSeconds));
                    else if (timeoutSeconds > candidate.timeoutSeconds) candidate.timeoutSeconds = timeoutSeconds;
                }
            } catch (StudioException exception) {
                // A stale encrypted value must not stop health checks for other datasources.
                log.warn("Skipping scheduled connection health probe for datasource {} because its configuration is unavailable: {}",
                        entity.getId(), exception.getMessage());
            }
        }
        LocalDateTime roundStartedAt = LocalDateTime.now();
        int processed = 0;
        for (ScheduledProbeCandidate candidate : candidates.values()) {
            if (processed >= datasourceConnectionHealthService.batchSize()) {
                break;
            }
            if (roundStartedAt.plusSeconds(datasourceConnectionHealthService.roundBudgetSeconds()).isBefore(LocalDateTime.now())) {
                break;
            }
            if (!datasourceConnectionHealthService.scheduledDue(candidate.definition.getTenantId(), candidate.runtimeClusterId, candidate.definition.getConnectionFingerprint())) {
                continue;
            }
            final DataSourceDefinition probeDefinition = candidate.definition;
            datasourceConnectionHealthService.submitScheduledProbe(probeDefinition, candidate.runtimeClusterId, new Callable<ConnectionTestResult>() {
                @Override
                public ConnectionTestResult call() {
                    return executeConnectionTest(probeDefinition, candidate.runtimeClusterId,
                            RuntimeDatasourceProbeMode.SCHEDULED_HEALTH);
                }
            }, candidate.timeoutSeconds);
            processed++;
        }
        try {
            datasourceConnectionHealthService.cleanupExpiredHistory();
        } catch (Exception ignored) {
            // History cleanup must not block scheduled health refresh.
        }
    }

    @Transactional
    public void delete(Long id) {
        DatasourceEntity entity = requireWritableEntity(id);
        dataModelMapper.delete(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getDatasourceId, id));
        if (datasourceClusterBindingService != null) {
            datasourceClusterBindingService.deleteBindings(entity.getTenantId(), id);
        }
        datasourceMapper.deleteById(id);
        dataModelIndexRebuildQueueService.enqueueDatasourceDelete(id);
    }

    private DataSourceDefinition toDefinition(DatasourceEntity entity, boolean maskSensitive) {
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(entity.getId());
        definition.setTenantId(entity.getTenantId());
        definition.setProjectId(entity.getProjectId());
        definition.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        definition.setCreatedAt(entity.getCreatedAt());
        definition.setUpdatedAt(entity.getUpdatedAt());
        definition.setCreatedBy(entity.getCreatedBy());
        definition.setName(entity.getName());
        definition.setTypeCode(entity.getTypeCode());
        definition.setSchemaVersionId(resolveReadableSchemaVersionId(entity));
        definition.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
        definition.setExecutable(entity.getExecutable() != null && entity.getExecutable() == 1);
        definition.setConnectionFingerprint(entity.getConnectionFingerprint());
        definition.setConnectionStatus(parseConnectionStatus(entity.getConnectionStatus()));
        definition.setLastConnectionTestAt(entity.getLastConnectionTestAt());
        definition.setLastConnectionTestMessage(entity.getLastConnectionTestMessage());
        definition.setLastConnectionTestDurationMs(entity.getLastConnectionTestDurationMs());
        definition.setConnectionTesting(false);
        definition.setConnectionStale(false);
        definition.setManualConnectionTestTimeoutSeconds(entity.getManualConnectionTestTimeoutSeconds());
        definition.setScheduledConnectionTestTimeoutSeconds(entity.getScheduledConnectionTestTimeoutSeconds());
        if (maskSensitive) {
            SensitiveMetadataView publicMetadata = publicSensitiveMetadata(entity.getTechnicalMetadata());
            definition.setTechnicalMetadata(publicMetadata.metadata);
            definition.setSavedSensitiveFieldKeys(publicMetadata.savedSensitiveFieldKeys);
        } else {
            definition.setTechnicalMetadata(entity.getTechnicalMetadata());
        }
        definition.setBusinessMetadata(entity.getBusinessMetadata());
        return definition;
    }

    private DataSourceListView toListView(DatasourceEntity entity) {
        DataSourceListView view = new DataSourceListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setTypeCode(entity.getTypeCode());
        view.setSchemaVersionId(entity.getSchemaVersionId());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setExecutable(entity.getExecutable() != null && entity.getExecutable().intValue() == 1);
        view.setConnectionFingerprint(entity.getConnectionFingerprint());
        view.setConnectionStatus(parseConnectionStatus(entity.getConnectionStatus()));
        view.setLastConnectionTestAt(entity.getLastConnectionTestAt());
        view.setLastConnectionTestMessage(entity.getLastConnectionTestMessage());
        view.setLastConnectionTestDurationMs(entity.getLastConnectionTestDurationMs());
        view.setConnectionTesting(false);
        view.setConnectionStale(false);
        view.setManualConnectionTestTimeoutSeconds(entity.getManualConnectionTestTimeoutSeconds());
        view.setScheduledConnectionTestTimeoutSeconds(entity.getScheduledConnectionTestTimeoutSeconds());
        return view;
    }

    private LambdaQueryWrapper<DatasourceEntity> selectSummaryColumns(LambdaQueryWrapper<DatasourceEntity> queryWrapper) {
        return queryWrapper.select(DatasourceEntity::getId,
                DatasourceEntity::getTenantId,
                DatasourceEntity::getProjectId,
                DatasourceEntity::getDeleted,
                DatasourceEntity::getCreatedAt,
                DatasourceEntity::getUpdatedAt,
                DatasourceEntity::getName,
                DatasourceEntity::getTypeCode,
                DatasourceEntity::getSchemaVersionId,
                DatasourceEntity::getEnabled,
                DatasourceEntity::getExecutable,
                DatasourceEntity::getConnectionFingerprint,
                DatasourceEntity::getConnectionStatus,
                DatasourceEntity::getLastConnectionTestAt,
                DatasourceEntity::getLastConnectionTestMessage,
                DatasourceEntity::getLastConnectionTestDurationMs,
                DatasourceEntity::getManualConnectionTestTimeoutSeconds,
                DatasourceEntity::getScheduledConnectionTestTimeoutSeconds);
    }

    private LambdaQueryWrapper<DatasourceEntity> selectOptionColumns(LambdaQueryWrapper<DatasourceEntity> queryWrapper) {
        return queryWrapper.select(DatasourceEntity::getId,
                DatasourceEntity::getTenantId,
                DatasourceEntity::getProjectId,
                DatasourceEntity::getDeleted,
                DatasourceEntity::getCreatedAt,
                DatasourceEntity::getUpdatedAt,
                DatasourceEntity::getName,
                DatasourceEntity::getTypeCode,
                DatasourceEntity::getSchemaVersionId,
                DatasourceEntity::getEnabled,
                DatasourceEntity::getExecutable);
    }

    private LambdaQueryWrapper<DatasourceEntity> selectMetricFilterOptionColumns(LambdaQueryWrapper<DatasourceEntity> queryWrapper) {
        return queryWrapper.select(DatasourceEntity::getId,
                DatasourceEntity::getName,
                DatasourceEntity::getTypeCode);
    }

    private DataSourceListView toBasicListView(DatasourceEntity entity) {
        DataSourceListView view = new DataSourceListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setTypeCode(entity.getTypeCode());
        view.setSchemaVersionId(entity.getSchemaVersionId());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setExecutable(entity.getExecutable() != null && entity.getExecutable().intValue() == 1);
        return view;
    }

    private DataSourceOptionView toOptionView(DatasourceEntity entity) {
        DataSourceOptionView view = new DataSourceOptionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setTypeCode(entity.getTypeCode());
        view.setSchemaVersionId(entity.getSchemaVersionId());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setExecutable(entity.getExecutable() != null && entity.getExecutable().intValue() == 1);
        return view;
    }

    private List<Long> normalizeApplicableClusterIds(Long projectId,
                                                     Long existingDatasourceId,
                                                     List<Long> applicableClusterIds) {
        if (datasourceClusterBindingService == null) {
            return applicableClusterIds == null ? new ArrayList<Long>() : new ArrayList<Long>(applicableClusterIds);
        }
        return datasourceClusterBindingService.normalizeForSave(
                projectId, existingDatasourceId, applicableClusterIds);
    }

    private void assertApplicableToRuntimeCluster(DatasourceEntity entity, Long runtimeClusterId) {
        requireExplicitRuntimeCluster(runtimeClusterId);
        if (datasourceClusterBindingService == null || entity == null || runtimeClusterId == null) {
            return;
        }
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        datasourceClusterBindingService.filterApplicableDatasourceIds(currentProjectId, runtimeClusterId,
                java.util.Collections.singletonList(entity.getId()));
        datasourceClusterBindingService.assertDatasourceApplicable(entity.getId(), runtimeClusterId);
    }

    private List<DatasourceEntity> filterByRuntimeCluster(List<DatasourceEntity> entities, Long runtimeClusterId) {
        if (datasourceClusterBindingService == null || runtimeClusterId == null || entities == null || entities.isEmpty()) {
            return entities;
        }
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        List<Long> datasourceIds = new ArrayList<Long>();
        for (DatasourceEntity entity : entities) {
            if (entity.getId() != null) {
                datasourceIds.add(entity.getId());
            }
        }
        Set<Long> allowedIds;
        try {
            allowedIds = datasourceClusterBindingService.filterApplicableDatasourceIds(
                    currentProjectId, runtimeClusterId, datasourceIds);
        } catch (StudioException ex) {
            // An existing resource may reference a cluster that has just been
            // disabled or de-authorized. Returning no choices keeps the
            // resource editable while save/execute paths continue to reject it.
            return new ArrayList<DatasourceEntity>();
        }
        List<DatasourceEntity> result = new ArrayList<DatasourceEntity>();
        for (DatasourceEntity entity : entities) {
            if (allowedIds.contains(entity.getId())) {
                result.add(entity);
            }
        }
        return result;
    }

    private void hydrateApplicableClusters(List<DataSourceDefinition> definitions) {
        if (datasourceClusterBindingService == null || definitions == null || definitions.isEmpty()) {
            return;
        }
        List<Long> datasourceIds = new ArrayList<Long>();
        for (DataSourceDefinition definition : definitions) {
            if (definition != null && definition.getId() != null) {
                datasourceIds.add(definition.getId());
            }
        }
        Map<Long, List<Long>> idsByDatasource = datasourceClusterBindingService.listApplicableClusterIds(datasourceIds);
        Map<Long, List<com.jdragon.studio.dto.model.RuntimeClusterView>> clustersByDatasource =
                datasourceClusterBindingService.listApplicableClusters(datasourceIds);
        for (DataSourceDefinition definition : definitions) {
            List<Long> ids = idsByDatasource.get(definition.getId());
            definition.setApplicableClusterIds(ids == null ? new ArrayList<Long>() : ids);
            List<com.jdragon.studio.dto.model.RuntimeClusterView> clusters = clustersByDatasource.get(definition.getId());
            definition.setApplicableClusters(clusters == null
                    ? new ArrayList<com.jdragon.studio.dto.model.RuntimeClusterView>() : clusters);
        }
    }

    private void hydrateApplicableClustersForListViews(List<DataSourceListView> views) {
        if (datasourceClusterBindingService == null || views == null || views.isEmpty()) {
            return;
        }
        List<Long> datasourceIds = new ArrayList<Long>();
        for (DataSourceListView view : views) {
            if (view != null && view.getId() != null) {
                datasourceIds.add(view.getId());
            }
        }
        Map<Long, List<Long>> idsByDatasource = datasourceClusterBindingService.listApplicableClusterIds(datasourceIds);
        Map<Long, List<com.jdragon.studio.dto.model.RuntimeClusterView>> clustersByDatasource =
                datasourceClusterBindingService.listApplicableClusters(datasourceIds);
        for (DataSourceListView view : views) {
            List<Long> ids = idsByDatasource.get(view.getId());
            view.setApplicableClusterIds(ids == null ? new ArrayList<Long>() : ids);
            List<com.jdragon.studio.dto.model.RuntimeClusterView> clusters = clustersByDatasource.get(view.getId());
            view.setApplicableClusters(clusters == null
                    ? new ArrayList<com.jdragon.studio.dto.model.RuntimeClusterView>() : clusters);
        }
    }

    private DataSourceDefinition buildDefinitionForTest(DataSourceSaveRequest request) {
        DatasourceEntity entity = request.getId() == null ? null : requireWritableEntity(request.getId());
        MetadataSchemaDefinition schema = findDatasourceSchema(request.getSchemaVersionId(), request.getTypeCode());
        Map<String, Object> technicalMetadata = applyDefaults(request.getTechnicalMetadata(), schema, MetadataScope.TECHNICAL);
        technicalMetadata = preserveSensitiveValues(entity == null ? null : entity.getTechnicalMetadata(), technicalMetadata);
        technicalMetadata = normalizeDatasourceConnectionMetadata(request.getTypeCode(), technicalMetadata);

        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(request.getId());
        definition.setTenantId(securityService.currentTenantId());
        definition.setProjectId(projectResourceAccessService.requireCurrentProjectId());
        definition.setName(request.getName());
        definition.setTypeCode(request.getTypeCode());
        definition.setSchemaVersionId(resolveSchemaVersionId(request, schema));
        definition.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        definition.setExecutable(Boolean.TRUE.equals(request.getExecutable()));
        definition.setManualConnectionTestTimeoutSeconds(normalizeTimeoutOverride(request.getManualConnectionTestTimeoutSeconds()));
        definition.setScheduledConnectionTestTimeoutSeconds(normalizeTimeoutOverride(request.getScheduledConnectionTestTimeoutSeconds()));
        definition.setTechnicalMetadata(technicalMetadata);
        definition.setBusinessMetadata(businessMetaModelMetadataService.normalizeForDatasource(request.getBusinessMetadata()));
        return definition;
    }

    private ConnectionTestResult executeConnectionTest(DataSourceDefinition definition, Long runtimeClusterId,
                                                       RuntimeDatasourceProbeMode mode) {
        ConnectionTestResult result = runtimeDatasourceProbeRouter().test(definition, runtimeClusterId, mode);
        if (result == null) {
            result = new ConnectionTestResult();
            result.setSuccess(false);
            result.setStatus(DataSourceConnectionStatus.UNAVAILABLE);
            result.setMessage("Connection test returned no result");
        }
        result.setMessage(truncateConnectionMessage(result.getMessage()));
        return result;
    }

    private RuntimeDatasourceProbeRouter runtimeDatasourceProbeRouter() {
        if (runtimeDatasourceProbeRouter == null) {
            throw new IllegalStateException("Runtime datasource probe router is not configured");
        }
        return runtimeDatasourceProbeRouter;
    }

    private void persistConnectionSnapshot(Long id, ConnectionTestResult result) {
        if (id == null || result == null) {
            return;
        }
        if (Boolean.TRUE.equals(result.getBusy()) || Boolean.TRUE.equals(result.getTesting())) {
            return;
        }
        DataSourceConnectionStatus status = result.getStatus() == null
                ? (result.isSuccess() ? DataSourceConnectionStatus.AVAILABLE : DataSourceConnectionStatus.UNAVAILABLE)
                : result.getStatus();
        LocalDateTime testAt = result.getLastTestAt() == null ? LocalDateTime.now() : result.getLastTestAt();
        datasourceMapper.update(null, new LambdaUpdateWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getId, id)
                .set(DatasourceEntity::getConnectionStatus, status.name())
                .set(DatasourceEntity::getLastConnectionTestAt, testAt)
                .set(DatasourceEntity::getLastConnectionTestMessage, truncateConnectionMessage(result.getMessage()))
                .set(DatasourceEntity::getLastConnectionTestDurationMs, result.getDurationMs()));
    }

    private void applyUnknownConnectionSnapshot(DatasourceEntity entity) {
        entity.setConnectionStatus(DataSourceConnectionStatus.UNKNOWN.name());
        entity.setLastConnectionTestAt(null);
        entity.setLastConnectionTestMessage(null);
        entity.setLastConnectionTestDurationMs(null);
    }

    private void clearConnectionSnapshot(Long id) {
        datasourceMapper.update(null, new LambdaUpdateWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getId, id)
                .set(DatasourceEntity::getConnectionStatus, DataSourceConnectionStatus.UNKNOWN.name())
                .set(DatasourceEntity::getLastConnectionTestAt, null)
                .set(DatasourceEntity::getLastConnectionTestMessage, null)
                .set(DatasourceEntity::getLastConnectionTestDurationMs, null));
    }

    private boolean connectionDefinitionChanged(boolean newEntity,
                                                String originalTypeCode,
                                                Long originalSchemaVersionId,
                                                Map<String, Object> originalTechnicalMetadata,
                                                String nextTypeCode,
                                                Long nextSchemaVersionId,
                                                Map<String, Object> nextTechnicalMetadata) {
        return newEntity
                || !Objects.equals(originalTypeCode, nextTypeCode)
                || !Objects.equals(originalSchemaVersionId, nextSchemaVersionId)
                || !Objects.equals(originalTechnicalMetadata, copyMetadata(nextTechnicalMetadata));
    }

    private void ensureConnectionFingerprint(DatasourceEntity entity) {
        if (entity == null || hasText(entity.getConnectionFingerprint())) {
            return;
        }
        String fingerprint = datasourceConnectionFingerprintService.fingerprint(entity.getTenantId(),
                entity.getTypeCode(),
                entity.getTechnicalMetadata());
        entity.setConnectionFingerprint(fingerprint);
        datasourceMapper.update(null, new LambdaUpdateWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getId, entity.getId())
                .set(DatasourceEntity::getConnectionFingerprint, fingerprint));
        datasourceConnectionHealthService.ensureHealthRow(entity.getTenantId(), fingerprint);
    }

    private Integer normalizeTimeoutOverride(Integer value) {
        if (value == null) {
            return null;
        }
        int maxTimeoutSeconds = 120;
        if (value.intValue() < 1 || value.intValue() > maxTimeoutSeconds) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Datasource connection test timeout must be between 1 and " + maxTimeoutSeconds + " seconds");
        }
        return value;
    }

    private DataSourceConnectionStatus parseConnectionStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return DataSourceConnectionStatus.UNKNOWN;
        }
        try {
            return DataSourceConnectionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return DataSourceConnectionStatus.UNKNOWN;
        }
    }

    private String truncateConnectionMessage(String message) {
        if (message == null || message.length() <= MAX_CONNECTION_TEST_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_CONNECTION_TEST_MESSAGE_LENGTH);
    }

    private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        if (metadata != null) {
            copy.putAll(metadata);
        }
        return copy;
    }

    private void ensureDatasourceCanTest(DataSourceDefinition definition) {
        if (definition == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found");
        }
        datasourceTypeCapabilityService.ensureExecutable(definition.getTypeCode());
        if (!Boolean.TRUE.equals(definition.getEnabled()) || !Boolean.TRUE.equals(definition.getExecutable())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource must be enabled and executable before testing connection");
        }
    }

    private LambdaQueryWrapper<DatasourceEntity> buildAccessibleQuery() {
        LambdaQueryWrapper<DatasourceEntity> queryWrapper = new LambdaQueryWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getTenantId, securityService.currentTenantId());
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return queryWrapper;
        }
        List<Long> sharedIds = projectResourceAccessService.sharedResourceIdList(StudioConstants.RESOURCE_TYPE_DATASOURCE);
        if (sharedIds.isEmpty()) {
            queryWrapper.eq(DatasourceEntity::getProjectId, currentProjectId);
            return queryWrapper;
        }
        queryWrapper.and(wrapper -> wrapper.eq(DatasourceEntity::getProjectId, currentProjectId)
                .or()
                .in(DatasourceEntity::getId, sharedIds));
        return queryWrapper;
    }

    private DatasourceEntity findAccessibleEntity(Long id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        if (entity == null || !Objects.equals(securityService.currentTenantId(), entity.getTenantId())) {
            return null;
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATASOURCE,
                entity.getProjectId(), entity.getId(), "Datasource not found: " + id);
        return entity;
    }

    private DatasourceEntity findReadableEntityForProject(Long projectId, Long id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        if (entity == null || !Objects.equals(securityService.currentTenantId(), entity.getTenantId())) {
            return null;
        }
        projectResourceAccessService.assertReadableFromProject(projectId,
                StudioConstants.RESOURCE_TYPE_DATASOURCE,
                entity.getProjectId(), entity.getId(), "Datasource not found: " + id);
        return entity;
    }

    private DatasourceEntity requireWritableEntity(Long id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        if (entity == null || !Objects.equals(securityService.currentTenantId(), entity.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + id);
        }
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private void requireExplicitRuntimeCluster(Long runtimeClusterId) {
        if (runtimeClusterId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Runtime cluster is required");
        }
    }

    private void ensureUniqueName(Long projectId, String name, Long selfId) {
        if (projectId == null || name == null || name.trim().isEmpty()) {
            return;
        }
        List<DatasourceEntity> duplicates = datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getProjectId, projectId)
                .eq(DatasourceEntity::getName, name.trim()));
        for (DatasourceEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource name already exists in the current project");
        }
    }

    private Map<String, Object> encryptSensitive(Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (input == null) {
            return output;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && isSensitive(entry.getKey()) && !String.valueOf(value).startsWith("ENC(")) {
                output.put(entry.getKey(), "ENC(" + encryptionService.encrypt(String.valueOf(value)) + ")");
            } else {
                output.put(entry.getKey(), value);
            }
        }
        return output;
    }

    private SensitiveMetadataView publicSensitiveMetadata(Map<String, Object> input) {
        SensitiveMetadataView result = new SensitiveMetadataView();
        if (input == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (isSensitive(entry.getKey())) {
                if (value != null && !String.valueOf(value).trim().isEmpty()) {
                    result.savedSensitiveFieldKeys.add(entry.getKey());
                }
                continue;
            }
            result.metadata.put(entry.getKey(), value);
        }
        return result;
    }

    private Map<String, Object> preserveSensitiveValues(Map<String, Object> existing,
                                                        Map<String, Object> incoming) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (incoming != null) {
            output.putAll(incoming);
        }
        if (existing == null || existing.isEmpty()) {
            return output;
        }
        for (Map.Entry<String, Object> entry : existing.entrySet()) {
            String key = entry.getKey();
            if (!isSensitive(key)) {
                continue;
            }
            if (!output.containsKey(key)) {
                output.put(key, entry.getValue());
                continue;
            }
            Object incomingValue = output.get(key);
            if (isBlankSensitiveValue(incomingValue)
                    || isExistingSensitiveMask(entry.getValue(), incomingValue)) {
                output.put(key, entry.getValue());
            }
        }
        return output;
    }

    private boolean isBlankSensitiveValue(Object value) {
        return value == null || (value instanceof String && ((String) value).trim().isEmpty());
    }

    /** Supports clients that were open before secret values stopped being returned. */
    private boolean isExistingSensitiveMask(Object existingValue, Object incomingValue) {
        if (!(existingValue instanceof String) || !(incomingValue instanceof String)) {
            return false;
        }
        String existingText = String.valueOf(existingValue);
        String incomingText = String.valueOf(incomingValue);
        if (existingText.equals(incomingText)) {
            return true;
        }
        if (!existingText.startsWith("ENC(") || !existingText.endsWith(")")) {
            return false;
        }
        try {
            String cipher = existingText.substring(4, existingText.length() - 1);
            return incomingText.equals(encryptionService.mask(encryptionService.decrypt(cipher)));
        } catch (RuntimeException ignored) {
            // A damaged legacy ciphertext must not make an unrelated edit fail.
            return false;
        }
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("accesskey")
                || normalized.contains("apikey")
                || normalized.contains("api_key")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("privatekey")
                || normalized.contains("cookie");
    }

    private static final class SensitiveMetadataView {
        private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        private final List<String> savedSensitiveFieldKeys = new ArrayList<String>();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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

    private Long resolveSchemaVersionId(DataSourceSaveRequest request, MetadataSchemaDefinition schema) {
        if (request.getSchemaVersionId() != null) {
            return request.getSchemaVersionId();
        }
        return schema == null ? null : schema.getCurrentVersionId();
    }

    private Long resolveReadableSchemaVersionId(DatasourceEntity entity) {
        if (entity.getSchemaVersionId() != null
                && metadataSchemaService.findSchemaByVersionId(entity.getSchemaVersionId()) != null) {
            return entity.getSchemaVersionId();
        }
        MetadataSchemaDefinition schema = findDatasourceSchema(null, entity.getTypeCode());
        return schema == null ? entity.getSchemaVersionId() : schema.getCurrentVersionId();
    }

    private MetadataSchemaDefinition findDatasourceSchema(Long schemaVersionId, String typeCode) {
        if (schemaVersionId != null) {
            MetadataSchemaDefinition schema = metadataSchemaService.findSchemaByVersionId(schemaVersionId);
            if (schema != null && "datasource".equalsIgnoreCase(schema.getObjectType())) {
                return schema;
            }
        }
        MetadataSchemaDefinition preferred = metadataSchemaService.findTechnicalMetaModel(typeCode, "source");
        if (preferred != null) {
            return preferred;
        }
        for (MetadataSchemaDefinition schema : metadataSchemaService.listSchemas()) {
            if ("datasource".equalsIgnoreCase(schema.getObjectType())
                    && typeCode != null
                    && typeCode.equalsIgnoreCase(schema.getTypeCode())
                    && schema.getCurrentVersionId() != null) {
                return schema;
            }
        }
        return null;
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
            Object defaultValue = parseDefaultValue(field);
            if (defaultValue != null) {
                output.put(field.getFieldKey(), defaultValue);
            }
        }
        return output;
    }

    /**
     * Kafka topics and consumer groups belong to models/tasks, not the shared
     * broker connection. Drop legacy datasource-level values when a datasource
     * is saved while retaining read-time compatibility for old records.
     */
    static Map<String, Object> normalizeDatasourceConnectionMetadata(String typeCode,
                                                                      Map<String, Object> metadata) {
        if ("kafka".equalsIgnoreCase(typeCode)) {
            return KafkaConfigurationSupport.normalizeDatasourceMetadata(metadata);
        }
        return metadata == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(metadata);
    }

    private Object parseDefaultValue(MetadataFieldDefinition field) {
        String defaultValue = field.getDefaultValue();
        if (defaultValue == null || defaultValue.trim().isEmpty()) {
            return null;
        }
        FieldValueType valueType = field.getValueType();
        if (valueType == null) {
            return defaultValue;
        }
        try {
            switch (valueType) {
                case BOOLEAN:
                    return Boolean.parseBoolean(defaultValue);
                case INTEGER:
                    return Integer.parseInt(defaultValue);
                case LONG:
                    return Long.parseLong(defaultValue);
                case DECIMAL:
                    return new BigDecimal(defaultValue);
                default:
                    return defaultValue;
            }
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static final class ScheduledProbeCandidate {
        private final DataSourceDefinition definition;
        private final Long runtimeClusterId;
        private int timeoutSeconds;

        private ScheduledProbeCandidate(DataSourceDefinition definition, Long runtimeClusterId, int timeoutSeconds) {
            this.definition = definition;
            this.runtimeClusterId = runtimeClusterId;
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}

