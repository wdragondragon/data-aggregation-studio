package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;
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
import com.jdragon.studio.dto.model.UnstructuredUploadResultView;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

@Service
public class UnstructuredManagementService {
    private static final Logger log = LoggerFactory.getLogger(UnstructuredManagementService.class);
    private static final Set<String> FILE_TYPES = Set.of(
            "local", "local_file", "file", "ftp", "sftp", "minio", "oss", "aliyun", "aliyun_oss", "aliyun-oss");
    private static final int MAX_AUDIT_MESSAGE_LENGTH = 1800;
    private static final String AUDIT_MESSAGE_TRUNCATED_SUFFIX = " ...[truncated]";
    private static final int MAX_SANITIZED_STACK_TRACE_LENGTH = 12 * 1024;
    private static final int MAX_SANITIZED_ERROR_MESSAGE_LENGTH = 2 * 1024;
    private static final String STACK_TRACE_TRUNCATED_SUFFIX = "\n...[truncated]";

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
        String operationId = operationId();
        long startedAt = System.nanoTime();
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId);
        String normalizedPath = normalizePath(path);
        assertPermissionLogged(operationId, datasource, normalizedPath,
                UnstructuredAclPermission.BROWSE, "BROWSE");
        log.debug("[UF_BROWSE_START] Server 开始路由非结构化目录浏览 operationId={} "
                        + "runtimeClusterId={} datasourceId={} datasourceType={} path={} pageSize={}",
                operationId, runtimeClusterId, datasourceId, datasource.getTypeCode(), normalizedPath, pageSize);
        try {
            FileTransferBrowserPageView result = runtimeRouter.browse(datasource, runtimeClusterId,
                    normalizedPath, cursor, pageSize, operationId);
            log.debug("[UF_BROWSE_COMPLETED] Server 非结构化目录浏览完成 operationId={} "
                            + "runtimeClusterId={} datasourceId={} path={} entries={} hasMore={} durationMillis={}",
                    operationId, runtimeClusterId, datasourceId, normalizedPath,
                    result == null ? 0 : result.getEntries().size(),
                    result == null ? null : result.getHasMore(), elapsedMillis(startedAt));
            return result;
        } catch (RuntimeException exception) {
            logRouteFailure(operationId, "BROWSE", datasource, runtimeClusterId,
                    normalizedPath, null, exception, startedAt);
            throw exception;
        }
    }

    public FileTransferFileEntryView stat(Long runtimeClusterId, Long datasourceId, String path,
                                          UnstructuredAclPermission permission) {
        String operationId = operationId();
        long startedAt = System.nanoTime();
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId);
        String normalizedPath = normalizePath(path);
        assertPermissionLogged(operationId, datasource, normalizedPath, permission, "STAT");
        log.debug("[UF_STAT_START] Server 开始路由非结构化文件属性读取 operationId={} "
                        + "runtimeClusterId={} datasourceId={} datasourceType={} path={}",
                operationId, runtimeClusterId, datasourceId, datasource.getTypeCode(), normalizedPath);
        try {
            FileTransferFileEntryView result = runtimeRouter.stat(
                    datasource, runtimeClusterId, normalizedPath, operationId);
            log.debug("[UF_STAT_COMPLETED] Server 非结构化文件属性读取完成 operationId={} "
                            + "runtimeClusterId={} datasourceId={} path={} directory={} size={} durationMillis={}",
                    operationId, runtimeClusterId, datasourceId, normalizedPath,
                    result.getDirectory(), result.getSize(), elapsedMillis(startedAt));
            return result;
        } catch (RuntimeException exception) {
            logRouteFailure(operationId, "STAT", datasource, runtimeClusterId,
                    normalizedPath, null, exception, startedAt);
            throw exception;
        }
    }

    public FileTransferFileEntryView statForUpload(Long runtimeClusterId, Long datasourceId,
                                                   String path) {
        String operationId = operationId();
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId);
        String normalizedPath = normalizePath(path);
        if ("/".equals(normalizedPath)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Upload target must be a file path");
        }
        assertPermissionLogged(operationId, datasource, parentPath(normalizedPath),
                UnstructuredAclPermission.EDIT, "STAT");
        return runtimeRouter.stat(datasource, runtimeClusterId, normalizedPath, operationId);
    }

    public UnstructuredUploadResultView upload(Long runtimeClusterId, Long datasourceId,
                                               String targetPath, boolean overwrite,
                                               long contentLength, InputStream input) {
        String operationId = operationId();
        long startedAt = System.nanoTime();
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId);
        String normalizedPath = normalizePath(targetPath);
        if ("/".equals(normalizedPath)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Upload target must be a file path");
        }
        assertPermissionLogged(operationId, datasource, parentPath(normalizedPath),
                UnstructuredAclPermission.EDIT, "UPLOAD");
        Long userId = securityService.currentUserId();
        String username = securityService.currentUsername();
        log.info("[UF_UPLOAD_START] Server 开始路由非结构化文件上传 operationId={} "
                        + "runtimeClusterId={} datasourceId={} datasourceType={} targetPath={} "
                        + "overwrite={} declaredBytes={}",
                operationId, runtimeClusterId, datasourceId, datasource.getTypeCode(),
                normalizedPath, overwrite, contentLength);
        try {
            if (!overwrite) {
                try {
                    runtimeRouter.stat(datasource, runtimeClusterId, normalizedPath, operationId);
                    throw new StudioException(StudioErrorCode.CONFLICT,
                            "Upload target already exists");
                } catch (StudioException exception) {
                    if (!StudioErrorCode.NOT_FOUND.equals(exception.getCode())) {
                        throw exception;
                    }
                }
            }
            long bytes = runtimeRouter.upload(datasource, runtimeClusterId, normalizedPath,
                    overwrite, contentLength, input, operationId);
            recordAuditSafely(datasource, runtimeClusterId, userId, username,
                    "UPLOAD", null, normalizedPath, false, "SUCCESS", bytes + " bytes");
            UnstructuredUploadResultView result = new UnstructuredUploadResultView();
            result.setOperation("UPLOAD");
            result.setTargetPath(normalizedPath);
            result.setBytes(bytes);
            result.setOverwritten(overwrite);
            result.setMessage("Upload completed");
            log.info("[UF_UPLOAD_COMPLETED] Server 非结构化文件上传完成 operationId={} "
                            + "runtimeClusterId={} datasourceId={} targetPath={} actualBytes={} durationMillis={}",
                    operationId, runtimeClusterId, datasourceId, normalizedPath,
                    bytes, elapsedMillis(startedAt));
            return result;
        } catch (RuntimeException exception) {
            recordAuditSafely(datasource, runtimeClusterId, userId, username,
                    "UPLOAD", null, normalizedPath, false, "FAILED", message(exception));
            logRouteFailure(operationId, "UPLOAD", datasource, runtimeClusterId,
                    null, normalizedPath, exception, startedAt);
            throw exception;
        }
    }

    public PreparedArchive prepareArchive(Long runtimeClusterId, Long datasourceId,
                                          List<String> paths) {
        String operationId = operationId();
        if (paths == null || paths.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "At least one archive path is required");
        }
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId);
        LinkedHashSet<String> normalizedPaths = new LinkedHashSet<String>();
        List<FileTransferFileEntryView> entries = new ArrayList<FileTransferFileEntryView>();
        for (String path : paths) {
            String normalizedPath = normalizePath(path);
            if (!normalizedPaths.add(normalizedPath)) {
                continue;
            }
            assertPermissionLogged(operationId, datasource, normalizedPath,
                    UnstructuredAclPermission.DOWNLOAD, "DOWNLOAD_ARCHIVE");
            entries.add(runtimeRouter.stat(datasource, runtimeClusterId, normalizedPath, operationId));
        }
        String fileName = "download.zip";
        if (normalizedPaths.size() == 1 && !entries.isEmpty()
                && Boolean.TRUE.equals(entries.get(0).getDirectory())) {
            String name = entries.get(0).getName();
            fileName = (name == null || name.isBlank() ? "download" : name) + ".zip";
        }
        return new PreparedArchive(datasource, runtimeClusterId,
                new ArrayList<String>(normalizedPaths), fileName,
                securityService.currentUserId(), securityService.currentUsername());
    }

    public PreparedNativeDownload prepareNativeDownload(Long runtimeClusterId, Long datasourceId,
                                                        List<String> paths) {
        String operationId = operationId();
        if (paths == null || paths.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "At least one download path is required");
        }
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId);
        LinkedHashSet<String> normalizedPaths = new LinkedHashSet<String>();
        List<FileTransferFileEntryView> entries = new ArrayList<FileTransferFileEntryView>();
        for (String path : paths) {
            String normalizedPath = normalizePath(path);
            if (!normalizedPaths.add(normalizedPath)) {
                continue;
            }
            assertPermissionLogged(operationId, datasource, normalizedPath,
                    UnstructuredAclPermission.DOWNLOAD, "DOWNLOAD");
            entries.add(runtimeRouter.stat(datasource, runtimeClusterId, normalizedPath, operationId));
        }
        if (normalizedPaths.size() == 1 && !entries.isEmpty()
                && !Boolean.TRUE.equals(entries.get(0).getDirectory())) {
            String normalizedPath = normalizedPaths.iterator().next();
            FileTransferFileEntryView entry = entries.get(0);
            String fileName = entry.getName() == null || entry.getName().isBlank()
                    ? "download" : entry.getName();
            PreparedDownload download = new PreparedDownload(
                    datasource, runtimeClusterId, normalizedPath, entry);
            return new PreparedNativeDownload(false, download, null, fileName,
                    entry.getSize(), List.of(normalizedPath));
        }

        String fileName = "download.zip";
        if (normalizedPaths.size() == 1 && !entries.isEmpty()) {
            String name = entries.get(0).getName();
            fileName = (name == null || name.isBlank() ? "download" : name) + ".zip";
        }
        List<String> preparedPaths = new ArrayList<String>(normalizedPaths);
        PreparedArchive archive = new PreparedArchive(datasource, runtimeClusterId,
                preparedPaths, fileName, securityService.currentUserId(),
                securityService.currentUsername());
        return new PreparedNativeDownload(true, null, archive, fileName,
                null, preparedPaths);
    }

    public void downloadArchive(PreparedArchive prepared, OutputStream output) {
        if (prepared == null || prepared.datasource() == null
                || prepared.runtimeClusterId() == null || prepared.paths() == null
                || prepared.paths().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Prepared archive context is incomplete");
        }
        String operationId = operationId();
        long startedAt = System.nanoTime();
        log.info("[UF_DOWNLOAD_START] Server 开始路由非结构化归档下载 operationId={} "
                        + "runtimeClusterId={} datasourceId={} datasourceType={} selectionCount={}",
                operationId, prepared.runtimeClusterId(), prepared.datasource().getId(),
                prepared.datasource().getTypeCode(), prepared.paths().size());
        try {
            runtimeRouter.downloadArchive(prepared.datasource(), prepared.runtimeClusterId(),
                    prepared.paths(), output, operationId);
            recordAuditSafely(prepared.datasource(), prepared.runtimeClusterId(),
                    prepared.userId(), prepared.username(), "DOWNLOAD_ARCHIVE",
                    String.join("\n", prepared.paths()), null, true,
                    "SUCCESS", prepared.paths().size() + " selected paths");
            log.info("[UF_DOWNLOAD_COMPLETED] Server 非结构化归档下载完成 operationId={} "
                            + "runtimeClusterId={} datasourceId={} selectionCount={} durationMillis={}",
                    operationId, prepared.runtimeClusterId(), prepared.datasource().getId(),
                    prepared.paths().size(), elapsedMillis(startedAt));
        } catch (RuntimeException exception) {
            recordAuditSafely(prepared.datasource(), prepared.runtimeClusterId(),
                    prepared.userId(), prepared.username(), "DOWNLOAD_ARCHIVE",
                    String.join("\n", prepared.paths()), null, true,
                    "FAILED", message(exception));
            logRouteFailure(operationId, "DOWNLOAD_ARCHIVE", prepared.datasource(),
                    prepared.runtimeClusterId(), null, null, exception, startedAt);
            throw exception;
        }
    }

    public String assertPermission(Long runtimeClusterId, Long datasourceId, String path,
                                   UnstructuredAclPermission permission) {
        String operationId = operationId();
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId);
        String normalizedPath = normalizePath(path);
        assertPermissionLogged(operationId, datasource, normalizedPath, permission, "TRANSFER");
        return normalizedPath;
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public UnstructuredOperationResultView operate(UnstructuredOperationRequest request) {
        String operationId = operationId();
        long startedAt = System.nanoTime();
        DataSourceDefinition datasource = requireDatasource(request.getDatasourceId(), request.getRuntimeClusterId());
        UnstructuredFileOperation operation = parseOperation(request.getOperation());
        String sourcePath = normalizePath(request.getSourcePath());
        String targetPath = request.getTargetPath() == null || request.getTargetPath().trim().isEmpty()
                ? null : normalizePath(request.getTargetPath());
        UnstructuredAclPermission permission = operation == UnstructuredFileOperation.DELETE
                ? UnstructuredAclPermission.DELETE : UnstructuredAclPermission.EDIT;
        assertPermissionLogged(operationId, datasource, sourcePath, permission, operation.name());
        if (targetPath != null) {
            assertPermissionLogged(operationId, datasource, parentPath(targetPath),
                    UnstructuredAclPermission.EDIT, operation.name());
        }
        log.info("[UF_OPERATION_START] Server 开始路由非结构化文件操作 operationId={} "
                        + "runtimeClusterId={} datasourceId={} datasourceType={} operation={} "
                        + "sourcePath={} targetPath={} recursiveConfirmed={}",
                operationId, request.getRuntimeClusterId(), datasource.getId(), datasource.getTypeCode(),
                operation.name(), sourcePath, targetPath, Boolean.TRUE.equals(request.getRecursiveConfirmed()));
        try {
            runtimeRouter.operate(datasource, request.getRuntimeClusterId(), operation.name(),
                    sourcePath, targetPath, request.getRecursiveConfirmed(), operationId);
            recordAuditSafely(datasource, request, operation.name(), sourcePath, targetPath, "SUCCESS", "");
            UnstructuredOperationResultView result = new UnstructuredOperationResultView();
            result.setOperation(operation.name());
            result.setSourcePath(sourcePath);
            result.setTargetPath(targetPath);
            result.setRecursive(Boolean.TRUE.equals(request.getRecursiveConfirmed()));
            result.setMessage("Operation completed");
            log.info("[UF_OPERATION_COMPLETED] Server 非结构化文件操作完成 operationId={} "
                            + "runtimeClusterId={} datasourceId={} operation={} sourcePath={} "
                            + "targetPath={} durationMillis={}",
                    operationId, request.getRuntimeClusterId(), datasource.getId(), operation.name(),
                    sourcePath, targetPath, elapsedMillis(startedAt));
            return result;
        } catch (RuntimeException exception) {
            recordAuditSafely(datasource, request, operation.name(), sourcePath, targetPath,
                    "FAILED", message(exception));
            logRouteFailure(operationId, operation.name(), datasource, request.getRuntimeClusterId(),
                    sourcePath, targetPath, exception, startedAt);
            throw exception;
        }
    }

    public PreparedDownload prepareDownload(Long runtimeClusterId, Long datasourceId, String path) {
        String operationId = operationId();
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId);
        String normalizedPath = normalizePath(path);
        assertPermissionLogged(operationId, datasource, normalizedPath,
                UnstructuredAclPermission.DOWNLOAD, "DOWNLOAD");
        FileTransferFileEntryView entry = runtimeRouter.stat(
                datasource, runtimeClusterId, normalizedPath, operationId);
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
        String operationId = operationId();
        long startedAt = System.nanoTime();
        log.info("[UF_DOWNLOAD_START] Server 开始路由非结构化文件下载 operationId={} "
                        + "runtimeClusterId={} datasourceId={} datasourceType={} path={} selectionCount=1",
                operationId, prepared.runtimeClusterId(), prepared.datasource().getId(),
                prepared.datasource().getTypeCode(), prepared.path());
        try {
            runtimeRouter.download(prepared.datasource(), prepared.runtimeClusterId(),
                    prepared.path(), output, operationId);
            log.info("[UF_DOWNLOAD_COMPLETED] Server 非结构化文件下载完成 operationId={} "
                            + "runtimeClusterId={} datasourceId={} path={} outputBytes={} durationMillis={}",
                    operationId, prepared.runtimeClusterId(), prepared.datasource().getId(), prepared.path(),
                    prepared.entry() == null ? null : prepared.entry().getSize(), elapsedMillis(startedAt));
        } catch (RuntimeException exception) {
            logRouteFailure(operationId, "DOWNLOAD", prepared.datasource(), prepared.runtimeClusterId(),
                    prepared.path(), null, exception, startedAt);
            throw exception;
        }
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

    private void assertPermissionLogged(String operationId, DataSourceDefinition datasource,
                                        String path, UnstructuredAclPermission permission,
                                        String operation) {
        if (hasPermission(datasource, path, permission)) {
            return;
        }
        log.warn("[UF_ACL_DENIED] 非结构化文件操作权限不足 operationId={} userId={} username={} "
                        + "datasourceId={} datasourceType={} operation={} requiredPermission={} path={}",
                operationId, securityService.currentUserId(), securityService.currentUsername(),
                datasource == null ? null : datasource.getId(),
                datasource == null ? null : datasource.getTypeCode(), operation, permission, path);
        throw new StudioException(StudioErrorCode.FORBIDDEN,
                "No " + permission.name() + " permission for path " + path);
    }

    private void logRouteFailure(String operationId, String operation,
                                 DataSourceDefinition datasource, Long runtimeClusterId,
                                 String sourcePath, String targetPath,
                                 RuntimeException exception, long startedAt) {
        String message = sanitizedErrorMessage(exception.getMessage());
        if (expectedRejection(exception)) {
            log.warn("[UF_OPERATION_REJECTED] Server 非结构化文件操作被拒绝 operationId={} "
                            + "runtimeClusterId={} datasourceId={} datasourceType={} operation={} "
                            + "sourcePath={} targetPath={} exceptionType={} message={} durationMillis={}",
                    operationId, runtimeClusterId, datasource == null ? null : datasource.getId(),
                    datasource == null ? null : datasource.getTypeCode(), operation,
                    sourcePath, targetPath, exception.getClass().getName(), message,
                    elapsedMillis(startedAt));
            return;
        }
        log.error("[UF_ROUTE_FAILED] Server 路由非结构化文件操作失败 operationId={} "
                        + "runtimeClusterId={} datasourceId={} datasourceType={} operation={} "
                        + "sourcePath={} targetPath={} exceptionType={} message={} durationMillis={} "
                        + "stackTrace={}",
                operationId, runtimeClusterId, datasource == null ? null : datasource.getId(),
                datasource == null ? null : datasource.getTypeCode(), operation,
                sourcePath, targetPath, exception.getClass().getName(), message,
                elapsedMillis(startedAt), sanitizedStackTrace(exception));
    }

    static String sanitizedStackTrace(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        String sanitized = StudioSensitiveLogSanitizer.sanitize(buffer.toString());
        if (sanitized == null || sanitized.length() <= MAX_SANITIZED_STACK_TRACE_LENGTH) {
            return sanitized;
        }
        int prefixLength = Math.max(0,
                MAX_SANITIZED_STACK_TRACE_LENGTH - STACK_TRACE_TRUNCATED_SUFFIX.length());
        return sanitized.substring(0, prefixLength) + STACK_TRACE_TRUNCATED_SUFFIX;
    }

    static String sanitizedErrorMessage(String message) {
        return StudioSensitiveLogSanitizer.sanitizeSingleLine(
                message, MAX_SANITIZED_ERROR_MESSAGE_LENGTH);
    }

    private boolean expectedRejection(RuntimeException exception) {
        if (exception instanceof StudioException studioException) {
            return Set.of(StudioErrorCode.BAD_REQUEST, StudioErrorCode.NOT_FOUND,
                            StudioErrorCode.CONFLICT, StudioErrorCode.FORBIDDEN,
                            StudioErrorCode.BUSINESS_ERROR)
                    .contains(studioException.getCode());
        }
        return exception instanceof IllegalArgumentException;
    }

    private String operationId() {
        return UUID.randomUUID().toString();
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
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
        List<UnstructuredSourceAclEntity> sourceRules = new ArrayList<>(
                sourceAclMapper.selectList(sourceAclQuery(datasource, permission.name())));
        sourceRules.sort(Comparator
                .comparingInt((UnstructuredSourceAclEntity rule) ->
                        UnstructuredAclPrincipalType.USER.name().equalsIgnoreCase(rule.getPrincipalType()) ? 0 : 1)
                .thenComparingInt(rule ->
                        UnstructuredAclEffect.DENY.name().equalsIgnoreCase(rule.getEffect()) ? 0 : 1));
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
        audit.setRecursive(Boolean.TRUE.equals(request.getRecursiveConfirmed()) ? 1 : 0); audit.setStatus(status);
        audit.setMessage(auditMessage(message));
        auditMapper.insert(audit);
    }

    private void recordAudit(DataSourceDefinition datasource, Long runtimeClusterId,
                             Long userId, String username, String operation,
                             String sourcePath, String targetPath, boolean recursive,
                             String status, String message) {
        UnstructuredOpAuditEntity audit = new UnstructuredOpAuditEntity();
        audit.setTenantId(datasource.getTenantId());
        audit.setProjectId(datasource.getProjectId());
        audit.setDatasourceId(datasource.getId());
        audit.setRuntimeClusterId(runtimeClusterId);
        audit.setUserId(userId);
        audit.setUsername(username);
        audit.setOperation(operation);
        audit.setSourcePath(sourcePath);
        audit.setTargetPath(targetPath);
        audit.setRecursive(recursive ? 1 : 0);
        audit.setStatus(status);
        audit.setMessage(auditMessage(message));
        auditMapper.insert(audit);
    }

    /**
     * Auditing must never replace the result of the remote file operation. A
     * schema/connection problem in the audit database is logged and allowed to
     * surface through operations/audit health checks instead.
     */
    private void recordAuditSafely(DataSourceDefinition datasource, UnstructuredOperationRequest request,
                                   String operation, String sourcePath, String targetPath,
                                   String status, String message) {
        try {
            recordAudit(datasource, request, operation, sourcePath, targetPath, status, message);
        } catch (RuntimeException auditException) {
            log.warn("[UF_AUDIT_FAILED] 非结构化操作审计写入失败 operation={} datasourceId={} "
                            + "status={} exceptionType={} message={} stackTrace={}",
                    operation, datasource == null ? null : datasource.getId(), status,
                    auditException.getClass().getName(),
                    sanitizedErrorMessage(auditException.getMessage()),
                    sanitizedStackTrace(auditException));
        }
    }

    private void recordAuditSafely(DataSourceDefinition datasource, Long runtimeClusterId,
                                   Long userId, String username, String operation,
                                   String sourcePath, String targetPath, boolean recursive,
                                   String status, String message) {
        try {
            recordAudit(datasource, runtimeClusterId, userId, username, operation,
                    sourcePath, targetPath, recursive, status, message);
        } catch (RuntimeException auditException) {
            log.warn("[UF_AUDIT_FAILED] 非结构化操作审计写入失败 operation={} datasourceId={} "
                            + "status={} exceptionType={} message={} stackTrace={}",
                    operation, datasource == null ? null : datasource.getId(), status,
                    auditException.getClass().getName(),
                    sanitizedErrorMessage(auditException.getMessage()),
                    sanitizedStackTrace(auditException));
        }
    }

    private String auditMessage(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.trim();
        if (normalized.length() <= MAX_AUDIT_MESSAGE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_AUDIT_MESSAGE_LENGTH - AUDIT_MESSAGE_TRUNCATED_SUFFIX.length())
                + AUDIT_MESSAGE_TRUNCATED_SUFFIX;
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

    public record PreparedArchive(DataSourceDefinition datasource,
                                  Long runtimeClusterId,
                                  List<String> paths,
                                  String fileName,
                                  Long userId,
                                  String username) {
    }

    public record PreparedNativeDownload(boolean archive,
                                         PreparedDownload download,
                                         PreparedArchive archiveDownload,
                                         String fileName,
                                         Long contentLength,
                                         List<String> paths) {
    }
}
