package com.jdragon.studio.worker.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.enums.RuntimeDatasourceProbeMode;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferBrowserPageView;
import com.jdragon.studio.dto.model.FileTransferFileEntryView;
import com.jdragon.studio.dto.model.FileTransferSelectionPreviewView;
import com.jdragon.studio.dto.model.RuntimeDatasourceHydrationResultView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.UnstructuredUploadResultView;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryOptionResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.dto.model.request.RuntimeDatasourceArchiveRequest;
import com.jdragon.studio.dto.model.request.RuntimeDatasourceProbeRequest;
import com.jdragon.studio.dto.model.request.RuntimeDatasourceUploadRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DatasourceClusterBindingEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeExecutor;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.worker.filetransfer.FileTransferPreviewExecutor;
import com.jdragon.studio.worker.unstructured.UnstructuredFileExecutor;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.net.URLEncoder;

/** Internal-only datasource execution with explicit cluster, tenant, project and binding checks. */
@RestController
@RequestMapping("/internal/runtime/datasource")
public class InternalDatasourceProbeController {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 2 * 1024;
    private static final String UNKNOWN_ERROR_MESSAGE = "Runtime datasource operation failed";

    private final RuntimeDatasourceProbeExecutor executor;
    private final StudioPlatformProperties properties;
    private final RuntimeClusterMapper runtimeClusterMapper;
    private final WorkerAuthorizationService workerAuthorizationService;
    private final DatasourceClusterBindingMapper datasourceClusterBindingMapper;
    private final DataSourceService dataSourceService;
    private final ObjectMapper objectMapper;
    private FileTransferPreviewExecutor fileTransferPreviewExecutor;
    private UnstructuredFileExecutor unstructuredFileExecutor;

    public InternalDatasourceProbeController(RuntimeDatasourceProbeExecutor executor,
                                             StudioPlatformProperties properties,
                                             RuntimeClusterMapper runtimeClusterMapper,
                                             WorkerAuthorizationService workerAuthorizationService,
                                             DatasourceClusterBindingMapper datasourceClusterBindingMapper,
                                             DataSourceService dataSourceService,
                                             ObjectMapper objectMapper) {
        this.executor = executor;
        this.properties = properties;
        this.runtimeClusterMapper = runtimeClusterMapper;
        this.workerAuthorizationService = workerAuthorizationService;
        this.datasourceClusterBindingMapper = datasourceClusterBindingMapper;
        this.dataSourceService = dataSourceService;
        this.objectMapper = objectMapper;
    }

    @Autowired
    void setFileTransferPreviewExecutor(FileTransferPreviewExecutor fileTransferPreviewExecutor) {
        this.fileTransferPreviewExecutor = fileTransferPreviewExecutor;
    }

    @Autowired
    void setUnstructuredFileExecutor(UnstructuredFileExecutor unstructuredFileExecutor) {
        this.unstructuredFileExecutor = unstructuredFileExecutor;
    }

