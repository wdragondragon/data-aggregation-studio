package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.system.SystemProjectMemberRequestView;
import com.jdragon.studio.dto.model.system.SystemProjectMemberView;
import com.jdragon.studio.dto.model.system.SystemProjectView;
import com.jdragon.studio.dto.model.system.SystemProjectWorkerView;
import com.jdragon.studio.dto.model.system.SystemTenantMemberView;
import com.jdragon.studio.dto.model.system.SystemTenantView;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.ProjectMemberRequestEntity;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.TenantEntity;
import com.jdragon.studio.infra.entity.TenantMemberEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;

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
                                                       ProjectWorkerBindingEntity binding) {
        SystemProjectWorkerView view = new SystemProjectWorkerView();
        view.setId(binding == null ? null : binding.getId());
        view.setTenantId(tenantId);
        view.setProjectId(projectId);
        view.setDeleted(Boolean.FALSE);
        view.setCreatedAt(binding == null ? null : binding.getCreatedAt());
        view.setUpdatedAt(binding == null ? null : binding.getUpdatedAt());
        view.setWorkerCode(lease != null ? lease.getWorkerCode() : binding == null ? null : binding.getWorkerCode());
        view.setWorkerKind(lease == null ? null : lease.getWorkerKind());
        view.setHostName(lease == null ? null : lease.getHostName());
        view.setStatus(lease == null ? "OFFLINE" : lease.getStatus());
        view.setLastHeartbeatAt(lease == null ? null : lease.getLastHeartbeatAt());
        view.setBoundToProject(binding != null && binding.getEnabled() != null && binding.getEnabled() == 1);
        view.setEnabled(binding != null && binding.getEnabled() != null && binding.getEnabled() == 1);
        return view;
    }
}
