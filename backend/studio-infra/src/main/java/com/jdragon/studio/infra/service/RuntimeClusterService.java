package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.RuntimeEndpointMode;
import com.jdragon.studio.dto.model.RuntimeClusterInstanceView;
import com.jdragon.studio.dto.model.RuntimeClusterProjectAuthorizationView;
import com.jdragon.studio.dto.model.RuntimeClusterView;
import com.jdragon.studio.dto.model.RuntimeEndpointView;
import com.jdragon.studio.dto.model.request.RuntimeClusterHeartbeatRequest;
import com.jdragon.studio.dto.model.request.RuntimeClusterProjectAuthorizationRequest;
import com.jdragon.studio.dto.model.request.RuntimeClusterSaveRequest;
import com.jdragon.studio.dto.model.request.RuntimeEndpointSaveRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectRuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeEndpointEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.ProjectRuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RuntimeEndpointMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Control-plane registry. Endpoint secrets are only decrypted by the invocation layer. */
@Service
public class RuntimeClusterService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeClusterService.class);
    private static final String RETIRED_LOCAL_ENDPOINT_MODE = "LOCAL";
    private static final DateTimeFormatter DATABASE_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final RuntimeClusterMapper clusterMapper;
    private final RuntimeEndpointMapper endpointMapper;
    private final ProjectRuntimeClusterMapper authorizationMapper;
    private final ProjectMapper projectMapper;
    private final RuntimeClusterReferenceRepository referenceRepository;
    private final StudioSecurityService security;
    private final EncryptionService encryption;
    private final ObjectMapper objectMapper;
    private StudioPlatformProperties properties;
    private RuntimeValidationService runtimeValidationService;
    private RuntimeClusterHeartbeatService runtimeClusterHeartbeatService;
    private RuntimeEndpointSecurityService runtimeEndpointSecurityService;
    private RuntimeEndpointHttpClient runtimeEndpointHttpClient;
    private RuntimeEndpointHeaderPolicy runtimeEndpointHeaderPolicy;
    private WorkerLeaseMapper workerLeaseMapper;

    public RuntimeClusterService(RuntimeClusterMapper clusterMapper, RuntimeEndpointMapper endpointMapper,
                                 ProjectRuntimeClusterMapper authorizationMapper, StudioSecurityService security,
                                 EncryptionService encryption, ObjectMapper objectMapper,
                                 RuntimeClusterReferenceRepository referenceRepository,
                                 ProjectMapper projectMapper) {
        this.clusterMapper = clusterMapper; this.endpointMapper = endpointMapper;
        this.authorizationMapper = authorizationMapper; this.security = security;
        this.encryption = encryption; this.objectMapper = objectMapper;
        this.referenceRepository = referenceRepository;
        this.projectMapper = projectMapper;
    }

    @Autowired
    void setStudioPlatformProperties(StudioPlatformProperties properties) {
        this.properties = properties;
    }

    @Autowired
    void setRuntimeValidationService(RuntimeValidationService runtimeValidationService) {
        this.runtimeValidationService = runtimeValidationService;
    }

    @Autowired
    void setRuntimeClusterHeartbeatService(RuntimeClusterHeartbeatService runtimeClusterHeartbeatService) {
        this.runtimeClusterHeartbeatService = runtimeClusterHeartbeatService;
    }

    @Autowired
    void setRuntimeEndpointSecurityService(RuntimeEndpointSecurityService runtimeEndpointSecurityService) {
        this.runtimeEndpointSecurityService = runtimeEndpointSecurityService;
    }

    @Autowired
    void setRuntimeEndpointHttpClient(RuntimeEndpointHttpClient runtimeEndpointHttpClient) {
        this.runtimeEndpointHttpClient = runtimeEndpointHttpClient;
    }

    @Autowired
    void setRuntimeEndpointHeaderPolicy(RuntimeEndpointHeaderPolicy runtimeEndpointHeaderPolicy) {
        this.runtimeEndpointHeaderPolicy = runtimeEndpointHeaderPolicy;
    }

    @Autowired
    void setWorkerLeaseMapper(WorkerLeaseMapper workerLeaseMapper) {
        this.workerLeaseMapper = workerLeaseMapper;
    }

    public List<RuntimeClusterView> options(Long projectId) {
        String tenantId = security.currentTenantId();
        Long effectiveProjectId = resolveProjectIdForOptions(projectId);
        if (effectiveProjectId != null) {
            requireTenantProject(effectiveProjectId);
        }
        List<RuntimeClusterEntity> clusters = clusterMapper.selectList(new LambdaQueryWrapper<RuntimeClusterEntity>()
                .eq(RuntimeClusterEntity::getTenantId, tenantId).eq(RuntimeClusterEntity::getEnabled, 1)
                .orderByAsc(RuntimeClusterEntity::getName));
        if (effectiveProjectId == null) return optionViews(clusters);
        List<ProjectRuntimeClusterEntity> configuredGrants = authorizationMapper.selectList(
                new LambdaQueryWrapper<ProjectRuntimeClusterEntity>()
                        .eq(ProjectRuntimeClusterEntity::getTenantId, tenantId)
                        .eq(ProjectRuntimeClusterEntity::getProjectId, effectiveProjectId));
        Map<Long, ProjectRuntimeClusterEntity> grantByClusterId = new LinkedHashMap<Long, ProjectRuntimeClusterEntity>();
        for (ProjectRuntimeClusterEntity grant : configuredGrants) {
            if (Integer.valueOf(1).equals(grant.getEnabled())) {
                grantByClusterId.put(grant.getRuntimeClusterId(), grant);
            }
        }
        List<RuntimeClusterView> result = new ArrayList<RuntimeClusterView>();
        for (RuntimeClusterEntity cluster : clusters) {
            ProjectRuntimeClusterEntity grant = grantByClusterId.get(cluster.getId());
            if (grant == null) {
                continue;
            }
            RuntimeClusterView option = optionView(cluster);
            option.setPreferred(Integer.valueOf(1).equals(grant.getPreferred()));
            option.setAllowManualOverride(Integer.valueOf(1).equals(grant.getAllowManualOverride()));
            result.add(option);
        }
        return result;
    }

    public List<RuntimeClusterView> list() { requireManage(); return views(clusterMapper.selectList(new LambdaQueryWrapper<RuntimeClusterEntity>()
            .eq(RuntimeClusterEntity::getTenantId, security.currentTenantId()).orderByAsc(RuntimeClusterEntity::getName))); }
    public RuntimeClusterView get(Long id) { requireManage(); return view(requireCluster(id)); }

    @Transactional
    public RuntimeClusterView save(RuntimeClusterSaveRequest request) {
        requireManage(); if (request == null) throw bad("Runtime cluster payload is required");
        RuntimeClusterEntity entity = request.getId() == null ? new RuntimeClusterEntity() : requireCluster(request.getId());
        String code = text(request.getCode(), "Runtime cluster code is required").toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9][A-Z0-9_-]{0,63}")) throw bad("Runtime cluster code is invalid");
        if (entity.getId() != null && !code.equals(entity.getCode())) throw bad("Runtime cluster code cannot be changed");
        Long exists = entity.getId() == null
                ? clusterMapper.selectPhysicalCountByTenantAndCode(security.currentTenantId(), code)
                : clusterMapper.selectCount(new LambdaQueryWrapper<RuntimeClusterEntity>()
                        .eq(RuntimeClusterEntity::getTenantId, security.currentTenantId())
                        .eq(RuntimeClusterEntity::getCode, code)
                        .ne(RuntimeClusterEntity::getId, entity.getId()));
        if (exists != null && exists > 0) throw new StudioException(StudioErrorCode.BUSINESS_ERROR, "Runtime cluster code already exists");
        String name = text(request.getName(), "Runtime cluster name is required");
        String version = trim(request.getVersion());
        int enabled = Boolean.FALSE.equals(request.getEnabled()) ? 0 : 1;
        entity.setTenantId(security.currentTenantId()); entity.setCode(code); entity.setName(name);
        entity.setVersion(version); entity.setEnabled(enabled);
        if (!StringUtils.hasText(entity.getStatus())) entity.setStatus("UNKNOWN");
        if (entity.getId() == null) {
            clusterMapper.insert(entity);
        } else {
            clusterMapper.update(null, new LambdaUpdateWrapper<RuntimeClusterEntity>()
                    .eq(RuntimeClusterEntity::getTenantId, security.currentTenantId())
                    .eq(RuntimeClusterEntity::getId, entity.getId())
                    .set(RuntimeClusterEntity::getName, name)
                    .set(RuntimeClusterEntity::getVersion, version)
                    .set(RuntimeClusterEntity::getEnabled, enabled)
                    .set(RuntimeClusterEntity::getUpdatedAt, LocalDateTime.now()));
        }
        revalidatePlacement(null, entity.getId());
        return view(entity);
    }

    @Transactional public RuntimeClusterView enable(Long id) { requireManage(); RuntimeClusterEntity e=requireCluster(id); updateClusterEnabled(e, 1); revalidatePlacement(null, e.getId()); return view(e); }
    @Transactional public RuntimeClusterView disable(Long id) { requireManage(); RuntimeClusterEntity e=requireCluster(id); updateClusterEnabled(e, 0); revalidatePlacement(null, e.getId()); return view(e); }

    @Transactional
    public void delete(Long id) {
        requireManage();
        RuntimeClusterEntity entity = requireCluster(id);
        if (Integer.valueOf(1).equals(entity.getEnabled())) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Disable the runtime cluster before deleting it");
        }
        if (hasOnlineInstance(entity)) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Stop all runtime cluster instances before deleting it");
        }
        if (referenceRepository.countBlockingReferences(security.currentTenantId(), entity.getId()) > 0L) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Remove runtime endpoints, project authorizations, datasource bindings and resource references before deleting this cluster");
        }
        referenceRepository.cleanupNonBlockingReferences(security.currentTenantId(), entity.getId());
        clusterMapper.deleteById(entity.getId());
    }

    public List<RuntimeClusterInstanceView> instances(Long clusterId) {
        requireManage();
        return instanceViews(requireCluster(clusterId), LocalDateTime.now());
    }

    public boolean hasOnlineInstance(RuntimeClusterEntity cluster) {
        if (cluster == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return hasActiveWorkerLease(cluster, now)
                || onlineInstanceCount(cluster, now) > 0
                || isRecentHeartbeat(cluster.getLastHeartbeatAt(), now);
    }

    private boolean hasActiveWorkerLease(RuntimeClusterEntity cluster, LocalDateTime now) {
        if (workerLeaseMapper == null || cluster.getId() == null || !StringUtils.hasText(cluster.getTenantId())) {
            return false;
        }
        LocalDateTime heartbeatThreshold = heartbeatThreshold(now);
        Long count = workerLeaseMapper.selectCount(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getTenantId, cluster.getTenantId())
                .eq(WorkerLeaseEntity::getRuntimeClusterId, cluster.getId())
                .eq(WorkerLeaseEntity::getStatus, StudioConstants.WORKER_STATUS_ONLINE)
                .and(wrapper -> wrapper.gt(WorkerLeaseEntity::getLeaseExpiresAt, now)
                        .or()
                        .ge(WorkerLeaseEntity::getLastHeartbeatAt, heartbeatThreshold)));
        return count != null && count.longValue() > 0L;
    }

    public List<RuntimeEndpointView> endpoints(Long clusterId) { requireManage(); requireCluster(clusterId); return endpointViews(endpointMapper.selectList(new LambdaQueryWrapper<RuntimeEndpointEntity>()
            .eq(RuntimeEndpointEntity::getTenantId, security.currentTenantId()).eq(RuntimeEndpointEntity::getRuntimeClusterId, clusterId))); }
    @Transactional
    public RuntimeEndpointView saveEndpoint(RuntimeEndpointSaveRequest request) {
        requireManage();
        if (request == null || request.getRuntimeClusterId() == null) {
            throw bad("Runtime cluster id is required");
        }
        requireCluster(request.getRuntimeClusterId());
        boolean creating = request.getId() == null;
        RuntimeEndpointEntity entity = creating ? new RuntimeEndpointEntity() : requireEndpoint(request.getId());
        if (!creating && !entity.getRuntimeClusterId().equals(request.getRuntimeClusterId())) {
            throw bad("Runtime endpoint cluster cannot be changed");
        }
        String mode = text(request.getMode(), "Runtime endpoint mode is required").toUpperCase(Locale.ROOT);
        if (!RuntimeEndpointMode.HTTP.name().equals(mode)) {
            throw bad("Only HTTP runtime endpoints are supported; migrate legacy LOCAL endpoints to a Worker HTTP/SLB endpoint");
        }
        entity.setTenantId(security.currentTenantId());
        entity.setRuntimeClusterId(request.getRuntimeClusterId());
        entity.setMode(mode);
        if (StringUtils.hasText(request.getEndpointUrl())) {
            entity.setEndpointCiphertext(encryption.encrypt(validateHttp(request.getEndpointUrl())));
        } else if (creating || !StringUtils.hasText(entity.getEndpointCiphertext())) {
            throw bad("Runtime endpoint URL is required");
        }
        if (request.getHeaders() != null || creating) {
            entity.setHeadersCiphertext(encryption.encrypt(writeJson(request.getHeaders())));
        }
        if (StringUtils.hasText(request.getToken())) {
            entity.setTokenCiphertext(encryption.encrypt(request.getToken().trim()));
        } else if (Boolean.TRUE.equals(request.getClearToken())) {
            entity.setTokenCiphertext(null);
        }
        entity.setConnectTimeoutMillis(timeout(request.getConnectTimeoutMillis(), 3000));
        entity.setReadTimeoutMillis(timeout(request.getReadTimeoutMillis(), 5000));
        entity.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? 0 : 1);
        if (creating) {
            endpointMapper.insert(entity);
        } else {
            endpointMapper.updateById(entity);
        }
        return endpointView(entity);
    }
    @Transactional
    public RuntimeEndpointView testEndpoint(Long id) {
        requireManage();
        RuntimeEndpointEntity endpoint = requireEndpoint(id);
        endpoint.setLastTestedAt(LocalDateTime.now());
        if (RETIRED_LOCAL_ENDPOINT_MODE.equalsIgnoreCase(endpoint.getMode())) {
            endpoint.setLastTestStatus("FAILED");
            endpoint.setLastTestMessage("Legacy LOCAL runtime endpoints are no longer executable; configure and test a Worker HTTP/SLB endpoint, then remove this record");
        } else if (!RuntimeEndpointMode.HTTP.name().equalsIgnoreCase(endpoint.getMode())) {
            endpoint.setLastTestStatus("FAILED");
            endpoint.setLastTestMessage("Stored runtime endpoint mode is unsupported; configure and test a Worker HTTP/SLB endpoint, then remove this record");
        } else if (!Integer.valueOf(1).equals(endpoint.getEnabled())) {
            endpoint.setLastTestStatus("DISABLED");
            endpoint.setLastTestMessage("Endpoint is disabled");
        } else {
            testHttpEndpoint(endpoint);
        }
        endpointMapper.updateById(endpoint);
        return endpointView(endpoint, false);
    }

    @Transactional
    public void deleteEndpoint(Long id) {
        requireManage();
        RuntimeEndpointEntity endpoint = requireEndpoint(id);
        if (Integer.valueOf(1).equals(endpoint.getEnabled())) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Disable the runtime endpoint before deleting it");
        }
        endpointMapper.deleteById(endpoint.getId());
    }

    @Transactional
    public RuntimeEndpointView disableEndpoint(Long id) {
        requireManage();
        RuntimeEndpointEntity endpoint = requireEndpoint(id);
        if (!Integer.valueOf(0).equals(endpoint.getEnabled())) {
            endpoint.setEnabled(0);
            endpointMapper.updateById(endpoint);
        }
        return endpointView(endpoint);
    }

    @Transactional
    public int refreshOfflineStatuses() {
        LocalDateTime threshold = heartbeatThreshold(LocalDateTime.now());
        return clusterMapper.update(null, new LambdaUpdateWrapper<RuntimeClusterEntity>()
                .eq(RuntimeClusterEntity::getStatus, "ONLINE")
                .isNotNull(RuntimeClusterEntity::getLastHeartbeatAt)
                .lt(RuntimeClusterEntity::getLastHeartbeatAt, threshold)
                .set(RuntimeClusterEntity::getStatus, "OFFLINE")
                .set(RuntimeClusterEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void testHttpEndpoint(RuntimeEndpointEntity endpoint) {
        try {
            String url = encryption.decrypt(endpoint.getEndpointCiphertext());
            URI validatedBase = endpointSecurity().validate(url);
            String validatedUrl = validatedBase.toString();
            String baseUrl = validatedUrl.endsWith("/")
                    ? validatedUrl.substring(0, validatedUrl.length() - 1) : validatedUrl;
            RuntimeEndpointSecurityService.ValidatedRuntimeEndpoint target =
                    endpointSecurity().validateRequestTarget(baseUrl + "/internal/runtime/health");
            if (properties == null || !StringUtils.hasText(properties.getInternalApiToken())) {
                endpoint.setLastTestStatus("FAILED");
                endpoint.setLastTestMessage("Internal runtime authentication is not configured");
                return;
            }
            Map<String, List<String>> requestHeaders = new LinkedHashMap<String, List<String>>();
            addRequestHeader(requestHeaders, "X-Studio-Internal-Token", properties.getInternalApiToken());
            addRequestHeader(requestHeaders, "X-Studio-Target-Cluster-Id",
                    String.valueOf(endpoint.getRuntimeClusterId()));
            Map<String, String> headers = endpointHeaderPolicy().sanitizeConfiguredHeaders(
                    readHeaders(endpoint), java.util.Collections.emptySet());
            String token = StringUtils.hasText(endpoint.getTokenCiphertext())
                    ? encryption.decrypt(endpoint.getTokenCiphertext()) : null;
            if (StringUtils.hasText(token) && !containsHeader(headers, "Authorization")) {
                addRequestHeader(requestHeaders, "Authorization", "Bearer " + token.trim());
            }
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                addRequestHeader(requestHeaders, entry.getKey(), entry.getValue());
            }
            RuntimeEndpointHttpClient.Response response = endpointHttpClient().execute(
                    target, "GET", requestHeaders, new byte[0],
                    timeout(endpoint.getConnectTimeoutMillis(), 3000),
                    timeout(endpoint.getReadTimeoutMillis(), 5000),
                    endpointSecurity().maxResponseBytes());
            boolean authenticatedRuntimeResponse = hasHeaderValue(response.getHeaders(),
                    RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                    RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
            boolean successful = response.getStatusCode() >= 200 && response.getStatusCode() < 300
                    && authenticatedRuntimeResponse;
            endpoint.setLastTestStatus(successful ? "SUCCESS" : "FAILED");
            endpoint.setLastTestMessage(response.getStatusCode() >= 200 && response.getStatusCode() < 300
                    && !authenticatedRuntimeResponse
                    ? "Runtime response authentication marker is missing"
                    : "HTTP " + response.getStatusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            endpoint.setLastTestStatus("FAILED");
            endpoint.setLastTestMessage("Connection test was interrupted");
        } catch (RuntimeEndpointSecurityService.ResponseTooLargeException ex) {
            endpoint.setLastTestStatus("FAILED");
            endpoint.setLastTestMessage("Runtime endpoint response exceeds the configured limit");
        } catch (StudioException ex) {
            endpoint.setLastTestStatus("FAILED");
            endpoint.setLastTestMessage(ex.getMessage());
        } catch (Exception ex) {
            endpoint.setLastTestStatus("FAILED");
            endpoint.setLastTestMessage("Connection failed: " + ex.getClass().getSimpleName());
        }
    }

    private Map<String, String> readHeaders(RuntimeEndpointEntity endpoint) {
        if (endpoint == null || !StringUtils.hasText(endpoint.getHeadersCiphertext())) return new LinkedHashMap<String, String>();
        try {
            Map<String, String> headers = objectMapper.readValue(encryption.decrypt(endpoint.getHeadersCiphertext()),
                    new TypeReference<LinkedHashMap<String, String>>() { });
            if (headers == null) throw new IllegalArgumentException("Runtime endpoint headers are null");
            return headers;
        } catch (Exception ex) {
            throw bad("Stored runtime endpoint headers cannot be decrypted");
        }
    }

    private boolean containsHeader(Map<String, String> headers, String expected) {
        for (String name : headers.keySet()) if (expected.equalsIgnoreCase(name)) return true;
        return false;
    }

    private boolean hasHeaderValue(Map<String, List<String>> headers, String expectedName, String expectedValue) {
        if (headers == null) return false;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!expectedName.equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) continue;
            for (String value : entry.getValue()) {
                if (expectedValue.equalsIgnoreCase(value)) return true;
            }
        }
        return false;
    }

    private RuntimeEndpointHeaderPolicy endpointHeaderPolicy() {
        return runtimeEndpointHeaderPolicy == null
                ? new RuntimeEndpointHeaderPolicy()
                : runtimeEndpointHeaderPolicy;
    }

    private void addRequestHeader(Map<String, List<String>> headers, String name, String value) {
        headers.computeIfAbsent(name, ignored -> new ArrayList<String>()).add(value);
    }

    public List<RuntimeClusterProjectAuthorizationView> projectAuthorizations(Long projectId) { requireManage(); requireTenantProject(projectId); return authorizationViews(authorizationMapper.selectList(new LambdaQueryWrapper<ProjectRuntimeClusterEntity>()
            .eq(ProjectRuntimeClusterEntity::getTenantId, security.currentTenantId()).eq(ProjectRuntimeClusterEntity::getProjectId, projectId))); }
    @Transactional public RuntimeClusterProjectAuthorizationView saveProjectAuthorization(RuntimeClusterProjectAuthorizationRequest r) {
        requireManage(); if(r==null || r.getProjectId()==null || r.getRuntimeClusterId()==null) throw bad("Project id and runtime cluster id are required"); requireTenantProject(r.getProjectId()); requireCluster(r.getRuntimeClusterId());
        ProjectRuntimeClusterEntity e=authorizationMapper.selectOne(new LambdaQueryWrapper<ProjectRuntimeClusterEntity>().eq(ProjectRuntimeClusterEntity::getTenantId,security.currentTenantId()).eq(ProjectRuntimeClusterEntity::getProjectId,r.getProjectId()).eq(ProjectRuntimeClusterEntity::getRuntimeClusterId,r.getRuntimeClusterId()).last("limit 1"));
        if(e==null){e=new ProjectRuntimeClusterEntity();e.setTenantId(security.currentTenantId());e.setProjectId(r.getProjectId());e.setRuntimeClusterId(r.getRuntimeClusterId());}
        boolean preferred=Boolean.TRUE.equals(r.getPreferred()); if(preferred) authorizationMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ProjectRuntimeClusterEntity>().eq(ProjectRuntimeClusterEntity::getTenantId,security.currentTenantId()).eq(ProjectRuntimeClusterEntity::getProjectId,r.getProjectId()).set(ProjectRuntimeClusterEntity::getPreferred,0));
        e.setEnabled(Boolean.FALSE.equals(r.getEnabled())?0:1); e.setPreferred(preferred?1:0); e.setAllowManualOverride(Boolean.TRUE.equals(r.getAllowManualOverride())?1:0); if(e.getId()==null)authorizationMapper.insert(e);else authorizationMapper.updateById(e); revalidatePlacement(r.getProjectId(), r.getRuntimeClusterId()); return authorizationView(e);
    }
    public void heartbeat(RuntimeClusterHeartbeatRequest r) {
        if (r == null) throw bad("Runtime heartbeat payload is required");
        String tenantId = StringUtils.hasText(r.getTenantId()) ? r.getTenantId().trim() : security.currentTenantId();
        runtimeClusterHeartbeatService.recordByCode(tenantId,
                text(r.getClusterCode(), "Runtime cluster code is required").toUpperCase(Locale.ROOT),
                text(r.getInstanceId(), "Runtime instance id is required"), new LinkedHashMap<String, Object>(),
                trim(r.getVersion()), trim(r.getSummary()), LocalDateTime.now());
    }
    public RuntimeClusterEntity requireAuthorized(Long projectId, Long clusterId) {
        requireTenantProject(projectId);
        RuntimeClusterEntity cluster = requireCluster(clusterId);
        if (!Integer.valueOf(1).equals(cluster.getEnabled())) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Runtime cluster is disabled");
        }
        Long selectedCount = authorizationMapper.selectCount(new LambdaQueryWrapper<ProjectRuntimeClusterEntity>()
                .eq(ProjectRuntimeClusterEntity::getTenantId, security.currentTenantId())
                .eq(ProjectRuntimeClusterEntity::getProjectId, projectId)
                .eq(ProjectRuntimeClusterEntity::getRuntimeClusterId, clusterId)
                .eq(ProjectRuntimeClusterEntity::getEnabled, 1));
        if (selectedCount == null || selectedCount.longValue() == 0L) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Runtime cluster is not authorized for this project");
        }
        return cluster;
    }
    public boolean hasProjectAuthorizations(Long projectId) {
        if (projectId == null) return false;
        requireTenantProject(projectId);
        Long count = authorizationMapper.selectCount(new LambdaQueryWrapper<ProjectRuntimeClusterEntity>()
                .eq(ProjectRuntimeClusterEntity::getTenantId, security.currentTenantId())
                .eq(ProjectRuntimeClusterEntity::getProjectId, projectId)
                .eq(ProjectRuntimeClusterEntity::getEnabled, 1));
        return count != null && count.longValue() > 0L;
    }
    public RuntimeClusterEntity requireManualOverrideAllowed(Long projectId, Long clusterId) {
        RuntimeClusterEntity cluster = requireAuthorized(projectId, clusterId);
        ProjectRuntimeClusterEntity grant = authorizationMapper.selectOne(new LambdaQueryWrapper<ProjectRuntimeClusterEntity>()
                .eq(ProjectRuntimeClusterEntity::getTenantId, security.currentTenantId())
                .eq(ProjectRuntimeClusterEntity::getProjectId, projectId)
                .eq(ProjectRuntimeClusterEntity::getRuntimeClusterId, clusterId)
                .eq(ProjectRuntimeClusterEntity::getEnabled, 1)
                .last("limit 1"));
        if (grant == null || !Integer.valueOf(1).equals(grant.getAllowManualOverride())) {
            throw new StudioException(StudioErrorCode.FORBIDDEN,
                    "Manual runtime cluster override is not allowed for this project");
        }
        return cluster;
    }
    public String clusterName(Long clusterId) {
        if (clusterId == null) return null;
        RuntimeClusterEntity cluster = clusterMapper.selectOne(new LambdaQueryWrapper<RuntimeClusterEntity>()
                .eq(RuntimeClusterEntity::getId, clusterId)
                .eq(RuntimeClusterEntity::getTenantId, security.currentTenantId())
                .last("limit 1"));
        return cluster == null ? null : cluster.getName();
    }
    private Long resolveProjectIdForOptions(Long requestedProjectId) {
        Long currentProjectId = security.currentProjectId();
        if (requestedProjectId == null && currentProjectId == null) {
            throw bad("Project id is required");
        }
        if (requestedProjectId == null || requestedProjectId.equals(currentProjectId)) {
            return requestedProjectId == null ? currentProjectId : requestedProjectId;
        }
        if (!security.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Runtime cluster options belong to another project");
        }
        return requestedProjectId;
    }
    private ProjectEntity requireTenantProject(Long projectId) {
        if (projectId == null) {
            throw bad("Project id is required");
        }
        ProjectEntity project = projectMapper.selectOne(new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getId, projectId)
                .eq(ProjectEntity::getTenantId, security.currentTenantId())
                .last("limit 1"));
        if (project == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Project not found");
        }
        return project;
    }
    private void updateClusterEnabled(RuntimeClusterEntity cluster, int enabled) {
        clusterMapper.update(null, new LambdaUpdateWrapper<RuntimeClusterEntity>()
                .eq(RuntimeClusterEntity::getTenantId, security.currentTenantId())
                .eq(RuntimeClusterEntity::getId, cluster.getId())
                .set(RuntimeClusterEntity::getEnabled, enabled)
                .set(RuntimeClusterEntity::getUpdatedAt, LocalDateTime.now()));
        cluster.setEnabled(enabled);
    }
    private RuntimeClusterEntity requireCluster(Long id){if(id==null)throw bad("Runtime cluster id is required");RuntimeClusterEntity e=clusterMapper.selectOne(new LambdaQueryWrapper<RuntimeClusterEntity>().eq(RuntimeClusterEntity::getId,id).eq(RuntimeClusterEntity::getTenantId,security.currentTenantId()).last("limit 1"));if(e==null)throw new StudioException(StudioErrorCode.NOT_FOUND,"Runtime cluster was not found");return e;}
    private RuntimeEndpointEntity requireEndpoint(Long id){RuntimeEndpointEntity e=endpointMapper.selectOne(new LambdaQueryWrapper<RuntimeEndpointEntity>().eq(RuntimeEndpointEntity::getId,id).eq(RuntimeEndpointEntity::getTenantId,security.currentTenantId()).last("limit 1"));if(e==null)throw new StudioException(StudioErrorCode.NOT_FOUND,"Runtime endpoint was not found");return e;}
    private void requireManage(){if(!security.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN,StudioConstants.ROLE_TENANT_ADMIN))throw new StudioException(StudioErrorCode.FORBIDDEN,"Runtime cluster management requires tenant administrator permission");}
    private List<RuntimeClusterView> views(List<RuntimeClusterEntity> entities) {
        List<RuntimeClusterView> result = new ArrayList<RuntimeClusterView>();
        for (RuntimeClusterEntity entity : entities) {
            result.add(view(entity));
        }
        return result;
    }

    private List<RuntimeClusterView> optionViews(List<RuntimeClusterEntity> entities) {
        List<RuntimeClusterView> result = new ArrayList<RuntimeClusterView>();
        for (RuntimeClusterEntity entity : entities) {
            result.add(optionView(entity));
        }
        return result;
    }

    private RuntimeClusterView view(RuntimeClusterEntity entity) {
        LocalDateTime now = LocalDateTime.now();
        List<RuntimeClusterInstanceView> instances = instanceViews(entity, now);
        int onlineInstanceCount = 0;
        for (RuntimeClusterInstanceView instance : instances) {
            if (instance.isOnline()) {
                onlineInstanceCount++;
            }
        }
        RuntimeClusterView view = new RuntimeClusterView();
        view.setId(entity.getId());
        view.setCode(entity.getCode());
        view.setName(entity.getName());
        view.setEnabled(Integer.valueOf(1).equals(entity.getEnabled()));
        view.setStatus(effectiveClusterStatus(entity, onlineInstanceCount, now));
        view.setVersion(entity.getVersion());
        view.setLastHeartbeatAt(entity.getLastHeartbeatAt());
        view.setOnlineInstanceCount(onlineInstanceCount);
        view.setInstances(instances);
        return view;
    }

    private RuntimeClusterView optionView(RuntimeClusterEntity entity) {
        RuntimeClusterView option = view(entity);
        option.setInstances(new ArrayList<RuntimeClusterInstanceView>());
        return option;
    }

    private List<RuntimeClusterInstanceView> instanceViews(RuntimeClusterEntity entity, LocalDateTime now) {
        List<RuntimeClusterInstanceView> result = new ArrayList<RuntimeClusterInstanceView>();
        Map<String, Object> values = entity.getInstancesJson() == null
                ? new LinkedHashMap<String, Object>() : entity.getInstancesJson();
        for (Object value : values.values()) {
            try {
                Map<String, Object> instanceValues = objectMapper.convertValue(value,
                        new TypeReference<LinkedHashMap<String, Object>>() { });
                Object heartbeatValue = instanceValues.remove("heartbeatAt");
                RuntimeClusterInstanceView instance = objectMapper.convertValue(
                        instanceValues, RuntimeClusterInstanceView.class);
                instance.setHeartbeatAt(parseHeartbeatAt(heartbeatValue));
                boolean online = instance.getHeartbeatAt() != null
                        && !instance.getHeartbeatAt().isBefore(heartbeatThreshold(now));
                instance.setOnline(online);
                instance.setStatus(online ? "ONLINE" : "OFFLINE");
                result.add(instance);
            } catch (IllegalArgumentException ignored) {
                // Ignore one malformed legacy summary without hiding the remaining instances.
            }
        }
        result.sort(Comparator.comparing(RuntimeClusterInstanceView::isOnline).reversed()
                .thenComparing(RuntimeClusterInstanceView::getHeartbeatAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    private LocalDateTime parseHeartbeatAt(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (!StringUtils.hasText(value == null ? null : String.valueOf(value))) {
            return null;
        }
        String text = String.valueOf(value).trim();
        try {
            return LocalDateTime.parse(text, DATABASE_DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException ignoredIsoFormat) {
                return null;
            }
        }
    }

    private int onlineInstanceCount(RuntimeClusterEntity entity, LocalDateTime now) {
        int count = 0;
        for (RuntimeClusterInstanceView instance : instanceViews(entity, now)) {
            if (instance.isOnline()) {
                count++;
            }
        }
        return count;
    }

    private String effectiveClusterStatus(RuntimeClusterEntity entity, int onlineInstanceCount, LocalDateTime now) {
        if (onlineInstanceCount > 0 || isRecentHeartbeat(entity.getLastHeartbeatAt(), now)) {
            return "ONLINE";
        }
        if (entity.getLastHeartbeatAt() != null || "OFFLINE".equalsIgnoreCase(entity.getStatus())) {
            return "OFFLINE";
        }
        return "UNKNOWN";
    }

    private boolean isRecentHeartbeat(LocalDateTime heartbeatAt, LocalDateTime now) {
        return heartbeatAt != null && !heartbeatAt.isBefore(heartbeatThreshold(now));
    }

    private LocalDateTime heartbeatThreshold(LocalDateTime now) {
        return now.minusSeconds(StudioConstants.WORKER_HEARTBEAT_TIMEOUT_SECONDS);
    }

    private void revalidatePlacement(Long projectId, Long clusterId) {
        if (runtimeValidationService != null && clusterId != null) {
            runtimeValidationService.revalidatePlacementScope(security.currentTenantId(), projectId, clusterId);
        }
    }
    private List<RuntimeEndpointView> endpointViews(List<RuntimeEndpointEntity> entities) {
        List<RuntimeEndpointView> result = new ArrayList<RuntimeEndpointView>();
        for (RuntimeEndpointEntity entity : entities) {
            result.add(endpointView(entity));
        }
        return result;
    }

    private RuntimeEndpointView endpointView(RuntimeEndpointEntity entity) {
        return endpointView(entity, true);
    }

    private RuntimeEndpointView endpointView(RuntimeEndpointEntity entity, boolean includeHeaderNames) {
        RuntimeEndpointView view = new RuntimeEndpointView();
        view.setId(entity.getId());
        view.setRuntimeClusterId(entity.getRuntimeClusterId());
        view.setMode(entity.getMode());
        view.setEndpointMasked(StringUtils.hasText(entity.getEndpointCiphertext())
                ? mask(decrypt(entity.getEndpointCiphertext())) : null);
        view.setHasToken(StringUtils.hasText(entity.getTokenCiphertext()));
        if (includeHeaderNames) {
            try {
                Map<String, String> headers = objectMapper.readValue(
                        decrypt(entity.getHeadersCiphertext()),
                        new TypeReference<LinkedHashMap<String, String>>() { });
                view.setHeaderNames(new ArrayList<String>(headers.keySet()));
            } catch (Exception ex) {
                view.setHeaderNames(new ArrayList<String>());
                log.warn("Failed to read runtime endpoint header names. endpointId={}", entity.getId());
            }
        }
        view.setConnectTimeoutMillis(entity.getConnectTimeoutMillis());
        view.setReadTimeoutMillis(entity.getReadTimeoutMillis());
        view.setEnabled(Integer.valueOf(1).equals(entity.getEnabled()));
        view.setLastTestedAt(entity.getLastTestedAt());
        view.setLastTestStatus(entity.getLastTestStatus());
        view.setLastTestMessage(entity.getLastTestMessage());
        return view;
    }
    private List<RuntimeClusterProjectAuthorizationView> authorizationViews(List<ProjectRuntimeClusterEntity> es){List<RuntimeClusterProjectAuthorizationView> r=new ArrayList<RuntimeClusterProjectAuthorizationView>();for(ProjectRuntimeClusterEntity e:es)r.add(authorizationView(e));return r;} private RuntimeClusterProjectAuthorizationView authorizationView(ProjectRuntimeClusterEntity e){RuntimeClusterProjectAuthorizationView v=new RuntimeClusterProjectAuthorizationView();v.setProjectId(e.getProjectId());v.setRuntimeClusterId(e.getRuntimeClusterId());v.setEnabled(Integer.valueOf(1).equals(e.getEnabled()));v.setPreferred(Integer.valueOf(1).equals(e.getPreferred()));v.setAllowManualOverride(Integer.valueOf(1).equals(e.getAllowManualOverride()));return v;}
    private String validateHttp(String value){return endpointSecurity().validate(value).toString();} private RuntimeEndpointSecurityService endpointSecurity(){return runtimeEndpointSecurityService==null?new RuntimeEndpointSecurityService(properties):runtimeEndpointSecurityService;} private RuntimeEndpointHttpClient endpointHttpClient(){return runtimeEndpointHttpClient==null?new RuntimeEndpointHttpClient():runtimeEndpointHttpClient;} private String mask(String url){if(!StringUtils.hasText(url))return null;try{URI u=URI.create(url);return u.getScheme()+"://"+u.getHost()+"/***";}catch(Exception e){return "***";}} private String decrypt(String value){return StringUtils.hasText(value)?encryption.decrypt(value):"{}";} private String writeJson(Map<String,String> values){try{return objectMapper.writeValueAsString(values==null?new LinkedHashMap<String,String>():values);}catch(Exception e){throw bad("Runtime endpoint headers cannot be serialized");}} private int timeout(Integer v,int d){return v==null?d:Math.min(Math.max(v,100),60000);} private String text(String v,String m){if(!StringUtils.hasText(v))throw bad(m);return v.trim();}private String trim(String v){return StringUtils.hasText(v)?v.trim():null;}private StudioException bad(String m){return new StudioException(StudioErrorCode.BAD_REQUEST,m);}
}
