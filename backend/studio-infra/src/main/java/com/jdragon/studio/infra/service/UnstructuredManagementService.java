package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
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
import com.jdragon.studio.dto.model.request.UnstructuredOperationRequest;
import com.jdragon.studio.dto.model.request.UnstructuredPathAclRequest;
import com.jdragon.studio.dto.model.request.UnstructuredSourceAclRequest;
import com.jdragon.studio.infra.entity.UnstructuredPathAclEntity;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UnstructuredOpAuditMapper;
import com.jdragon.studio.infra.mapper.UnstructuredPathAclMapper;
import com.jdragon.studio.infra.mapper.UnstructuredSourceAclMapper;
import com.jdragon.studio.infra.service.unstructured.UnstructuredAclService;
import com.jdragon.studio.infra.service.unstructured.UnstructuredAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.io.InputStream;
import java.io.OutputStream;

@Service
public class UnstructuredManagementService {
    private static final Logger log = LoggerFactory.getLogger(UnstructuredManagementService.class);

    private final DataSourceService dataSourceService;
    private final RuntimeClusterSelectionService runtimeClusterSelectionService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final StudioSecurityService securityService;
    private final RuntimeDatasourceProbeRouter runtimeRouter;
    private final DatasourceTypeCapabilityService capabilityService;
    private final UnstructuredAclService aclService;
    private final UnstructuredAuditService auditService;

    @Autowired
    public UnstructuredManagementService(DataSourceService dataSourceService,
                                         RuntimeClusterSelectionService runtimeClusterSelectionService,
                                         ProjectResourceAccessService projectResourceAccessService,
                                         StudioSecurityService securityService,
                                         RuntimeDatasourceProbeRouter runtimeRouter,
                                         DatasourceTypeCapabilityService capabilityService,
                                         UnstructuredAclService aclService,
                                         UnstructuredAuditService auditService) {
        this.dataSourceService = dataSourceService;
        this.runtimeClusterSelectionService = runtimeClusterSelectionService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.securityService = securityService;
        this.runtimeRouter = runtimeRouter;
        this.capabilityService = capabilityService;
        this.aclService = aclService;
        this.auditService = auditService;
    }

    /**
     * Compatibility constructor retained for focused unit tests and external embedders.
     */
    public UnstructuredManagementService(DataSourceService dataSourceService,
                                         RuntimeClusterSelectionService runtimeClusterSelectionService,
                                         ProjectResourceAccessService projectResourceAccessService,
                                         StudioSecurityService securityService,
                                         RuntimeDatasourceProbeRouter runtimeRouter,
                                         UnstructuredSourceAclMapper sourceAclMapper,
                                         UnstructuredPathAclMapper pathAclMapper,
                                         UnstructuredOpAuditMapper auditMapper,
                                         ProjectMemberMapper projectMemberMapper,
                                         StudioUserMapper userMapper,
                                         DatasourceTypeCapabilityService capabilityService) {
        this.dataSourceService = dataSourceService;
        this.runtimeClusterSelectionService = runtimeClusterSelectionService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.securityService = securityService;
        this.runtimeRouter = runtimeRouter;
        this.capabilityService = capabilityService;
        this.aclService = new UnstructuredAclService(dataSourceService, projectResourceAccessService,
                securityService, sourceAclMapper, pathAclMapper, projectMemberMapper, userMapper,
                capabilityService);
        this.auditService = new UnstructuredAuditService(auditMapper, securityService);
    }

