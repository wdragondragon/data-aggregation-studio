package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataDevelopmentDirectoryView;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.DataDevelopmentScriptView;
import com.jdragon.studio.dto.model.DataDevelopmentTreeNode;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.request.DataDevelopmentDirectorySaveRequest;
import com.jdragon.studio.dto.model.request.DataDevelopmentMoveRequest;
import com.jdragon.studio.dto.model.request.DataDevelopmentScriptSaveRequest;
import com.jdragon.studio.dto.model.request.DataScriptExecutionRequest;
import com.jdragon.studio.dto.model.request.SqlExecutionRequest;
import com.jdragon.studio.infra.entity.DataDevelopmentDirectoryEntity;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.mapper.DataDevelopmentDirectoryMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.service.script.DataDevelopmentExecutionContext;
import com.jdragon.studio.infra.service.script.DataDevelopmentScriptExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

@Service
public class DataDevelopmentService {

    private final DataDevelopmentDirectoryMapper directoryMapper;
    private final DataDevelopmentScriptMapper scriptMapper;
    private final DataSourceService dataSourceService;
    private final DataDevelopmentSqlExecutor sqlExecutor;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final Map<ScriptType, DataDevelopmentScriptExecutor> scriptExecutors;

    public DataDevelopmentService(DataDevelopmentDirectoryMapper directoryMapper,
                                  DataDevelopmentScriptMapper scriptMapper,
                                  DataSourceService dataSourceService,
                                  DataDevelopmentSqlExecutor sqlExecutor,
                                  StudioSecurityService securityService,
                                  ProjectResourceAccessService projectResourceAccessService,
                                  List<DataDevelopmentScriptExecutor> scriptExecutors) {
        this.directoryMapper = directoryMapper;
        this.scriptMapper = scriptMapper;
        this.dataSourceService = dataSourceService;
        this.sqlExecutor = sqlExecutor;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.scriptExecutors = new HashMap<ScriptType, DataDevelopmentScriptExecutor>();
        if (scriptExecutors != null) {
            for (DataDevelopmentScriptExecutor executor : scriptExecutors) {
                this.scriptExecutors.put(executor.getScriptType(), executor);
            }
        }
    }

    public List<DataDevelopmentTreeNode> tree() {
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        return buildTree(listDirectoryEntities(tenantId, projectId), listScriptEntities(tenantId, projectId, null));
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

    public List<DataDevelopmentScriptView> listScripts(ScriptType scriptType) {
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        List<DataDevelopmentScriptView> result = new ArrayList<DataDevelopmentScriptView>();
        for (DataDevelopmentScriptEntity entity : listScriptEntities(tenantId, projectId, scriptType)) {
            result.add(toScriptView(entity));
        }
        return result;
    }

    public List<DataSourceDefinition> listSqlCapableDatasources() {
        projectResourceAccessService.requireCurrentProjectId();
        List<DataSourceDefinition> result = new ArrayList<DataSourceDefinition>();
        for (DataSourceDefinition datasource : dataSourceService.list()) {
            if (sqlExecutor.supports(datasource)) {
                result.add(datasource);
            }
        }
        return result;
    }

    public DataDevelopmentScriptView getScript(Long scriptId) {
        return toScriptView(requireReadableScript(scriptId));
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
        DataSourceDefinition datasource = resolveScriptDatasource(request.getScriptType(), request.getDatasourceId());
        validateScriptFileName(tenantId, projectId, request.getDirectoryId(), request.getFileName(), entity.getId());
        entity.setTenantId(tenantId);
        entity.setProjectId(projectId);
        entity.setDirectoryId(request.getDirectoryId());
        entity.setFileName(request.getFileName().trim());
        entity.setScriptType(request.getScriptType().name());
        entity.setDatasourceId(datasource == null ? null : datasource.getId());
        entity.setDescription(blankToNull(request.getDescription()));
        entity.setContent(request.getContent());
        if (entity.getId() == null) {
            scriptMapper.insert(entity);
        } else {
            scriptMapper.updateById(entity);
        }
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
        projectResourceAccessService.requireCurrentProjectId();
        if (request.getScriptType() != ScriptType.SQL) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only SQL scripts can use the SQL execution endpoint");
        }
        return sqlExecutor.executeSql(requireSqlDatasource(request.getDatasourceId()), request.getContent(), request.getMaxRows());
    }

