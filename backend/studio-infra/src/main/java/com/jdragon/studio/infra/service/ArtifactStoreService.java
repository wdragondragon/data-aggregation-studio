package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.ArtifactStoreView;
import com.jdragon.studio.dto.model.request.ArtifactStoreSaveRequest;
import com.jdragon.studio.infra.entity.ArtifactStoreEntity;
import com.jdragon.studio.infra.entity.EnvironmentDependencyEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentEntity;
import com.jdragon.studio.infra.mapper.ArtifactStoreMapper;
import com.jdragon.studio.infra.mapper.EnvironmentDependencyMapper;
import com.jdragon.studio.infra.mapper.ScriptEnvironmentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tenant control-plane registry for physical artifact repositories. */
@Service
public class ArtifactStoreService {
    private final ArtifactStoreMapper mapper;
    private final EnvironmentDependencyMapper dependencyMapper;
    private final ScriptEnvironmentMapper environmentMapper;
    private final StudioSecurityService security;
    private final EncryptionService encryption;
    private final RuntimeEndpointSecurityService endpointSecurity;
    private final RuntimeEndpointHttpClient httpClient;

    public ArtifactStoreService(ArtifactStoreMapper mapper, EnvironmentDependencyMapper dependencyMapper,
                                ScriptEnvironmentMapper environmentMapper,
                                StudioSecurityService security, EncryptionService encryption,
                                RuntimeEndpointSecurityService endpointSecurity, RuntimeEndpointHttpClient httpClient) {
        this.mapper = mapper;
        this.dependencyMapper = dependencyMapper;
        this.environmentMapper = environmentMapper;
        this.security = security;
        this.encryption = encryption;
        this.endpointSecurity = endpointSecurity;
        this.httpClient = httpClient;
    }

    public List<ArtifactStoreView> list(boolean enabledOnly) {
        if (!enabledOnly) requireManage();
        LambdaQueryWrapper<ArtifactStoreEntity> query = new LambdaQueryWrapper<ArtifactStoreEntity>()
                .and(item -> item.eq(ArtifactStoreEntity::getTenantId, security.currentTenantId())
                        .or().eq(ArtifactStoreEntity::getScopeType, "PLATFORM"))
                .orderByAsc(ArtifactStoreEntity::getStoreName);
        if (enabledOnly) query.eq(ArtifactStoreEntity::getEnabled, 1);
        List<ArtifactStoreView> result = new ArrayList<ArtifactStoreView>();
        for (ArtifactStoreEntity entity : mapper.selectList(query)) result.add(view(entity));
        return result;
    }

    public ArtifactStoreView get(Long id) {
        requireManage();
        return view(require(id));
    }

