package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.EnvironmentDependencyView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.EnvironmentDependencySaveRequest;
import com.jdragon.studio.infra.entity.EnvironmentDependencyEntity;
import com.jdragon.studio.infra.mapper.EnvironmentDependencyMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class EnvironmentDependencyService {

    private final EnvironmentDependencyMapper dependencyMapper;
    private final StudioSecurityService securityService;
    private final ObjectProvider<ScriptEnvironmentRuntimeService> runtimeServiceProvider;

    public EnvironmentDependencyService(EnvironmentDependencyMapper dependencyMapper,
                                        StudioSecurityService securityService,
                                        ObjectProvider<ScriptEnvironmentRuntimeService> runtimeServiceProvider) {
        this.dependencyMapper = dependencyMapper;
        this.securityService = securityService;
        this.runtimeServiceProvider = runtimeServiceProvider;
    }

    public PageView<EnvironmentDependencyView> queryPage(Integer pageNum, Integer pageSize, String keyword, Boolean enabled) {
        int safePageNo = normalizePageNo(pageNum);
        int safePageSize = normalizePageSize(pageSize);
        String normalizedKeyword = normalizeNullableText(keyword);
        Page<EnvironmentDependencyEntity> page = new Page<EnvironmentDependencyEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<EnvironmentDependencyEntity> wrapper = new LambdaQueryWrapper<EnvironmentDependencyEntity>()
                .eq(EnvironmentDependencyEntity::getTenantId, securityService.currentTenantId())
                .and(hasText(normalizedKeyword), query -> query.like(EnvironmentDependencyEntity::getName, normalizedKeyword)
                        .or()
                        .like(EnvironmentDependencyEntity::getVersion, normalizedKeyword)
                        .or()
                        .like(EnvironmentDependencyEntity::getArtifactUrl, normalizedKeyword)
                        .or()
                        .like(EnvironmentDependencyEntity::getDescription, normalizedKeyword))
                .eq(enabled != null, EnvironmentDependencyEntity::getEnabled, Boolean.TRUE.equals(enabled) ? Integer.valueOf(1) : Integer.valueOf(0))
                .orderByDesc(EnvironmentDependencyEntity::getUpdatedAt)
                .orderByDesc(EnvironmentDependencyEntity::getId);
        Page<EnvironmentDependencyEntity> entityPage = dependencyMapper.selectPage(page, wrapper);
        List<EnvironmentDependencyView> items = new ArrayList<EnvironmentDependencyView>();
        for (EnvironmentDependencyEntity entity : entityPage.getRecords()) {
            items.add(toView(entity));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    public List<EnvironmentDependencyView> options(Boolean enabledOnly) {
        LambdaQueryWrapper<EnvironmentDependencyEntity> wrapper = new LambdaQueryWrapper<EnvironmentDependencyEntity>()
                .eq(EnvironmentDependencyEntity::getTenantId, securityService.currentTenantId())
                .eq(Boolean.TRUE.equals(enabledOnly), EnvironmentDependencyEntity::getEnabled, Integer.valueOf(1))
                .orderByAsc(EnvironmentDependencyEntity::getName)
                .orderByAsc(EnvironmentDependencyEntity::getVersion)
                .orderByAsc(EnvironmentDependencyEntity::getId);
        List<EnvironmentDependencyView> result = new ArrayList<EnvironmentDependencyView>();
        for (EnvironmentDependencyEntity entity : dependencyMapper.selectList(wrapper)) {
            result.add(toView(entity));
        }
        return result;
    }

    public EnvironmentDependencyView get(Long id) {
        return toView(requireEntity(id));
    }

    @Transactional
    public EnvironmentDependencyView saveOrUpdateCheck(EnvironmentDependencySaveRequest request) {
        requireManager();
        validateRequest(request);
        EnvironmentDependencyEntity entity = request.getId() == null
                ? new EnvironmentDependencyEntity()
                : requireEntity(request.getId());
        ensureUniqueNameVersion(normalizeText(request.getName()), normalizeNullableText(request.getVersion()), entity.getId());
        entity.setTenantId(securityService.currentTenantId());
        entity.setName(normalizeText(request.getName()));
        entity.setVersion(normalizeNullableText(request.getVersion()));
        entity.setArtifactUrl(normalizeText(request.getArtifactUrl()));
        entity.setArtifactType(normalizeArtifactType(request.getArtifactType()));
        entity.setChecksum(normalizeNullableText(request.getChecksum()));
        entity.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? Integer.valueOf(0) : Integer.valueOf(1));
        entity.setDescription(normalizeNullableText(request.getDescription()));
        if (entity.getId() == null) {
            dependencyMapper.insert(entity);
        } else {
            dependencyMapper.updateById(entity);
        }
        invalidateAllEnvironments();
        return get(entity.getId());
    }

    @Transactional
    public EnvironmentDependencyView enable(Long id) {
        return updateEnabled(id, true);
    }

    @Transactional
    public EnvironmentDependencyView disable(Long id) {
        return updateEnabled(id, false);
    }

    @Transactional
    public void delete(Long id) {
        requireManager();
        dependencyMapper.deleteById(requireEntity(id).getId());
        invalidateAllEnvironments();
    }

    EnvironmentDependencyEntity requireEnabledDependency(Long id) {
        EnvironmentDependencyEntity entity = requireEntity(id);
        if (entity.getEnabled() == null || entity.getEnabled().intValue() != 1) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Environment dependency is disabled: " + id);
        }
        return entity;
    }

    EnvironmentDependencyView toView(EnvironmentDependencyEntity entity) {
        EnvironmentDependencyView view = new EnvironmentDependencyView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setVersion(entity.getVersion());
        view.setArtifactUrl(entity.getArtifactUrl());
        view.setArtifactType(entity.getArtifactType());
        view.setChecksum(entity.getChecksum());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setDescription(entity.getDescription());
        return view;
    }

    private EnvironmentDependencyView updateEnabled(Long id, boolean enabled) {
        requireManager();
        EnvironmentDependencyEntity entity = requireEntity(id);
        entity.setEnabled(enabled ? Integer.valueOf(1) : Integer.valueOf(0));
        dependencyMapper.updateById(entity);
        invalidateAllEnvironments();
        return get(id);
    }

    private void invalidateAllEnvironments() {
        ScriptEnvironmentRuntimeService runtimeService = runtimeServiceProvider.getIfAvailable();
        if (runtimeService != null) {
            runtimeService.clearAll();
        }
        JavaDataDevelopmentExecutor.clearCompiledCache();
    }

    private EnvironmentDependencyEntity requireEntity(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Environment dependency id is required");
        }
        EnvironmentDependencyEntity entity = dependencyMapper.selectById(id);
        if (entity == null || !securityService.currentTenantId().equals(entity.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Environment dependency not found: " + id);
        }
        return entity;
    }

    private void validateRequest(EnvironmentDependencySaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Environment dependency request is required");
        }
        if (!hasText(request.getName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency name is required");
        }
        if (!hasText(request.getArtifactUrl())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Artifact URL is required");
        }
        normalizeArtifactType(request.getArtifactType());
    }

    private void ensureUniqueNameVersion(String name, String version, Long selfId) {
        EnvironmentDependencyEntity duplicate = dependencyMapper.selectOne(new LambdaQueryWrapper<EnvironmentDependencyEntity>()
                .eq(EnvironmentDependencyEntity::getTenantId, securityService.currentTenantId())
                .eq(EnvironmentDependencyEntity::getName, name)
                .eq(version != null, EnvironmentDependencyEntity::getVersion, version)
                .isNull(version == null, EnvironmentDependencyEntity::getVersion)
                .last("limit 1"));
        if (duplicate == null || (selfId != null && selfId.equals(duplicate.getId()))) {
            return;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency name and version already exist");
    }

    private String normalizeArtifactType(String artifactType) {
        String normalized = normalizeText(artifactType);
        if (normalized == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Artifact type is required");
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!"JAR".equals(normalized) && !"ZIP".equals(normalized)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Artifact type must be JAR or ZIP");
        }
        return normalized;
    }

    private void requireManager() {
        if (!securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN,
                StudioConstants.ROLE_TENANT_ADMIN,
                StudioConstants.ROLE_ADMIN,
                StudioConstants.ROLE_PROJECT_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo.intValue() < 1 ? 1 : pageNo.intValue();
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize.intValue() < 1) {
            return 20;
        }
        return Math.min(pageSize.intValue(), 200);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalizeText(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeNullableText(String value) {
        String normalized = normalizeText(value);
        return hasText(normalized) ? normalized : null;
    }
}