    public DataScriptExecutionResultView execute(DataScriptExecutionRequest request) {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        validateScriptType(request.getScriptType());
        DataDevelopmentExecutionContext context = new DataDevelopmentExecutionContext();
        context.setScriptType(request.getScriptType());
        context.setContent(request.getContent());
        context.setDatasourceId(request.getDatasourceId());
        context.setDatasource(resolveScriptDatasource(request.getScriptType(), request.getDatasourceId()));
        context.setMaxRows(request.getMaxRows());
        context.setTenantId(securityService.currentTenantId());
        context.setUsername(securityService.currentUsername());
        context.setArguments(request.getArguments());
        Map<String, Object> runtimeContext = new LinkedHashMap<String, Object>();
        runtimeContext.put("tenantId", context.getTenantId());
        runtimeContext.put("projectId", projectId);
        runtimeContext.put("username", context.getUsername());
        context.setRuntimeContext(runtimeContext);
        return requireExecutor(request.getScriptType()).execute(context);
    }

    public DataScriptExecutionResultView executeScript(Long scriptId, Integer maxRows, Map<String, Object> arguments, Map<String, Object> runtimeContext) {
        DataDevelopmentScriptEntity script = requireReadableScript(scriptId);
        ScriptType scriptType = ScriptType.valueOf(script.getScriptType());
        DataDevelopmentExecutionContext context = new DataDevelopmentExecutionContext();
        context.setScriptId(script.getId());
        context.setScriptName(script.getFileName());
        context.setScriptType(scriptType);
        context.setContent(script.getContent());
        context.setDatasourceId(script.getDatasourceId());
        context.setDatasource(resolveScriptDatasource(scriptType, script.getDatasourceId()));
        context.setMaxRows(maxRows);
        context.setTenantId(script.getTenantId());
        context.setUsername(resolveExecutionUsername(runtimeContext));
        context.setArguments(arguments);
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

    private DataSourceDefinition requireSqlDatasource(Long datasourceId) {
        DataSourceDefinition datasource = dataSourceService.getInternal(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        if (!sqlExecutor.supports(datasource)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only database datasources can execute SQL scripts");
        }
        return datasource;
    }

    private DataSourceDefinition resolveScriptDatasource(ScriptType scriptType, Long datasourceId) {
        if (scriptType == ScriptType.SQL) {
            return requireSqlDatasource(datasourceId);
        }
        if (datasourceId == null) {
            return null;
        }
        return dataSourceService.getInternal(datasourceId);
    }

    private List<DataDevelopmentDirectoryEntity> listDirectoryEntities(String tenantId, Long projectId) {
        return directoryMapper.selectList(new LambdaQueryWrapper<DataDevelopmentDirectoryEntity>()
                .eq(DataDevelopmentDirectoryEntity::getTenantId, tenantId)
                .eq(DataDevelopmentDirectoryEntity::getProjectId, projectId)
                .orderByAsc(DataDevelopmentDirectoryEntity::getParentId)
                .orderByAsc(DataDevelopmentDirectoryEntity::getName));
    }

    private List<DataDevelopmentScriptEntity> listScriptEntities(String tenantId, Long projectId, ScriptType scriptType) {
        LambdaQueryWrapper<DataDevelopmentScriptEntity> wrapper = new LambdaQueryWrapper<DataDevelopmentScriptEntity>()
                .eq(DataDevelopmentScriptEntity::getTenantId, tenantId)
                .orderByAsc(DataDevelopmentScriptEntity::getDirectoryId)
                .orderByAsc(DataDevelopmentScriptEntity::getProjectId)
                .orderByAsc(DataDevelopmentScriptEntity::getFileName);
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
        return scriptMapper.selectList(wrapper);
    }

    private List<DataDevelopmentTreeNode> buildTree(List<DataDevelopmentDirectoryEntity> directories,
                                                    List<DataDevelopmentScriptEntity> scripts) {
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
            DataSourceDefinition datasource = dataSourceService.get(entity.getDatasourceId());
            node.setDatasourceName(datasource == null ? null : datasource.getName());
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
        if (scriptType != ScriptType.SQL && scriptType != ScriptType.JAVA && scriptType != ScriptType.PYTHON) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only SQL, Java and Python scripts are currently supported");
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
        DataSourceDefinition datasource = entity.getDatasourceId() == null ? null : dataSourceService.get(entity.getDatasourceId());
        if (datasource != null) {
            view.setDatasourceName(datasource.getName());
            view.setDatasourceTypeCode(datasource.getTypeCode());
        }
        view.setDescription(entity.getDescription());
        view.setContent(entity.getContent());
        return view;
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
}
