package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataSourceListView;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.DatasourceConnectionTestRecordView;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Service
public class DataSourceService {
    private static final int MAX_CONNECTION_TEST_MESSAGE_LENGTH = 1000;

    private final DatasourceMapper datasourceMapper;
    private final DataModelMapper dataModelMapper;
    private final EncryptionService encryptionService;
    private final AggregationSourceCapabilityProvider capabilityProvider;
    private final MetadataSchemaService metadataSchemaService;
    private final DataModelIndexRebuildQueueService dataModelIndexRebuildQueueService;
    private final BusinessMetaModelMetadataService businessMetaModelMetadataService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final DatasourceConnectionFingerprintService datasourceConnectionFingerprintService;
    private final DatasourceConnectionHealthService datasourceConnectionHealthService;

    public DataSourceService(DatasourceMapper datasourceMapper,
                             DataModelMapper dataModelMapper,
                             EncryptionService encryptionService,
                             AggregationSourceCapabilityProvider capabilityProvider,
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
        this.capabilityProvider = capabilityProvider;
        this.metadataSchemaService = metadataSchemaService;
        this.dataModelIndexRebuildQueueService = dataModelIndexRebuildQueueService;
        this.businessMetaModelMetadataService = businessMetaModelMetadataService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.datasourceConnectionFingerprintService = datasourceConnectionFingerprintService;
        this.datasourceConnectionHealthService = datasourceConnectionHealthService;
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
        return result;
    }

