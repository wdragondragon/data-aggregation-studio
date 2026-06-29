package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.EnvironmentDependencyOptionView;
import com.jdragon.studio.dto.model.EnvironmentDependencyView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.ScriptEnvironmentListView;
import com.jdragon.studio.dto.model.ScriptEnvironmentOptionView;
import com.jdragon.studio.dto.model.ScriptEnvironmentView;
import com.jdragon.studio.dto.model.request.ScriptEnvironmentSaveRequest;
import com.jdragon.studio.infra.entity.EnvironmentDependencyEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentDependencyRelEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentEntity;
import com.jdragon.studio.infra.mapper.EnvironmentDependencyMapper;
import com.jdragon.studio.infra.mapper.ScriptEnvironmentDependencyRelMapper;
import com.jdragon.studio.infra.mapper.ScriptEnvironmentMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ScriptEnvironmentService {

    public static final String DEFAULT_ENVIRONMENT_CODE = "default-application";

    private final ScriptEnvironmentMapper environmentMapper;
    private final ScriptEnvironmentDependencyRelMapper relationMapper;
    private final EnvironmentDependencyMapper dependencyMapper;
    private final EnvironmentDependencyService dependencyService;
    private final StudioSecurityService securityService;
    private final ObjectProvider<ScriptEnvironmentRuntimeService> runtimeServiceProvider;

    public ScriptEnvironmentService(ScriptEnvironmentMapper environmentMapper,
                                    ScriptEnvironmentDependencyRelMapper relationMapper,
                                    EnvironmentDependencyMapper dependencyMapper,
                                    EnvironmentDependencyService dependencyService,
                                    StudioSecurityService securityService,
                                    ObjectProvider<ScriptEnvironmentRuntimeService> runtimeServiceProvider) {
        this.environmentMapper = environmentMapper;
        this.relationMapper = relationMapper;
        this.dependencyMapper = dependencyMapper;
        this.dependencyService = dependencyService;
        this.securityService = securityService;
        this.runtimeServiceProvider = runtimeServiceProvider;
    }

    public PageView<ScriptEnvironmentListView> queryPage(Integer pageNum, Integer pageSize, String keyword, Boolean enabled) {
        ensureDefaultEnvironmentId();
        int safePageNo = normalizePageNo(pageNum);
        int safePageSize = normalizePageSize(pageSize);
        String normalizedKeyword = normalizeNullableText(keyword);
        Page<ScriptEnvironmentEntity> page = new Page<ScriptEnvironmentEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<ScriptEnvironmentEntity> wrapper = new LambdaQueryWrapper<ScriptEnvironmentEntity>()
                .select(ScriptEnvironmentEntity::getId,
                        ScriptEnvironmentEntity::getTenantId,
                        ScriptEnvironmentEntity::getDeleted,
                        ScriptEnvironmentEntity::getCreatedAt,
                        ScriptEnvironmentEntity::getUpdatedAt,
                        ScriptEnvironmentEntity::getEnvironmentName,
                        ScriptEnvironmentEntity::getEnvironmentCode,
                        ScriptEnvironmentEntity::getEnabled,
                        ScriptEnvironmentEntity::getUseApplicationParent,
                        ScriptEnvironmentEntity::getEnvironmentVersion)
                .eq(ScriptEnvironmentEntity::getTenantId, securityService.currentTenantId())
                .and(hasText(normalizedKeyword), query -> query.like(ScriptEnvironmentEntity::getEnvironmentName, normalizedKeyword)
                        .or()
                        .like(ScriptEnvironmentEntity::getEnvironmentCode, normalizedKeyword)
                        .or()
                        .like(ScriptEnvironmentEntity::getDescription, normalizedKeyword))
                .eq(enabled != null, ScriptEnvironmentEntity::getEnabled, Boolean.TRUE.equals(enabled) ? Integer.valueOf(1) : Integer.valueOf(0))
                .orderByAsc(ScriptEnvironmentEntity::getEnvironmentName)
                .orderByDesc(ScriptEnvironmentEntity::getUpdatedAt)
                .orderByDesc(ScriptEnvironmentEntity::getId);
        Page<ScriptEnvironmentEntity> entityPage = environmentMapper.selectPage(page, wrapper);
        Map<Long, EnvironmentDependencyListBundle> dependencyMap = loadDependencyOptionsByEnvironment(entityPage.getRecords());
        List<ScriptEnvironmentListView> items = new ArrayList<ScriptEnvironmentListView>();
        for (ScriptEnvironmentEntity entity : entityPage.getRecords()) {
            items.add(toListView(entity, dependencyMap.get(entity.getId())));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    public List<ScriptEnvironmentOptionView> options(Boolean enabledOnly) {
        ensureDefaultEnvironmentId();
        LambdaQueryWrapper<ScriptEnvironmentEntity> wrapper = new LambdaQueryWrapper<ScriptEnvironmentEntity>()
                .select(ScriptEnvironmentEntity::getId,
                        ScriptEnvironmentEntity::getTenantId,
                        ScriptEnvironmentEntity::getDeleted,
                        ScriptEnvironmentEntity::getCreatedAt,
                        ScriptEnvironmentEntity::getUpdatedAt,
                        ScriptEnvironmentEntity::getEnvironmentName,
                        ScriptEnvironmentEntity::getEnvironmentCode,
                        ScriptEnvironmentEntity::getEnabled)
                .eq(ScriptEnvironmentEntity::getTenantId, securityService.currentTenantId())
                .eq(Boolean.TRUE.equals(enabledOnly), ScriptEnvironmentEntity::getEnabled, Integer.valueOf(1))
                .orderByAsc(ScriptEnvironmentEntity::getEnvironmentName)
                .orderByAsc(ScriptEnvironmentEntity::getId);
        List<ScriptEnvironmentOptionView> result = new ArrayList<ScriptEnvironmentOptionView>();
        for (ScriptEnvironmentEntity entity : environmentMapper.selectList(wrapper)) {
            result.add(toOptionView(entity));
        }
        return result;
    }

    public Map<Long, ScriptEnvironmentOptionView> enabledOptionMapByIds(Set<Long> environmentIds) {
        Map<Long, ScriptEnvironmentOptionView> result = new HashMap<Long, ScriptEnvironmentOptionView>();
        if (environmentIds == null || environmentIds.isEmpty()) {
            return result;
        }
        List<ScriptEnvironmentEntity> entities = environmentMapper.selectList(new LambdaQueryWrapper<ScriptEnvironmentEntity>()
                .select(ScriptEnvironmentEntity::getId,
                        ScriptEnvironmentEntity::getTenantId,
                        ScriptEnvironmentEntity::getDeleted,
                        ScriptEnvironmentEntity::getCreatedAt,
                        ScriptEnvironmentEntity::getUpdatedAt,
                        ScriptEnvironmentEntity::getEnvironmentName,
                        ScriptEnvironmentEntity::getEnvironmentCode,
                        ScriptEnvironmentEntity::getEnabled)
                .eq(ScriptEnvironmentEntity::getTenantId, securityService.currentTenantId())
                .eq(ScriptEnvironmentEntity::getEnabled, Integer.valueOf(1))
                .in(ScriptEnvironmentEntity::getId, environmentIds));
        for (ScriptEnvironmentEntity entity : entities) {
            result.put(entity.getId(), toOptionView(entity));
        }
        return result;
    }

    public ScriptEnvironmentView get(Long id) {
        return toView(requireEntity(id), true);
    }

    @Transactional
    public ScriptEnvironmentView saveOrUpdateCheck(ScriptEnvironmentSaveRequest request) {
        requireManager();
        validateRequest(request);
        ScriptEnvironmentEntity entity = request.getId() == null
                ? new ScriptEnvironmentEntity()
                : requireEntity(request.getId());
        ensureNotDefaultCodeMutation(entity, request);
        String environmentName = normalizeText(request.getEnvironmentName());
        String environmentCode = normalizeText(request.getEnvironmentCode());
        ensureUnique(environmentName, environmentCode, entity.getId());
        entity.setTenantId(securityService.currentTenantId());
        entity.setEnvironmentName(environmentName);
        entity.setEnvironmentCode(environmentCode);
        entity.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? Integer.valueOf(0) : Integer.valueOf(1));
        entity.setUseApplicationParent(Boolean.FALSE.equals(request.getUseApplicationParent()) ? Integer.valueOf(0) : Integer.valueOf(1));
        entity.setEnvironmentVersion(entity.getEnvironmentVersion() == null ? Long.valueOf(1L) : Long.valueOf(entity.getEnvironmentVersion().longValue() + 1L));
        entity.setDescription(normalizeNullableText(request.getDescription()));
        if (entity.getId() == null) {
            environmentMapper.insert(entity);
        } else {
            environmentMapper.updateById(entity);
            deleteRelations(entity.getId());
        }
        saveRelations(entity.getId(), normalizeDependencyIds(request.getDependencyIds()));
        invalidateEnvironment(entity.getId());
        return get(entity.getId());
    }

    @Transactional
    public ScriptEnvironmentView enable(Long id) {
        return updateEnabled(id, true);
    }

    @Transactional
    public ScriptEnvironmentView disable(Long id) {
        ScriptEnvironmentEntity entity = requireEntity(id);
        if (DEFAULT_ENVIRONMENT_CODE.equals(entity.getEnvironmentCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Default application environment cannot be disabled");
        }
        return updateEnabled(id, false);
    }

    @Transactional
    public ScriptEnvironmentView refresh(Long id) {
        requireManager();
        ScriptEnvironmentEntity entity = requireEntity(id);
        incrementVersion(entity);
        invalidateEnvironment(id);
        return get(id);
    }

    public Long ensureDefaultEnvironmentId() {
        ScriptEnvironmentEntity existing = findByCode(DEFAULT_ENVIRONMENT_CODE);
        if (existing != null) {
            return existing.getId();
        }
        ScriptEnvironmentEntity entity = new ScriptEnvironmentEntity();
        entity.setTenantId(securityService.currentTenantId());
        entity.setEnvironmentName("默认应用运行环境");
        entity.setEnvironmentCode(DEFAULT_ENVIRONMENT_CODE);
        entity.setEnabled(Integer.valueOf(1));
        entity.setUseApplicationParent(Integer.valueOf(1));
        entity.setEnvironmentVersion(Long.valueOf(1L));
        entity.setDescription("默认使用 Studio 应用类加载器的 Java 脚本运行环境");
        environmentMapper.insert(entity);
        return entity.getId();
    }

    public ScriptEnvironmentEntity requireEnabledEnvironment(Long id) {
        Long environmentId = id == null ? ensureDefaultEnvironmentId() : id;
        ScriptEnvironmentEntity entity = requireEntity(environmentId);
        if (entity.getEnabled() == null || entity.getEnabled().intValue() != 1) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Script environment is disabled: " + environmentId);
        }
        return entity;
    }

    public List<EnvironmentDependencyEntity> listEnabledDependencies(Long environmentId) {
        Long resolvedEnvironmentId = environmentId == null ? ensureDefaultEnvironmentId() : environmentId;
        List<ScriptEnvironmentDependencyRelEntity> relations = relationMapper.selectList(new LambdaQueryWrapper<ScriptEnvironmentDependencyRelEntity>()
                .eq(ScriptEnvironmentDependencyRelEntity::getTenantId, securityService.currentTenantId())
                .eq(ScriptEnvironmentDependencyRelEntity::getEnvironmentId, resolvedEnvironmentId)
                .orderByAsc(ScriptEnvironmentDependencyRelEntity::getSortOrder)
                .orderByAsc(ScriptEnvironmentDependencyRelEntity::getId));
        List<EnvironmentDependencyEntity> result = new ArrayList<EnvironmentDependencyEntity>();
        for (ScriptEnvironmentDependencyRelEntity relation : relations) {
            EnvironmentDependencyEntity dependency = dependencyMapper.selectById(relation.getDependencyId());
            if (dependency != null
                    && securityService.currentTenantId().equals(dependency.getTenantId())
                    && dependency.getEnabled() != null
                    && dependency.getEnabled().intValue() == 1) {
                result.add(dependency);
            }
        }
        return result;
    }

    private ScriptEnvironmentView updateEnabled(Long id, boolean enabled) {
        requireManager();
        ScriptEnvironmentEntity entity = requireEntity(id);
        entity.setEnabled(enabled ? Integer.valueOf(1) : Integer.valueOf(0));
        incrementVersion(entity);
        invalidateEnvironment(id);
        return get(id);
    }

    private void incrementVersion(ScriptEnvironmentEntity entity) {
        entity.setEnvironmentVersion(Long.valueOf(entity.getEnvironmentVersion() == null ? 1L : entity.getEnvironmentVersion().longValue() + 1L));
        environmentMapper.updateById(entity);
    }

    private void invalidateEnvironment(Long environmentId) {
        ScriptEnvironmentRuntimeService runtimeService = runtimeServiceProvider.getIfAvailable();
        if (runtimeService != null) {
            runtimeService.clearEnvironment(environmentId);
        }
        JavaDataDevelopmentExecutor.clearCompiledCache(environmentId, null);
    }

    private void saveRelations(Long environmentId, List<Long> dependencyIds) {
        int sortOrder = 1;
        for (Long dependencyId : dependencyIds) {
            EnvironmentDependencyEntity dependency = dependencyService.requireEnabledDependency(dependencyId);
            ScriptEnvironmentDependencyRelEntity relation = new ScriptEnvironmentDependencyRelEntity();
            relation.setTenantId(securityService.currentTenantId());
            relation.setEnvironmentId(environmentId);
            relation.setDependencyId(dependency.getId());
            relation.setSortOrder(Integer.valueOf(sortOrder++));
            relationMapper.insert(relation);
        }
    }

    private void deleteRelations(Long environmentId) {
        relationMapper.delete(new LambdaQueryWrapper<ScriptEnvironmentDependencyRelEntity>()
                .eq(ScriptEnvironmentDependencyRelEntity::getTenantId, securityService.currentTenantId())
                .eq(ScriptEnvironmentDependencyRelEntity::getEnvironmentId, environmentId));
    }

    private ScriptEnvironmentView toView(ScriptEnvironmentEntity entity, boolean includeDependencies) {
        ScriptEnvironmentView view = new ScriptEnvironmentView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setEnvironmentName(entity.getEnvironmentName());
        view.setEnvironmentCode(entity.getEnvironmentCode());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setUseApplicationParent(entity.getUseApplicationParent() == null || entity.getUseApplicationParent().intValue() == 1);
        view.setEnvironmentVersion(entity.getEnvironmentVersion());
        view.setDescription(entity.getDescription());
        if (includeDependencies) {
            for (EnvironmentDependencyEntity dependency : listDependencies(entity.getId())) {
                view.getDependencyIds().add(dependency.getId());
                view.getDependencies().add(dependencyService.toReferenceView(dependency));
            }
        }
        return view;
    }

    private ScriptEnvironmentListView toListView(ScriptEnvironmentEntity entity, EnvironmentDependencyListBundle dependencies) {
        ScriptEnvironmentListView view = new ScriptEnvironmentListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setEnvironmentName(entity.getEnvironmentName());
        view.setEnvironmentCode(entity.getEnvironmentCode());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setUseApplicationParent(entity.getUseApplicationParent() == null || entity.getUseApplicationParent().intValue() == 1);
        view.setEnvironmentVersion(entity.getEnvironmentVersion());
        if (dependencies != null) {
            view.setDependencyIds(dependencies.dependencyIds);
            view.setDependencies(dependencies.dependencies);
        }
        return view;
    }

    private ScriptEnvironmentOptionView toOptionView(ScriptEnvironmentEntity entity) {
        ScriptEnvironmentOptionView view = new ScriptEnvironmentOptionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setEnvironmentName(entity.getEnvironmentName());
        view.setEnvironmentCode(entity.getEnvironmentCode());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        return view;
    }

    private Map<Long, EnvironmentDependencyListBundle> loadDependencyOptionsByEnvironment(List<ScriptEnvironmentEntity> environments) {
        Map<Long, EnvironmentDependencyListBundle> result = new HashMap<Long, EnvironmentDependencyListBundle>();
        if (environments == null || environments.isEmpty()) {
            return result;
        }
        List<Long> environmentIds = new ArrayList<Long>();
        for (ScriptEnvironmentEntity environment : environments) {
            if (environment.getId() != null) {
                environmentIds.add(environment.getId());
                result.put(environment.getId(), new EnvironmentDependencyListBundle());
            }
        }
        if (environmentIds.isEmpty()) {
            return result;
        }
        List<ScriptEnvironmentDependencyRelEntity> relations = relationMapper.selectList(new LambdaQueryWrapper<ScriptEnvironmentDependencyRelEntity>()
                .select(ScriptEnvironmentDependencyRelEntity::getEnvironmentId,
                        ScriptEnvironmentDependencyRelEntity::getDependencyId,
                        ScriptEnvironmentDependencyRelEntity::getSortOrder,
                        ScriptEnvironmentDependencyRelEntity::getId)
                .eq(ScriptEnvironmentDependencyRelEntity::getTenantId, securityService.currentTenantId())
                .in(ScriptEnvironmentDependencyRelEntity::getEnvironmentId, environmentIds)
                .orderByAsc(ScriptEnvironmentDependencyRelEntity::getEnvironmentId)
                .orderByAsc(ScriptEnvironmentDependencyRelEntity::getSortOrder)
                .orderByAsc(ScriptEnvironmentDependencyRelEntity::getId));
        Set<Long> dependencyIds = new LinkedHashSet<Long>();
        for (ScriptEnvironmentDependencyRelEntity relation : relations) {
            if (relation.getDependencyId() != null) {
                dependencyIds.add(relation.getDependencyId());
            }
        }
        Map<Long, EnvironmentDependencyOptionView> dependenciesById = loadDependencyOptionsById(dependencyIds);
        for (ScriptEnvironmentDependencyRelEntity relation : relations) {
            EnvironmentDependencyListBundle bundle = result.get(relation.getEnvironmentId());
            if (bundle == null || relation.getDependencyId() == null) {
                continue;
            }
            bundle.dependencyIds.add(relation.getDependencyId());
            EnvironmentDependencyOptionView dependency = dependenciesById.get(relation.getDependencyId());
            if (dependency != null) {
                bundle.dependencies.add(dependency);
            }
        }
        return result;
    }

    private Map<Long, EnvironmentDependencyOptionView> loadDependencyOptionsById(Set<Long> dependencyIds) {
        Map<Long, EnvironmentDependencyOptionView> result = new HashMap<Long, EnvironmentDependencyOptionView>();
        if (dependencyIds == null || dependencyIds.isEmpty()) {
            return result;
        }
        List<EnvironmentDependencyEntity> dependencies = dependencyMapper.selectList(new LambdaQueryWrapper<EnvironmentDependencyEntity>()
                .select(EnvironmentDependencyEntity::getId,
                        EnvironmentDependencyEntity::getTenantId,
                        EnvironmentDependencyEntity::getDeleted,
                        EnvironmentDependencyEntity::getCreatedAt,
                        EnvironmentDependencyEntity::getUpdatedAt,
                        EnvironmentDependencyEntity::getName,
                        EnvironmentDependencyEntity::getVersion,
                        EnvironmentDependencyEntity::getScriptType,
                        EnvironmentDependencyEntity::getEnabled)
                .eq(EnvironmentDependencyEntity::getTenantId, securityService.currentTenantId())
                .in(EnvironmentDependencyEntity::getId, dependencyIds));
        for (EnvironmentDependencyEntity dependency : dependencies) {
            result.put(dependency.getId(), toDependencyOptionView(dependency));
        }
        return result;
    }

    private EnvironmentDependencyOptionView toDependencyOptionView(EnvironmentDependencyEntity entity) {
        EnvironmentDependencyOptionView view = new EnvironmentDependencyOptionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setVersion(entity.getVersion());
        view.setScriptType(hasText(entity.getScriptType()) ? entity.getScriptType() : "JAVA");
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        return view;
    }

    private List<EnvironmentDependencyEntity> listDependencies(Long environmentId) {
        List<ScriptEnvironmentDependencyRelEntity> relations = relationMapper.selectList(new LambdaQueryWrapper<ScriptEnvironmentDependencyRelEntity>()
                .eq(ScriptEnvironmentDependencyRelEntity::getTenantId, securityService.currentTenantId())
                .eq(ScriptEnvironmentDependencyRelEntity::getEnvironmentId, environmentId)
                .orderByAsc(ScriptEnvironmentDependencyRelEntity::getSortOrder)
                .orderByAsc(ScriptEnvironmentDependencyRelEntity::getId));
        List<EnvironmentDependencyEntity> result = new ArrayList<EnvironmentDependencyEntity>();
        for (ScriptEnvironmentDependencyRelEntity relation : relations) {
            EnvironmentDependencyEntity dependency = dependencyMapper.selectById(relation.getDependencyId());
            if (dependency != null && securityService.currentTenantId().equals(dependency.getTenantId())) {
                result.add(dependency);
            }
        }
        return result;
    }

    private ScriptEnvironmentEntity requireEntity(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Script environment id is required");
        }
        ScriptEnvironmentEntity entity = environmentMapper.selectById(id);
        if (entity == null || !securityService.currentTenantId().equals(entity.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Script environment not found: " + id);
        }
        return entity;
    }

    private ScriptEnvironmentEntity findByCode(String code) {
        return environmentMapper.selectOne(new LambdaQueryWrapper<ScriptEnvironmentEntity>()
                .eq(ScriptEnvironmentEntity::getTenantId, securityService.currentTenantId())
                .eq(ScriptEnvironmentEntity::getEnvironmentCode, code)
                .last("limit 1"));
    }

    private void validateRequest(ScriptEnvironmentSaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Script environment request is required");
        }
        if (!hasText(request.getEnvironmentName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Environment name is required");
        }
        if (!hasText(request.getEnvironmentCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Environment code is required");
        }
    }

    private void ensureNotDefaultCodeMutation(ScriptEnvironmentEntity entity, ScriptEnvironmentSaveRequest request) {
        if (entity.getId() == null) {
            return;
        }
        if (DEFAULT_ENVIRONMENT_CODE.equals(entity.getEnvironmentCode())
                && !DEFAULT_ENVIRONMENT_CODE.equals(normalizeText(request.getEnvironmentCode()))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Default application environment code cannot be changed");
        }
    }

    private void ensureUnique(String environmentName, String environmentCode, Long selfId) {
        List<ScriptEnvironmentEntity> duplicates = environmentMapper.selectList(new LambdaQueryWrapper<ScriptEnvironmentEntity>()
                .eq(ScriptEnvironmentEntity::getTenantId, securityService.currentTenantId())
                .and(wrapper -> wrapper.eq(ScriptEnvironmentEntity::getEnvironmentName, environmentName)
                        .or()
                        .eq(ScriptEnvironmentEntity::getEnvironmentCode, environmentCode)));
        for (ScriptEnvironmentEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Environment name or code already exists");
        }
    }

    private List<Long> normalizeDependencyIds(List<Long> dependencyIds) {
        Set<Long> uniqueIds = new LinkedHashSet<Long>();
        if (dependencyIds != null) {
            for (Long dependencyId : dependencyIds) {
                if (dependencyId != null) {
                    uniqueIds.add(dependencyId);
                }
            }
        }
        return new ArrayList<Long>(uniqueIds);
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

    private static final class EnvironmentDependencyListBundle {
        private final List<Long> dependencyIds = new ArrayList<Long>();
        private final List<EnvironmentDependencyOptionView> dependencies = new ArrayList<EnvironmentDependencyOptionView>();
    }
}
