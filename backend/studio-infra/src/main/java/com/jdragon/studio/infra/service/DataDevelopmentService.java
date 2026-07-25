package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataDevelopmentDirectoryView;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.DataDevelopmentScriptListView;
import com.jdragon.studio.dto.model.DataDevelopmentScriptView;
import com.jdragon.studio.dto.model.DataDevelopmentTreeNode;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataSourceListView;
import com.jdragon.studio.dto.model.DataSourceOptionView;
import com.jdragon.studio.dto.model.ScriptEnvironmentOptionView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.request.DataDevelopmentDirectorySaveRequest;
import com.jdragon.studio.dto.model.request.DataDevelopmentMoveRequest;
import com.jdragon.studio.dto.model.request.DataDevelopmentScriptSaveRequest;
import com.jdragon.studio.dto.model.request.DataScriptExecutionRequest;
import com.jdragon.studio.dto.model.request.SavedDataScriptExecutionRequest;
import com.jdragon.studio.dto.model.request.SqlExecutionRequest;
import com.jdragon.studio.infra.entity.DataDevelopmentDirectoryEntity;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataDevelopmentDirectoryMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.service.script.DataDevelopmentExecutionContext;
import com.jdragon.studio.infra.service.script.DataDevelopmentScriptExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashMap;

@Service
public class DataDevelopmentService {

    private final DataDevelopmentDirectoryMapper directoryMapper;
    private final DataDevelopmentScriptMapper scriptMapper;
    private final DataSourceService dataSourceService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final ScriptEnvironmentService scriptEnvironmentService;
    private final DataDevelopmentWorkerExecutionService workerExecutionService;
    private RuntimeClusterSelectionService runtimeClusterSelectionService;
    private DataModelMapper dataModelMapper;
    private RuntimeResourceRevisionService runtimeResourceRevisionService;
    private final Map<ScriptType, DataDevelopmentScriptExecutor> scriptExecutors;

    public DataDevelopmentService(DataDevelopmentDirectoryMapper directoryMapper,
                                  DataDevelopmentScriptMapper scriptMapper,
                                  DataSourceService dataSourceService,
                                  DatasourceTypeCapabilityService datasourceTypeCapabilityService,
                                  StudioSecurityService securityService,
                                  ProjectResourceAccessService projectResourceAccessService,
                                  ScriptEnvironmentService scriptEnvironmentService,
                                  DataDevelopmentWorkerExecutionService workerExecutionService) {
        this.directoryMapper = directoryMapper;
        this.scriptMapper = scriptMapper;
        this.dataSourceService = dataSourceService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.scriptEnvironmentService = scriptEnvironmentService;
        this.workerExecutionService = workerExecutionService;
        this.scriptExecutors = new HashMap<ScriptType, DataDevelopmentScriptExecutor>();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setScriptExecutors(List<DataDevelopmentScriptExecutor> scriptExecutors) {
        this.scriptExecutors.clear();
        if (scriptExecutors != null) {
            for (DataDevelopmentScriptExecutor executor : scriptExecutors) {
                this.scriptExecutors.put(executor.getScriptType(), executor);
            }
        }
    }

    public List<DataDevelopmentTreeNode> tree() {
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        List<DataDevelopmentScriptEntity> scripts = listScriptEntities(tenantId, projectId, null);
        return buildTree(listDirectoryEntities(tenantId, projectId), scripts,
                listDatasourceSummaries(scripts), listEnvironmentSummaries(scripts));
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeClusterSelectionService(RuntimeClusterSelectionService runtimeClusterSelectionService) { this.runtimeClusterSelectionService = runtimeClusterSelectionService; }

    @org.springframework.beans.factory.annotation.Autowired
    void setDataModelMapper(DataModelMapper dataModelMapper) { this.dataModelMapper = dataModelMapper; }

    @org.springframework.beans.factory.annotation.Autowired
    void setRuntimeResourceRevisionService(RuntimeResourceRevisionService runtimeResourceRevisionService) {
        this.runtimeResourceRevisionService = runtimeResourceRevisionService;
    }

    public List<DataDevelopmentDirectoryView> listDirectories() {
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        List<DataDevelopmentDirectoryView> result = new ArrayList<DataDevelopmentDirectoryView>();
        for (DataDevelopmentDirectoryEntity entity : listDirectoryEntities(tenantId, projectId)) {
            result.add(toDirectoryView(entity));
        }
        return result;
    }

    public List<DataDevelopmentScriptListView> listScripts(ScriptType scriptType) {
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        List<DataDevelopmentScriptEntity> scripts = listScriptEntities(tenantId, projectId, scriptType);
        Map<Long, DataSourceListView> datasourceMap = listDatasourceSummaries(scripts);
        EnvironmentSummaryBundle environmentBundle = listEnvironmentSummaries(scripts);
        List<DataDevelopmentScriptListView> result = new ArrayList<DataDevelopmentScriptListView>();
        for (DataDevelopmentScriptEntity entity : scripts) {
            result.add(toScriptListView(entity, datasourceMap, environmentBundle));
        }
        if (runtimeClusterSelectionService != null) {
            runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT, result);
        }
        return result;
    }

    public Map<String, Long> scriptTypeCounts() {
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        for (ScriptType scriptType : ScriptType.values()) {
            Long count = scriptMapper.selectCount(buildScriptQuery(tenantId, projectId, scriptType));
            result.put(scriptType.name(), count == null ? 0L : count);
        }
        return result;
    }

    public List<DataDevelopmentScriptListView> listRecentScriptSummaries(int limit) {
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        int safeLimit = limit <= 0 ? 4 : Math.min(limit, 20);
        List<DataDevelopmentScriptEntity> scripts = scriptMapper.selectList(buildScriptQuery(tenantId, projectId, null)
                .select(DataDevelopmentScriptEntity::getId,
                        DataDevelopmentScriptEntity::getTenantId,
                        DataDevelopmentScriptEntity::getProjectId,
                        DataDevelopmentScriptEntity::getDeleted,
                        DataDevelopmentScriptEntity::getCreatedAt,
                        DataDevelopmentScriptEntity::getUpdatedAt,
                        DataDevelopmentScriptEntity::getDirectoryId,
                        DataDevelopmentScriptEntity::getFileName,
                        DataDevelopmentScriptEntity::getScriptType,
                        DataDevelopmentScriptEntity::getRuntimeClusterId,
                        DataDevelopmentScriptEntity::getDatasourceId,
                        DataDevelopmentScriptEntity::getEnvironmentId,
                        DataDevelopmentScriptEntity::getDescription)
                .orderByDesc(DataDevelopmentScriptEntity::getUpdatedAt)
                .orderByDesc(DataDevelopmentScriptEntity::getCreatedAt)
                .last("limit " + safeLimit));
        List<DataDevelopmentScriptListView> result = new ArrayList<DataDevelopmentScriptListView>();
        for (DataDevelopmentScriptEntity entity : scripts) {
            result.add(toScriptListView(entity, Collections.emptyMap()));
        }
        if (runtimeClusterSelectionService != null) {
            runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT, result);
        }
        return result;
    }

