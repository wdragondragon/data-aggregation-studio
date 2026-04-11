package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.WorkspaceAccessOverviewView;
import com.jdragon.studio.dto.model.WorkspaceAccessProjectView;
import com.jdragon.studio.dto.model.WorkspaceAccessRequestView;
import com.jdragon.studio.dto.model.WorkspaceAccessTenantGroupView;
import com.jdragon.studio.dto.model.request.WorkspaceAccessApplyRequest;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.ProjectMemberRequestEntity;
import com.jdragon.studio.infra.entity.TenantEntity;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberRequestMapper;
import com.jdragon.studio.infra.mapper.TenantMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkspaceAccessService {

    private final TenantMapper tenantMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectMemberRequestMapper projectMemberRequestMapper;
    private final StudioSecurityService securityService;
    private final NotificationService notificationService;

    public WorkspaceAccessService(TenantMapper tenantMapper,
                                  ProjectMapper projectMapper,
                                  ProjectMemberMapper projectMemberMapper,
                                  ProjectMemberRequestMapper projectMemberRequestMapper,
                                  StudioSecurityService securityService,
                                  NotificationService notificationService) {
        this.tenantMapper = tenantMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectMemberRequestMapper = projectMemberRequestMapper;
        this.securityService = securityService;
        this.notificationService = notificationService;
    }

    public WorkspaceAccessOverviewView overview() {
        Long currentUserId = requireCurrentUserId();
        List<ProjectMemberEntity> activeMemberships = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getUserId, currentUserId)
                .eq(ProjectMemberEntity::getStatus, StudioConstants.MEMBER_STATUS_ACTIVE));
        Set<Long> joinedProjectIds = new LinkedHashSet<Long>();
        for (ProjectMemberEntity membership : activeMemberships) {
            if (membership.getProjectId() != null) {
                joinedProjectIds.add(membership.getProjectId());
            }
        }

        List<ProjectMemberRequestEntity> requests = projectMemberRequestMapper.selectList(new LambdaQueryWrapper<ProjectMemberRequestEntity>()
                .eq(ProjectMemberRequestEntity::getUserId, currentUserId)
                .orderByDesc(ProjectMemberRequestEntity::getCreatedAt)
                .orderByDesc(ProjectMemberRequestEntity::getId));
        Map<Long, ProjectMemberRequestEntity> latestPendingRequestByProject = new LinkedHashMap<Long, ProjectMemberRequestEntity>();
        Set<Long> projectIds = new LinkedHashSet<Long>();
        for (ProjectMemberRequestEntity request : requests) {
            if (request.getProjectId() != null) {
                projectIds.add(request.getProjectId());
            }
            if (request.getProjectId() != null
                    && StudioConstants.MEMBER_REQUEST_PENDING.equalsIgnoreCase(request.getStatus())
                    && !latestPendingRequestByProject.containsKey(request.getProjectId())) {
                latestPendingRequestByProject.put(request.getProjectId(), request);
            }
        }

        List<ProjectEntity> enabledProjects = projectMapper.selectList(new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getEnabled, Integer.valueOf(1))
                .orderByAsc(ProjectEntity::getTenantId)
                .orderByDesc(ProjectEntity::getDefaultProject)
                .orderByAsc(ProjectEntity::getProjectName));
        for (ProjectEntity project : enabledProjects) {
            if (project.getId() != null) {
                projectIds.add(project.getId());
            }
        }
        Map<Long, ProjectEntity> projectMap = loadProjectMap(projectIds);
        Map<String, TenantEntity> tenantMap = loadTenantMap(projectMap.values());

        WorkspaceAccessOverviewView overview = new WorkspaceAccessOverviewView();
        overview.setTenantGroups(buildTenantGroups(enabledProjects, joinedProjectIds, latestPendingRequestByProject, tenantMap));
        overview.setRequests(buildRequestViews(requests, projectMap, tenantMap));
        return overview;
    }

    @Transactional
    public WorkspaceAccessRequestView apply(WorkspaceAccessApplyRequest request) {
        Long currentUserId = requireCurrentUserId();
        ProjectEntity project = requireEnabledProject(request == null ? null : request.getProjectId());
        ensureNotActiveMember(project.getId(), currentUserId);
        ensureNoPendingRequest(project.getId(), currentUserId);

        ProjectMemberRequestEntity entity = new ProjectMemberRequestEntity();
        entity.setTenantId(project.getTenantId());
        entity.setProjectId(project.getId());
        entity.setUserId(currentUserId);
        entity.setRequestType(StudioConstants.MEMBER_REQUEST_APPLY);
        entity.setStatus(StudioConstants.MEMBER_REQUEST_PENDING);
        entity.setReason(hasText(request == null ? null : request.getReason()) ? request.getReason().trim() : null);
        projectMemberRequestMapper.insert(entity);

        notifyApprovers(project, entity);
        return toRequestView(entity, project, loadTenant(project.getTenantId()));
    }

    @Transactional
    public WorkspaceAccessRequestView cancel(Long requestId) {
        Long currentUserId = requireCurrentUserId();
        ProjectMemberRequestEntity entity = requireOwnPendingRequest(requestId, currentUserId);
        entity.setStatus(StudioConstants.MEMBER_REQUEST_CANCELLED);
        entity.setReviewComment("Cancelled by applicant");
        projectMemberRequestMapper.updateById(entity);
        ProjectEntity project = requireProject(entity.getProjectId());
        return toRequestView(entity, project, loadTenant(project.getTenantId()));
    }

    private List<WorkspaceAccessTenantGroupView> buildTenantGroups(List<ProjectEntity> enabledProjects,
                                                                   Set<Long> joinedProjectIds,
                                                                   Map<Long, ProjectMemberRequestEntity> latestPendingRequestByProject,
                                                                   Map<String, TenantEntity> tenantMap) {
        Map<String, WorkspaceAccessTenantGroupView> grouped = new LinkedHashMap<String, WorkspaceAccessTenantGroupView>();
        for (ProjectEntity project : enabledProjects) {
            if (project == null || project.getId() == null || joinedProjectIds.contains(project.getId())) {
                continue;
            }
            TenantEntity tenant = tenantMap.get(project.getTenantId());
            if (tenant == null || !asBoolean(tenant.getEnabled(), true)) {
                continue;
            }
            WorkspaceAccessTenantGroupView group = grouped.get(tenant.getTenantId());
            if (group == null) {
                group = new WorkspaceAccessTenantGroupView();
                group.setTenantId(tenant.getTenantId());
                group.setTenantCode(tenant.getTenantCode());
                group.setTenantName(tenant.getTenantName());
                group.setEnabled(asBoolean(tenant.getEnabled(), true));
                grouped.put(tenant.getTenantId(), group);
            }
            ProjectMemberRequestEntity pendingRequest = latestPendingRequestByProject.get(project.getId());
            WorkspaceAccessProjectView view = new WorkspaceAccessProjectView();
            view.setProjectId(project.getId());
            view.setTenantId(project.getTenantId());
            view.setTenantName(tenant.getTenantName());
            view.setProjectCode(project.getProjectCode());
            view.setProjectName(project.getProjectName());
            view.setDescription(project.getDescription());
            view.setEnabled(asBoolean(project.getEnabled(), true));
            if (pendingRequest != null) {
                view.setPendingRequestId(pendingRequest.getId());
                view.setPendingRequestStatus(pendingRequest.getStatus());
            }
            group.getProjects().add(view);
        }
        return new ArrayList<WorkspaceAccessTenantGroupView>(grouped.values());
    }

    private List<WorkspaceAccessRequestView> buildRequestViews(List<ProjectMemberRequestEntity> requests,
                                                               Map<Long, ProjectEntity> projectMap,
                                                               Map<String, TenantEntity> tenantMap) {
        List<WorkspaceAccessRequestView> result = new ArrayList<WorkspaceAccessRequestView>();
        for (ProjectMemberRequestEntity request : requests) {
            ProjectEntity project = projectMap.get(request.getProjectId());
            TenantEntity tenant = project == null ? loadTenant(request.getTenantId()) : tenantMap.get(project.getTenantId());
            result.add(toRequestView(request, project, tenant));
        }
        return result;
    }

    private WorkspaceAccessRequestView toRequestView(ProjectMemberRequestEntity entity,
                                                     ProjectEntity project,
                                                     TenantEntity tenant) {
        WorkspaceAccessRequestView view = new WorkspaceAccessRequestView();
        view.setRequestId(entity.getId());
        view.setProjectId(entity.getProjectId());
        view.setTenantId(project == null ? entity.getTenantId() : project.getTenantId());
        view.setTenantName(tenant == null ? null : tenant.getTenantName());
        view.setProjectName(project == null ? null : project.getProjectName());
        view.setRequestType(entity.getRequestType());
        view.setStatus(entity.getStatus());
        view.setReason(entity.getReason());
        view.setReviewComment(entity.getReviewComment());
        view.setCreatedAt(entity.getCreatedAt());
        view.setReviewedAt(isReviewedStatus(entity.getStatus()) ? entity.getUpdatedAt() : null);
        return view;
    }

    private void notifyApprovers(ProjectEntity project, ProjectMemberRequestEntity request) {
        if (project == null || request == null || request.getId() == null) {
            return;
        }
        Set<Long> recipientUserIds = new LinkedHashSet<Long>(notificationService.superAdminUserIds());
        List<ProjectMemberEntity> projectAdmins = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getTenantId, project.getTenantId())
                .eq(ProjectMemberEntity::getProjectId, project.getId())
                .eq(ProjectMemberEntity::getStatus, StudioConstants.MEMBER_STATUS_ACTIVE)
                .eq(ProjectMemberEntity::getRoleCode, StudioConstants.ROLE_PROJECT_ADMIN));
        for (ProjectMemberEntity projectAdmin : projectAdmins) {
            if (projectAdmin.getUserId() != null) {
                recipientUserIds.add(projectAdmin.getUserId());
            }
        }
        if (recipientUserIds.isEmpty()) {
            return;
        }
        notificationService.notifyUsers(new ArrayList<Long>(recipientUserIds),
                new NotificationCommand()
                        .setCategory(StudioConstants.NOTIFICATION_CATEGORY_PROJECT_ACCESS_REQUEST)
                        .setTitle("收到新的项目加入申请")
                        .setContent("用户 " + safeUsername() + " 申请加入项目 " + project.getProjectName() + "。")
                        .setTargetType("PROJECT_MEMBER_REQUEST")
                        .setTargetId(request.getId())
                        .setTargetPath("/system?tab=requests")
                        .setTargetTenantId(project.getTenantId())
                        .setTargetProjectId(project.getId())
                        .setDedupeKey("project-access-request:" + request.getId() + ":pending"));
    }

    private void ensureNotActiveMember(Long projectId, Long userId) {
        Long count = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, projectId)
                .eq(ProjectMemberEntity::getUserId, userId)
                .eq(ProjectMemberEntity::getStatus, StudioConstants.MEMBER_STATUS_ACTIVE));
        if (count != null && count.longValue() > 0L) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "You have already joined this project");
        }
    }

    private void ensureNoPendingRequest(Long projectId, Long userId) {
        Long count = projectMemberRequestMapper.selectCount(new LambdaQueryWrapper<ProjectMemberRequestEntity>()
                .eq(ProjectMemberRequestEntity::getProjectId, projectId)
                .eq(ProjectMemberRequestEntity::getUserId, userId)
                .eq(ProjectMemberRequestEntity::getRequestType, StudioConstants.MEMBER_REQUEST_APPLY)
                .eq(ProjectMemberRequestEntity::getStatus, StudioConstants.MEMBER_REQUEST_PENDING));
        if (count != null && count.longValue() > 0L) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "A pending access request already exists for this project");
        }
    }

    private ProjectMemberRequestEntity requireOwnPendingRequest(Long requestId, Long userId) {
        if (requestId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Request id is required");
        }
        ProjectMemberRequestEntity entity = projectMemberRequestMapper.selectById(requestId);
        if (entity == null || !userId.equals(entity.getUserId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Access request not found");
        }
        if (!StudioConstants.MEMBER_REQUEST_PENDING.equalsIgnoreCase(entity.getStatus())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only pending requests can be cancelled");
        }
        return entity;
    }

    private ProjectEntity requireEnabledProject(Long projectId) {
        ProjectEntity project = requireProject(projectId);
        if (!asBoolean(project.getEnabled(), true)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Project is disabled");
        }
        TenantEntity tenant = loadTenant(project.getTenantId());
        if (tenant == null || !asBoolean(tenant.getEnabled(), true)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Tenant is disabled");
        }
        return project;
    }

    private ProjectEntity requireProject(Long projectId) {
        if (projectId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Project id is required");
        }
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Project not found");
        }
        return project;
    }

    private Map<Long, ProjectEntity> loadProjectMap(Set<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProjectEntity> projects = projectMapper.selectBatchIds(projectIds);
        Map<Long, ProjectEntity> projectMap = new LinkedHashMap<Long, ProjectEntity>();
        for (ProjectEntity project : projects) {
            projectMap.put(project.getId(), project);
        }
        return projectMap;
    }

    private Map<String, TenantEntity> loadTenantMap(Iterable<ProjectEntity> projects) {
        Set<String> tenantIds = new LinkedHashSet<String>();
        for (ProjectEntity project : projects) {
            if (project != null && hasText(project.getTenantId())) {
                tenantIds.add(project.getTenantId().trim());
            }
        }
        if (tenantIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<TenantEntity> tenants = tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
                .in(TenantEntity::getTenantId, tenantIds));
        Map<String, TenantEntity> tenantMap = new LinkedHashMap<String, TenantEntity>();
        for (TenantEntity tenant : tenants) {
            tenantMap.put(tenant.getTenantId(), tenant);
        }
        return tenantMap;
    }

    private TenantEntity loadTenant(String tenantId) {
        if (!hasText(tenantId)) {
            return null;
        }
        return tenantMapper.selectOne(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getTenantId, tenantId.trim())
                .last("limit 1"));
    }

    private Long requireCurrentUserId() {
        Long currentUserId = securityService.currentUserId();
        if (currentUserId == null) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return currentUserId;
    }

    private String safeUsername() {
        return hasText(securityService.currentUsername()) ? securityService.currentUsername().trim() : "unknown";
    }

    private boolean isReviewedStatus(String status) {
        return StudioConstants.MEMBER_REQUEST_APPROVED.equalsIgnoreCase(status)
                || StudioConstants.MEMBER_REQUEST_REJECTED.equalsIgnoreCase(status)
                || StudioConstants.MEMBER_REQUEST_ACCEPTED.equalsIgnoreCase(status);
    }

    private boolean asBoolean(Integer value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value.intValue() != 0;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
