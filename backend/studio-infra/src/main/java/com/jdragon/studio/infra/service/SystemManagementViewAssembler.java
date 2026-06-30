package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.system.SystemProjectMemberRequestView;
import com.jdragon.studio.dto.model.system.SystemProjectMemberView;
import com.jdragon.studio.dto.model.system.SystemProjectOptionView;
import com.jdragon.studio.dto.model.system.SystemProjectView;
import com.jdragon.studio.dto.model.system.ResourceShareView;
import com.jdragon.studio.dto.model.system.ShareResourceOptionView;
import com.jdragon.studio.dto.model.system.SystemProjectWorkerView;
import com.jdragon.studio.dto.model.system.SystemTenantMemberView;
import com.jdragon.studio.dto.model.system.SystemTenantView;
import com.jdragon.studio.dto.model.system.SystemWorkerInstanceView;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.ProjectMemberRequestEntity;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.ResourceShareEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.TenantEntity;
import com.jdragon.studio.infra.entity.TenantMemberEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

final class SystemManagementViewAssembler {

    private SystemManagementViewAssembler() {
    }

    static SystemTenantView toTenantView(TenantEntity entity) {
        SystemTenantView view = new SystemTenantView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setTenantCode(entity.getTenantCode());
        view.setTenantName(entity.getTenantName());
        view.setDescription(entity.getDescription());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
        return view;
    }

    static SystemProjectView toProjectView(ProjectEntity entity) {
        SystemProjectView view = new SystemProjectView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setProjectCode(entity.getProjectCode());
        view.setProjectName(entity.getProjectName());
        view.setDescription(entity.getDescription());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
        view.setDefaultProject(entity.getDefaultProject() != null && entity.getDefaultProject() == 1);
        return view;
    }

    static SystemProjectOptionView toProjectOptionView(ProjectEntity entity) {
        SystemProjectOptionView view = new SystemProjectOptionView();
        view.setId(entity.getId());
        view.setProjectName(entity.getProjectName());
        return view;
    }

    static SystemTenantMemberView toTenantMemberView(TenantMemberEntity member, StudioUserEntity user) {
        SystemTenantMemberView view = new SystemTenantMemberView();
        view.setId(member.getId());
        view.setTenantId(member.getTenantId());
        view.setDeleted(member.getDeleted() != null && member.getDeleted() == 1);
        view.setCreatedAt(member.getCreatedAt());
        view.setUpdatedAt(member.getUpdatedAt());
        view.setUserId(member.getUserId());
        view.setUsername(user == null ? null : user.getUsername());
        view.setDisplayName(user == null ? null : user.getDisplayName());
        view.setRoleCode(member.getRoleCode());
        view.setStatus(member.getStatus());
        return view;
    }

    static SystemProjectMemberView toProjectMemberView(ProjectMemberEntity member,
                                                       ProjectEntity project,
                                                       StudioUserEntity user) {
        SystemProjectMemberView view = new SystemProjectMemberView();
        view.setId(member.getId());
        view.setTenantId(member.getTenantId());
        view.setProjectId(member.getProjectId());
        view.setDeleted(member.getDeleted() != null && member.getDeleted() == 1);
        view.setCreatedAt(member.getCreatedAt());
        view.setUpdatedAt(member.getUpdatedAt());
        view.setUserId(member.getUserId());
        view.setUsername(user == null ? null : user.getUsername());
        view.setDisplayName(user == null ? null : user.getDisplayName());
        view.setProjectName(project == null ? null : project.getProjectName());
        view.setRoleCode(member.getRoleCode());
        view.setStatus(member.getStatus());
        return view;
    }

    static SystemProjectMemberRequestView toProjectMemberRequestView(ProjectMemberRequestEntity request,
                                                                     ProjectEntity project,
                                                                     StudioUserEntity user,
                                                                     StudioUserEntity inviter,
                                                                     StudioUserEntity reviewer) {
        SystemProjectMemberRequestView view = new SystemProjectMemberRequestView();
        view.setId(request.getId());
        view.setTenantId(request.getTenantId());
        view.setProjectId(request.getProjectId());
        view.setDeleted(request.getDeleted() != null && request.getDeleted() == 1);
        view.setCreatedAt(request.getCreatedAt());
        view.setUpdatedAt(request.getUpdatedAt());
        view.setUserId(request.getUserId());
        view.setUsername(user == null ? null : user.getUsername());
        view.setDisplayName(user == null ? null : user.getDisplayName());
        view.setProjectName(project == null ? null : project.getProjectName());
        view.setRequestType(request.getRequestType());
        view.setStatus(request.getStatus());
        view.setInviterUserId(request.getInviterUserId());
        view.setInviterUsername(inviter == null ? null : inviter.getUsername());
        view.setReviewerUserId(request.getReviewerUserId());
        view.setReviewerUsername(reviewer == null ? null : reviewer.getUsername());
        view.setReason(request.getReason());
        view.setReviewComment(request.getReviewComment());
        return view;
    }