    public List<DataSourceDefinition> listSqlCapableDatasources(Long runtimeClusterId) {
        projectResourceAccessService.requireCurrentProjectId();
        runtimeClusterSelectionService.assertExplicitSelection(runtimeClusterId);
        Set<Long> applicableDatasourceIds = new LinkedHashSet<Long>();
        for (DataSourceOptionView option : dataSourceService.listBasicOptionsByTypes(
                new LinkedHashSet<String>(datasourceTypeCapabilityService.sqlExecutableTypes()), runtimeClusterId)) {
            if (option != null && option.getId() != null) {
                applicableDatasourceIds.add(option.getId());
            }
        }
        List<DataSourceDefinition> result = new ArrayList<DataSourceDefinition>();
        for (DataSourceDefinition datasource : dataSourceService.list()) {
            if (datasource != null && applicableDatasourceIds.contains(datasource.getId())) {
                result.add(datasource);
            }
        }
        return result;
    }

    public List<DataSourceOptionView> listSqlCapableDatasourceOptions(Long runtimeClusterId) {
        projectResourceAccessService.requireCurrentProjectId();
        runtimeClusterSelectionService.assertExplicitSelection(runtimeClusterId);
        return dataSourceService.listBasicOptionsByTypes(
                new LinkedHashSet<String>(datasourceTypeCapabilityService.sqlExecutableTypes()), runtimeClusterId);
    }

    public List<String> listSqlDatasourceTypes() {
        return new ArrayList<String>(datasourceTypeCapabilityService.sqlExecutableTypes());
    }

    public DataDevelopmentScriptView getScript(Long scriptId) {
        DataDevelopmentScriptView view = toScriptView(requireReadableScript(scriptId));
        return runtimeClusterSelectionService == null ? view
                : runtimeClusterSelectionService.hydrateRuntimeValidation(StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT, view);
    }

    @Transactional
    public DataDevelopmentDirectoryView saveDirectory(DataDevelopmentDirectorySaveRequest request) {
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        DataDevelopmentDirectoryEntity entity = request.getId() == null
                ? new DataDevelopmentDirectoryEntity()
                : requireDirectory(request.getId());
        if (request.getParentId() != null) {
            requireDirectory(request.getParentId());
        }
        validateDirectoryName(tenantId, projectId, request.getParentId(), request.getName(), entity.getId());
        entity.setTenantId(tenantId);
        entity.setProjectId(projectId);
        entity.setParentId(request.getParentId());
        entity.setName(request.getName().trim());
        entity.setPermissionCode(blankToNull(request.getPermissionCode()));
        entity.setDescription(blankToNull(request.getDescription()));
        if (entity.getId() == null) {
            directoryMapper.insert(entity);
        } else {
            directoryMapper.updateById(entity);
        }
        return toDirectoryView(entity);
    }

    @Transactional
    public void moveDirectory(Long directoryId, DataDevelopmentMoveRequest request) {
        DataDevelopmentDirectoryEntity entity = requireDirectory(directoryId);
        Long targetDirectoryId = request == null ? null : request.getTargetDirectoryId();
        if (targetDirectoryId != null) {
            requireDirectory(targetDirectoryId);
            if (directoryId.equals(targetDirectoryId)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Directory cannot be moved into itself");
            }
            ensureNotDescendant(directoryId, targetDirectoryId);
        }
        validateDirectoryName(entity.getTenantId(), entity.getProjectId(), targetDirectoryId, entity.getName(), entity.getId());
        entity.setParentId(targetDirectoryId);
        directoryMapper.updateById(entity);
    }

    @Transactional
    public void deleteDirectory(Long directoryId) {
        DataDevelopmentDirectoryEntity entity = requireDirectory(directoryId);
        List<DataDevelopmentDirectoryEntity> directories = listDirectoryEntities(entity.getTenantId(), entity.getProjectId());
        Set<Long> directoryIds = collectDescendantIds(directoryId, directories);
        directoryIds.add(directoryId);
        scriptMapper.delete(new LambdaQueryWrapper<DataDevelopmentScriptEntity>()
                .eq(DataDevelopmentScriptEntity::getTenantId, entity.getTenantId())
                .eq(DataDevelopmentScriptEntity::getProjectId, entity.getProjectId())
                .in(DataDevelopmentScriptEntity::getDirectoryId, directoryIds));
        directoryMapper.delete(new LambdaQueryWrapper<DataDevelopmentDirectoryEntity>()
                .eq(DataDevelopmentDirectoryEntity::getTenantId, entity.getTenantId())
                .eq(DataDevelopmentDirectoryEntity::getProjectId, entity.getProjectId())
                .in(DataDevelopmentDirectoryEntity::getId, directoryIds));
    }