    public List<UnstructuredSourceView> sources(Long runtimeClusterId) {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        runtimeClusterSelectionService.resolveForSave(projectId, runtimeClusterId);
        Set<String> sourceTypes = capabilityService.typesWithRuntimeCapability("browse", true);
        List<DataSourceOptionView> options = dataSourceService.listBasicOptionsByTypes(
                sourceTypes, runtimeClusterId);
        List<UnstructuredSourceView> result = new ArrayList<UnstructuredSourceView>();
        for (DataSourceOptionView option : options) {
            DataSourceDefinition datasource = dataSourceService.get(option.getId());
            if (datasource == null
                    || !capabilityService.hasRuntimeCapability(datasource.getTypeCode(), "browse")) {
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
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId, "browse");
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
        DataSourceDefinition datasource = requireDatasource(
                datasourceId, runtimeClusterId, runtimeCapability(permission));
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
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId, "write");
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
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId, "write");
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
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId, "read");
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
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId, "read");
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
        DataSourceDefinition datasource = requireDatasource(
                datasourceId, runtimeClusterId, runtimeCapability(permission));
        String normalizedPath = normalizePath(path);
        assertPermissionLogged(operationId, datasource, normalizedPath, permission, "TRANSFER");
        return normalizedPath;
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public UnstructuredOperationResultView operate(UnstructuredOperationRequest request) {
        String operationId = operationId();
        long startedAt = System.nanoTime();
        DataSourceDefinition datasource = requireDatasource(
                request.getDatasourceId(), request.getRuntimeClusterId(), "manage");
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
        DataSourceDefinition datasource = requireDatasource(datasourceId, runtimeClusterId, "read");
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
        return aclService.sourceAcl(datasourceId);
    }

    @Transactional
    public List<UnstructuredAclEntryView> replaceSourceAcl(Long datasourceId, UnstructuredSourceAclRequest request) {
        return aclService.replaceSourceAcl(datasourceId, request);
    }

    public List<UnstructuredAclEntryView> pathAcl(Long datasourceId, String path) {
        return aclService.pathAcl(datasourceId, path);
    }

    @Transactional
    public List<UnstructuredAclEntryView> replacePathAcl(Long datasourceId, UnstructuredPathAclRequest request) {
        return aclService.replacePathAcl(datasourceId, request);
    }

    @Transactional
    public void deleteAcl(Long id) {
        aclService.deleteAcl(id);
    }

    public List<StudioUserOptionView> userOptions() {
        return aclService.userOptions();
    }

    public UnstructuredPermissionView permissions(Long datasourceId, String path) {
        return aclService.permissions(datasourceId, path);
    }

    private DataSourceDefinition requireDatasource(Long datasourceId, Long runtimeClusterId,
                                                   String runtimeCapability) {
        DataSourceDefinition datasource = requireDatasourceWithoutCluster(
                datasourceId, runtimeCapability);
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        runtimeClusterSelectionService.validateExplicitDatasourceSelection(projectId, runtimeClusterId,
                List.of(datasourceId));
        return datasource;
    }

    private DataSourceDefinition requireDatasourceWithoutCluster(Long datasourceId) {
        return requireDatasourceWithoutCluster(datasourceId, "browse");
    }

    private DataSourceDefinition requireDatasourceWithoutCluster(Long datasourceId,
                                                                  String runtimeCapability) {
        DataSourceDefinition datasource = dataSourceService.requireRunnableForExecution(datasourceId);
        if (capabilityService == null) {
            throw new IllegalStateException("Datasource runtime capability service is required");
        }
        capabilityService.ensureRuntimeCapability(datasource.getTypeCode(), runtimeCapability);
        return datasource;
    }

    private String runtimeCapability(UnstructuredAclPermission permission) {
        if (permission == UnstructuredAclPermission.DOWNLOAD) {
            return "read";
        }
        if (permission == UnstructuredAclPermission.EDIT) {
            return "write";
        }
        if (permission == UnstructuredAclPermission.DELETE) {
            return "manage";
        }
        return "browse";
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
        return UnstructuredAuditService.sanitizedStackTrace(throwable);
    }

    static String sanitizedErrorMessage(String message) {
        return UnstructuredAuditService.sanitizedErrorMessage(message);
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
        return aclService.hasPermission(datasource, path, permission);
    }

    boolean matchesPath(UnstructuredPathAclEntity rule, String candidate) {
        return aclService.matchesPath(rule, candidate);
    }

    private boolean isOwnerOrAdmin(DataSourceDefinition datasource) {
        return aclService.isOwnerOrAdmin(datasource);
    }

    private void recordAuditSafely(DataSourceDefinition datasource, UnstructuredOperationRequest request,
                                   String operation, String sourcePath, String targetPath,
                                   String status, String message) {
        auditService.recordSafely(datasource, request, operation, sourcePath, targetPath,
                status, message);
    }

    private void recordAuditSafely(DataSourceDefinition datasource, Long runtimeClusterId,
                                   Long userId, String username, String operation,
                                   String sourcePath, String targetPath, boolean recursive,
                                   String status, String message) {
        auditService.recordSafely(datasource, runtimeClusterId, userId, username, operation,
                sourcePath, targetPath, recursive, status, message);
    }

    private String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private UnstructuredFileOperation parseOperation(String value) {
        try { return UnstructuredFileOperation.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported file operation"); }
    }

    private String normalizePath(String rawPath) {
        return UnstructuredAclService.normalizePath(rawPath);
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