    static SystemProjectWorkerView toProjectWorkerView(Long projectId,
                                                       String tenantId,
                                                       WorkerLeaseEntity lease,
                                                       ProjectWorkerBindingEntity binding,
                                                       Integer onlineInstanceCount,
                                                       Integer recentInstanceCount,
                                                       LocalDateTime latestHeartbeatAt,
                                                       String displayStatus,
                                                       List<SystemWorkerInstanceView> instances) {
        SystemProjectWorkerView view = new SystemProjectWorkerView();
        view.setId(binding == null ? null : binding.getId());
        view.setTenantId(tenantId);
        view.setProjectId(projectId);
        view.setDeleted(Boolean.FALSE);
        view.setCreatedAt(binding == null ? null : binding.getCreatedAt());
        view.setUpdatedAt(binding == null ? null : binding.getUpdatedAt());
        String workerGroupCode = resolveWorkerGroupCode(lease, binding);
        view.setWorkerGroupCode(workerGroupCode);
        view.setWorkerCode(lease != null && hasText(lease.getWorkerCode()) ? lease.getWorkerCode() : workerGroupCode);
        view.setWorkerInstanceId(lease == null ? null : lease.getInstanceId());
        view.setWorkerKind(lease == null ? null : lease.getWorkerKind());
        view.setHostName(lease == null ? null : lease.getHostName());
        view.setPodName(lease == null ? null : lease.getPodName());
        view.setNodeName(lease == null ? null : lease.getNodeName());
        view.setOnlineInstanceCount(onlineInstanceCount == null ? 0 : onlineInstanceCount);
        view.setRecentInstanceCount(recentInstanceCount == null ? 0 : recentInstanceCount);
        view.setStatus(displayStatus == null ? (lease == null ? "NO_INSTANCE" : lease.getStatus()) : displayStatus);
        view.setDisplayStatus(view.getStatus());
        view.setLastHeartbeatAt(latestHeartbeatAt == null && lease != null ? lease.getLastHeartbeatAt() : latestHeartbeatAt);
        view.setLatestHeartbeatAt(view.getLastHeartbeatAt());
        view.setBoundToProject(binding != null);
        view.setEnabled(binding != null && binding.getEnabled() != null && binding.getEnabled() == 1);
        view.setInstances(instances == null ? Collections.<SystemWorkerInstanceView>emptyList() : instances);
        return view;
    }

    static ResourceShareView toResourceShareView(ResourceShareEntity share,
                                                 ProjectEntity sourceProject,
                                                 ProjectEntity targetProject,
                                                 ShareResourceOptionView resource) {
        ResourceShareView view = new ResourceShareView();
        view.setId(share.getId());
        view.setTenantId(share.getTenantId());
        view.setProjectId(share.getSourceProjectId());
        view.setDeleted(share.getDeleted() != null && share.getDeleted() == 1);
        view.setCreatedAt(share.getCreatedAt());
        view.setUpdatedAt(share.getUpdatedAt());
        view.setSourceProjectId(share.getSourceProjectId());
        view.setSourceProjectName(sourceProject == null ? null : sourceProject.getProjectName());
        view.setTargetProjectId(share.getTargetProjectId());
        view.setTargetProjectName(targetProject == null ? null : targetProject.getProjectName());
        view.setResourceType(share.getResourceType());
        view.setResourceId(share.getResourceId());
        view.setResourceLabel(resource == null ? null : resource.getLabel());
        view.setResourceName(resource == null ? null : resource.getName());
        view.setResourceCode(resource == null ? null : resource.getCode());
        view.setResourceStatus(resource == null ? null : resource.getStatus());
        view.setSharedByUserId(share.getSharedByUserId());
        view.setEnabled(share.getEnabled() != null && share.getEnabled() == 1);
        return view;
    }

    static SystemWorkerInstanceView toWorkerInstanceView(WorkerLeaseEntity lease, boolean online) {
        SystemWorkerInstanceView view = new SystemWorkerInstanceView();
        view.setWorkerGroupCode(lease == null ? null : lease.getWorkerGroupCode());
        view.setWorkerCode(lease == null ? null : lease.getWorkerCode());
        view.setWorkerInstanceId(lease == null ? null : lease.getInstanceId());
        view.setWorkerKind(lease == null ? null : lease.getWorkerKind());
        view.setHostName(lease == null ? null : lease.getHostName());
        view.setPodName(lease == null ? null : lease.getPodName());
        view.setNodeName(lease == null ? null : lease.getNodeName());
        view.setStatus(online ? "ONLINE" : "OFFLINE");
        view.setLastHeartbeatAt(lease == null ? null : lease.getLastHeartbeatAt());
        view.setLeaseExpiresAt(lease == null ? null : lease.getLeaseExpiresAt());
        view.setOnline(online);
        return view;
    }

    private static String resolveWorkerGroupCode(WorkerLeaseEntity lease, ProjectWorkerBindingEntity binding) {
        if (lease != null && hasText(lease.getWorkerGroupCode())) {
            return lease.getWorkerGroupCode();
        }
        if (binding != null && hasText(binding.getWorkerGroupCode())) {
            return binding.getWorkerGroupCode();
        }
        if (lease != null && hasText(lease.getWorkerCode())) {
            return lease.getWorkerCode();
        }
        return binding == null ? null : binding.getWorkerCode();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