    @Transactional
    public DataDevelopmentScriptView saveScript(DataDevelopmentScriptSaveRequest request) {
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        validateScriptType(request.getScriptType());
        DataDevelopmentScriptEntity entity = request.getId() == null
                ? new DataDevelopmentScriptEntity()
                : requireWritableScript(request.getId());
        if (request.getDirectoryId() != null) {
            requireDirectory(request.getDirectoryId());
        }
        DataSourceDefinition datasource = resolveScriptDatasource(
                request.getScriptType(), request.getDatasourceId(), false);
        Set<Long> runtimeDatasourceIds = new LinkedHashSet<Long>();
        if (datasource != null && datasource.getId() != null) {
            runtimeDatasourceIds.add(datasource.getId());
        }
        if (request.getScriptType() == ScriptType.FLINK_QUESTION_SQL) {
            collectModelDatasourceIds(requireFlinkQuestionModelIds(
                    request.getExecutionConfig(), null), runtimeDatasourceIds);
        }
        Long runtimeClusterId = runtimeClusterSelectionService.validateDatasourceSelectionForResourceSave(
                projectId, request.getRuntimeClusterId(), entity.getRuntimeClusterId(),
                entity.getId() != null, runtimeDatasourceIds);
        validateScriptFileName(tenantId, projectId, request.getDirectoryId(), request.getFileName(), entity.getId());
        entity.setTenantId(tenantId);
        entity.setProjectId(projectId);
        entity.setRuntimeClusterId(runtimeClusterId);
        entity.setDirectoryId(request.getDirectoryId());
        entity.setFileName(request.getFileName().trim());
        entity.setScriptType(request.getScriptType().name());
        entity.setDatasourceId(datasource == null ? null : datasource.getId());
        entity.setEnvironmentId(resolveScriptEnvironmentId(request.getScriptType(), request.getEnvironmentId()));
        entity.setDescription(blankToNull(request.getDescription()));
        entity.setContent(request.getContent());
        entity.setExecutionConfigJson(resolveScriptExecutionConfig(request.getScriptType(), request.getExecutionConfig()));
        if (entity.getId() == null) {
            scriptMapper.insert(entity);
        } else {
            scriptMapper.updateById(entity);
        }
        runtimeClusterSelectionService.markResourceValid(StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT, entity.getId());
        return getScript(entity.getId());
    }

    @Transactional
    public void moveScript(Long scriptId, DataDevelopmentMoveRequest request) {
        DataDevelopmentScriptEntity entity = requireWritableScript(scriptId);
        Long targetDirectoryId = request == null ? null : request.getTargetDirectoryId();
        if (targetDirectoryId != null) {
            requireDirectory(targetDirectoryId);
        }
        validateScriptFileName(entity.getTenantId(), entity.getProjectId(), targetDirectoryId, entity.getFileName(), entity.getId());
        entity.setDirectoryId(targetDirectoryId);
        scriptMapper.updateById(entity);
    }

    @Transactional
    public void deleteScript(Long scriptId) {
        requireWritableScript(scriptId);
        scriptMapper.deleteById(scriptId);
    }