    @PostMapping("/probe")
    public Result<ConnectionTestResult> probe(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, true, datasource -> executor.test(datasource));
    }

    @PostMapping("/discover")
    public Result<ModelDiscoveryResult> discover(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false, datasource -> executor.discover(
                datasource, request.getKeyword(), request.getPageNo(), request.getPageSize()));
    }

    @PostMapping("/discover-options")
    public Result<ModelDiscoveryOptionResult> discoverOptions(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false, datasource -> executor.discoverOptions(
                datasource, request.getKeyword(), request.getPageNo(), request.getPageSize()));
    }

    @PostMapping("/hydrate")
    public Result<RuntimeDatasourceHydrationResultView> hydrate(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false,
                datasource -> executor.hydrate(datasource, request.getPhysicalLocators()));
    }

    @PostMapping("/preview")
    public Result<List<Map<String, Object>>> preview(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false,
                datasource -> executor.preview(datasource, request.getModel(), request.getLimit()));
    }

    @PostMapping("/query")
    public Result<SqlExecutionResultView> query(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false, datasource -> executor.query(
                datasource, request.getSql(), request.getParameters(), request.getMaxRows()));
    }

    @PostMapping("/file-browser")
    public Result<FileTransferBrowserPageView> fileBrowser(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @RequestHeader(value = RuntimeInternalHeaders.OPERATION_ID_HEADER, required = false) String operationId,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false, datasource -> unstructuredFileExecutor.browse(
                datasource, request.getPath(), request.getCursor(), request.getPageSize()), operationId);
    }

    @PostMapping("/file-stat")
    public Result<FileTransferFileEntryView> fileStat(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @RequestHeader(value = RuntimeInternalHeaders.OPERATION_ID_HEADER, required = false) String operationId,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false, datasource -> {
            try {
                return unstructuredFileExecutor.stat(datasource, request.getPath());
            } catch (IllegalStateException exception) {
                if (hasCause(exception, java.nio.file.NoSuchFileException.class)) {
                    throw new StudioException(StudioErrorCode.NOT_FOUND,
                            "File path was not found", exception);
                }
                throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                        safeErrorMessage(exception), exception);
            }
        }, operationId);
    }

    @PostMapping("/file-operation")
    public Result<Void> fileOperation(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @RequestHeader(value = RuntimeInternalHeaders.OPERATION_ID_HEADER, required = false) String operationId,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        return execute(token, request, false, datasource -> {
            try {
                unstructuredFileExecutor.operate(datasource, request.getFileOperation(), request.getOperationPath(),
                        request.getOperationTargetPath(), request.getRecursiveConfirmed());
            } catch (IllegalStateException exception) {
                throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                        safeErrorMessage(exception), exception);
            }
            return null;
        }, operationId);
    }

    public Result<Void> fileOperation(String token, RuntimeDatasourceProbeRequest request) {
        return fileOperation(token, null, request);
    }

    @PostMapping("/file-download")
    public ResponseEntity<StreamingResponseBody> fileDownload(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @RequestHeader(value = RuntimeInternalHeaders.OPERATION_ID_HEADER, required = false) String operationId,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        StudioRequestContext previous = StudioRequestContextHolder.getContext();
        try (OperationMdcScope ignored = bindOperationId(operationId)) {
            DataSourceDefinition datasource = prepare(token, request, false);
            FileTransferFileEntryView entry = unstructuredFileExecutor.stat(datasource, request.getPath());
            if (Boolean.TRUE.equals(entry.getDirectory())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only files can be downloaded");
            }
            String name = entry.getName() == null ? "download" : entry.getName();
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            StreamingResponseBody body = output -> {
                try (OperationMdcScope streamScope = bindOperationId(operationId)) {
                    unstructuredFileExecutor.download(datasource, request.getPath(), output);
                }
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(entry.getSize() == null ? 0L : entry.getSize())
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encoded)
                    .header("X-Studio-Runtime-Response", "AUTHENTICATED")
                    .body(body);
        } finally {
            restoreContext(previous);
        }
    }

    @PostMapping(value = "/file-upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Result<UnstructuredUploadResultView>> fileUpload(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @RequestHeader(value = RuntimeInternalHeaders.OPERATION_ID_HEADER, required = false) String operationId,
            @RequestHeader(RuntimeInternalHeaders.RUNTIME_REQUEST_HEADER) String encodedRequest,
            HttpServletRequest servletRequest) throws java.io.IOException {
        StudioRequestContext previous = StudioRequestContextHolder.getContext();
        try (OperationMdcScope ignored = bindOperationId(operationId)) {
            RuntimeDatasourceUploadRequest request = decodeUploadRequest(encodedRequest);
            if (servletRequest.getContentLengthLong() != request.getContentLength()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Upload content length does not match the runtime request");
            }
            DataSourceDefinition datasource = prepare(token, request, false);
            long bytes = unstructuredFileExecutor.upload(datasource, request.getTargetPath(),
                    Boolean.TRUE.equals(request.getOverwrite()), request.getContentLength(),
                    servletRequest.getInputStream());
            UnstructuredUploadResultView result = new UnstructuredUploadResultView();
            result.setOperation("UPLOAD");
            result.setTargetPath(request.getTargetPath());
            result.setBytes(bytes);
            result.setOverwritten(Boolean.TRUE.equals(request.getOverwrite()));
            result.setMessage("Upload completed");
            return ResponseEntity.ok(Result.success(result));
        } catch (IllegalStateException exception) {
            if (hasCause(exception, FileAlreadyExistsException.class)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Result.error(StudioErrorCode.CONFLICT, safeErrorMessage(exception)));
            }
            return ResponseEntity.badRequest()
                    .body(Result.error(StudioErrorCode.BUSINESS_ERROR, safeErrorMessage(exception)));
        } finally {
            restoreContext(previous);
        }
    }

    public ResponseEntity<Result<UnstructuredUploadResultView>> fileUpload(
            String token, String encodedRequest, HttpServletRequest servletRequest) throws java.io.IOException {
        return fileUpload(token, null, encodedRequest, servletRequest);
    }

    @PostMapping("/file-archive")
    public ResponseEntity<StreamingResponseBody> fileArchive(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @RequestHeader(value = RuntimeInternalHeaders.OPERATION_ID_HEADER, required = false) String operationId,
            @Valid @RequestBody RuntimeDatasourceArchiveRequest request) {
        StudioRequestContext previous = StudioRequestContextHolder.getContext();
        try (OperationMdcScope ignored = bindOperationId(operationId)) {
            DataSourceDefinition datasource = prepare(token, request, false);
            StreamingResponseBody body = output -> {
                try (OperationMdcScope streamScope = bindOperationId(operationId)) {
                    unstructuredFileExecutor.downloadArchive(datasource, request.getPaths(), output);
                }
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''download.zip")
                    .body(body);
        } finally {
            restoreContext(previous);
        }
    }

    @PostMapping("/file-transfer-preview")
    public Result<FileTransferSelectionPreviewView> fileTransferPreview(
            @RequestHeader(value = "X-Studio-Internal-Token", required = false) String token,
            @Valid @RequestBody RuntimeDatasourceProbeRequest request) {
        if (fileTransferPreviewExecutor == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "File transfer preview executor is unavailable");
        }
        return execute(token, request, false, datasource -> fileTransferPreviewExecutor.preview(
                datasource, request.getFileTransferSpec(), request.getFileTransferParameters(),
                request.getFileTransferPreviewLimit()));
    }

    private <T> Result<T> execute(String token,
                                  RuntimeDatasourceProbeRequest request,
                                  boolean draftAllowed,
                                  Function<DataSourceDefinition, T> action) {
        return execute(token, request, draftAllowed, action, null);
    }

    private <T> Result<T> execute(String token,
                                  RuntimeDatasourceProbeRequest request,
                                  boolean draftAllowed,
                                  Function<DataSourceDefinition, T> action,
                                  String operationId) {
        StudioRequestContext previous = StudioRequestContextHolder.getContext();
        try (OperationMdcScope ignored = bindOperationId(operationId)) {
            DataSourceDefinition datasource = prepare(token, request, draftAllowed);
            return Result.success(action.apply(datasource));
        } catch (StudioException exception) {
            return Result.error(exception.getCode(), safeErrorMessage(exception));
        } finally {
            restoreContext(previous);
        }
    }

    private RuntimeDatasourceUploadRequest decodeUploadRequest(String encodedRequest) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(encodedRequest);
            RuntimeDatasourceUploadRequest request = objectMapper.readValue(
                    json, RuntimeDatasourceUploadRequest.class);
            if (request.getContentLength() == null || request.getContentLength() < 0L
                    || !StringUtils.hasText(request.getTargetPath())) {
                throw new IllegalArgumentException("Upload request is incomplete");
            }
            return request;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid runtime upload request", exception);
        }
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String safeErrorMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        String sanitized = StudioSensitiveLogSanitizer.sanitizeSingleLine(
                message, MAX_ERROR_MESSAGE_LENGTH);
        return sanitized == null || sanitized.isBlank() ? UNKNOWN_ERROR_MESSAGE : sanitized;
    }

    private DataSourceDefinition prepare(String token, RuntimeDatasourceProbeRequest request,
                                         boolean draftAllowed) {
        validateInternalToken(token);
        validateRuntimeIdentity(request);
        if (!workerAuthorizationService.isRuntimeClusterAuthorizedForProject(
                request.getTenantId(), request.getProjectId(), request.getTargetClusterId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Runtime cluster is not authorized for the requested project");
        }
        StudioRequestContext context = new StudioRequestContext();
        context.setTenantId(request.getTenantId());
        context.setProjectId(request.getProjectId());
        context.setUserId(request.getUserId());
        context.setUsername(request.getUsername());
        StudioRequestContextHolder.setContext(context);
        return resolveDatasource(request, draftAllowed);
    }

    private void validateInternalToken(String token) {
        String expected = properties.getInternalApiToken();
        if (!StringUtils.hasText(token) || !StringUtils.hasText(expected)
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Internal runtime authentication failed");
        }
    }

    private void validateRuntimeIdentity(RuntimeDatasourceProbeRequest request) {
        if (request == null || request.getTargetClusterId() == null
                || !StringUtils.hasText(request.getTargetClusterCode())
                || !StringUtils.hasText(request.getTenantId())
                || request.getProjectId() == null || request.getMode() == null
                || request.getDatasource() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Datasource runtime identity is incomplete");
        }
        RuntimeClusterEntity cluster = runtimeClusterMapper.selectById(request.getTargetClusterId());
        boolean matches = cluster != null
                && Integer.valueOf(1).equals(cluster.getEnabled())
                && request.getTenantId().equals(cluster.getTenantId())
                && request.getTargetClusterCode().equalsIgnoreCase(cluster.getCode())
                && StringUtils.hasText(properties.getRuntimeClusterCode())
                && properties.getRuntimeClusterCode().trim().equalsIgnoreCase(cluster.getCode());
        if (!matches) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Target runtime cluster identity does not match this worker");
        }
    }

    private DataSourceDefinition resolveDatasource(RuntimeDatasourceProbeRequest request,
                                                   boolean draftAllowed) {
        DataSourceDefinition supplied = request.getDatasource();
        if (!Objects.equals(request.getTenantId(), supplied.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Datasource belongs to another tenant");
        }
        if (RuntimeDatasourceProbeMode.DRAFT_FORM == request.getMode()) {
            return resolveDraftDatasource(request, supplied, draftAllowed);
        }
        if (supplied.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Stored datasource id is required for runtime execution");
        }
        DataSourceDefinition canonical = dataSourceService.getInternal(supplied.getId());
        if (canonical == null
                || !Objects.equals(canonical.getTenantId(), request.getTenantId())
                || !Objects.equals(canonical.getId(), supplied.getId())
                || !Objects.equals(canonical.getProjectId(), supplied.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Datasource is not available");
        }
        assertStoredBinding(canonical.getId(), request);
        return canonical;
    }

    private DataSourceDefinition resolveDraftDatasource(RuntimeDatasourceProbeRequest request,
                                                        DataSourceDefinition supplied,
                                                        boolean draftAllowed) {
        if (!draftAllowed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Draft datasource payloads are only accepted by the connection probe endpoint");
        }
        if (!Objects.equals(request.getProjectId(), supplied.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Datasource draft belongs to another project");
        }
        if (supplied.getApplicableClusterIds() == null
                || !supplied.getApplicableClusterIds().contains(request.getTargetClusterId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Datasource draft is not applicable to the requested runtime cluster");
        }
        if (supplied.getId() != null) {
            DataSourceDefinition canonical = dataSourceService.getInternal(supplied.getId());
            if (canonical == null
                    || !Objects.equals(canonical.getTenantId(), request.getTenantId())
                    || !Objects.equals(canonical.getProjectId(), request.getProjectId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Datasource is not available");
            }
        }
        return supplied;
    }

    private void assertStoredBinding(Long datasourceId, RuntimeDatasourceProbeRequest request) {
        Long count = datasourceClusterBindingMapper.selectCount(
                new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                        .eq(DatasourceClusterBindingEntity::getTenantId, request.getTenantId())
                        .eq(DatasourceClusterBindingEntity::getDatasourceId, datasourceId)
                        .eq(DatasourceClusterBindingEntity::getRuntimeClusterId, request.getTargetClusterId())
                        .eq(DatasourceClusterBindingEntity::getEnabled, 1));
        if (count == null || count.longValue() == 0L) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Datasource is not applicable to the requested runtime cluster");
        }
    }

    private void restoreContext(StudioRequestContext previous) {
        if (previous == null) {
            StudioRequestContextHolder.clear();
        } else {
            StudioRequestContextHolder.setContext(previous);
        }
    }

    private OperationMdcScope bindOperationId(String operationId) {
        return new OperationMdcScope(operationId);
    }

    private static final class OperationMdcScope implements AutoCloseable {
        private final Map<String, String> previous;

        private OperationMdcScope(String operationId) {
            this.previous = MDC.getCopyOfContextMap();
            String safeOperationId = safeOperationId(operationId);
            if (safeOperationId != null) {
                MDC.put("operationId", safeOperationId);
            } else {
                MDC.remove("operationId");
            }
        }

        private static String safeOperationId(String operationId) {
            return RuntimeInternalHeaders.normalizeOperationId(operationId);
        }

        @Override
        public void close() {
            if (previous == null || previous.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }
}
