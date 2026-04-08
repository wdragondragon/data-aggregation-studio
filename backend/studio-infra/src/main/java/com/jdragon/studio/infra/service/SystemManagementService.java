package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.system.SystemProjectView;
import com.jdragon.studio.dto.model.system.SystemProjectMemberRequestView;
import com.jdragon.studio.dto.model.system.SystemProjectMemberView;
import com.jdragon.studio.dto.model.system.SystemProjectWorkerView;
import com.jdragon.studio.dto.model.system.SystemTenantMemberView;
import com.jdragon.studio.dto.model.system.SystemTenantView;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.ProjectMemberRequestEntity;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.ResourceShareEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.TenantEntity;
import com.jdragon.studio.infra.entity.TenantMemberEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberRequestMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ResourceShareMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.TenantMapper;
import com.jdragon.studio.infra.mapper.TenantMemberMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SystemManagementService {

    private final TenantMapper tenantMapper;
    private final ProjectMapper projectMapper;
    private final TenantMemberMapper tenantMemberMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectMemberRequestMapper projectMemberRequestMapper;
    private final ProjectWorkerBindingMapper projectWorkerBindingMapper;
    private final ResourceShareMapper resourceShareMapper;
    private final StudioUserMapper userMapper;
    private final WorkerLeaseMapper workerLeaseMapper;
    private final DatasourceMapper datasourceMapper;
    private final DataModelMapper dataModelMapper;
    private final CollectionTaskDefinitionMapper collectionTaskDefinitionMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final StudioSecurityService securityService;

    public SystemManagementService(TenantMapper tenantMapper,
                                   ProjectMapper projectMapper,
                                   TenantMemberMapper tenantMemberMapper,
                                   ProjectMemberMapper projectMemberMapper,
                                   ProjectMemberRequestMapper projectMemberRequestMapper,
                                   ProjectWorkerBindingMapper projectWorkerBindingMapper,
                                   ResourceShareMapper resourceShareMapper,
                                   StudioUserMapper userMapper,
                                   WorkerLeaseMapper workerLeaseMapper,
                                   DatasourceMapper datasourceMapper,
                                   DataModelMapper dataModelMapper,
                                   CollectionTaskDefinitionMapper collectionTaskDefinitionMapper,
                                   WorkflowDefinitionMapper workflowDefinitionMapper,
                                   StudioSecurityService securityService) {
        this.tenantMapper = tenantMapper;
        this.projectMapper = projectMapper;
        this.tenantMemberMapper = tenantMemberMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectMemberRequestMapper = projectMemberRequestMapper;
        this.projectWorkerBindingMapper = projectWorkerBindingMapper;
        this.resourceShareMapper = resourceShareMapper;
        this.userMapper = userMapper;
        this.workerLeaseMapper = workerLeaseMapper;
        this.datasourceMapper = datasourceMapper;
        this.dataModelMapper = dataModelMapper;
        this.collectionTaskDefinitionMapper = collectionTaskDefinitionMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.securityService = securityService;
    }

    public List<SystemTenantView> listTenants() {
        List<TenantEntity> tenants;
        if (hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)) {
            tenants = tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
                    .orderByAsc(TenantEntity::getTenantName));
        } else if (hasText(securityService.currentTenantId())) {
            tenants = tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
                    .eq(TenantEntity::getTenantId, securityService.currentTenantId())
                    .orderByAsc(TenantEntity::getTenantName));
        } else {
            tenants = Collections.emptyList();
        }
        List<SystemTenantView> result = new ArrayList<SystemTenantView>();
        for (TenantEntity tenant : tenants) {
            result.add(toTenantView(tenant));
        }
        return result;
    }

    @Transactional
    public TenantEntity saveTenant(TenantEntity entity) {
        requireAnyRole(StudioConstants.ROLE_SUPER_ADMIN);
        if (entity == null || !hasText(entity.getTenantCode()) || !hasText(entity.getTenantName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Tenant code and name are required");
        }
        if (entity.getId() == null) {
            entity.setTenantId(entity.getTenantCode().trim());
            tenantMapper.insert(entity);
            return entity;
        }
        TenantEntity existing = requireTenant(entity.getId());
        existing.setTenantName(entity.getTenantName());
        existing.setDescription(entity.getDescription());
        existing.setEnabled(entity.getEnabled());
        tenantMapper.updateById(existing);
        return existing;
    }

    @Transactional
    public void deleteTenant(Long tenantId) {
        requireAnyRole(StudioConstants.ROLE_SUPER_ADMIN);
        TenantEntity tenant = requireTenant(tenantId);
        if (StudioConstants.DEFAULT_TENANT_ID.equalsIgnoreCase(tenant.getTenantId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Default tenant cannot be deleted");
        }
        Long projectCount = projectMapper.selectCount(new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getTenantId, tenant.getTenantId()));
        if (projectCount != null && projectCount.longValue() > 0L) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Delete tenant projects before deleting the tenant");
        }
        tenantMapper.deleteById(tenantId);
    }

    public List<SystemProjectView> listProjects() {
        String tenantId = requireCurrentTenantId();
        LambdaQueryWrapper<ProjectEntity> queryWrapper = new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getTenantId, tenantId)
                .orderByDesc(ProjectEntity::getDefaultProject)
                .orderByAsc(ProjectEntity::getProjectName);
        if (!hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN)
                && securityService.currentProjectId() != null) {
            queryWrapper.eq(ProjectEntity::getId, securityService.currentProjectId());
        }
        List<SystemProjectView> result = new ArrayList<SystemProjectView>();
        for (ProjectEntity project : projectMapper.selectList(queryWrapper)) {
            result.add(toProjectView(project));
        }
        return result;
    }

    @Transactional
    public ProjectEntity saveProject(ProjectEntity entity) {
        requireAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN);
        String tenantId = requireCurrentTenantId();
        if (entity == null || !hasText(entity.getProjectCode()) || !hasText(entity.getProjectName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Project code and name are required");
        }
        ProjectEntity target = entity.getId() == null ? new ProjectEntity() : requireProject(entity.getId(), tenantId);
        target.setTenantId(tenantId);
        target.setProjectCode(entity.getProjectCode());
        target.setProjectName(entity.getProjectName());
        target.setDescription(entity.getDescription());
        target.setEnabled(entity.getEnabled());
        target.setDefaultProject(entity.getDefaultProject());
        if (target.getId() == null) {
            projectMapper.insert(target);
        } else {
            projectMapper.updateById(target);
        }
        if (target.getDefaultProject() != null && target.getDefaultProject() == 1) {
            clearDefaultProject(tenantId, target.getId());
        }
        return target;
    }

    @Transactional
    public void deleteProject(Long projectId) {
        requireAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN);
        String tenantId = requireCurrentTenantId();
        ProjectEntity project = requireProject(projectId, tenantId);
        if (project.getDefaultProject() != null && project.getDefaultProject() == 1) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Default project cannot be deleted");
        }
        if (hasProjectResources(project.getId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Delete project resources before deleting the project");
        }
        projectMapper.deleteById(projectId);
    }

    public StudioUserEntity requireUser(Long userId) {
        if (userId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "User id is required");
        }
        StudioUserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "User not found");
        }
        return user;
    }

    public List<SystemTenantMemberView> listTenantMembers() {
        requireAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN);
        String tenantId = requireCurrentTenantId();
        List<TenantMemberEntity> members = tenantMemberMapper.selectList(new LambdaQueryWrapper<TenantMemberEntity>()
                .eq(TenantMemberEntity::getTenantId, tenantId)
                .orderByAsc(TenantMemberEntity::getCreatedAt));
        Map<Long, StudioUserEntity> userMap = loadUserMap(extractTenantUserIds(members));
        List<SystemTenantMemberView> result = new ArrayList<SystemTenantMemberView>();
        for (TenantMemberEntity member : members) {
            result.add(toTenantMemberView(member, userMap.get(member.getUserId())));
        }
        return result;
    }

    @Transactional
    public TenantMemberEntity saveTenantMember(TenantMemberEntity entity) {
        requireAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN);
        String tenantId = requireCurrentTenantId();
        StudioUserEntity user = requireUser(entity == null ? null : entity.getUserId());
        TenantMemberEntity target = entity.getId() == null
                ? tenantMemberMapper.selectOne(new LambdaQueryWrapper<TenantMemberEntity>()
                .eq(TenantMemberEntity::getTenantId, tenantId)
                .eq(TenantMemberEntity::getUserId, user.getId())
                .last("limit 1"))
                : requireTenantMember(entity.getId(), tenantId);
        if (target == null) {
            target = new TenantMemberEntity();
        }
        target.setTenantId(tenantId);
        target.setUserId(user.getId());
        target.setRoleCode(hasText(entity.getRoleCode()) ? entity.getRoleCode().trim() : StudioConstants.ROLE_TENANT_ADMIN);
        target.setStatus(hasText(entity.getStatus()) ? entity.getStatus().trim() : StudioConstants.MEMBER_STATUS_ACTIVE);
        if (target.getId() == null) {
            tenantMemberMapper.insert(target);
        } else {
            tenantMemberMapper.updateById(target);
        }
        return target;
    }

    @Transactional
    public void deleteTenantMember(Long tenantMemberId) {
        requireAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN);
        tenantMemberMapper.deleteById(requireTenantMember(tenantMemberId, requireCurrentTenantId()).getId());
    }

    public List<SystemProjectMemberView> listProjectMembers(Long projectId) {
        ProjectEntity project = requireManageableProject(projectId);
        List<ProjectMemberEntity> members = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, project.getId())
                .orderByAsc(ProjectMemberEntity::getCreatedAt));
        Map<Long, StudioUserEntity> userMap = loadUserMap(extractProjectUserIds(members));
        List<SystemProjectMemberView> result = new ArrayList<SystemProjectMemberView>();
        for (ProjectMemberEntity member : members) {
            result.add(toProjectMemberView(member, project, userMap.get(member.getUserId())));
        }
        return result;
    }

    @Transactional
    public ProjectMemberEntity saveProjectMember(ProjectMemberEntity entity) {
        ProjectEntity project = requireManageableProject(entity == null ? null : entity.getProjectId());
        StudioUserEntity user = requireUser(entity == null ? null : entity.getUserId());
        ProjectMemberEntity target = entity.getId() == null
                ? projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, project.getId())
                .eq(ProjectMemberEntity::getUserId, user.getId())
                .last("limit 1"))
                : requireProjectMember(entity.getId(), project.getId(), project.getTenantId());
        if (target == null) {
            target = new ProjectMemberEntity();
        }
        target.setTenantId(project.getTenantId());
        target.setProjectId(project.getId());
        target.setUserId(user.getId());
        target.setRoleCode(hasText(entity.getRoleCode()) ? entity.getRoleCode().trim() : StudioConstants.ROLE_PROJECT_MEMBER);
        target.setStatus(hasText(entity.getStatus()) ? entity.getStatus().trim() : StudioConstants.MEMBER_STATUS_ACTIVE);
        if (target.getId() == null) {
            projectMemberMapper.insert(target);
        } else {
            projectMemberMapper.updateById(target);
        }
        return target;
    }

    @Transactional
    public void deleteProjectMember(Long projectMemberId) {
        ProjectMemberEntity member = requireProjectMember(projectMemberId, null, requireCurrentTenantId());
        requireManageableProject(member.getProjectId());
        projectMemberMapper.deleteById(projectMemberId);
    }

    public List<SystemProjectMemberRequestView> listProjectMemberRequests(Long projectId) {
        ProjectEntity project = requireManageableProject(projectId);
        List<ProjectMemberRequestEntity> requests = projectMemberRequestMapper.selectList(new LambdaQueryWrapper<ProjectMemberRequestEntity>()
                .eq(ProjectMemberRequestEntity::getProjectId, project.getId())
                .orderByDesc(ProjectMemberRequestEntity::getCreatedAt));
        Set<Long> userIds = new LinkedHashSet<Long>();
        for (ProjectMemberRequestEntity request : requests) {
            addIfNotNull(userIds, request.getUserId());
            addIfNotNull(userIds, request.getInviterUserId());
            addIfNotNull(userIds, request.getReviewerUserId());
        }
        Map<Long, StudioUserEntity> userMap = loadUserMap(userIds);
        List<SystemProjectMemberRequestView> result = new ArrayList<SystemProjectMemberRequestView>();
        for (ProjectMemberRequestEntity request : requests) {
            result.add(toProjectMemberRequestView(
                    request,
                    project,
                    userMap.get(request.getUserId()),
                    userMap.get(request.getInviterUserId()),
                    userMap.get(request.getReviewerUserId())));
        }
        return result;
    }

    @Transactional
    public ProjectMemberRequestEntity saveProjectMemberRequest(ProjectMemberRequestEntity entity) {
        ProjectEntity project = requireManageableProject(entity == null ? null : entity.getProjectId());
        StudioUserEntity user = requireUser(entity == null ? null : entity.getUserId());
        ProjectMemberRequestEntity target = entity.getId() == null
                ? new ProjectMemberRequestEntity()
                : requireProjectMemberRequest(entity.getId(), project.getId(), project.getTenantId());
        target.setTenantId(project.getTenantId());
        target.setProjectId(project.getId());
        target.setUserId(user.getId());
        target.setRequestType(hasText(entity.getRequestType()) ? entity.getRequestType().trim() : StudioConstants.MEMBER_REQUEST_INVITE);
        target.setStatus(hasText(entity.getStatus()) ? entity.getStatus().trim() : StudioConstants.MEMBER_REQUEST_PENDING);
        target.setReason(entity.getReason());
        target.setReviewComment(entity.getReviewComment());
        if (StudioConstants.MEMBER_REQUEST_INVITE.equalsIgnoreCase(target.getRequestType())) {
            target.setInviterUserId(securityService.currentUserId());
        } else if (entity.getInviterUserId() != null) {
            target.setInviterUserId(entity.getInviterUserId());
        }
        if (isReviewStatus(target.getStatus())) {
            target.setReviewerUserId(securityService.currentUserId());
        }
        if (target.getId() == null) {
            projectMemberRequestMapper.insert(target);
        } else {
            projectMemberRequestMapper.updateById(target);
        }
        if (isApprovedStatus(target.getStatus())) {
            ensureProjectMembership(project.getTenantId(), project.getId(), user.getId(), StudioConstants.ROLE_PROJECT_MEMBER);
        }
        return target;
    }

    @Transactional
    public void deleteProjectMemberRequest(Long requestId) {
        ProjectMemberRequestEntity request = requireProjectMemberRequest(requestId, null, requireCurrentTenantId());
        requireManageableProject(request.getProjectId());
        projectMemberRequestMapper.deleteById(requestId);
    }

    public List<SystemProjectWorkerView> listProjectWorkers(Long projectId) {
        ProjectEntity project = requireTenantManagedProject(projectId);
        List<WorkerLeaseEntity> workerLeases = workerLeaseMapper.selectList(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .eq(WorkerLeaseEntity::getTenantId, project.getTenantId())
                .orderByDesc(WorkerLeaseEntity::getLastHeartbeatAt)
                .orderByAsc(WorkerLeaseEntity::getWorkerCode));
        List<ProjectWorkerBindingEntity> bindings = projectWorkerBindingMapper.selectList(new LambdaQueryWrapper<ProjectWorkerBindingEntity>()
                .eq(ProjectWorkerBindingEntity::getTenantId, project.getTenantId())
                .eq(ProjectWorkerBindingEntity::getProjectId, project.getId())
                .orderByAsc(ProjectWorkerBindingEntity::getWorkerCode));
        Map<String, WorkerLeaseEntity> leaseMap = new LinkedHashMap<String, WorkerLeaseEntity>();
        for (WorkerLeaseEntity lease : workerLeases) {
            leaseMap.put(lease.getWorkerCode(), lease);
        }
        Map<String, ProjectWorkerBindingEntity> bindingMap = new LinkedHashMap<String, ProjectWorkerBindingEntity>();
        for (ProjectWorkerBindingEntity binding : bindings) {
            bindingMap.put(binding.getWorkerCode(), binding);
        }
        Set<String> workerCodes = new LinkedHashSet<String>();
        workerCodes.addAll(leaseMap.keySet());
        workerCodes.addAll(bindingMap.keySet());
        List<SystemProjectWorkerView> result = new ArrayList<SystemProjectWorkerView>();
        for (String workerCode : workerCodes) {
            result.add(toProjectWorkerView(project.getId(), project.getTenantId(), leaseMap.get(workerCode), bindingMap.get(workerCode)));
        }
        return result;
    }

    @Transactional
    public ProjectWorkerBindingEntity saveProjectWorkerBinding(ProjectWorkerBindingEntity entity) {
        ProjectEntity project = requireTenantManagedProject(entity == null ? null : entity.getProjectId());
        if (entity == null || !hasText(entity.getWorkerCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Worker code is required");
        }
        ProjectWorkerBindingEntity target = entity.getId() == null
                ? projectWorkerBindingMapper.selectOne(new LambdaQueryWrapper<ProjectWorkerBindingEntity>()
                .eq(ProjectWorkerBindingEntity::getTenantId, project.getTenantId())
                .eq(ProjectWorkerBindingEntity::getProjectId, project.getId())
                .eq(ProjectWorkerBindingEntity::getWorkerCode, entity.getWorkerCode().trim())
                .last("limit 1"))
                : requireProjectWorkerBinding(entity.getId(), project.getId(), project.getTenantId());
        if (target == null) {
            target = new ProjectWorkerBindingEntity();
        }
        target.setTenantId(project.getTenantId());
        target.setProjectId(project.getId());
        target.setWorkerCode(entity.getWorkerCode().trim());
        target.setEnabled(entity.getEnabled() == null ? 1 : entity.getEnabled());
        if (target.getId() == null) {
            projectWorkerBindingMapper.insert(target);
        } else {
            projectWorkerBindingMapper.updateById(target);
        }
        return target;
    }

    @Transactional
    public void deleteProjectWorkerBinding(Long bindingId) {
        ProjectWorkerBindingEntity binding = requireProjectWorkerBinding(bindingId, null, requireCurrentTenantId());
        requireTenantManagedProject(binding.getProjectId());
        projectWorkerBindingMapper.deleteById(bindingId);
    }

    public List<ResourceShareEntity> listResourceShares(String resourceType, Long projectId) {
        ProjectEntity project = requireManageableProject(projectId);
        LambdaQueryWrapper<ResourceShareEntity> queryWrapper = new LambdaQueryWrapper<ResourceShareEntity>()
                .eq(ResourceShareEntity::getTenantId, project.getTenantId())
                .eq(ResourceShareEntity::getSourceProjectId, project.getId())
                .orderByDesc(ResourceShareEntity::getCreatedAt);
        if (hasText(resourceType)) {
            queryWrapper.eq(ResourceShareEntity::getResourceType, resourceType.trim().toUpperCase());
        }
        return resourceShareMapper.selectList(queryWrapper);
    }

    @Transactional
    public ResourceShareEntity saveResourceShare(ResourceShareEntity entity) {
        ProjectEntity project = requireManageableProject(entity == null ? null : entity.getSourceProjectId());
        if (entity == null || entity.getTargetProjectId() == null || !hasText(entity.getResourceType()) || entity.getResourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Resource share target, type and resource id are required");
        }
        ProjectEntity targetProject = requireProject(entity.getTargetProjectId(), project.getTenantId());
        if (project.getId().equals(targetProject.getId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Source and target project cannot be the same");
        }
        ResourceShareEntity target = entity.getId() == null
                ? resourceShareMapper.selectOne(new LambdaQueryWrapper<ResourceShareEntity>()
                .eq(ResourceShareEntity::getTenantId, project.getTenantId())
                .eq(ResourceShareEntity::getResourceType, entity.getResourceType().trim().toUpperCase())
                .eq(ResourceShareEntity::getResourceId, entity.getResourceId())
                .eq(ResourceShareEntity::getTargetProjectId, targetProject.getId())
                .last("limit 1"))
                : requireResourceShare(entity.getId(), project.getTenantId(), project.getId());
        if (target == null) {
            target = new ResourceShareEntity();
        }
        target.setTenantId(project.getTenantId());
        target.setSourceProjectId(project.getId());
        target.setTargetProjectId(targetProject.getId());
        target.setResourceType(entity.getResourceType().trim().toUpperCase());
        target.setResourceId(entity.getResourceId());
        target.setSharedByUserId(securityService.currentUserId());
        target.setEnabled(entity.getEnabled() == null ? 1 : entity.getEnabled());
        if (target.getId() == null) {
            resourceShareMapper.insert(target);
        } else {
            resourceShareMapper.updateById(target);
        }
        return target;
    }

    @Transactional
    public void deleteResourceShare(Long shareId) {
        ResourceShareEntity share = requireResourceShare(shareId, requireCurrentTenantId(), null);
        requireManageableProject(share.getSourceProjectId());
        resourceShareMapper.deleteById(shareId);
    }

    public ProjectEntity requireManageableProject(Long projectId) {
        String tenantId = requireCurrentTenantId();
        Long resolvedProjectId = projectId != null ? projectId : securityService.currentProjectId();
        ProjectEntity project = requireProject(resolvedProjectId, tenantId);
        if (hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN)) {
            return project;
        }
        if (hasAnyRole(StudioConstants.ROLE_PROJECT_ADMIN)
                && securityService.currentProjectId() != null
                && securityService.currentProjectId().equals(project.getId())) {
            return project;
        }
        throw new StudioException(StudioErrorCode.FORBIDDEN, "Project management is not allowed in the current context");
    }

    public ProjectEntity requireTenantManagedProject(Long projectId) {
        requireAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN);
        return requireProject(projectId != null ? projectId : securityService.currentProjectId(), requireCurrentTenantId());
    }

    private SystemTenantView toTenantView(TenantEntity entity) {
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

    private SystemProjectView toProjectView(ProjectEntity entity) {
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

    private SystemTenantMemberView toTenantMemberView(TenantMemberEntity member, StudioUserEntity user) {
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

    private SystemProjectMemberView toProjectMemberView(ProjectMemberEntity member, ProjectEntity project, StudioUserEntity user) {
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

    private SystemProjectMemberRequestView toProjectMemberRequestView(ProjectMemberRequestEntity request,
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

    private SystemProjectWorkerView toProjectWorkerView(Long projectId,
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

    private Map<Long, StudioUserEntity> loadUserMap(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, StudioUserEntity> userMap = new LinkedHashMap<Long, StudioUserEntity>();
        for (StudioUserEntity user : userMapper.selectBatchIds(userIds)) {
            userMap.put(user.getId(), user);
        }
        return userMap;
    }

    private Set<Long> extractTenantUserIds(List<TenantMemberEntity> members) {
        Set<Long> userIds = new LinkedHashSet<Long>();
        for (TenantMemberEntity member : members) {
            addIfNotNull(userIds, member.getUserId());
        }
        return userIds;
    }

    private Set<Long> extractProjectUserIds(List<ProjectMemberEntity> members) {
        Set<Long> userIds = new LinkedHashSet<Long>();
        for (ProjectMemberEntity member : members) {
            addIfNotNull(userIds, member.getUserId());
        }
        return userIds;
    }

    private void ensureProjectMembership(String tenantId,
                                         Long projectId,
                                         Long userId,
                                         String roleCode) {
        ProjectMemberEntity member = projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, projectId)
                .eq(ProjectMemberEntity::getUserId, userId)
                .last("limit 1"));
        if (member == null) {
            member = new ProjectMemberEntity();
            member.setTenantId(tenantId);
            member.setProjectId(projectId);
            member.setUserId(userId);
            member.setRoleCode(roleCode);
            member.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
            projectMemberMapper.insert(member);
            return;
        }
        member.setTenantId(tenantId);
        member.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
        if (!hasText(member.getRoleCode())) {
            member.setRoleCode(roleCode);
        }
        projectMemberMapper.updateById(member);
    }

    private boolean isReviewStatus(String status) {
        return StudioConstants.MEMBER_REQUEST_APPROVED.equalsIgnoreCase(status)
                || StudioConstants.MEMBER_REQUEST_REJECTED.equalsIgnoreCase(status);
    }

    private boolean isApprovedStatus(String status) {
        return StudioConstants.MEMBER_REQUEST_APPROVED.equalsIgnoreCase(status)
                || StudioConstants.MEMBER_REQUEST_ACCEPTED.equalsIgnoreCase(status);
    }

    private void addIfNotNull(Set<Long> target, Long value) {
        if (value != null) {
            target.add(value);
        }
    }

    private void clearDefaultProject(String tenantId, Long keepProjectId) {
        List<ProjectEntity> projects = projectMapper.selectList(new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getTenantId, tenantId)
                .eq(ProjectEntity::getDefaultProject, 1));
        for (ProjectEntity project : projects) {
            if (keepProjectId != null && keepProjectId.equals(project.getId())) {
                continue;
            }
            project.setDefaultProject(0);
            projectMapper.updateById(project);
        }
    }

    private boolean hasProjectResources(Long projectId) {
        return count(datasourceMapper.selectCount(new LambdaQueryWrapper<DatasourceEntity>()
                .eq(DatasourceEntity::getProjectId, projectId)))
                || count(dataModelMapper.selectCount(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getProjectId, projectId)))
                || count(collectionTaskDefinitionMapper.selectCount(new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
                .eq(CollectionTaskDefinitionEntity::getProjectId, projectId)))
                || count(workflowDefinitionMapper.selectCount(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                .eq(WorkflowDefinitionEntity::getProjectId, projectId)));
    }

    private boolean count(Long value) {
        return value != null && value.longValue() > 0L;
    }

    private TenantEntity requireTenant(Long tenantId) {
        TenantEntity tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Tenant not found");
        }
        return tenant;
    }

    private ProjectEntity requireProject(Long projectId, String tenantId) {
        if (projectId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Project id is required");
        }
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null || !tenantId.equals(project.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Project not found");
        }
        return project;
    }

    private TenantMemberEntity requireTenantMember(Long tenantMemberId, String tenantId) {
        TenantMemberEntity member = tenantMemberMapper.selectById(tenantMemberId);
        if (member == null || !tenantId.equals(member.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Tenant member not found");
        }
        return member;
    }

    private ProjectMemberEntity requireProjectMember(Long projectMemberId, Long projectId, String tenantId) {
        ProjectMemberEntity member = projectMemberMapper.selectById(projectMemberId);
        if (member == null || !tenantId.equals(member.getTenantId())
                || (projectId != null && !projectId.equals(member.getProjectId()))) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Project member not found");
        }
        return member;
    }

    private ProjectMemberRequestEntity requireProjectMemberRequest(Long requestId, Long projectId, String tenantId) {
        ProjectMemberRequestEntity request = projectMemberRequestMapper.selectById(requestId);
        if (request == null || !tenantId.equals(request.getTenantId())
                || (projectId != null && !projectId.equals(request.getProjectId()))) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Project member request not found");
        }
        return request;
    }

    private ProjectWorkerBindingEntity requireProjectWorkerBinding(Long bindingId, Long projectId, String tenantId) {
        ProjectWorkerBindingEntity binding = projectWorkerBindingMapper.selectById(bindingId);
        if (binding == null || !tenantId.equals(binding.getTenantId())
                || (projectId != null && !projectId.equals(binding.getProjectId()))) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Project worker binding not found");
        }
        return binding;
    }

    private ResourceShareEntity requireResourceShare(Long shareId, String tenantId, Long sourceProjectId) {
        ResourceShareEntity share = resourceShareMapper.selectById(shareId);
        if (share == null || !tenantId.equals(share.getTenantId())
                || (sourceProjectId != null && !sourceProjectId.equals(share.getSourceProjectId()))) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Resource share not found");
        }
        return share;
    }

    private String requireCurrentTenantId() {
        if (!hasText(securityService.currentTenantId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Tenant context is required");
        }
        return securityService.currentTenantId();
    }

    private void requireAnyRole(String... roleCodes) {
        if (!hasAnyRole(roleCodes)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
    }

    private boolean hasAnyRole(String... roleCodes) {
        List<String> currentRoles = securityService.currentRoleCodes();
        if (currentRoles == null || currentRoles.isEmpty()) {
            return false;
        }
        for (String currentRole : currentRoles) {
            if (!hasText(currentRole)) {
                continue;
            }
            for (String roleCode : roleCodes) {
                if (roleCode != null && roleCode.equalsIgnoreCase(currentRole)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
