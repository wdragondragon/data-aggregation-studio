package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
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

    public WorkerAuthorizationService(ProjectWorkerBindingMapper projectWorkerBindingMapper,
                                      WorkerLeaseMapper workerLeaseMapper) {
        this.projectWorkerBindingMapper = projectWorkerBindingMapper;
        this.workerLeaseMapper = workerLeaseMapper;
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

    public boolean isWorkerAuthorizedForProject(String tenantId, Long projectId, String workerCode) {
        if (!hasText(workerCode)) {
            return false;
        }
        for (WorkerLeaseEntity worker : listAvailableWorkers(tenantId, projectId)) {
            if (workerCode.equalsIgnoreCase(worker.getWorkerCode())) {
                return true;
            }
        }
        return false;
    }

    public List<WorkerLeaseEntity> listAvailableWorkers(String tenantId, Long projectId) {
        if (!hasText(tenantId)) {
            return new ArrayList<WorkerLeaseEntity>();
        }
        List<String> workerCodes = boundWorkerCodes(tenantId, projectId);
        if (workerCodes.isEmpty()) {
            return new ArrayList<WorkerLeaseEntity>();
        }
        LocalDateTime heartbeatThreshold = LocalDateTime.now()
                .minusSeconds(StudioConstants.WORKER_HEARTBEAT_TIMEOUT_SECONDS);
        return workerLeaseMapper.selectList(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getTenantId, tenantId)
                .eq(WorkerLeaseEntity::getStatus, StudioConstants.WORKER_STATUS_ONLINE)
                .ge(WorkerLeaseEntity::getLastHeartbeatAt, heartbeatThreshold)
                .in(WorkerLeaseEntity::getWorkerCode, workerCodes)
                .orderByDesc(WorkerLeaseEntity::getLastHeartbeatAt)
                .orderByAsc(WorkerLeaseEntity::getWorkerCode));
    }

    public List<String> boundWorkerCodes(String tenantId, Long projectId) {
        if (!hasText(tenantId) || projectId == null) {
            return new ArrayList<String>();
        }
        List<ProjectWorkerBindingEntity> bindings = projectWorkerBindingMapper.selectList(new LambdaQueryWrapper<ProjectWorkerBindingEntity>()
                .eq(ProjectWorkerBindingEntity::getTenantId, tenantId)
                .eq(ProjectWorkerBindingEntity::getProjectId, projectId)
                .eq(ProjectWorkerBindingEntity::getEnabled, 1)
                .orderByAsc(ProjectWorkerBindingEntity::getWorkerCode));
        Set<String> workerCodes = new LinkedHashSet<String>();
        for (ProjectWorkerBindingEntity binding : bindings) {
            if (hasText(binding.getWorkerCode())) {
                workerCodes.add(binding.getWorkerCode().trim());
            }
        }
        return new ArrayList<String>(workerCodes);
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