    public List<DataSourceListView> listSummaries() {
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
                        DatasourceEntity::getExecutable,
                        DatasourceEntity::getConnectionFingerprint,
                        DatasourceEntity::getConnectionStatus,
                        DatasourceEntity::getLastConnectionTestAt,
                        DatasourceEntity::getLastConnectionTestMessage,
                        DatasourceEntity::getLastConnectionTestDurationMs,
                        DatasourceEntity::getManualConnectionTestTimeoutSeconds,
                        DatasourceEntity::getScheduledConnectionTestTimeoutSeconds)
                .orderByAsc(DatasourceEntity::getProjectId)
                .orderByAsc(DatasourceEntity::getName));
        List<DataSourceDefinition> definitions = new ArrayList<DataSourceDefinition>();
        for (DatasourceEntity entity : entities) {
            definitions.add(toDefinition(entity, true));
        }
        datasourceConnectionHealthService.hydrateDefinitions(definitions);
        List<DataSourceListView> result = new ArrayList<DataSourceListView>();
        for (DataSourceDefinition definition : definitions) {
            result.add(toListView(definition));
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
        return definition;
    }

    public DataSourceDefinition getInternal(Long id) {
        DatasourceEntity entity = findAccessibleEntity(id);
        return entity == null ? null : toDefinition(entity, false);
    }

    @Transactional
    public DataSourceDefinition save(DataSourceSaveRequest request) {
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        String currentTenantId = securityService.currentTenantId();
        datasourceTypeCapabilityService.ensureEnabled(request.getTypeCode());
        DatasourceEntity entity = request.getId() == null ? new DatasourceEntity() : requireWritableEntity(request.getId());
        if (entity == null) {
            entity = new DatasourceEntity();
        }
        boolean newEntity = entity.getId() == null;
        String originalConnectionFingerprint = entity.getConnectionFingerprint();
        MetadataSchemaDefinition schema = findDatasourceSchema(request.getSchemaVersionId(), request.getTypeCode());
        Map<String, Object> technicalMetadata = applyDefaults(request.getTechnicalMetadata(), schema, MetadataScope.TECHNICAL);
        technicalMetadata = preserveSensitiveValues(entity.getTechnicalMetadata(), technicalMetadata);
        Long resolvedSchemaVersionId = resolveSchemaVersionId(request, schema);
        Map<String, Object> encryptedTechnicalMetadata = encryptSensitive(technicalMetadata);
        String connectionFingerprint = datasourceConnectionFingerprintService.fingerprint(currentTenantId,
                request.getTypeCode(),
                encryptedTechnicalMetadata);
        boolean connectionDefinitionChanged = newEntity || !Objects.equals(originalConnectionFingerprint, connectionFingerprint);
        ensureUniqueName(currentProjectId, request.getName(), entity.getId());
        entity.setTenantId(currentTenantId);
        entity.setProjectId(currentProjectId);
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
        DataSourceDefinition saved = toDefinition(entity, true);
        List<DataSourceDefinition> definitions = new ArrayList<DataSourceDefinition>();
        definitions.add(saved);
        datasourceConnectionHealthService.hydrateDefinitions(definitions);
        return saved;
    }

    public ConnectionTestResult testConnection(Long id) {
        DatasourceEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + id);
        }
        ensureConnectionFingerprint(entity);
        DataSourceDefinition definition = toDefinition(entity, false);
        ensureDatasourceCanTest(definition);
        final DataSourceDefinition probeDefinition = definition;
        int timeoutSeconds = datasourceConnectionHealthService.effectiveManualTimeout(definition);
        ConnectionTestResult result = datasourceConnectionHealthService.runManualProbe(definition, new Callable<ConnectionTestResult>() {
            @Override
            public ConnectionTestResult call() {
                return executeConnectionTest(probeDefinition);
            }
        }, timeoutSeconds);
        persistConnectionSnapshot(id, result);
        return result;
    }

    public ConnectionTestResult testConnection(DataSourceSaveRequest request) {
        datasourceTypeCapabilityService.ensureEnabled(request.getTypeCode());
        final DataSourceDefinition definition = buildDefinitionForTest(request);
        ensureDatasourceCanTest(definition);
        int timeoutSeconds = datasourceConnectionHealthService.effectiveManualTimeout(definition);
        return datasourceConnectionHealthService.runCurrentFormProbe(new Callable<ConnectionTestResult>() {
            @Override
            public ConnectionTestResult call() {
                return executeConnectionTest(definition);
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
        DataSourceDefinition definition = getInternal(id);
        datasourceTypeCapabilityService.ensureReadable(definition.getTypeCode());
        return capabilityProvider.discoverModels(definition, keyword, pageNo, pageSize);
    }

    public List<DatasourceConnectionTestRecordView> connectionHistory(Long id, Integer days, Integer limit) {
        DatasourceEntity entity = findAccessibleEntity(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + id);
        }
        ensureConnectionFingerprint(entity);
        return datasourceConnectionHealthService.history(entity.getTenantId(), entity.getConnectionFingerprint(), days, limit);
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
        for (DatasourceEntity entity : entities) {
            ensureConnectionFingerprint(entity);
            if (!hasText(entity.getConnectionFingerprint())) {
                continue;
            }
            String key = entity.getTenantId() + "\n" + entity.getConnectionFingerprint();
            DataSourceDefinition definition = toDefinition(entity, false);
            int timeoutSeconds = datasourceConnectionHealthService.effectiveScheduledTimeout(definition);
            ScheduledProbeCandidate candidate = candidates.get(key);
            if (candidate == null) {
                candidates.put(key, new ScheduledProbeCandidate(definition, timeoutSeconds));
            } else if (timeoutSeconds > candidate.timeoutSeconds) {
                candidate.timeoutSeconds = timeoutSeconds;
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
            if (!datasourceConnectionHealthService.scheduledDue(candidate.definition.getTenantId(), candidate.definition.getConnectionFingerprint())) {
                continue;
            }
            final DataSourceDefinition probeDefinition = candidate.definition;
            datasourceConnectionHealthService.submitScheduledProbe(probeDefinition, new Callable<ConnectionTestResult>() {
                @Override
                public ConnectionTestResult call() {
                    return executeConnectionTest(probeDefinition);
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
        requireWritableEntity(id);
        dataModelMapper.delete(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getDatasourceId, id));
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
        definition.setTechnicalMetadata(maskSensitive ? maskSensitive(entity.getTechnicalMetadata()) : entity.getTechnicalMetadata());
        definition.setBusinessMetadata(entity.getBusinessMetadata());
        return definition;
    }

    private DataSourceListView toListView(DataSourceDefinition definition) {
        DataSourceListView view = new DataSourceListView();
        view.setId(definition.getId());
        view.setTenantId(definition.getTenantId());
        view.setProjectId(definition.getProjectId());
        view.setDeleted(definition.getDeleted());
        view.setCreatedAt(definition.getCreatedAt());
        view.setUpdatedAt(definition.getUpdatedAt());
        view.setName(definition.getName());
        view.setTypeCode(definition.getTypeCode());
        view.setSchemaVersionId(definition.getSchemaVersionId());
        view.setEnabled(definition.getEnabled());
        view.setExecutable(definition.getExecutable());
        view.setConnectionFingerprint(definition.getConnectionFingerprint());
        view.setConnectionStatus(definition.getConnectionStatus());
        view.setLastConnectionTestAt(definition.getLastConnectionTestAt());
        view.setLastConnectionTestMessage(definition.getLastConnectionTestMessage());
        view.setLastConnectionTestDurationMs(definition.getLastConnectionTestDurationMs());
        view.setConnectionTesting(definition.getConnectionTesting());
        view.setConnectionStale(definition.getConnectionStale());
        view.setNextConnectionProbeAt(definition.getNextConnectionProbeAt());
        view.setManualConnectionTestTimeoutSeconds(definition.getManualConnectionTestTimeoutSeconds());
        view.setScheduledConnectionTestTimeoutSeconds(definition.getScheduledConnectionTestTimeoutSeconds());
        view.setRecentConnectionTests(definition.getRecentConnectionTests());
        return view;
    }

    private DataSourceDefinition buildDefinitionForTest(DataSourceSaveRequest request) {
        DatasourceEntity entity = request.getId() == null ? null : requireWritableEntity(request.getId());
        MetadataSchemaDefinition schema = findDatasourceSchema(request.getSchemaVersionId(), request.getTypeCode());
        Map<String, Object> technicalMetadata = applyDefaults(request.getTechnicalMetadata(), schema, MetadataScope.TECHNICAL);
        technicalMetadata = preserveSensitiveValues(entity == null ? null : entity.getTechnicalMetadata(), technicalMetadata);

        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(request.getId());
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

    private ConnectionTestResult executeConnectionTest(DataSourceDefinition definition) {
        long startedAtNanos = System.nanoTime();
        ConnectionTestResult result = capabilityProvider.testConnection(definition);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        if (result == null) {
            result = new ConnectionTestResult();
            result.setSuccess(false);
            result.setMessage("Connection test returned no result");
        }
        result.setStatus(result.isSuccess() ? DataSourceConnectionStatus.AVAILABLE : DataSourceConnectionStatus.UNAVAILABLE);
        result.setDurationMs(durationMs);
        result.setMessage(truncateConnectionMessage(result.getMessage()));
        return result;
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
        if (entity == null) {
            return null;
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATASOURCE,
                entity.getProjectId(), entity.getId(), "Datasource not found: " + id);
        return entity;
    }

    private DatasourceEntity requireWritableEntity(Long id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + id);
        }
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
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

    private Map<String, Object> maskSensitive(Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (input == null) {
            return output;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && isSensitive(entry.getKey()) && String.valueOf(value).startsWith("ENC(") && String.valueOf(value).endsWith(")")) {
                String cipher = String.valueOf(value).substring(4, String.valueOf(value).length() - 1);
                output.put(entry.getKey(), encryptionService.mask(encryptionService.decrypt(cipher)));
            } else {
                output.put(entry.getKey(), value);
            }
        }
        return output;
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
        Map<String, Object> maskedExisting = maskSensitive(existing);
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
            Object maskedValue = maskedExisting.get(key);
            if (incomingValue instanceof String
                    && maskedValue instanceof String
                    && String.valueOf(incomingValue).equals(maskedValue)) {
                output.put(key, entry.getValue());
            }
        }
        return output;
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("accesskey");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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
        private int timeoutSeconds;

        private ScheduledProbeCandidate(DataSourceDefinition definition, int timeoutSeconds) {
            this.definition = definition;
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}

