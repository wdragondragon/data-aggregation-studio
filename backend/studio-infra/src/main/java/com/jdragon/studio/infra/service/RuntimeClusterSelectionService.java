package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.BaseDefinition;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Single server-side guard for explicit runtime placement. */
@Service
public class RuntimeClusterSelectionService {
    private final RuntimeClusterService runtimeClusterService;
    private final DatasourceClusterBindingService datasourceClusterBindingService;
    private RuntimeValidationService runtimeValidationService;

    public RuntimeClusterSelectionService(RuntimeClusterService runtimeClusterService,
                                          DatasourceClusterBindingService datasourceClusterBindingService) {
        this.runtimeClusterService = runtimeClusterService;
        this.datasourceClusterBindingService = datasourceClusterBindingService;
    }

    @Autowired
    void setRuntimeValidationService(RuntimeValidationService runtimeValidationService) {
        this.runtimeValidationService = runtimeValidationService;
    }

    public Long resolveForSave(Long projectId, Long runtimeClusterId) {
        if (runtimeClusterId == null) {
            throw runtimeClusterRequired();
        }
        RuntimeClusterEntity cluster = runtimeClusterService.requireAuthorized(projectId, runtimeClusterId);
        return cluster.getId();
    }

    /** Save-time placement guard for both new and existing resources. */
    public Long resolveForResourceSave(Long projectId,
                                       Long requestedRuntimeClusterId,
                                       Long existingRuntimeClusterId,
                                       boolean existingResource) {
        if (requestedRuntimeClusterId == null) {
            if (!existingResource) {
                throw runtimeClusterRequired();
            }
            if (existingRuntimeClusterId != null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Runtime cluster cannot be cleared once selected");
            }
            throw runtimeClusterRequired();
        }
        RuntimeClusterEntity cluster = runtimeClusterService.requireAuthorized(projectId, requestedRuntimeClusterId);
        return cluster.getId();
    }

    public void assertExplicitSelection(Long runtimeClusterId) {
        if (runtimeClusterId == null) {
            throw runtimeClusterRequired();
        }
    }

    /**
     * Runtime operations that do not belong to an already placed resource must
     * never inherit an OMS or process-local runtime implicitly.
     */
    public Long validateExplicitDatasourceSelection(Long projectId,
                                                     Long runtimeClusterId,
                                                     Collection<Long> datasourceIds) {
        assertExplicitSelection(runtimeClusterId);
        return validateDatasourceSelection(projectId, runtimeClusterId, datasourceIds);
    }

    public Long validateDatasourceSelection(Long projectId, Long runtimeClusterId, Collection<Long> datasourceIds) {
        Long selectedClusterId = resolveForSave(projectId, runtimeClusterId);
        validateDatasourceBindings(selectedClusterId, datasourceIds);
        return selectedClusterId;
    }

    public Long validateDatasourceSelectionForResourceSave(Long projectId,
                                                            Long requestedRuntimeClusterId,
                                                            Long existingRuntimeClusterId,
                                                            boolean existingResource,
                                                            Collection<Long> datasourceIds) {
        Long selectedClusterId = resolveForResourceSave(projectId, requestedRuntimeClusterId,
                existingRuntimeClusterId, existingResource);
        validateDatasourceBindings(selectedClusterId, datasourceIds);
        return selectedClusterId;
    }

    private void validateDatasourceBindings(Long selectedClusterId, Collection<Long> datasourceIds) {
        if (selectedClusterId == null || datasourceIds == null) {
            return;
        }
        Set<Long> uniqueDatasourceIds = new LinkedHashSet<Long>();
        for (Long datasourceId : datasourceIds) {
            if (datasourceId != null) {
                uniqueDatasourceIds.add(datasourceId);
            }
        }
        for (Long datasourceId : uniqueDatasourceIds) {
            datasourceClusterBindingService.assertDatasourceApplicable(datasourceId, selectedClusterId);
        }
    }

    public String runtimeClusterName(Long projectId, Long runtimeClusterId) {
        return runtimeClusterService.clusterName(runtimeClusterId);
    }

    public void assertExistingResourceRunnable(Long projectId, Long runtimeClusterId, Collection<Long> datasourceIds) {
        validateDatasourceSelection(projectId, runtimeClusterId, datasourceIds);
    }

    public void assertResourceValid(String resourceType, Long resourceId) {
        if (runtimeValidationService != null) {
            runtimeValidationService.assertResourceValid(resourceType, resourceId);
        }
    }

    public Long validateManualOverride(Long projectId, Long runtimeClusterId, Collection<Long> datasourceIds) {
        runtimeClusterService.requireManualOverrideAllowed(projectId, runtimeClusterId);
        for (Long datasourceId : datasourceIds == null ? new LinkedHashSet<Long>() : datasourceIds) {
            if (datasourceId != null) datasourceClusterBindingService.assertDatasourceApplicable(datasourceId, runtimeClusterId);
        }
        return runtimeClusterId;
    }

    public void markResourceValid(String resourceType, Long resourceId) {
        if (runtimeValidationService != null) runtimeValidationService.markResourceValid(resourceType, resourceId);
    }

    public <T extends BaseDefinition> T hydrateRuntimeValidation(String resourceType, T view) {
        if (runtimeValidationService != null) {
            runtimeValidationService.hydrate(resourceType, view);
        }
        hydratePlacementValidation(view);
        return view;
    }

    public <T extends BaseDefinition> List<T> hydrateRuntimeValidation(String resourceType, List<T> views) {
        if (runtimeValidationService != null) {
            runtimeValidationService.hydrate(resourceType, views);
        }
        if (views != null) {
            for (T view : views) {
                hydratePlacementValidation(view);
            }
        }
        return views;
    }

    private void hydratePlacementValidation(BaseDefinition view) {
        if (view == null || Boolean.FALSE.equals(view.getRuntimeValid())) {
            return;
        }
        Long runtimeClusterId = readRuntimeClusterId(view);
        if (runtimeClusterId == null) {
            view.setRuntimeValid(Boolean.FALSE);
            view.setRuntimeValidationMessage("Runtime cluster is required");
            return;
        }
        try {
            runtimeClusterService.requireAuthorized(view.getProjectId(), runtimeClusterId);
        } catch (StudioException ex) {
            view.setRuntimeValid(Boolean.FALSE);
            view.setRuntimeValidationMessage(ex.getMessage());
        }
    }

    private Long readRuntimeClusterId(BaseDefinition view) {
        try {
            Object value = view.getClass().getMethod("getRuntimeClusterId").invoke(view);
            return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private StudioException runtimeClusterRequired() {
        return new StudioException(StudioErrorCode.BAD_REQUEST, "Runtime cluster is required");
    }
}
