package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.enums.RuntimeDatasourceProbeMode;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferBrowserPageView;
import com.jdragon.studio.dto.model.FileTransferFileEntryView;
import com.jdragon.studio.dto.model.FileTransferSelectionPreviewView;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.RuntimeDatasourceHydrationResultView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryOptionResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.dto.model.request.RuntimeDatasourceProbeRequest;
import com.jdragon.studio.dto.model.request.RuntimeDatasourceArchiveRequest;
import com.jdragon.studio.dto.model.request.RuntimeDatasourceUploadRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeEndpointEntity;
import com.jdragon.studio.infra.entity.DatasourceClusterBindingEntity;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RuntimeEndpointMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.OutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Base64;

/** Routes datasource probes to the selected runtime. No endpoint means UNKNOWN, never UNAVAILABLE. */
@Service
public class RuntimeDatasourceProbeRouter {
    private static final String INTERNAL_TOKEN_HEADER = "X-Studio-Internal-Token";
    private static final String TARGET_CLUSTER_ID_HEADER = "X-Studio-Target-Cluster-Id";
    private final RuntimeClusterMapper clusterMapper;
    private final RuntimeEndpointMapper endpointMapper;
    private final EncryptionService encryption;
    private final ObjectMapper objectMapper;
    private final StudioPlatformProperties properties;
    private final RuntimeClusterService runtimeClusterService;
    private final RuntimeEndpointSecurityService runtimeEndpointSecurityService;
    private final RuntimeEndpointHeaderPolicy runtimeEndpointHeaderPolicy;
    private final RuntimeEndpointHttpClient runtimeEndpointHttpClient;
    private WorkerAuthorizationService workerAuthorizationService;
    private DatasourceClusterBindingMapper datasourceClusterBindingMapper;

    public RuntimeDatasourceProbeRouter(RuntimeClusterMapper clusterMapper, RuntimeEndpointMapper endpointMapper,
                                        EncryptionService encryption, ObjectMapper objectMapper,
                                        StudioPlatformProperties properties,
                                        RuntimeClusterService runtimeClusterService,
                                        RuntimeEndpointSecurityService runtimeEndpointSecurityService,
                                        RuntimeEndpointHeaderPolicy runtimeEndpointHeaderPolicy,
                                        RuntimeEndpointHttpClient runtimeEndpointHttpClient) {
        this.clusterMapper = clusterMapper; this.endpointMapper = endpointMapper; this.encryption = encryption;
        this.objectMapper = objectMapper; this.properties = properties;
        this.runtimeClusterService = runtimeClusterService;
        this.runtimeEndpointSecurityService = runtimeEndpointSecurityService;
        this.runtimeEndpointHeaderPolicy = runtimeEndpointHeaderPolicy;
        this.runtimeEndpointHttpClient = runtimeEndpointHttpClient;
    }

    @Autowired
    void setRuntimeIdentityServices(WorkerAuthorizationService workerAuthorizationService,
                                    DatasourceClusterBindingMapper datasourceClusterBindingMapper) {
        this.workerAuthorizationService = workerAuthorizationService;
        this.datasourceClusterBindingMapper = datasourceClusterBindingMapper;
    }

    public ConnectionTestResult test(DataSourceDefinition datasource, Long clusterId) {
        return test(datasource, clusterId, RuntimeDatasourceProbeMode.STORED);
    }

