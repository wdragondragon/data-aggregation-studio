package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.UnstructuredAclEffect;
import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
import com.jdragon.studio.dto.enums.UnstructuredAclPrincipalType;
import com.jdragon.studio.dto.enums.UnstructuredFileOperation;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataSourceOptionView;
import com.jdragon.studio.dto.model.FileTransferBrowserPageView;
import com.jdragon.studio.dto.model.FileTransferFileEntryView;
import com.jdragon.studio.dto.model.StudioUserOptionView;
import com.jdragon.studio.dto.model.UnstructuredAclEntryView;
import com.jdragon.studio.dto.model.UnstructuredOperationResultView;
import com.jdragon.studio.dto.model.UnstructuredPermissionView;
import com.jdragon.studio.dto.model.UnstructuredSourceView;
import com.jdragon.studio.dto.model.request.UnstructuredAclEntryRequest;
import com.jdragon.studio.dto.model.request.UnstructuredOperationRequest;
import com.jdragon.studio.dto.model.request.UnstructuredPathAclRequest;
import com.jdragon.studio.dto.model.request.UnstructuredSourceAclRequest;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.UnstructuredOpAuditEntity;
import com.jdragon.studio.infra.entity.UnstructuredPathAclEntity;
import com.jdragon.studio.infra.entity.UnstructuredSourceAclEntity;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UnstructuredOpAuditMapper;
import com.jdragon.studio.infra.mapper.UnstructuredPathAclMapper;
import com.jdragon.studio.infra.mapper.UnstructuredSourceAclMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class UnstructuredManagementService {
    private static final Set<String> FILE_TYPES = Set.of(
            "local", "local_file", "file", "ftp", "sftp", "minio", "oss", "aliyun", "aliyun_oss", "aliyun-oss");

    private final DataSourceService dataSourceService;
    private final RuntimeClusterSelectionService runtimeClusterSelectionService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final StudioSecurityService securityService;
    private final RuntimeDatasourceProbeRouter runtimeRouter;
    private final UnstructuredSourceAclMapper sourceAclMapper;
    private final UnstructuredPathAclMapper pathAclMapper;
    private final UnstructuredOpAuditMapper auditMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final StudioUserMapper userMapper;

    public UnstructuredManagementService(DataSourceService dataSourceService,
                                         RuntimeClusterSelectionService runtimeClusterSelectionService,
                                         ProjectResourceAccessService projectResourceAccessService,
                                         StudioSecurityService securityService,
                                         RuntimeDatasourceProbeRouter runtimeRouter,
                                         UnstructuredSourceAclMapper sourceAclMapper,
                                         UnstructuredPathAclMapper pathAclMapper,
                                         UnstructuredOpAuditMapper auditMapper,
                                         ProjectMemberMapper projectMemberMapper,
                                         StudioUserMapper userMapper) {
        this.dataSourceService = dataSourceService;
        this.runtimeClusterSelectionService = runtimeClusterSelectionService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.securityService = securityService;
        this.runtimeRouter = runtimeRouter;
        this.sourceAclMapper = sourceAclMapper;
        this.pathAclMapper = pathAclMapper;
        this.auditMapper = auditMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.userMapper = userMapper;
    }

    public List<UnstructuredSourceView> sources(Long runtimeClusterId) {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        runtimeClusterSelectionService.resolveForSave(projectId, runtimeClusterId);
        List<DataSourceOptionView> options = dataSourceService.listBasicOptionsByTypes(FILE_TYPES, runtimeClusterId);
        List<UnstructuredSourceView> result = new ArrayList<UnstructuredSourceView>();
        for (DataSourceOptionView option : options) {
            DataSourceDefinition datasource = dataSourceService.get(option.getId());
            if (datasource == null || !isFileType(datasource.getTypeCode())) {
                continue;
            }
            UnstructuredSourceView view = sourceView(datasource, runtimeClusterId);
            if (!view.getEffectivePermissions().isEmpty()) {
                result.add(view);
            }
        }
        return result;
    }

    public FileTransferBrowserPageView browse(Long runtimeClusterId, Long datasourceId,
                                              String path, String cursor, Integer pageSize) {
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId);
        String normalizedPath = normalizePath(path);
        assertPermission(datasource, normalizedPath, UnstructuredAclPermission.BROWSE);
        return runtimeRouter.browse(datasource, runtimeClusterId, normalizedPath, cursor, pageSize);
    }

    public FileTransferFileEntryView stat(Long runtimeClusterId, Long datasourceId, String path,
                                          UnstructuredAclPermission permission) {
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId);
        String normalizedPath = normalizePath(path);
        assertPermission(datasource, normalizedPath, permission);
        return runtimeRouter.stat(datasource, runtimeClusterId, normalizedPath);
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public UnstructuredOperationResultView operate(UnstructuredOperationRequest request) {
        DataSourceDefinition datasource = requireDatasource(request.getDatasourceId(), request.getRuntimeClusterId());
        UnstructuredFileOperation operation = parseOperation(request.getOperation());
        String sourcePath = normalizePath(request.getSourcePath());
        String targetPath = request.getTargetPath() == null || request.getTargetPath().trim().isEmpty()
                ? null : normalizePath(request.getTargetPath());
        UnstructuredAclPermission permission = operation == UnstructuredFileOperation.DELETE
                ? UnstructuredAclPermission.DELETE : UnstructuredAclPermission.EDIT;
        assertPermission(datasource, sourcePath, permission);
        if (targetPath != null) {
            assertPermission(datasource, parentPath(targetPath), UnstructuredAclPermission.EDIT);
        }
        try {
            runtimeRouter.operate(datasource, request.getRuntimeClusterId(), operation.name(),
                    sourcePath, targetPath, request.getRecursiveConfirmed());
            recordAudit(datasource, request, operation.name(), sourcePath, targetPath, "SUCCESS", "");
            UnstructuredOperationResultView result = new UnstructuredOperationResultView();
            result.setOperation(operation.name());
            result.setSourcePath(sourcePath);
            result.setTargetPath(targetPath);
            result.setRecursive(Boolean.TRUE.equals(request.getRecursiveConfirmed()));
            result.setMessage("Operation completed");
            return result;
        } catch (RuntimeException exception) {
            recordAudit(datasource, request, operation.name(), sourcePath, targetPath,
                    "FAILED", message(exception));
            throw exception;
        }
    }

    public PreparedDownload prepareDownload(Long runtimeClusterId, Long datasourceId, String path) {
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId);
        String normalizedPath = normalizePath(path);
        assertPermission(datasource, normalizedPath, UnstructuredAclPermission.DOWNLOAD);
        FileTransferFileEntryView entry = runtimeRouter.stat(
                datasource, runtimeClusterId, normalizedPath);
        if (Boolean.TRUE.equals(entry.getDirectory())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Only files can be downloaded");
        }
        return new PreparedDownload(datasource, runtimeClusterId, normalizedPath, entry);
    }

    public void download(PreparedDownload prepared, java.io.OutputStream output) {
        if (prepared == null || prepared.datasource() == null
                || prepared.runtimeClusterId() == null || prepared.path() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Prepared download context is incomplete");
        }
        runtimeRouter.download(prepared.datasource(), prepared.runtimeClusterId(),
                prepared.path(), output);
    }

    public List<UnstructuredAclEntryView> sourceAcl(Long datasourceId) {
        DataSourceDefinition datasource = requireDatasourceWithoutCluster(datasourceId);
        requireAclManager(datasource);
        return sourceAclViews(datasource, sourceAclMapper.selectList(sourceAclQuery(datasource, null)));
    }

    @Transactional
    public List<UnstructuredAclEntryView> replaceSourceAcl(Long datasourceId, UnstructuredSourceAclRequest request) {
        DataSourceDefinition datasource = requireDatasourceWithoutCluster(datasourceId);
        requireAclManager(datasource);
        replaceSourceEntries(datasource, request == null ? null : request.getEntries());
        return sourceAcl(datasourceId);
    }

    public List<UnstructuredAclEntryView> pathAcl(Long datasourceId, String path) {
        DataSourceDefinition datasource = requireDatasourceWithoutCluster(datasourceId);
        requireAclManager(datasource);
        String normalizedPath = normalizePath(path);
        return pathAclViews(datasource, pathAclMapper.selectList(pathAclQuery(datasource, normalizedPath)));
    }

    @Transactional
    public List<UnstructuredAclEntryView> replacePathAcl(Long datasourceId, UnstructuredPathAclRequest request) {
        DataSourceDefinition datasource = requireDatasourceWithoutCluster(datasourceId);
        requireAclManager(datasource);
        String path = normalizePath(request == null ? null : request.getPath());
        pathAclMapper.delete(pathAclQuery(datasource, path));
        for (UnstructuredAclEntryRequest entry : request == null ? List.<UnstructuredAclEntryRequest>of() : request.getEntries()) {
            pathAclMapper.insert(toPathEntity(datasource, path,
                    !Boolean.FALSE.equals(request.getDirectory()), entry));
        }
        return pathAcl(datasourceId, path);
    }

    @Transactional
    public void deleteAcl(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "ACL id is required");
        }
        UnstructuredSourceAclEntity source = sourceAclMapper.selectById(id);
        if (source != null) {
            DataSourceDefinition datasource = requireDatasourceWithoutCluster(source.getDatasourceId());
            requireAclManager(datasource);
            sourceAclMapper.deleteById(id);
            return;
        }
        UnstructuredPathAclEntity path = pathAclMapper.selectById(id);
        if (path != null) {
            DataSourceDefinition datasource = requireDatasourceWithoutCluster(path.getDatasourceId());
            requireAclManager(datasource);
            pathAclMapper.deleteById(id);
            return;
        }
        throw new StudioException(StudioErrorCode.NOT_FOUND, "ACL not found");
    }

    public List<StudioUserOptionView> userOptions() {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        List<ProjectMemberEntity> members = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, projectId)
                .eq(ProjectMemberEntity::getStatus, "ACTIVE")
                .orderByAsc(ProjectMemberEntity::getUserId));
        List<StudioUserOptionView> result = new ArrayList<StudioUserOptionView>();
        for (ProjectMemberEntity member : members) {
            StudioUserEntity user = userMapper.selectById(member.getUserId());
            if (user == null || !Integer.valueOf(1).equals(user.getEnabled())) continue;
            StudioUserOptionView view = new StudioUserOptionView();
            view.setId(user.getId());
            view.setUsername(user.getUsername());
            view.setDisplayName(user.getDisplayName());
            result.add(view);
        }
        return result;
    }

    public UnstructuredPermissionView permissions(Long datasourceId, String path) {
        DataSourceDefinition datasource = requireDatasourceWithoutCluster(datasourceId);
        String normalizedPath = normalizePath(path);
        UnstructuredPermissionView view = new UnstructuredPermissionView();
        view.setDatasourceId(datasourceId);
        view.setPath(normalizedPath);
        view.setOwnerOrAdmin(isOwnerOrAdmin(datasource));
        for (UnstructuredAclPermission permission : UnstructuredAclPermission.values()) {
            if (hasPermission(datasource, normalizedPath, permission)) {
                view.getEffectivePermissions().add(permission.name());
            }
        }
        return view;
    }

    private DataSourceDefinition requireDatasource(Long datasourceId, Long runtimeClusterId) {
        DataSourceDefinition datasource = requireDatasourceWithoutCluster(datasourceId);
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        runtimeClusterSelectionService.validateExplicitDatasourceSelection(projectId, runtimeClusterId,
                List.of(datasourceId));
        return datasource;
    }

    private DataSourceDefinition requireDatasourceWithoutCluster(Long datasourceId) {
        DataSourceDefinition datasource = dataSourceService.requireRunnableForExecution(datasourceId);
        if (!isFileType(datasource.getTypeCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Only Local, FTP, SFTP, MinIO and OSS datasources are supported");
        }
        return datasource;
    }

    private boolean isFileType(String typeCode) {
        return typeCode != null && FILE_TYPES.contains(typeCode.trim().toLowerCase(Locale.ROOT));
    }

    private UnstructuredSourceView sourceView(DataSourceDefinition datasource, Long runtimeClusterId) {
        UnstructuredSourceView view = new UnstructuredSourceView();
        view.setId(datasource.getId());
        view.setName(datasource.getName());
        view.setTypeCode(datasource.getTypeCode());
        view.setRuntimeClusterId(runtimeClusterId);
        view.setCreatedBy(datasource.getCreatedBy());
        view.setAclManageable(isOwnerOrAdmin(datasource));
        for (UnstructuredAclPermission permission : UnstructuredAclPermission.values()) {
            if (hasPermission(datasource, "/", permission)) view.getEffectivePermissions().add(permission.name());
        }
        return view;
    }

    private void assertPermission(DataSourceDefinition datasource, String path, UnstructuredAclPermission permission) {
        if (!hasPermission(datasource, path, permission)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN,
                    "No " + permission.name() + " permission for path " + path);
        }
    }

    boolean hasPermission(DataSourceDefinition datasource, String path, UnstructuredAclPermission permission) {
        if (isOwnerOrAdmin(datasource)) return true;
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        Long userId = securityService.currentUserId();
        if (userId == null || projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, projectId)
                .eq(ProjectMemberEntity::getUserId, userId)
                .eq(ProjectMemberEntity::getStatus, "ACTIVE")) == 0L) return false;
        List<UnstructuredPathAclEntity> pathRules = new ArrayList<>(pathAclMapper.selectList(new LambdaQueryWrapper<UnstructuredPathAclEntity>()
                .eq(UnstructuredPathAclEntity::getTenantId, datasource.getTenantId())
                .eq(UnstructuredPathAclEntity::getProjectId, datasource.getProjectId())
                .eq(UnstructuredPathAclEntity::getDatasourceId, datasource.getId())
                .eq(UnstructuredPathAclEntity::getPermission, permission.name())));
        pathRules.sort(Comparator
                .comparingInt((UnstructuredPathAclEntity rule) -> rule.getPath() == null ? 0 : rule.getPath().length())
                .reversed()
                .thenComparingInt(rule -> UnstructuredAclPrincipalType.USER.name().equalsIgnoreCase(rule.getPrincipalType()) ? 0 : 1)
                .thenComparingInt(rule -> UnstructuredAclEffect.DENY.name().equalsIgnoreCase(rule.getEffect()) ? 0 : 1));
        String normalizedPath = normalizePath(path);
        for (UnstructuredPathAclEntity rule : pathRules) {
            if (!matchesPath(rule, normalizedPath)) continue;
            Boolean decision = ruleDecision(rule.getPrincipalType(), rule.getUserId(), rule.getEffect(), userId);
            if (decision != null) return decision;
        }
        List<UnstructuredSourceAclEntity> sourceRules = sourceAclMapper.selectList(sourceAclQuery(datasource, permission.name()));
        for (UnstructuredSourceAclEntity rule : sourceRules) {
            Boolean decision = ruleDecision(rule.getPrincipalType(), rule.getUserId(), rule.getEffect(), userId);
            if (decision != null) return decision;
        }
        return permission == UnstructuredAclPermission.BROWSE || permission == UnstructuredAclPermission.DOWNLOAD;
    }

    private Boolean ruleDecision(String principalType, Long ruleUserId, String effect, Long currentUserId) {
        boolean applies = UnstructuredAclPrincipalType.PROJECT.name().equalsIgnoreCase(principalType)
                || (UnstructuredAclPrincipalType.USER.name().equalsIgnoreCase(principalType)
                && ruleUserId != null && ruleUserId.equals(currentUserId));
        if (!applies || effect == null || UnstructuredAclEffect.INHERIT.name().equalsIgnoreCase(effect)) return null;
        return UnstructuredAclEffect.ALLOW.name().equalsIgnoreCase(effect);
    }

    boolean matchesPath(UnstructuredPathAclEntity rule, String candidate) {
        String normalizedRule = normalizePath(rule == null ? null : rule.getPath());
        String normalizedCandidate = normalizePath(candidate);
        if (normalizedRule.equals(normalizedCandidate)) return true;
        if (rule != null && Integer.valueOf(0).equals(rule.getDirectory())) return false;
        return "/".equals(normalizedRule) || normalizedCandidate.startsWith(normalizedRule + "/");
    }

    private boolean isOwnerOrAdmin(DataSourceDefinition datasource) {
        return securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN,
                StudioConstants.ROLE_TENANT_ADMIN, StudioConstants.ROLE_ADMIN,
                StudioConstants.ROLE_PROJECT_ADMIN)
                || (datasource.getCreatedBy() != null && datasource.getCreatedBy().equals(securityService.currentUserId()));
    }

    private void requireAclManager(DataSourceDefinition datasource) {
        if (!isOwnerOrAdmin(datasource)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN,
                    "Only the datasource creator or an administrator can manage ACLs");
        }
    }

    private LambdaQueryWrapper<UnstructuredSourceAclEntity> sourceAclQuery(DataSourceDefinition datasource, String permission) {
        LambdaQueryWrapper<UnstructuredSourceAclEntity> query = new LambdaQueryWrapper<UnstructuredSourceAclEntity>()
                .eq(UnstructuredSourceAclEntity::getTenantId, datasource.getTenantId())
                .eq(UnstructuredSourceAclEntity::getProjectId, datasource.getProjectId())
                .eq(UnstructuredSourceAclEntity::getDatasourceId, datasource.getId());
        if (permission != null) query.eq(UnstructuredSourceAclEntity::getPermission, permission);
        return query.orderByAsc(UnstructuredSourceAclEntity::getId);
    }

    private LambdaQueryWrapper<UnstructuredPathAclEntity> pathAclQuery(DataSourceDefinition datasource, String path) {
        return new LambdaQueryWrapper<UnstructuredPathAclEntity>()
                .eq(UnstructuredPathAclEntity::getTenantId, datasource.getTenantId())
                .eq(UnstructuredPathAclEntity::getProjectId, datasource.getProjectId())
                .eq(UnstructuredPathAclEntity::getDatasourceId, datasource.getId())
                .eq(UnstructuredPathAclEntity::getPath, path)
                .orderByAsc(UnstructuredPathAclEntity::getId);
    }

    private void replaceSourceEntries(DataSourceDefinition datasource, List<UnstructuredAclEntryRequest> entries) {
        sourceAclMapper.delete(sourceAclQuery(datasource, null));
        for (UnstructuredAclEntryRequest entry : entries == null ? List.<UnstructuredAclEntryRequest>of() : entries) {
            sourceAclMapper.insert(toSourceEntity(datasource, entry));
        }
    }

    private UnstructuredSourceAclEntity toSourceEntity(DataSourceDefinition datasource, UnstructuredAclEntryRequest entry) {
        validateAclEntry(datasource, entry);
        UnstructuredSourceAclEntity entity = new UnstructuredSourceAclEntity();
        entity.setTenantId(datasource.getTenantId()); entity.setProjectId(datasource.getProjectId());
        entity.setDatasourceId(datasource.getId()); entity.setPrincipalType(entry.getPrincipalType().toUpperCase(Locale.ROOT));
        entity.setUserId(entity.getPrincipalType().equals("USER") ? entry.getUserId() : null);
        entity.setPermission(entry.getPermission().toUpperCase(Locale.ROOT));
        entity.setEffect(entry.getEffect().toUpperCase(Locale.ROOT)); entity.setCreatedBy(securityService.currentUserId());
        return entity;
    }

    private UnstructuredPathAclEntity toPathEntity(DataSourceDefinition datasource, String path,
                                                    boolean directory, UnstructuredAclEntryRequest entry) {
        validateAclEntry(datasource, entry);
        UnstructuredPathAclEntity entity = new UnstructuredPathAclEntity();
        entity.setTenantId(datasource.getTenantId()); entity.setProjectId(datasource.getProjectId());
        entity.setDatasourceId(datasource.getId()); entity.setPath(path); entity.setDirectory(directory ? 1 : 0);
        entity.setPrincipalType(entry.getPrincipalType().toUpperCase(Locale.ROOT));
        entity.setUserId(entity.getPrincipalType().equals("USER") ? entry.getUserId() : null);
        entity.setPermission(entry.getPermission().toUpperCase(Locale.ROOT));
        entity.setEffect(entry.getEffect().toUpperCase(Locale.ROOT)); entity.setCreatedBy(securityService.currentUserId());
        return entity;
    }

    private void validateAclEntry(DataSourceDefinition datasource, UnstructuredAclEntryRequest entry) {
        if (entry == null || entry.getPrincipalType() == null || entry.getPermission() == null || entry.getEffect() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "ACL entry is incomplete");
        }
        String principal = entry.getPrincipalType().toUpperCase(Locale.ROOT);
        if (!Set.of("PROJECT", "USER").contains(principal)) throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid ACL principal type");
        if ("USER".equals(principal)) {
            if (entry.getUserId() == null || projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMemberEntity>()
                    .eq(ProjectMemberEntity::getProjectId, datasource.getProjectId())
                    .eq(ProjectMemberEntity::getUserId, entry.getUserId())
                    .eq(ProjectMemberEntity::getStatus, "ACTIVE")) == 0L) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "ACL user must be an active project member");
            }
        }
        if (!Set.of("BROWSE", "DOWNLOAD", "EDIT", "DELETE").contains(entry.getPermission().toUpperCase(Locale.ROOT))
                || !Set.of("ALLOW", "DENY", "INHERIT").contains(entry.getEffect().toUpperCase(Locale.ROOT))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid ACL permission or effect");
        }
    }

    private List<UnstructuredAclEntryView> sourceAclViews(DataSourceDefinition datasource, List<UnstructuredSourceAclEntity> entities) {
        List<UnstructuredAclEntryView> result = new ArrayList<UnstructuredAclEntryView>();
        for (UnstructuredSourceAclEntity entity : entities) result.add(toView(entity));
        return result;
    }

    private List<UnstructuredAclEntryView> pathAclViews(DataSourceDefinition datasource, List<UnstructuredPathAclEntity> entities) {
        List<UnstructuredAclEntryView> result = new ArrayList<UnstructuredAclEntryView>();
        for (UnstructuredPathAclEntity entity : entities) result.add(toView(entity));
        return result;
    }

    private UnstructuredAclEntryView toView(UnstructuredSourceAclEntity entity) {
        UnstructuredAclEntryView view = new UnstructuredAclEntryView(); view.setId(entity.getId()); view.setDatasourceId(entity.getDatasourceId());
        view.setPrincipalType(entity.getPrincipalType()); view.setUserId(entity.getUserId()); view.setPermission(entity.getPermission()); view.setEffect(entity.getEffect());
        hydrateUser(view); return view;
    }

    private UnstructuredAclEntryView toView(UnstructuredPathAclEntity entity) {
        UnstructuredAclEntryView view = new UnstructuredAclEntryView(); view.setId(entity.getId()); view.setDatasourceId(entity.getDatasourceId()); view.setPath(entity.getPath());
        view.setDirectory(!Integer.valueOf(0).equals(entity.getDirectory()));
        view.setPrincipalType(entity.getPrincipalType()); view.setUserId(entity.getUserId()); view.setPermission(entity.getPermission()); view.setEffect(entity.getEffect());
        hydrateUser(view); return view;
    }

    private void hydrateUser(UnstructuredAclEntryView view) {
        if (view.getUserId() == null) return;
        StudioUserEntity user = userMapper.selectById(view.getUserId());
        if (user != null) { view.setUsername(user.getUsername()); view.setDisplayName(user.getDisplayName()); }
    }

    private void recordAudit(DataSourceDefinition datasource, UnstructuredOperationRequest request, String operation,
                             String sourcePath, String targetPath, String status, String message) {
        UnstructuredOpAuditEntity audit = new UnstructuredOpAuditEntity();
        audit.setTenantId(datasource.getTenantId()); audit.setProjectId(datasource.getProjectId());
        audit.setDatasourceId(datasource.getId()); audit.setRuntimeClusterId(request.getRuntimeClusterId());
        audit.setUserId(securityService.currentUserId()); audit.setUsername(securityService.currentUsername());
        audit.setOperation(operation); audit.setSourcePath(sourcePath); audit.setTargetPath(targetPath);
        audit.setRecursive(Boolean.TRUE.equals(request.getRecursiveConfirmed()) ? 1 : 0); audit.setStatus(status); audit.setMessage(message);
        auditMapper.insert(audit);
    }

    private String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private UnstructuredFileOperation parseOperation(String value) {
        try { return UnstructuredFileOperation.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported file operation"); }
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) return "/";
        String value = rawPath.trim().replace('\\', '/');
        if (value.indexOf('\0') >= 0) throw new StudioException(StudioErrorCode.BAD_REQUEST, "Path contains a NUL character");
        while (value.contains("//")) value = value.replace("//", "/");
        if (!value.startsWith("/")) value = "/" + value;
        List<String> segments = new ArrayList<String>();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) throw new StudioException(StudioErrorCode.BAD_REQUEST, "Path must not contain '..'");
            segments.add(segment);
        }
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    private String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    public record PreparedDownload(DataSourceDefinition datasource,
                                   Long runtimeClusterId,
                                   String path,
                                   FileTransferFileEntryView entry) {
    }
}