    public SqlExecutionResultView execute(SqlExecutionRequest request) {
        if (request.getScriptType() != ScriptType.SQL) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only SQL scripts can use the SQL execution endpoint");
        }
        DataScriptExecutionRequest scriptRequest = new DataScriptExecutionRequest();
        scriptRequest.setRuntimeClusterId(request.getRuntimeClusterId());
        scriptRequest.setScriptType(request.getScriptType());
        scriptRequest.setDatasourceId(request.getDatasourceId());
        scriptRequest.setContent(request.getContent());
        scriptRequest.setMaxRows(request.getMaxRows());
        DataScriptExecutionResultView result = execute(scriptRequest);
        if (result.getSqlResult() == null) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    result.getMessage() == null ? "SQL execution returned no result" : result.getMessage());
        }
        return result.getSqlResult();
    }

    public DataScriptExecutionResultView execute(DataScriptExecutionRequest request) {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        validateScriptType(request.getScriptType());
        if (request.getScriptType() != ScriptType.SQL && request.getScriptType() != ScriptType.FLINK_QUESTION_SQL) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Non-SQL scripts must be saved before execution");
        }
        Set<Long> datasourceIds = executionDatasourceIds(request);
        Long runtimeClusterId = runtimeClusterSelectionService == null
                ? request.getRuntimeClusterId()
                : runtimeClusterSelectionService.validateExplicitDatasourceSelection(projectId,
                request.getRuntimeClusterId(), datasourceIds);
        if (runtimeClusterId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Runtime cluster is required");
        }
        request.setRuntimeClusterId(runtimeClusterId);
        return workerExecutionService.executeInlineScript(request, projectId, 120);
    }

    public DataScriptExecutionResultView executeInlineLocally(DataScriptExecutionRequest request,
                                                              Map<String, Object> suppliedRuntimeContext) {
        Long projectId = suppliedRuntimeContext != null && suppliedRuntimeContext.get("projectId") instanceof Number
                ? Long.valueOf(((Number) suppliedRuntimeContext.get("projectId")).longValue())
                : projectResourceAccessService.requireCurrentProjectId();
        validateScriptType(request.getScriptType());
        if (request.getScriptType() != ScriptType.SQL && request.getScriptType() != ScriptType.FLINK_QUESTION_SQL) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Non-SQL scripts must be saved before execution");
        }
        DataDevelopmentExecutionContext context = new DataDevelopmentExecutionContext();
        context.setScriptType(request.getScriptType());
        context.setContent(request.getContent());
        context.setDatasourceId(request.getDatasourceId());
        context.setDatasource(resolveScriptDatasource(
                request.getScriptType(), request.getDatasourceId(), true));
        context.setEnvironmentId(resolveScriptEnvironmentId(request.getScriptType(), request.getEnvironmentId()));
        context.setMaxRows(request.getMaxRows());
        context.setTenantId(securityService.currentTenantId());
        context.setUsername(securityService.currentUsername());
        context.setArguments(request.getArguments());
        context.setExecutionConfig(resolveScriptExecutionConfig(request.getScriptType(), request.getExecutionConfig()));
        Map<String, Object> runtimeContext = suppliedRuntimeContext == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(suppliedRuntimeContext);
        runtimeContext.put("tenantId", context.getTenantId());
        runtimeContext.put("projectId", projectId);
        runtimeContext.put("username", context.getUsername());
        runtimeContext.put("runtimeClusterId", request.getRuntimeClusterId());
        context.setRuntimeContext(runtimeContext);
        return requireExecutor(request.getScriptType()).execute(context);
    }

    private Set<Long> executionDatasourceIds(DataScriptExecutionRequest request) {
        Set<Long> datasourceIds = new LinkedHashSet<Long>();
        if (request.getScriptType() == ScriptType.SQL) {
            DataSourceDefinition datasource = requireSqlDatasource(request.getDatasourceId(), false);
            datasourceIds.add(datasource.getId());
            return datasourceIds;
        }
        collectModelDatasourceIds(requireFlinkQuestionModelIds(
                request.getExecutionConfig(), request.getArguments()), datasourceIds);
        return datasourceIds;
    }

    private void collectModelDatasourceIds(Map<String, Object> executionConfig, Set<Long> datasourceIds) {
        collectModelDatasourceIds(toLongIds(
                executionConfig == null ? null : executionConfig.get("modelIds")), datasourceIds);
    }

    private void collectModelDatasourceIds(Collection<Long> modelIds, Set<Long> datasourceIds) {
        for (Long modelId : modelIds) {
            if (dataModelMapper == null) {
                throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                        "Runtime model validation is unavailable");
            }
            DataModelEntity model = dataModelMapper.selectById(modelId);
            if (model == null) {
                throw new StudioException(StudioErrorCode.NOT_FOUND, "Data model not found: " + modelId);
            }
            projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATA_MODEL,
                    model.getProjectId(), model.getId(), "Data model not found: " + modelId);
            if (model.getDatasourceId() != null) {
                datasourceIds.add(model.getDatasourceId());
            }
        }
    }

    private Set<Long> requireFlinkQuestionModelIds(Map<String, Object> executionConfig,
                                                    Map<String, Object> arguments) {
        Set<Long> modelIds = toLongIds(executionConfig == null ? null : executionConfig.get("modelIds"));
        if (modelIds.isEmpty()) {
            modelIds = toLongIds(arguments == null ? null : arguments.get("modelIds"));
        }
        if (modelIds.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "modelIds are required for 模型 Flink SQL execution");
        }
        return modelIds;
    }

    private Set<Long> toLongIds(Object value) {
        Set<Long> result = new LinkedHashSet<Long>();
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                toLongId(item).ifPresent(result::add);
            }
        } else if (value instanceof String) {
            for (String item : ((String) value).split(",")) {
                toLongId(item).ifPresent(result::add);
            }
        } else {
            toLongId(value).ifPresent(result::add);
        }
        return result;
    }

    private Optional<Long> toLongId(Object value) {
        if (value instanceof Number) {
            return Optional.of(Long.valueOf(((Number) value).longValue()));
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            try {
                return Optional.of(Long.valueOf(((String) value).trim()));
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public DataScriptExecutionResultView executeSavedScript(Long scriptId, SavedDataScriptExecutionRequest request) {
        DataDevelopmentScriptEntity script = requireReadableScript(scriptId);
        SavedDataScriptExecutionRequest effectiveRequest = request == null
                ? new SavedDataScriptExecutionRequest() : request;
        Set<Long> runtimeDatasourceIds = new LinkedHashSet<Long>();
        if (script.getDatasourceId() != null) {
            runtimeDatasourceIds.add(script.getDatasourceId());
        }
        if (ScriptType.FLINK_QUESTION_SQL.name().equals(script.getScriptType())) {
            collectModelDatasourceIds(script.getExecutionConfigJson(), runtimeDatasourceIds);
            collectModelDatasourceIds(effectiveRequest.getExecutionConfig(), runtimeDatasourceIds);
        }
        Long runtimeClusterId = script.getRuntimeClusterId();
        if (runtimeClusterSelectionService != null) {
            if (effectiveRequest.getRuntimeClusterId() != null) {
                runtimeClusterId = runtimeClusterSelectionService.validateManualOverride(
                        script.getProjectId(), effectiveRequest.getRuntimeClusterId(), runtimeDatasourceIds);
            } else {
                runtimeClusterSelectionService.assertResourceValid(
                        StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT, script.getId());
                runtimeClusterSelectionService.assertExistingResourceRunnable(
                        script.getProjectId(), runtimeClusterId, runtimeDatasourceIds);
            }
        }
        ScriptType scriptType = ScriptType.valueOf(script.getScriptType());
        validateScriptType(scriptType);
        if (scriptType == ScriptType.SQL) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SQL scripts should use the SQL execution endpoint");
        }
        resolveScriptEnvironmentId(scriptType, script.getEnvironmentId());
        return workerExecutionService.executeSavedScript(script,
                scriptType,
                runtimeClusterId,
                effectiveRequest.getArguments(),
                effectiveRequest.getExecutionConfig(),
                effectiveRequest.getMaxRows(),
                effectiveRequest.getWaitTimeoutSeconds());
    }

    public DataScriptExecutionResultView executeScript(Long scriptId, Integer maxRows, Map<String, Object> arguments, Map<String, Object> runtimeContext) {
        return executeScript(scriptId, maxRows, arguments, runtimeContext, null);
    }

    public DataScriptExecutionResultView executeScript(Long scriptId,
                                                       Integer maxRows,
                                                       Map<String, Object> arguments,
                                                       Map<String, Object> runtimeContext,
                                                       Map<String, Object> executionConfigOverride) {
        DataDevelopmentScriptEntity script = requireReadableScript(scriptId);
        Object expectedRevision = runtimeContext == null ? null : runtimeContext.get("resourceRevision");
        String currentRevision = runtimeResourceRevisionService == null
                ? (script.getUpdatedAt() == null ? null : script.getUpdatedAt().toString())
                : runtimeResourceRevisionService.scriptRevision(script.getId());
        if (expectedRevision != null && currentRevision != null
                && !currentRevision.equals(String.valueOf(expectedRevision))) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Script configuration changed after dispatch; submit a new run");
        }
        ScriptType scriptType = ScriptType.valueOf(script.getScriptType());
        DataDevelopmentExecutionContext context = new DataDevelopmentExecutionContext();
        context.setScriptId(script.getId());
        context.setScriptName(script.getFileName());
        context.setScriptType(scriptType);
        context.setContent(script.getContent());
        context.setDatasourceId(script.getDatasourceId());
        context.setDatasource(resolveScriptDatasource(scriptType, script.getDatasourceId(), true));
        context.setEnvironmentId(resolveScriptEnvironmentId(scriptType, script.getEnvironmentId()));
        context.setMaxRows(maxRows);
        context.setTenantId(script.getTenantId());
        context.setUsername(resolveExecutionUsername(runtimeContext));
        context.setArguments(arguments);
        context.setExecutionConfig(resolveExecutionConfigForRun(scriptType,
                script.getExecutionConfigJson(), executionConfigOverride));
        context.setRuntimeContext(buildRuntimeContext(script, runtimeContext));
        return requireExecutor(scriptType).execute(context);
    }

    private DataDevelopmentDirectoryEntity requireDirectory(Long directoryId) {
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        DataDevelopmentDirectoryEntity entity = directoryMapper.selectById(directoryId);
        if (entity == null || !matchesTenant(entity.getTenantId()) || !matchesProject(entity.getProjectId(), currentProjectId)) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Directory not found: " + directoryId);
        }
        return entity;
    }

    private DataDevelopmentScriptEntity requireReadableScript(Long scriptId) {
        projectResourceAccessService.requireCurrentProjectId();
        DataDevelopmentScriptEntity entity = scriptMapper.selectById(scriptId);
        if (entity == null || !matchesTenant(entity.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Script not found: " + scriptId);
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT,
                entity.getProjectId(), entity.getId(), "Script not found: " + scriptId);
        return entity;
    }

    private DataDevelopmentScriptEntity requireWritableScript(Long scriptId) {
        DataDevelopmentScriptEntity entity = requireReadableScript(scriptId);
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private DataSourceDefinition requireSqlDatasource(Long datasourceId, boolean execution) {
        DataSourceDefinition datasource = execution
                ? dataSourceService.getInternal(datasourceId)
                : dataSourceService.get(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        if (!datasourceTypeCapabilityService.isSqlExecutable(datasource.getTypeCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only database datasources can execute SQL scripts");
        }
        return datasource;
    }

    private DataSourceDefinition resolveScriptDatasource(ScriptType scriptType, Long datasourceId,
                                                         boolean execution) {
        if (scriptType == ScriptType.SQL) {
            return requireSqlDatasource(datasourceId, execution);
        }
        if (scriptType == ScriptType.FLINK_QUESTION_SQL) {
            return null;
        }
        if (datasourceId == null) {
            return null;
        }
        return execution ? dataSourceService.getInternal(datasourceId) : dataSourceService.get(datasourceId);
    }

    private Map<String, Object> resolveScriptExecutionConfig(ScriptType scriptType, Map<String, Object> executionConfig) {
        if (scriptType != ScriptType.FLINK_QUESTION_SQL) {
            return new LinkedHashMap<String, Object>();
        }
        return executionConfig == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(executionConfig);
    }

    private Map<String, Object> resolveExecutionConfigForRun(ScriptType scriptType,
                                                             Map<String, Object> savedConfig,
                                                             Map<String, Object> overrideConfig) {
        Map<String, Object> result = resolveScriptExecutionConfig(scriptType, savedConfig);
        if (scriptType == ScriptType.FLINK_QUESTION_SQL
                && overrideConfig != null
                && !overrideConfig.isEmpty()) {
            result.putAll(resolveScriptExecutionConfig(scriptType, overrideConfig));
        }
        return result;
    }

    private List<DataDevelopmentDirectoryEntity> listDirectoryEntities(String tenantId, Long projectId) {
        return directoryMapper.selectList(new LambdaQueryWrapper<DataDevelopmentDirectoryEntity>()
                .eq(DataDevelopmentDirectoryEntity::getTenantId, tenantId)
                .eq(DataDevelopmentDirectoryEntity::getProjectId, projectId)
                .orderByAsc(DataDevelopmentDirectoryEntity::getParentId)
                .orderByAsc(DataDevelopmentDirectoryEntity::getName));
    }

    private List<DataDevelopmentScriptEntity> listScriptEntities(String tenantId, Long projectId, ScriptType scriptType) {
        LambdaQueryWrapper<DataDevelopmentScriptEntity> wrapper = buildScriptQuery(tenantId, projectId, scriptType)
                .select(DataDevelopmentScriptEntity::getId,
                        DataDevelopmentScriptEntity::getTenantId,
                        DataDevelopmentScriptEntity::getProjectId,
                        DataDevelopmentScriptEntity::getDeleted,
                        DataDevelopmentScriptEntity::getCreatedAt,
                        DataDevelopmentScriptEntity::getUpdatedAt,
                        DataDevelopmentScriptEntity::getDirectoryId,
                        DataDevelopmentScriptEntity::getFileName,
                        DataDevelopmentScriptEntity::getScriptType,
                        DataDevelopmentScriptEntity::getRuntimeClusterId,
                        DataDevelopmentScriptEntity::getDatasourceId,
                        DataDevelopmentScriptEntity::getEnvironmentId,
                        DataDevelopmentScriptEntity::getDescription)
                .orderByAsc(DataDevelopmentScriptEntity::getDirectoryId)
                .orderByAsc(DataDevelopmentScriptEntity::getProjectId)
                .orderByAsc(DataDevelopmentScriptEntity::getFileName);
        return scriptMapper.selectList(wrapper);
    }

    private LambdaQueryWrapper<DataDevelopmentScriptEntity> buildScriptQuery(String tenantId, Long projectId, ScriptType scriptType) {
        LambdaQueryWrapper<DataDevelopmentScriptEntity> wrapper = new LambdaQueryWrapper<DataDevelopmentScriptEntity>()
                .eq(DataDevelopmentScriptEntity::getTenantId, tenantId);
        List<Long> sharedIds = projectResourceAccessService.sharedResourceIdList(StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT);
        if (projectId != null) {
            if (sharedIds.isEmpty()) {
                wrapper.eq(DataDevelopmentScriptEntity::getProjectId, projectId);
            } else {
                wrapper.and(query -> query.eq(DataDevelopmentScriptEntity::getProjectId, projectId)
                        .or()
                        .in(DataDevelopmentScriptEntity::getId, sharedIds));
            }
        }
        if (scriptType != null) {
            wrapper.eq(DataDevelopmentScriptEntity::getScriptType, scriptType.name());
        }
        return wrapper;
    }

    private List<DataDevelopmentTreeNode> buildTree(List<DataDevelopmentDirectoryEntity> directories,
                                                    List<DataDevelopmentScriptEntity> scripts,
                                                    Map<Long, DataSourceListView> datasourceMap,
                                                    EnvironmentSummaryBundle environmentBundle) {
        Map<Long, DataDevelopmentTreeNode> directoryNodes = new LinkedHashMap<Long, DataDevelopmentTreeNode>();
        List<DataDevelopmentTreeNode> roots = new ArrayList<DataDevelopmentTreeNode>();
        for (DataDevelopmentDirectoryEntity entity : directories) {
            DataDevelopmentTreeNode node = new DataDevelopmentTreeNode();
            node.setNodeKey("dir-" + entity.getId());
            node.setNodeType("DIRECTORY");
            node.setDirectoryId(entity.getId());
            node.setParentId(entity.getParentId());
            node.setName(entity.getName());
            node.setPermissionCode(entity.getPermissionCode());
            directoryNodes.put(entity.getId(), node);
        }
        for (DataDevelopmentDirectoryEntity entity : directories) {
            DataDevelopmentTreeNode node = directoryNodes.get(entity.getId());
            if (entity.getParentId() == null) {
                roots.add(node);
            } else if (directoryNodes.containsKey(entity.getParentId())) {
                directoryNodes.get(entity.getParentId()).getChildren().add(node);
            } else {
                roots.add(node);
            }
        }
        for (DataDevelopmentScriptEntity entity : scripts) {
            DataDevelopmentTreeNode node = new DataDevelopmentTreeNode();
            node.setNodeKey("script-" + entity.getId());
            node.setNodeType("SCRIPT");
            node.setScriptId(entity.getId());
            node.setDirectoryId(entity.getDirectoryId());
            node.setProjectId(entity.getProjectId());
            node.setName(entity.getFileName());
            node.setScriptType(entity.getScriptType() == null ? null : ScriptType.valueOf(entity.getScriptType()));
            node.setRuntimeClusterId(entity.getRuntimeClusterId());
            DataSourceListView datasource = datasourceMap.get(entity.getDatasourceId());
            node.setDatasourceName(datasource == null ? null : datasource.getName());
            if (ScriptType.JAVA.name().equals(entity.getScriptType())) {
                ScriptEnvironmentEntityView environment = resolveEnvironment(entity.getEnvironmentId(), environmentBundle);
                node.setEnvironmentId(environment == null ? null : environment.id);
                node.setEnvironmentName(environment == null ? null : environment.name);
            }
            if (entity.getDirectoryId() != null && directoryNodes.containsKey(entity.getDirectoryId())) {
                directoryNodes.get(entity.getDirectoryId()).getChildren().add(node);
            } else {
                roots.add(node);
            }
        }
        sortTree(roots);
        return roots;
    }

    private void sortTree(List<DataDevelopmentTreeNode> nodes) {
        Collections.sort(nodes, new Comparator<DataDevelopmentTreeNode>() {
            @Override
            public int compare(DataDevelopmentTreeNode left, DataDevelopmentTreeNode right) {
                int leftRank = "DIRECTORY".equalsIgnoreCase(left.getNodeType()) ? 0 : 1;
                int rightRank = "DIRECTORY".equalsIgnoreCase(right.getNodeType()) ? 0 : 1;
                if (leftRank != rightRank) {
                    return Integer.compare(leftRank, rightRank);
                }
                return String.valueOf(left.getName()).compareToIgnoreCase(String.valueOf(right.getName()));
            }
        });
        for (DataDevelopmentTreeNode node : nodes) {
            sortTree(node.getChildren());
        }
    }

    private void validateDirectoryName(String tenantId, Long projectId, Long parentId, String name, Long selfId) {
        if (name == null || name.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Directory name is required");
        }
        List<DataDevelopmentDirectoryEntity> siblings = directoryMapper.selectList(new LambdaQueryWrapper<DataDevelopmentDirectoryEntity>()
                .eq(DataDevelopmentDirectoryEntity::getTenantId, tenantId)
                .eq(DataDevelopmentDirectoryEntity::getProjectId, projectId)
                .eq(parentId != null, DataDevelopmentDirectoryEntity::getParentId, parentId)
                .isNull(parentId == null, DataDevelopmentDirectoryEntity::getParentId));
        for (DataDevelopmentDirectoryEntity sibling : siblings) {
            if (selfId != null && selfId.equals(sibling.getId())) {
                continue;
            }
            if (name.trim().equalsIgnoreCase(sibling.getName())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "A directory with the same name already exists");
            }
        }
    }

    private void validateScriptFileName(String tenantId, Long projectId, Long directoryId, String fileName, Long selfId) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "File name is required");
        }
        List<DataDevelopmentScriptEntity> siblings = scriptMapper.selectList(new LambdaQueryWrapper<DataDevelopmentScriptEntity>()
                .eq(DataDevelopmentScriptEntity::getTenantId, tenantId)
                .eq(DataDevelopmentScriptEntity::getProjectId, projectId)
                .eq(directoryId != null, DataDevelopmentScriptEntity::getDirectoryId, directoryId)
                .isNull(directoryId == null, DataDevelopmentScriptEntity::getDirectoryId));
        for (DataDevelopmentScriptEntity sibling : siblings) {
            if (selfId != null && selfId.equals(sibling.getId())) {
                continue;
            }
            if (fileName.trim().equalsIgnoreCase(sibling.getFileName())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "A script with the same file name already exists");
            }
        }
    }

    private void validateScriptType(ScriptType scriptType) {
        if (scriptType == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Script type is required");
        }
        if (scriptType != ScriptType.SQL
                && scriptType != ScriptType.FLINK_QUESTION_SQL
                && scriptType != ScriptType.JAVA
                && scriptType != ScriptType.PYTHON) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Only SQL, 模型 Flink SQL, Java and Python scripts are currently supported");
        }
    }

    private DataDevelopmentScriptExecutor requireExecutor(ScriptType scriptType) {
        DataDevelopmentScriptExecutor executor = scriptExecutors.get(scriptType);
        if (executor == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported script type: " + scriptType);
        }
        return executor;
    }

    private void ensureNotDescendant(Long directoryId, Long targetDirectoryId) {
        Map<Long, DataDevelopmentDirectoryEntity> directoryMap = new LinkedHashMap<Long, DataDevelopmentDirectoryEntity>();
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        for (DataDevelopmentDirectoryEntity entity : listDirectoryEntities(securityService.currentTenantId(), currentProjectId)) {
            directoryMap.put(entity.getId(), entity);
        }
        Long cursor = targetDirectoryId;
        while (cursor != null) {
            if (directoryId.equals(cursor)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Directory cannot be moved into its descendant");
            }
            DataDevelopmentDirectoryEntity current = directoryMap.get(cursor);
            cursor = current == null ? null : current.getParentId();
        }
    }

    private Set<Long> collectDescendantIds(Long directoryId, List<DataDevelopmentDirectoryEntity> directories) {
        Set<Long> result = new LinkedHashSet<Long>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (DataDevelopmentDirectoryEntity entity : directories) {
                if (entity.getParentId() == null) {
                    continue;
                }
                if (directoryId.equals(entity.getParentId()) || result.contains(entity.getParentId())) {
                    if (result.add(entity.getId())) {
                        changed = true;
                    }
                }
            }
        }
        return result;
    }

    private DataDevelopmentDirectoryView toDirectoryView(DataDevelopmentDirectoryEntity entity) {
        DataDevelopmentDirectoryView view = new DataDevelopmentDirectoryView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setParentId(entity.getParentId());
        view.setName(entity.getName());
        view.setPermissionCode(entity.getPermissionCode());
        view.setDescription(entity.getDescription());
        return view;
    }

    private DataDevelopmentScriptView toScriptView(DataDevelopmentScriptEntity entity) {
        DataDevelopmentScriptView view = new DataDevelopmentScriptView();
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null : runtimeClusterSelectionService.runtimeClusterName(entity.getProjectId(), entity.getRuntimeClusterId()));
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setDirectoryId(entity.getDirectoryId());
        view.setFileName(entity.getFileName());
        view.setScriptType(entity.getScriptType() == null ? null : ScriptType.valueOf(entity.getScriptType()));
        view.setDatasourceId(entity.getDatasourceId());
        DataSourceListView datasource = safeResolveScriptDatasource(entity.getDatasourceId());
        if (datasource != null) {
            view.setDatasourceName(datasource.getName());
            view.setDatasourceTypeCode(datasource.getTypeCode());
        }
        if (ScriptType.JAVA.name().equals(entity.getScriptType())) {
            ScriptEnvironmentEntityView environment = safeResolveEnvironment(entity.getEnvironmentId());
            view.setEnvironmentId(environment == null ? null : environment.id);
            view.setEnvironmentName(environment == null ? null : environment.name);
        }
        view.setDescription(entity.getDescription());
        view.setContent(entity.getContent());
        view.setExecutionConfig(new LinkedHashMap<String, Object>(resolveScriptExecutionConfig(
                view.getScriptType(), entity.getExecutionConfigJson())));
        return view;
    }

    private DataDevelopmentScriptListView toScriptListView(DataDevelopmentScriptEntity entity,
                                                           Map<Long, DataSourceListView> datasourceMap) {
        return toScriptListView(entity, datasourceMap, null);
    }

    private DataDevelopmentScriptListView toScriptListView(DataDevelopmentScriptEntity entity,
                                                           Map<Long, DataSourceListView> datasourceMap,
                                                           EnvironmentSummaryBundle environmentBundle) {
        DataDevelopmentScriptListView view = new DataDevelopmentScriptListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setRuntimeClusterName(runtimeClusterSelectionService == null ? null : runtimeClusterSelectionService.runtimeClusterName(entity.getProjectId(), entity.getRuntimeClusterId()));
        view.setDirectoryId(entity.getDirectoryId());
        view.setFileName(entity.getFileName());
        view.setScriptType(entity.getScriptType() == null ? null : ScriptType.valueOf(entity.getScriptType()));
        view.setDatasourceId(entity.getDatasourceId());
        DataSourceListView datasource = datasourceMap.get(entity.getDatasourceId());
        if (datasource != null) {
            view.setDatasourceName(datasource.getName());
            view.setDatasourceTypeCode(datasource.getTypeCode());
        }
        if (ScriptType.JAVA.name().equals(entity.getScriptType())) {
            ScriptEnvironmentEntityView environment = environmentBundle == null
                    ? safeResolveEnvironment(entity.getEnvironmentId())
                    : resolveEnvironment(entity.getEnvironmentId(), environmentBundle);
            view.setEnvironmentId(environment == null ? null : environment.id);
            view.setEnvironmentName(environment == null ? null : environment.name);
        }
        view.setDescription(entity.getDescription());
        return view;
    }

    private Map<Long, DataSourceListView> listDatasourceSummaries(List<DataDevelopmentScriptEntity> scripts) {
        Set<Long> datasourceIds = new LinkedHashSet<Long>();
        if (scripts != null) {
            for (DataDevelopmentScriptEntity script : scripts) {
                if (script.getDatasourceId() != null) {
                    datasourceIds.add(script.getDatasourceId());
                }
            }
        }
        return dataSourceService.listBasicSummaryMap(datasourceIds);
    }

    private EnvironmentSummaryBundle listEnvironmentSummaries(List<DataDevelopmentScriptEntity> scripts) {
        EnvironmentSummaryBundle bundle = new EnvironmentSummaryBundle();
        Set<Long> environmentIds = new LinkedHashSet<Long>();
        boolean needsDefaultEnvironment = false;
        if (scripts != null) {
            for (DataDevelopmentScriptEntity script : scripts) {
                if (script == null || !ScriptType.JAVA.name().equals(script.getScriptType())) {
                    continue;
                }
                if (script.getEnvironmentId() == null) {
                    needsDefaultEnvironment = true;
                } else {
                    environmentIds.add(script.getEnvironmentId());
                }
            }
        }
        if (needsDefaultEnvironment) {
            bundle.defaultEnvironment = safeResolveEnvironment(null);
        }
        Map<Long, ScriptEnvironmentOptionView> options = scriptEnvironmentService.enabledOptionMapByIds(environmentIds);
        for (Map.Entry<Long, ScriptEnvironmentOptionView> entry : options.entrySet()) {
            ScriptEnvironmentOptionView option = entry.getValue();
            if (option != null) {
                bundle.environments.put(entry.getKey(),
                        new ScriptEnvironmentEntityView(option.getId(), option.getEnvironmentName()));
            }
        }
        return bundle;
    }

    private Long resolveScriptEnvironmentId(ScriptType scriptType, Long environmentId) {
        if (scriptType != ScriptType.JAVA) {
            return null;
        }
        return scriptEnvironmentService.requireEnabledEnvironment(environmentId).getId();
    }

    private ScriptEnvironmentEntityView safeResolveEnvironment(Long environmentId) {
        try {
            com.jdragon.studio.infra.entity.ScriptEnvironmentEntity entity = scriptEnvironmentService.requireEnabledEnvironment(environmentId);
            return new ScriptEnvironmentEntityView(entity.getId(), entity.getEnvironmentName());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private ScriptEnvironmentEntityView resolveEnvironment(Long environmentId, EnvironmentSummaryBundle environmentBundle) {
        if (environmentBundle == null) {
            return safeResolveEnvironment(environmentId);
        }
        if (environmentId == null) {
            return environmentBundle.defaultEnvironment;
        }
        return environmentBundle.environments.get(environmentId);
    }

    private DataSourceListView safeResolveScriptDatasource(Long datasourceId) {
        if (datasourceId == null) {
            return null;
        }
        return dataSourceService.listBasicSummaryMap(Collections.singleton(datasourceId)).get(datasourceId);
    }

    private boolean matchesTenant(String tenantId) {
        return securityService.currentTenantId().equals(tenantId);
    }

    private boolean matchesProject(Long projectId, Long expectedProjectId) {
        if (projectId == null || expectedProjectId == null) {
            return projectId == null && expectedProjectId == null;
        }
        return projectId.longValue() == expectedProjectId.longValue();
    }

    private String resolveExecutionUsername(Map<String, Object> runtimeContext) {
        String username = securityService.currentUsername();
        if (username != null && !username.trim().isEmpty()) {
            return username;
        }
        Object runtimeUsername = runtimeContext == null ? null : runtimeContext.get("username");
        if (runtimeUsername == null) {
            runtimeUsername = runtimeContext == null ? null : runtimeContext.get("workerCode");
        }
        return runtimeUsername == null ? null : String.valueOf(runtimeUsername);
    }

    private Map<String, Object> buildRuntimeContext(DataDevelopmentScriptEntity script, Map<String, Object> runtimeContext) {
        Map<String, Object> context = runtimeContext == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(runtimeContext);
        context.put("tenantId", script.getTenantId());
        Long executionProjectId = projectResourceAccessService.currentProjectId();
        if (runtimeContext != null && runtimeContext.get("projectId") != null) {
            Object runtimeProjectId = runtimeContext.get("projectId");
            if (runtimeProjectId instanceof Number) {
                executionProjectId = ((Number) runtimeProjectId).longValue();
            } else {
                try {
                    executionProjectId = Long.valueOf(String.valueOf(runtimeProjectId));
                } catch (NumberFormatException ignored) {
                    executionProjectId = script.getProjectId();
                }
            }
        }
        context.put("projectId", executionProjectId == null ? script.getProjectId() : executionProjectId);
        context.put("scriptId", script.getId());
        context.put("scriptName", script.getFileName());
        String username = resolveExecutionUsername(runtimeContext);
        if (username != null && !username.trim().isEmpty()) {
            context.put("username", username);
        }
        return context;
    }

    private String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static final class ScriptEnvironmentEntityView {
        private final Long id;
        private final String name;

        private ScriptEnvironmentEntityView(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class EnvironmentSummaryBundle {
        private final Map<Long, ScriptEnvironmentEntityView> environments = new HashMap<Long, ScriptEnvironmentEntityView>();
        private ScriptEnvironmentEntityView defaultEnvironment;
    }
}