    public ConnectionTestResult test(DataSourceDefinition datasource, Long clusterId,
                                     RuntimeDatasourceProbeMode mode) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) return unknown("Target runtime cluster has no online endpoint");
        RuntimeDatasourceProbeRequest payload = payload(cluster, datasource, mode);
        try { return post(endpoint, payload, "/internal/runtime/datasource/probe", ConnectionTestResult.class); }
        catch (RemoteRuntimeException ex) { throw ex; }
        catch (Exception ex) { return unknown("Target runtime cluster probe is unavailable"); }
    }
    public ModelDiscoveryResult discover(DataSourceDefinition datasource, Long clusterId, String keyword, Integer pageNo, Integer pageSize) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceProbeRequest payload = payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED);
        payload.setKeyword(keyword); payload.setPageNo(pageNo); payload.setPageSize(pageSize);
        try { return post(endpoint, payload, "/internal/runtime/datasource/discover", ModelDiscoveryResult.class); }
        catch (RemoteRuntimeException ex) { throw ex; }
        catch (Exception ex) { throw unavailable(); }
    }
    public ModelDiscoveryOptionResult discoverOptions(DataSourceDefinition datasource, Long clusterId, String keyword, Integer pageNo, Integer pageSize) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceProbeRequest payload = payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED);
        payload.setKeyword(keyword); payload.setPageNo(pageNo); payload.setPageSize(pageSize);
        try { return post(endpoint, payload, "/internal/runtime/datasource/discover-options", ModelDiscoveryOptionResult.class); }
        catch (RemoteRuntimeException ex) { throw ex; }
        catch (Exception ex) { throw unavailable(); }
    }

    public RuntimeDatasourceHydrationResultView hydrate(DataSourceDefinition datasource, Long clusterId,
                                                        List<String> physicalLocators) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceProbeRequest payload = payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED);
        payload.setPhysicalLocators(physicalLocators);
        try {
            return objectMapper.convertValue(postPayload(endpoint, payload,
                    "/internal/runtime/datasource/hydrate"), RuntimeDatasourceHydrationResultView.class);
        } catch (RemoteRuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public List<Map<String, Object>> preview(DataSourceDefinition datasource, Long clusterId,
                                             DataModelDefinition model, Integer limit) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceProbeRequest payload = payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED);
        payload.setModel(model);
        payload.setLimit(limit);
        try {
            return objectMapper.convertValue(postPayload(endpoint, payload,
                    "/internal/runtime/datasource/preview"),
                    new TypeReference<List<Map<String, Object>>>() { });
        } catch (RemoteRuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public SqlExecutionResultView query(DataSourceDefinition datasource,
                                        Long clusterId,
                                        String sql,
                                        List<Object> parameters,
                                        Integer maxRows) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceProbeRequest payload = payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED);
        payload.setSql(sql);
        payload.setParameters(parameters);
        payload.setMaxRows(maxRows);
        try {
            return objectMapper.convertValue(postPayload(endpoint, payload,
                    "/internal/runtime/datasource/query"), SqlExecutionResultView.class);
        } catch (RemoteRuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public FileTransferBrowserPageView browse(DataSourceDefinition datasource, Long clusterId,
                                              String path, String cursor, Integer pageSize) {
        return browse(datasource, clusterId, path, cursor, pageSize, null);
    }

    public FileTransferBrowserPageView browse(DataSourceDefinition datasource, Long clusterId,
                                              String path, String cursor, Integer pageSize,
                                              String operationId) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceProbeRequest payload = payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED);
        payload.setPath(path);
        payload.setCursor(cursor);
        payload.setPageSize(pageSize);
        try {
            return post(endpoint, payload, "/internal/runtime/datasource/file-browser",
                    FileTransferBrowserPageView.class, operationId);
        } catch (RemoteRuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public FileTransferFileEntryView stat(DataSourceDefinition datasource, Long clusterId, String path) {
        return stat(datasource, clusterId, path, null);
    }

    public FileTransferFileEntryView stat(DataSourceDefinition datasource, Long clusterId,
                                          String path, String operationId) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceProbeRequest payload = payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED);
        payload.setPath(path);
        try {
            return post(endpoint, payload, "/internal/runtime/datasource/file-stat",
                    FileTransferFileEntryView.class, operationId);
        } catch (RemoteRuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public void operate(DataSourceDefinition datasource, Long clusterId, String operation,
                        String sourcePath, String targetPath, Boolean recursiveConfirmed) {
        operate(datasource, clusterId, operation, sourcePath, targetPath, recursiveConfirmed, null);
    }

    public void operate(DataSourceDefinition datasource, Long clusterId, String operation,
                        String sourcePath, String targetPath, Boolean recursiveConfirmed,
                        String operationId) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceProbeRequest payload = payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED);
        payload.setFileOperation(operation);
        payload.setOperationPath(sourcePath);
        payload.setOperationTargetPath(targetPath);
        payload.setRecursiveConfirmed(recursiveConfirmed);
        try {
            postPayload(endpoint, payload, "/internal/runtime/datasource/file-operation", operationId);
        } catch (RemoteRuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public void download(DataSourceDefinition datasource, Long clusterId, String path,
                         OutputStream output) {
        download(datasource, clusterId, path, output, null);
    }

    public void download(DataSourceDefinition datasource, Long clusterId, String path,
                         OutputStream output, String operationId) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceProbeRequest payload = payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED);
        payload.setPath(path);
        try {
            String endpointUrl = encryption.decrypt(endpoint.getEndpointCiphertext());
            String baseUrl = runtimeEndpointSecurityService.validate(endpointUrl).toString().replaceAll("/+$", "");
            RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                    runtimeEndpointSecurityService.validateRequestTarget(baseUrl + "/internal/runtime/datasource/file-download");
            if (!StringUtils.hasText(properties.getInternalApiToken())) throw unavailable();
            RuntimeEndpointHttpClient.StreamingResponse response = runtimeEndpointHttpClient.executeStreaming(
                    target, "POST", runtimeRequestHeaders(endpoint, operationId),
                    serializePayload(payload).getBytes(StandardCharsets.UTF_8),
                    timeout(endpoint.getConnectTimeoutMillis(), 3000),
                    downloadReadIdleTimeout(), output);
            if (!RuntimeInternalHeaders.isAuthenticatedRuntimeResponse(response.getHeaders())) throw unavailable();
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                Map<String, Object> envelope = objectMapper.readValue(response.getErrorBody(),
                        new TypeReference<LinkedHashMap<String, Object>>() { });
                throw remoteError(response.getStatusCode(), envelope);
            }
        } catch (RemoteRuntimeException ex) {
            throw ex;
        } catch (StudioException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public void downloadArchive(DataSourceDefinition datasource, Long clusterId,
                                List<String> paths, OutputStream output) {
        downloadArchive(datasource, clusterId, paths, output, null);
    }

    public void downloadArchive(DataSourceDefinition datasource, Long clusterId,
                                List<String> paths, OutputStream output, String operationId) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceArchiveRequest payload = objectMapper.convertValue(
                payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED),
                RuntimeDatasourceArchiveRequest.class);
        payload.setPaths(paths == null ? new ArrayList<String>() : new ArrayList<String>(paths));
        try {
            executeStreamingDownload(endpoint, payload,
                    "/internal/runtime/datasource/file-archive", output, operationId);
        } catch (RemoteRuntimeException ex) {
            throw ex;
        } catch (StudioException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public long upload(DataSourceDefinition datasource, Long clusterId, String targetPath,
                       boolean overwrite, long contentLength, InputStream input) {
        return upload(datasource, clusterId, targetPath, overwrite, contentLength, input, null);
    }

    public long upload(DataSourceDefinition datasource, Long clusterId, String targetPath,
                       boolean overwrite, long contentLength, InputStream input,
                       String operationId) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceUploadRequest payload = objectMapper.convertValue(
                payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED),
                RuntimeDatasourceUploadRequest.class);
        payload.setTargetPath(targetPath);
        payload.setOverwrite(overwrite);
        payload.setContentLength(contentLength);
        try {
            String endpointUrl = encryption.decrypt(endpoint.getEndpointCiphertext());
            String baseUrl = runtimeEndpointSecurityService.validate(endpointUrl).toString().replaceAll("/+$", "");
            RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                    runtimeEndpointSecurityService.validateRequestTarget(
                            baseUrl + "/internal/runtime/datasource/file-upload");
            if (!StringUtils.hasText(properties.getInternalApiToken())) throw unavailable();
            Map<String, List<String>> headers = runtimeRequestHeaders(endpoint, operationId);
            headers.put("Content-Type", List.of("application/octet-stream"));
            addHeader(headers, RuntimeInternalHeaders.RUNTIME_REQUEST_HEADER,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                            serializePayload(payload).getBytes(StandardCharsets.UTF_8)));
            RuntimeEndpointHttpClient.Response response = runtimeEndpointHttpClient.execute(
                    target, "POST", headers, input, contentLength,
                    timeout(endpoint.getConnectTimeoutMillis(), 3000),
                    longTransferTimeout(endpoint.getReadTimeoutMillis()), 1024 * 1024);
            if (!RuntimeInternalHeaders.isAuthenticatedRuntimeResponse(response.getHeaders())) {
                throw unavailable();
            }
            Map<String, Object> envelope = objectMapper.readValue(response.getBody(),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            if (!Boolean.TRUE.equals(envelope.get("success"))) {
                throw remoteError(response.getStatusCode(), envelope);
            }
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw unavailable();
            }
            Object data = envelope.get("data");
            if (!(data instanceof Map<?, ?> dataMap) || dataMap.get("bytes") == null) {
                throw unavailable();
            }
            return Long.parseLong(String.valueOf(dataMap.get("bytes")));
        } catch (RemoteRuntimeException ex) {
            throw ex;
        } catch (StudioException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public FileTransferSelectionPreviewView previewFileSelection(
            DataSourceDefinition datasource,
            Long clusterId,
            Map<String, Object> spec,
            Map<String, String> parameters,
            Integer limit) {
        RuntimeClusterEntity cluster = requireCluster(datasource, clusterId);
        RuntimeEndpointEntity endpoint = endpoint(cluster);
        if (endpoint == null || !online(cluster)) throw unavailable();
        RuntimeDatasourceProbeRequest payload = payload(cluster, datasource, RuntimeDatasourceProbeMode.STORED);
        payload.setFileTransferSpec(spec == null
                ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(spec));
        payload.setFileTransferParameters(parameters == null
                ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(parameters));
        payload.setFileTransferPreviewLimit(limit);
        try {
            return post(endpoint, payload, "/internal/runtime/datasource/file-transfer-preview",
                    FileTransferSelectionPreviewView.class);
        } catch (RemoteRuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    private RuntimeClusterEntity requireCluster(DataSourceDefinition datasource, Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Runtime cluster is required");
        }
        RuntimeClusterEntity cluster = clusterMapper.selectOne(new LambdaQueryWrapper<RuntimeClusterEntity>()
                .eq(RuntimeClusterEntity::getId, id).eq(RuntimeClusterEntity::getTenantId, datasource.getTenantId()).last("limit 1"));
        if (cluster == null || !Integer.valueOf(1).equals(cluster.getEnabled())) throw new StudioException(StudioErrorCode.FORBIDDEN, "Runtime cluster is unavailable");
        return cluster;
    }
    private boolean online(RuntimeClusterEntity cluster) { return runtimeClusterService.hasOnlineInstance(cluster); }
    private RuntimeEndpointEntity endpoint(RuntimeClusterEntity cluster) {
        return endpointMapper.selectOne(new LambdaQueryWrapper<RuntimeEndpointEntity>()
                .eq(RuntimeEndpointEntity::getTenantId, cluster.getTenantId()).eq(RuntimeEndpointEntity::getRuntimeClusterId, cluster.getId())
                .eq(RuntimeEndpointEntity::getMode, "HTTP").eq(RuntimeEndpointEntity::getEnabled, 1)
                .orderByAsc(RuntimeEndpointEntity::getId).last("limit 1"));
    }
    private <T> T post(RuntimeEndpointEntity endpoint, RuntimeDatasourceProbeRequest payload,
                       String path, Class<T> valueType) throws Exception {
        return post(endpoint, payload, path, valueType, null);
    }

    private <T> T post(RuntimeEndpointEntity endpoint, RuntimeDatasourceProbeRequest payload,
                       String path, Class<T> valueType, String operationId) throws Exception {
        return objectMapper.convertValue(postPayload(endpoint, payload, path, operationId), valueType);
    }

    private RuntimeDatasourceProbeRequest payload(RuntimeClusterEntity cluster,
                                                  DataSourceDefinition datasource,
                                                  RuntimeDatasourceProbeMode mode) {
        if (cluster == null || datasource == null || !StringUtils.hasText(datasource.getTenantId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource runtime identity is incomplete");
        }
        StudioRequestContext context = StudioRequestContextHolder.getContext();
        String tenantId = context != null && StringUtils.hasText(context.getTenantId())
                ? context.getTenantId().trim() : datasource.getTenantId().trim();
        if (!tenantId.equals(datasource.getTenantId().trim())) {
            throw new StudioException(StudioErrorCode.FORBIDDEN,
                    "Datasource belongs to another tenant");
        }
        Long projectId = context != null && context.getProjectId() != null
                ? context.getProjectId() : datasource.getProjectId();
        if (projectId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Project context is required");
        }
        RuntimeDatasourceProbeMode resolvedMode = mode == null
                ? RuntimeDatasourceProbeMode.STORED : mode;
        if (!workerAuthorizationService.isRuntimeClusterAuthorizedForProject(
                tenantId, projectId, cluster.getId())) {
            throw new StudioException(StudioErrorCode.FORBIDDEN,
                    "Runtime cluster is not authorized for the current project");
        }
        assertDatasourceApplicable(datasource, cluster.getId(), tenantId, resolvedMode);
        RuntimeDatasourceProbeRequest payload = new RuntimeDatasourceProbeRequest();
        payload.setTargetClusterId(cluster.getId());
        payload.setTargetClusterCode(cluster.getCode());
        payload.setTenantId(tenantId);
        payload.setProjectId(projectId);
        payload.setUserId(context == null ? null : context.getUserId());
        payload.setUsername(context == null ? "studio-system" : context.getUsername());
        payload.setMode(resolvedMode);
        payload.setDatasource(RuntimeDatasourceProbeMode.DRAFT_FORM == resolvedMode
                ? datasource : storedDatasourceIdentity(datasource));
        return payload;
    }

    private DataSourceDefinition storedDatasourceIdentity(DataSourceDefinition datasource) {
        DataSourceDefinition identity = new DataSourceDefinition();
        identity.setId(datasource.getId());
        identity.setTenantId(datasource.getTenantId());
        identity.setProjectId(datasource.getProjectId());
        identity.setTypeCode(datasource.getTypeCode());
        identity.setApplicableClusterIds(datasource.getApplicableClusterIds() == null
                ? new ArrayList<Long>() : new ArrayList<Long>(datasource.getApplicableClusterIds()));
        return identity;
    }

    private void assertDatasourceApplicable(DataSourceDefinition datasource, Long clusterId,
                                            String tenantId, RuntimeDatasourceProbeMode mode) {
        if (RuntimeDatasourceProbeMode.DRAFT_FORM == mode) {
            if (datasource.getApplicableClusterIds() == null
                    || !datasource.getApplicableClusterIds().contains(clusterId)) {
                throw new StudioException(StudioErrorCode.FORBIDDEN,
                        "Datasource draft is not applicable to the selected runtime cluster");
            }
            return;
        }
        if (datasource.getId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Stored datasource id is required for runtime execution");
        }
        Long count = datasourceClusterBindingMapper.selectCount(
                new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                        .eq(DatasourceClusterBindingEntity::getTenantId, tenantId)
                        .eq(DatasourceClusterBindingEntity::getDatasourceId, datasource.getId())
                        .eq(DatasourceClusterBindingEntity::getRuntimeClusterId, clusterId)
                        .eq(DatasourceClusterBindingEntity::getEnabled, 1));
        if (count == null || count.longValue() == 0L) {
            throw new StudioException(StudioErrorCode.FORBIDDEN,
                    "Datasource is not applicable to the selected runtime cluster");
        }
    }

    private Object postPayload(RuntimeEndpointEntity endpoint, RuntimeDatasourceProbeRequest payload,
                               String path) throws Exception {
        return postPayload(endpoint, payload, path, null);
    }

    private Object postPayload(RuntimeEndpointEntity endpoint, RuntimeDatasourceProbeRequest payload,
                               String path, String operationId) throws Exception {
        String endpointUrl = encryption.decrypt(endpoint.getEndpointCiphertext());
        String validatedEndpointUrl = runtimeEndpointSecurityService.validate(endpointUrl).toString();
        String baseUrl = validatedEndpointUrl.replaceAll("/+$", "");
        RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                runtimeEndpointSecurityService.validateRequestTarget(baseUrl + path);
        if (!StringUtils.hasText(properties.getInternalApiToken())) throw unavailable();
        String requestBody = serializePayload(payload);
        Map<String, List<String>> requestHeaders = runtimeRequestHeaders(endpoint, operationId);
        RuntimeEndpointHttpClient.Response response = runtimeEndpointHttpClient.execute(
                target, "POST", requestHeaders, requestBody.getBytes(StandardCharsets.UTF_8),
                timeout(endpoint.getConnectTimeoutMillis(), 3000),
                timeout(endpoint.getReadTimeoutMillis(), 5000),
                runtimeEndpointSecurityService.maxResponseBytes());
        if (!RuntimeInternalHeaders.isAuthenticatedRuntimeResponse(response.getHeaders())) {
            throw unavailable();
        }
        byte[] responseBody = response.getBody();
        Map<String,Object> envelope = objectMapper.readValue(responseBody,
                new TypeReference<LinkedHashMap<String,Object>>() {});
        if (!Boolean.TRUE.equals(envelope.get("success"))) {
            throw remoteError(response.getStatusCode(), envelope);
        }
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) throw unavailable();
        return envelope.get("data");
    }

    private void executeStreamingDownload(RuntimeEndpointEntity endpoint,
                                          RuntimeDatasourceProbeRequest payload,
                                          String path,
                                          OutputStream output) throws Exception {
        executeStreamingDownload(endpoint, payload, path, output, null);
    }

    private void executeStreamingDownload(RuntimeEndpointEntity endpoint,
                                          RuntimeDatasourceProbeRequest payload,
                                          String path,
                                          OutputStream output,
                                          String operationId) throws Exception {
        String endpointUrl = encryption.decrypt(endpoint.getEndpointCiphertext());
        String baseUrl = runtimeEndpointSecurityService.validate(endpointUrl).toString().replaceAll("/+$", "");
        RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                runtimeEndpointSecurityService.validateRequestTarget(baseUrl + path);
        if (!StringUtils.hasText(properties.getInternalApiToken())) throw unavailable();
        RuntimeEndpointHttpClient.StreamingResponse response = runtimeEndpointHttpClient.executeStreaming(
                target, "POST", runtimeRequestHeaders(endpoint, operationId),
                serializePayload(payload).getBytes(StandardCharsets.UTF_8),
                timeout(endpoint.getConnectTimeoutMillis(), 3000),
                downloadReadIdleTimeout(), output);
        if (!RuntimeInternalHeaders.isAuthenticatedRuntimeResponse(response.getHeaders())) throw unavailable();
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            Map<String, Object> envelope = objectMapper.readValue(response.getErrorBody(),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            throw remoteError(response.getStatusCode(), envelope);
        }
    }

    private Map<String, List<String>> runtimeRequestHeaders(RuntimeEndpointEntity endpoint) throws Exception {
        return runtimeRequestHeaders(endpoint, null);
    }

    private Map<String, List<String>> runtimeRequestHeaders(RuntimeEndpointEntity endpoint,
                                                            String operationId) throws Exception {
        if (endpoint == null || endpoint.getRuntimeClusterId() == null) {
            throw unavailable();
        }
        Map<String, List<String>> requestHeaders = new LinkedHashMap<String, List<String>>();
        addHeader(requestHeaders, "Content-Type", "application/json");
        addHeader(requestHeaders, INTERNAL_TOKEN_HEADER, properties.getInternalApiToken());
        String headers = endpoint.getHeadersCiphertext() == null
                ? null : encryption.decrypt(endpoint.getHeadersCiphertext());
        Map<String, String> configuredHeaders = new LinkedHashMap<String, String>();
        if (StringUtils.hasText(headers)) {
            configuredHeaders.putAll(objectMapper.readValue(headers,
                    new TypeReference<LinkedHashMap<String, String>>() {}));
            configuredHeaders = runtimeEndpointHeaderPolicy.sanitizeConfiguredHeaders(
                    configuredHeaders, Set.of("content-type"));
            for (Map.Entry<String, String> entry : configuredHeaders.entrySet()) {
                addHeader(requestHeaders, entry.getKey(), entry.getValue());
            }
        }
        if (StringUtils.hasText(endpoint.getTokenCiphertext()) && !containsHeader(configuredHeaders, "Authorization")) {
            addHeader(requestHeaders, "Authorization",
                    "Bearer " + encryption.decrypt(endpoint.getTokenCiphertext()));
        }
        // The worker validates the target cluster independently of the request body. Keep this
        // identity owned by the selected endpoint so configured endpoint headers cannot spoof it.
        addHeader(requestHeaders, TARGET_CLUSTER_ID_HEADER,
                String.valueOf(endpoint.getRuntimeClusterId()));
        String safeOperationId = RuntimeInternalHeaders.normalizeOperationId(operationId);
        if (safeOperationId != null) {
            addHeader(requestHeaders, RuntimeInternalHeaders.OPERATION_ID_HEADER, safeOperationId);
        }
        return requestHeaders;
    }

    private String serializePayload(RuntimeDatasourceProbeRequest payload) throws Exception {
        if (payload == null || RuntimeDatasourceProbeMode.DRAFT_FORM == payload.getMode()) {
            return objectMapper.writeValueAsString(payload);
        }
        Map<String, Object> request = objectMapper.convertValue(payload,
                new TypeReference<LinkedHashMap<String, Object>>() { });
        DataSourceDefinition datasource = payload.getDatasource();
        LinkedHashMap<String, Object> identity = new LinkedHashMap<String, Object>();
        if (datasource != null) {
            identity.put("id", datasource.getId());
            identity.put("tenantId", datasource.getTenantId());
            identity.put("projectId", datasource.getProjectId());
            identity.put("typeCode", datasource.getTypeCode());
            identity.put("applicableClusterIds", datasource.getApplicableClusterIds());
        }
        request.put("datasource", identity);
        return objectMapper.writeValueAsString(request);
    }

    private RemoteRuntimeException remoteError(int status, Map<String, Object> envelope) {
        String code = text(envelope == null ? null : envelope.get("code"));
        String message = text(envelope == null ? null : envelope.get("message"));
        if (!StringUtils.hasText(code)) {
            code = status == 403 ? StudioErrorCode.FORBIDDEN
                    : status == 404 ? StudioErrorCode.NOT_FOUND
                    : status == 409 ? StudioErrorCode.CONFLICT
                    : status >= 500 ? StudioErrorCode.INTERNAL_SERVER_ERROR
                    : StudioErrorCode.BUSINESS_ERROR;
        }
        if (!StringUtils.hasText(message)) {
            message = "Target runtime datasource operation failed";
        }
        return new RemoteRuntimeException(code, message);
    }
    private String text(Object value) { return value == null ? null : String.valueOf(value).trim(); }
    private ConnectionTestResult unknown(String message) { ConnectionTestResult r=new ConnectionTestResult(); r.setSuccess(false);r.setStatus(DataSourceConnectionStatus.UNKNOWN);r.setMessage(message);return r; }
    private StudioException unavailable() { return new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE, "Target runtime cluster is unavailable"); }
    private int timeout(Integer value, int fallback) { return value == null ? fallback : Math.max(100, Math.min(value, 60000)); }
    private int longTransferTimeout(Integer value) {
        int configured = value == null || value <= 0 ? 300000 : Math.max(value, 300000);
        return Math.max(1000, Math.min(configured, 30 * 60 * 1000));
    }
    private int downloadReadIdleTimeout() {
        Integer configured = properties.getFileTransfer().getDownloadReadIdleTimeoutMillis();
        int timeout = configured == null || configured <= 0 ? 30 * 60 * 1000 : configured;
        return Math.max(1000, Math.min(timeout, 30 * 60 * 1000));
    }
    private boolean containsHeader(Map<String, String> headers, String expected) {
        for (String name : headers.keySet()) if (expected.equalsIgnoreCase(name)) return true;
        return false;
    }
    private void addHeader(Map<String, List<String>> headers, String name, String value) {
        headers.computeIfAbsent(name, ignored -> new ArrayList<String>()).add(value);
    }
    private static final class RemoteRuntimeException extends StudioException {
        private RemoteRuntimeException(String code, String message) {
            super(code, message);
        }
    }
}
