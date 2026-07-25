package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.ProjectRuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.ProjectRuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class WorkerAuthorizationService {

    private final ProjectWorkerBindingMapper projectWorkerBindingMapper;
    private final WorkerLeaseMapper workerLeaseMapper;
    private final RuntimeClusterMapper runtimeClusterMapper;
    private final ProjectRuntimeClusterMapper projectRuntimeClusterMapper;

    public WorkerAuthorizationService(ProjectWorkerBindingMapper projectWorkerBindingMapper,
                                      WorkerLeaseMapper workerLeaseMapper,
                                      RuntimeClusterMapper runtimeClusterMapper,
                                      ProjectRuntimeClusterMapper projectRuntimeClusterMapper) {
        this.projectWorkerBindingMapper = projectWorkerBindingMapper;
        this.workerLeaseMapper = workerLeaseMapper;
        this.runtimeClusterMapper = runtimeClusterMapper;
        this.projectRuntimeClusterMapper = projectRuntimeClusterMapper;
    }

    public boolean hasAvailableWorker(String tenantId, Long projectId) {
        return !listAvailableWorkers(tenantId, projectId).isEmpty();
    }

    public void assertProjectHasAvailableWorker(String tenantId, Long projectId) {
        if (hasAvailableWorker(tenantId, projectId)) {
            return;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST,
                "No authorized online worker is available for the current project");
    }

    public boolean isWorkerAuthorizedForProject(String tenantId, Long projectId, String workerGroupCode) {
        if (!hasText(workerGroupCode)) {
            return false;
        }
        String normalizedWorkerGroupCode = workerGroupCode.trim();
        for (WorkerLeaseEntity worker : listAvailableWorkers(tenantId, projectId)) {
            if (normalizedWorkerGroupCode.equalsIgnoreCase(resolveWorkerGroupCode(worker))) {
                return true;
            }
        }
        return false;
    }

    public boolean isRuntimeClusterAuthorizedForProject(String tenantId, Long projectId, Long runtimeClusterId) {
        if (!hasText(tenantId) || projectId == null || runtimeClusterId == null) {
            return false;
        }
        Long clusterCount = runtimeClusterMapper.selectCount(new LambdaQueryWrapper<RuntimeClusterEntity>()
                .eq(RuntimeClusterEntity::getId, runtimeClusterId)
                .eq(RuntimeClusterEntity::getTenantId, tenantId.trim())
                .eq(RuntimeClusterEntity::getEnabled, 1));
        if (clusterCount == null || clusterCount.longValue() == 0L) {
            return false;
        }
        return isProjectRuntimeClusterGrantEnabled(tenantId, projectId, runtimeClusterId);
    }

    public boolean isProjectRuntimeClusterGrantEnabled(String tenantId, Long projectId, Long runtimeClusterId) {
        if (!hasText(tenantId) || projectId == null || runtimeClusterId == null) {
            return false;
        }
        Long authorizationCount = projectRuntimeClusterMapper.selectCount(
                new LambdaQueryWrapper<ProjectRuntimeClusterEntity>()
                        .eq(ProjectRuntimeClusterEntity::getTenantId, tenantId.trim())
                        .eq(ProjectRuntimeClusterEntity::getProjectId, projectId)
                        .eq(ProjectRuntimeClusterEntity::getRuntimeClusterId, runtimeClusterId)
                        .eq(ProjectRuntimeClusterEntity::getEnabled, 1));
        return authorizationCount != null && authorizationCount.longValue() > 0L;
    }

    public List<WorkerLeaseEntity> listAvailableWorkers(String tenantId, Long projectId) {
        if (!hasText(tenantId)) {
            return new ArrayList<WorkerLeaseEntity>();
        }
        List<String> workerGroupCodes = boundWorkerGroupCodes(tenantId, projectId);
        if (workerGroupCodes.isEmpty()) {
            return new ArrayList<WorkerLeaseEntity>();
        }
        LocalDateTime heartbeatThreshold = LocalDateTime.now()
                .minusSeconds(StudioConstants.WORKER_HEARTBEAT_TIMEOUT_SECONDS);
        return workerLeaseMapper.selectList(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getTenantId, tenantId)
                .eq(WorkerLeaseEntity::getStatus, StudioConstants.WORKER_STATUS_ONLINE)
                .ge(WorkerLeaseEntity::getLastHeartbeatAt, heartbeatThreshold)
                .and(wrapper -> wrapper.in(WorkerLeaseEntity::getWorkerGroupCode, workerGroupCodes)
                        .or(nested -> nested.isNull(WorkerLeaseEntity::getWorkerGroupCode)
                                .in(WorkerLeaseEntity::getWorkerCode, workerGroupCodes)))
                .orderByDesc(WorkerLeaseEntity::getLastHeartbeatAt)
                .orderByAsc(WorkerLeaseEntity::getWorkerGroupCode)
                .orderByAsc(WorkerLeaseEntity::getWorkerCode));
    }

    public List<String> boundWorkerGroupCodes(String tenantId, Long projectId) {
        if (!hasText(tenantId) || projectId == null) {
            return new ArrayList<String>();
        }
        List<ProjectWorkerBindingEntity> bindings = projectWorkerBindingMapper.selectList(new LambdaQueryWrapper<ProjectWorkerBindingEntity>()
                .eq(ProjectWorkerBindingEntity::getTenantId, tenantId)
                .eq(ProjectWorkerBindingEntity::getProjectId, projectId)
                .eq(ProjectWorkerBindingEntity::getEnabled, 1)
                .orderByAsc(ProjectWorkerBindingEntity::getWorkerGroupCode)
                .orderByAsc(ProjectWorkerBindingEntity::getWorkerCode));
        Set<String> workerGroupCodes = new LinkedHashSet<String>();
        for (ProjectWorkerBindingEntity binding : bindings) {
            String workerGroupCode = resolveWorkerGroupCode(binding);
            if (hasText(workerGroupCode)) {
                workerGroupCodes.add(workerGroupCode.trim());
            }
        }
        return new ArrayList<String>(workerGroupCodes);
    }

    public List<String> boundWorkerCodes(String tenantId, Long projectId) {
        return boundWorkerGroupCodes(tenantId, projectId);
    }

    private String resolveWorkerGroupCode(ProjectWorkerBindingEntity binding) {
        if (binding == null) {
            return null;
        }
        if (hasText(binding.getWorkerGroupCode())) {
            return binding.getWorkerGroupCode();
        }
        return binding.getWorkerCode();
    }

    private String resolveWorkerGroupCode(WorkerLeaseEntity lease) {
        if (lease == null) {
            return null;
        }
        if (hasText(lease.getWorkerGroupCode())) {
            return lease.getWorkerGroupCode();
        }
        return lease.getWorkerCode();
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