    public Map<String, Object> test(Long id) {
        requireManage();
        ArtifactStoreEntity entity = require(id);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("provider", entity.getProvider());
        try {
            if ("OSS".equals(entity.getProvider())) {
                com.aliyun.oss.OSS client = new com.aliyun.oss.OSSClientBuilder()
                        .build(entity.getEndpoint(), username(entity), secret(entity));
                try {
                    if (!client.doesBucketExist(entity.getBucket())) throw new IllegalStateException("Bucket does not exist");
                } finally { client.shutdown(); }
            } else {
                String target = StringUtils.hasText(entity.getSimpleIndexUrl()) ? entity.getSimpleIndexUrl() : entity.getEndpoint();
                Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
                String username = username(entity);
                String secret = secret(entity);
                if (StringUtils.hasText(username) || StringUtils.hasText(secret)) {
                    String token = java.util.Base64.getEncoder().encodeToString(
                            ((username == null ? "" : username) + ":" + (secret == null ? "" : secret))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    headers.put("Authorization", java.util.Collections.singletonList("Basic " + token));
                }
                int status = httpClient.execute(endpointSecurity.validateRequestTarget(target), "GET", headers,
                        null, 5000, 10000, 1024 * 1024).getStatusCode();
                if (status < 200 || status >= 400) throw new IllegalStateException("Repository returned HTTP " + status);
            }
            result.put("success", true);
            result.put("message", "Connection succeeded");
        } catch (Exception ex) {
            result.put("success", false);
            result.put("message", ex.getMessage());
        }
        return result;
    }

    @Transactional
    public ArtifactStoreView save(ArtifactStoreSaveRequest request) {
        requireManage();
        if (request == null) throw bad("Artifact store payload is required");
        ArtifactStoreEntity entity = request.getId() == null ? new ArtifactStoreEntity() : require(request.getId());
        requireWritable(entity);
        String code = required(request.getStoreCode(), "Store code is required").toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9][A-Z0-9_-]{0,63}")) throw bad("Store code is invalid");
        String provider = required(request.getProvider(), "Provider is required").toUpperCase(Locale.ROOT);
        if (!provider.equals("OSS") && !provider.equals("GITLAB") && !provider.equals("PYPISERVER")
                && !provider.equals("NEXUS") && !provider.equals("PYPI")) {
            throw bad("Provider must be OSS, GITLAB, PYPISERVER, NEXUS or PYPI");
        }
        String scopeType = StringUtils.hasText(request.getScopeType())
                ? request.getScopeType().trim().toUpperCase(Locale.ROOT) : "TENANT";
        if (!"PLATFORM".equals(scopeType) && !"TENANT".equals(scopeType)) throw bad("Scope must be PLATFORM or TENANT");
        if ("PLATFORM".equals(scopeType) && !security.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Only super administrators can manage platform stores");
        }
        Long duplicate = mapper.selectCount(new LambdaQueryWrapper<ArtifactStoreEntity>()
                .eq(ArtifactStoreEntity::getTenantId, security.currentTenantId())
                .eq(ArtifactStoreEntity::getStoreCode, code)
                .ne(entity.getId() != null, ArtifactStoreEntity::getId, entity.getId()));
        if (duplicate != null && duplicate > 0) throw bad("Store code already exists");
        entity.setTenantId(security.currentTenantId());
        entity.setStoreName(required(request.getStoreName(), "Store name is required"));
        entity.setStoreCode(code);
        entity.setProvider(provider);
        entity.setScopeType(scopeType);
        entity.setConfigVersion(Long.valueOf(entity.getConfigVersion() == null
                ? 1L : entity.getConfigVersion().longValue() + 1L));
        entity.setEndpoint(uri(request.getEndpoint(), "Endpoint is required"));
        entity.setUploadUrl(optionalUri(request.getUploadUrl()));
        entity.setSimpleIndexUrl(optionalUri(request.getSimpleIndexUrl()));
        entity.setBucket(trim(request.getBucket()));
        entity.setRegion(trim(request.getRegion()));
        entity.setRootPrefix(trim(request.getRootPrefix()));
        if (Boolean.FALSE.equals(request.getVerifySsl())) throw bad("TLS certificate verification cannot be disabled");
        entity.setVerifySsl(1);
        entity.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? 0 : 1);
        entity.setDescription(trim(request.getDescription()));
        if (Boolean.TRUE.equals(request.getClearCredential())) {
            entity.setUsernameCiphertext(null);
            entity.setSecretCiphertext(null);
        } else {
            if (StringUtils.hasText(request.getUsername())) entity.setUsernameCiphertext(encryption.encrypt(request.getUsername().trim()));
            if (StringUtils.hasText(request.getSecret())) entity.setSecretCiphertext(encryption.encrypt(request.getSecret()));
        }
        if ("OSS".equals(provider) && !StringUtils.hasText(entity.getBucket())) throw bad("OSS bucket is required");
        if (("NEXUS".equals(provider) || "PYPI".equals(provider))
                && !StringUtils.hasText(entity.getSimpleIndexUrl())) {
            throw bad("PyPI/Nexus simple index URL is required");
        }
        if (entity.getId() == null) mapper.insert(entity); else mapper.updateById(entity);
        return view(entity);
    }

    @Transactional
    public ArtifactStoreView setEnabled(Long id, boolean enabled) {
        requireManage();
        ArtifactStoreEntity entity = require(id);
        requireWritable(entity);
        entity.setEnabled(enabled ? 1 : 0);
        entity.setConfigVersion(Long.valueOf(entity.getConfigVersion() == null
                ? 1L : entity.getConfigVersion().longValue() + 1L));
        mapper.updateById(entity);
        return view(entity);
    }

    @Transactional
    public void delete(Long id) {
        requireManage();
        ArtifactStoreEntity entity = require(id);
        requireWritable(entity);
        if (Integer.valueOf(1).equals(entity.getEnabled())) throw bad("Disable the artifact store before deleting it");
        Long referenceCount = dependencyMapper.selectCount(new LambdaQueryWrapper<EnvironmentDependencyEntity>()
                .eq(EnvironmentDependencyEntity::getArtifactStoreId, id));
        if (referenceCount != null && referenceCount.longValue() > 0L) {
            throw bad("Artifact store is still referenced by " + referenceCount + " dependency version(s)");
        }
        Long environmentReferenceCount = environmentMapper.selectCount(new LambdaQueryWrapper<ScriptEnvironmentEntity>()
                .eq(ScriptEnvironmentEntity::getPythonRepositoryId, id));
        if (environmentReferenceCount != null && environmentReferenceCount.longValue() > 0L) {
            throw bad("Artifact store is still referenced by " + environmentReferenceCount + " script environment(s)");
        }
        mapper.deleteById(id);
    }

    public ArtifactStoreEntity requireEnabled(Long id) {
        ArtifactStoreEntity entity = require(id);
        if (!Integer.valueOf(1).equals(entity.getEnabled())) throw bad("Artifact store is disabled");
        return entity;
    }

    public ArtifactStoreEntity requireAccessible(Long id) {
        return require(id);
    }

    public String username(ArtifactStoreEntity entity) {
        return StringUtils.hasText(entity.getUsernameCiphertext()) ? encryption.decrypt(entity.getUsernameCiphertext()) : null;
    }

    public String secret(ArtifactStoreEntity entity) {
        return StringUtils.hasText(entity.getSecretCiphertext()) ? encryption.decrypt(entity.getSecretCiphertext()) : null;
    }

    private ArtifactStoreEntity require(Long id) {
        ArtifactStoreEntity entity = id == null ? null : mapper.selectById(id);
        if (entity == null || (!security.currentTenantId().equals(entity.getTenantId())
                && !"PLATFORM".equals(entity.getScopeType()))) throw bad("Artifact store not found");
        return entity;
    }

    private void requireWritable(ArtifactStoreEntity entity) {
        if (entity != null && "PLATFORM".equals(entity.getScopeType())
                && !security.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Only super administrators can modify platform stores");
        }
    }

    private ArtifactStoreView view(ArtifactStoreEntity entity) {
        ArtifactStoreView view = new ArtifactStoreView();
        view.setId(entity.getId()); view.setStoreName(entity.getStoreName()); view.setStoreCode(entity.getStoreCode());
        view.setProvider(entity.getProvider()); view.setScopeType(entity.getScopeType());
        view.setConfigVersion(entity.getConfigVersion());
        view.setEndpoint(entity.getEndpoint()); view.setUploadUrl(entity.getUploadUrl());
        view.setSimpleIndexUrl(entity.getSimpleIndexUrl()); view.setBucket(entity.getBucket()); view.setRegion(entity.getRegion());
        view.setRootPrefix(entity.getRootPrefix()); view.setHasUsername(StringUtils.hasText(entity.getUsernameCiphertext()));
        view.setHasSecret(StringUtils.hasText(entity.getSecretCiphertext())); view.setVerifySsl(!Integer.valueOf(0).equals(entity.getVerifySsl()));
        view.setEnabled(Integer.valueOf(1).equals(entity.getEnabled())); view.setDescription(entity.getDescription());
        view.setCreatedAt(entity.getCreatedAt()); view.setUpdatedAt(entity.getUpdatedAt());
        return view;
    }

    private String uri(String value, String message) {
        String normalized = required(value, message);
        try {
            URI parsed = URI.create(normalized);
            if (!"http".equalsIgnoreCase(parsed.getScheme()) && !"https".equalsIgnoreCase(parsed.getScheme())) throw bad(message);
            if (parsed.getHost() == null || parsed.getUserInfo() != null) throw bad(message);
            return parsed.toString();
        } catch (IllegalArgumentException ex) { throw bad(message); }
    }
    private String optionalUri(String value) { return StringUtils.hasText(value) ? uri(value, "Repository URL is invalid") : null; }
    private String required(String value, String message) { if (!StringUtils.hasText(value)) throw bad(message); return value.trim(); }
    private String trim(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private StudioException bad(String message) { return new StudioException(StudioErrorCode.BAD_REQUEST, message); }
    private void requireManage() {
        if (!security.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Artifact store management requires tenant administrator permission");
        }
    }
}
