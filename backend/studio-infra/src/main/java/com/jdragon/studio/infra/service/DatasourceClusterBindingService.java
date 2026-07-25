package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.RuntimeClusterView;
import com.jdragon.studio.infra.entity.DatasourceClusterBindingEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maintains the explicit datasource-to-runtime-cluster applicability relation. */
@Service
public class DatasourceClusterBindingService {
    private final DatasourceClusterBindingMapper bindingMapper;
    private final RuntimeClusterMapper runtimeClusterMapper;
    private final RuntimeClusterService runtimeClusterService;
    private final StudioSecurityService securityService;

    public DatasourceClusterBindingService(DatasourceClusterBindingMapper bindingMapper,
                                           RuntimeClusterMapper runtimeClusterMapper,
                                           RuntimeClusterService runtimeClusterService,
                                           StudioSecurityService securityService) {
        this.bindingMapper = bindingMapper;
        this.runtimeClusterMapper = runtimeClusterMapper;
        this.runtimeClusterService = runtimeClusterService;
        this.securityService = securityService;
    }

    /**
     * Validates a save request and returns normalized IDs. Every datasource
     * must have at least one explicit runtime-cluster binding.
     */
    public List<Long> normalizeForSave(Long projectId, Collection<Long> requestedClusterIds) {
        return normalizeForSave(projectId, null, requestedClusterIds);
    }

    public List<Long> normalizeForSave(Long projectId,
                                       Long existingDatasourceId,
                                       Collection<Long> requestedClusterIds) {
        String tenantId = securityService.currentTenantId();
        Long configuredCount = runtimeClusterMapper.selectCount(new LambdaQueryWrapper<RuntimeClusterEntity>()
                .eq(RuntimeClusterEntity::getTenantId, tenantId));
        Set<Long> requested = normalizeIds(requestedClusterIds);
        Set<Long> existing = activeClusterIds(tenantId, existingDatasourceId);
        if (requested.isEmpty()) {
            if (existingDatasourceId == null) {
                throw badRequest("At least one applicable runtime cluster is required for a new datasource");
            }
            if (!existing.isEmpty()) {
                throw badRequest("Datasource applicable clusters cannot be cleared once selected");
            }
            throw badRequest("At least one applicable runtime cluster is required");
        }
        if (configuredCount == null || configuredCount.longValue() == 0L) {
            throw badRequest("Runtime clusters have not been configured");
        }
        for (Long clusterId : requested) {
            runtimeClusterService.requireAuthorized(projectId, clusterId);
        }
        return new ArrayList<Long>(requested);
    }

    @Transactional
    public void replaceBindings(String tenantId, Long datasourceId, Collection<Long> runtimeClusterIds) {
        if (datasourceId == null) {
            return;
        }
        Set<Long> desired = normalizeIds(runtimeClusterIds);
        List<DatasourceClusterBindingEntity> existing = bindingMapper.selectList(new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                .eq(DatasourceClusterBindingEntity::getTenantId, tenantId)
                .eq(DatasourceClusterBindingEntity::getDatasourceId, datasourceId));
        Map<Long, DatasourceClusterBindingEntity> byClusterId = new LinkedHashMap<Long, DatasourceClusterBindingEntity>();
        for (DatasourceClusterBindingEntity item : existing) {
            if (item.getRuntimeClusterId() != null) {
                byClusterId.put(item.getRuntimeClusterId(), item);
            }
        }
        for (Long clusterId : desired) {
            DatasourceClusterBindingEntity item = byClusterId.remove(clusterId);
            if (item == null) {
                item = new DatasourceClusterBindingEntity();
                item.setTenantId(tenantId);
                item.setDatasourceId(datasourceId);
                item.setRuntimeClusterId(clusterId);
                item.setEnabled(1);
                bindingMapper.insert(item);
            } else if (!Integer.valueOf(1).equals(item.getEnabled())) {
                item.setEnabled(1);
                bindingMapper.updateById(item);
            }
        }
        for (DatasourceClusterBindingEntity item : byClusterId.values()) {
            if (Integer.valueOf(1).equals(item.getEnabled())) {
                item.setEnabled(0);
                bindingMapper.updateById(item);
            }
        }
    }

    @Transactional
    public void deleteBindings(String tenantId, Long datasourceId) {
        if (datasourceId == null) {
            return;
        }
        bindingMapper.delete(new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                .eq(DatasourceClusterBindingEntity::getTenantId, tenantId)
                .eq(DatasourceClusterBindingEntity::getDatasourceId, datasourceId));
    }

    public Map<Long, List<Long>> listApplicableClusterIds(Collection<Long> datasourceIds) {
        return listApplicableClusterIds(securityService.currentTenantId(), datasourceIds);
    }

    public Map<Long, List<Long>> listApplicableClusterIds(String tenantId, Collection<Long> datasourceIds) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return new LinkedHashMap<Long, List<Long>>();
        }
        List<DatasourceClusterBindingEntity> bindings = bindingMapper.selectList(new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                .eq(DatasourceClusterBindingEntity::getTenantId, tenantId)
                .in(DatasourceClusterBindingEntity::getDatasourceId, datasourceIds)
                .eq(DatasourceClusterBindingEntity::getEnabled, 1)
                .orderByAsc(DatasourceClusterBindingEntity::getRuntimeClusterId));
        Map<Long, List<Long>> result = new LinkedHashMap<Long, List<Long>>();
        for (DatasourceClusterBindingEntity binding : bindings) {
            List<Long> clusterIds = result.get(binding.getDatasourceId());
            if (clusterIds == null) {
                clusterIds = new ArrayList<Long>();
                result.put(binding.getDatasourceId(), clusterIds);
            }
            clusterIds.add(binding.getRuntimeClusterId());
        }
        return result;
    }

    public Map<Long, List<RuntimeClusterView>> listApplicableClusters(Collection<Long> datasourceIds) {
        Map<Long, List<Long>> idsByDatasource = listApplicableClusterIds(datasourceIds);
        if (idsByDatasource.isEmpty()) {
            return new LinkedHashMap<Long, List<RuntimeClusterView>>();
        }
        Set<Long> clusterIds = new LinkedHashSet<Long>();
        for (List<Long> ids : idsByDatasource.values()) {
            clusterIds.addAll(ids);
        }
        List<RuntimeClusterEntity> clusters = runtimeClusterMapper.selectList(new LambdaQueryWrapper<RuntimeClusterEntity>()
                .eq(RuntimeClusterEntity::getTenantId, securityService.currentTenantId())
                .in(RuntimeClusterEntity::getId, clusterIds));
        Map<Long, RuntimeClusterView> viewById = new LinkedHashMap<Long, RuntimeClusterView>();
        for (RuntimeClusterEntity cluster : clusters) {
            RuntimeClusterView view = new RuntimeClusterView();
            view.setId(cluster.getId());
            view.setCode(cluster.getCode());
            view.setName(cluster.getName());
            view.setEnabled(Integer.valueOf(1).equals(cluster.getEnabled()));
            view.setStatus(cluster.getStatus());
            viewById.put(cluster.getId(), view);
        }
        Map<Long, List<RuntimeClusterView>> result = new LinkedHashMap<Long, List<RuntimeClusterView>>();
        for (Map.Entry<Long, List<Long>> entry : idsByDatasource.entrySet()) {
            List<RuntimeClusterView> views = new ArrayList<RuntimeClusterView>();
            for (Long clusterId : entry.getValue()) {
                RuntimeClusterView view = viewById.get(clusterId);
                if (view != null) {
                    views.add(view);
                }
            }
            result.put(entry.getKey(), views);
        }
        return result;
    }

    /** Filters IDs by an explicitly selected runtime cluster. */
    public Set<Long> filterApplicableDatasourceIds(Long projectId, Long runtimeClusterId, Collection<Long> datasourceIds) {
        Set<Long> candidates = normalizeIds(datasourceIds);
        if (runtimeClusterId == null || candidates.isEmpty()) {
            return candidates;
        }
        runtimeClusterService.requireAuthorized(projectId, runtimeClusterId);
        List<DatasourceClusterBindingEntity> bindings = bindingMapper.selectList(new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                .eq(DatasourceClusterBindingEntity::getTenantId, securityService.currentTenantId())
                .eq(DatasourceClusterBindingEntity::getRuntimeClusterId, runtimeClusterId)
                .eq(DatasourceClusterBindingEntity::getEnabled, 1)
                .in(DatasourceClusterBindingEntity::getDatasourceId, candidates));
        Set<Long> result = new LinkedHashSet<Long>();
        for (DatasourceClusterBindingEntity binding : bindings) {
            result.add(binding.getDatasourceId());
        }
        return result;
    }

    public void assertDatasourceApplicable(Long datasourceId, Long runtimeClusterId) {
        if (datasourceId == null) {
            return;
        }
        if (runtimeClusterId == null) {
            throw badRequest("Runtime cluster is required");
        }
        Long count = bindingMapper.selectCount(new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                .eq(DatasourceClusterBindingEntity::getTenantId, securityService.currentTenantId())
                .eq(DatasourceClusterBindingEntity::getDatasourceId, datasourceId)
                .eq(DatasourceClusterBindingEntity::getRuntimeClusterId, runtimeClusterId)
                .eq(DatasourceClusterBindingEntity::getEnabled, 1));
        if (count == null || count.longValue() == 0L) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR, "Datasource is not applicable to the selected runtime cluster");
        }
    }

    /**
     * @deprecated Runtime placement is always explicit. Kept temporarily for
     * binary/source compatibility with callers being migrated.
     */
    @Deprecated
    private Set<Long> normalizeIds(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return new LinkedHashSet<Long>();
        }
        Set<Long> result = new LinkedHashSet<Long>();
        for (Long value : values) {
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private Set<Long> activeClusterIds(String tenantId, Long datasourceId) {
        Set<Long> result = new LinkedHashSet<Long>();
        if (datasourceId == null) {
            return result;
        }
        List<DatasourceClusterBindingEntity> bindings = bindingMapper.selectList(
                new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                        .eq(DatasourceClusterBindingEntity::getTenantId, tenantId)
                        .eq(DatasourceClusterBindingEntity::getDatasourceId, datasourceId)
                        .eq(DatasourceClusterBindingEntity::getEnabled, 1));
        if (bindings == null) {
            return result;
        }
        for (DatasourceClusterBindingEntity binding : bindings) {
            if (binding != null && binding.getRuntimeClusterId() != null) {
                result.add(binding.getRuntimeClusterId());
            }
        }
        return result;
    }

    private StudioException badRequest(String message) {
        return new StudioException(StudioErrorCode.BAD_REQUEST, message);
    }
}
