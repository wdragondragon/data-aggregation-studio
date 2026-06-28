package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.system.ResourceShareView;
import com.jdragon.studio.dto.model.system.SystemProjectView;
import com.jdragon.studio.dto.model.system.ShareResourceOptionView;
import com.jdragon.studio.dto.model.system.SystemProjectMemberRequestView;
import com.jdragon.studio.dto.model.system.SystemProjectMemberView;
import com.jdragon.studio.dto.model.system.SystemProjectWorkerView;
import com.jdragon.studio.dto.model.system.SystemTenantMemberView;
import com.jdragon.studio.dto.model.system.SystemTenantView;
import com.jdragon.studio.dto.model.system.SystemWorkerInstanceView;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
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
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberRequestMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.ResourceShareMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.TenantMapper;
import com.jdragon.studio.infra.mapper.TenantMemberMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
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

import static com.jdragon.studio.infra.service.SystemManagementViewAssembler.toProjectMemberRequestView;
import static com.jdragon.studio.infra.service.SystemManagementViewAssembler.toProjectMemberView;
import static com.jdragon.studio.infra.service.SystemManagementViewAssembler.toProjectView;
import static com.jdragon.studio.infra.service.SystemManagementViewAssembler.toResourceShareView;
import static com.jdragon.studio.infra.service.SystemManagementViewAssembler.toProjectWorkerView;
import static com.jdragon.studio.infra.service.SystemManagementViewAssembler.toTenantMemberView;
import static com.jdragon.studio.infra.service.SystemManagementViewAssembler.toTenantView;
import static com.jdragon.studio.infra.service.SystemManagementViewAssembler.toWorkerInstanceView;

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
    private final DataDevelopmentScriptMapper dataDevelopmentScriptMapper;
    private final StudioSecurityService securityService;
    private final NotificationService notificationService;
    private final SystemResourceShareSupport resourceShareSupport;
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 200;
    private static final long WORKER_RECENT_INSTANCE_HOURS = 24L;

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
                                   DataDevelopmentScriptMapper dataDevelopmentScriptMapper,
                                   DataServiceDefinitionMapper dataServiceDefinitionMapper,
                                   DataIngestionServiceMapper dataIngestionServiceMapper,
                                   ProtocolConversionServiceMapper protocolConversionServiceMapper,
                                   StudioSecurityService securityService,
                                   NotificationService notificationService) {
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
        this.dataDevelopmentScriptMapper = dataDevelopmentScriptMapper;
        this.securityService = securityService;
        this.notificationService = notificationService;
        this.resourceShareSupport = new SystemResourceShareSupport(
                datasourceMapper,
                dataModelMapper,
                collectionTaskDefinitionMapper,
                workflowDefinitionMapper,
                dataDevelopmentScriptMapper,
                dataServiceDefinitionMapper,
                dataIngestionServiceMapper,
                protocolConversionServiceMapper,
                notificationService);
    }

    public List<SystemTenantView> listTenants() {
        List<TenantEntity> tenants = tenantMapper.selectList(tenantListQuery());
        List<SystemTenantView> result = new ArrayList<SystemTenantView>();
        for (TenantEntity tenant : tenants) {
            result.add(toTenantView(tenant));
        }
        return result;
    }

    public PageView<SystemTenantView> listTenantsPage(Integer pageNo, Integer pageSize) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        Page<TenantEntity> page = new Page<TenantEntity>(safePageNo, safePageSize);
        Page<TenantEntity> entityPage = tenantMapper.selectPage(page, tenantListQuery());
        List<SystemTenantView> items = new ArrayList<SystemTenantView>();
        for (TenantEntity tenant : entityPage.getRecords()) {
            items.add(toTenantView(tenant));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
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
        List<SystemProjectView> result = new ArrayList<SystemProjectView>();
        for (ProjectEntity project : projectMapper.selectList(projectListQuery(tenantId))) {
            result.add(toProjectView(project));
        }
        return result;
    }

    public PageView<SystemProjectView> listProjectsPage(Integer pageNo, Integer pageSize) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        String tenantId = requireCurrentTenantId();
        Page<ProjectEntity> page = new Page<ProjectEntity>(safePageNo, safePageSize);
        Page<ProjectEntity> entityPage = projectMapper.selectPage(page, projectListQuery(tenantId));
        List<SystemProjectView> items = new ArrayList<SystemProjectView>();
        for (ProjectEntity project : entityPage.getRecords()) {
            items.add(toProjectView(project));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
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
        List<TenantMemberEntity> members = tenantMemberMapper.selectList(tenantMemberListQuery(tenantId));
        Map<Long, StudioUserEntity> userMap = loadUserMap(extractTenantUserIds(members));
        List<SystemTenantMemberView> result = new ArrayList<SystemTenantMemberView>();
        for (TenantMemberEntity member : members) {
            result.add(toTenantMemberView(member, userMap.get(member.getUserId())));
        }
        return result;
    }

    public PageView<SystemTenantMemberView> listTenantMembersPage(Integer pageNo, Integer pageSize) {
        requireAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN);
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        String tenantId = requireCurrentTenantId();
        Page<TenantMemberEntity> page = new Page<TenantMemberEntity>(safePageNo, safePageSize);
        Page<TenantMemberEntity> entityPage = tenantMemberMapper.selectPage(page, tenantMemberListQuery(tenantId));
        List<TenantMemberEntity> members = entityPage.getRecords();
        Map<Long, StudioUserEntity> userMap = loadUserMap(extractTenantUserIds(members));
        List<SystemTenantMemberView> items = new ArrayList<SystemTenantMemberView>();
        for (TenantMemberEntity member : members) {
            items.add(toTenantMemberView(member, userMap.get(member.getUserId())));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    @Transactional
    public TenantMemberEntity saveTenantMember(TenantMemberEntity entity) {
        requireAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN);
        String tenantId = requireCurrentTenantId();
        StudioUserEntity user = requireUser(entity == null ? null : entity.getUserId());
        TenantMemberEntity target = entity.getId() == null
                ? tenantMemberMapper.selectByTenantAndUserIncludingDeleted(tenantId, user.getId())
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
            tenantMemberMapper.restoreById(target.getId(), tenantId, user.getId(), target.getRoleCode(), target.getStatus());
            target.setDeleted(0);
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
        List<ProjectMemberEntity> members = projectMemberMapper.selectList(projectMemberListQuery(project));
        Map<Long, StudioUserEntity> userMap = loadUserMap(extractProjectUserIds(members));
        List<SystemProjectMemberView> result = new ArrayList<SystemProjectMemberView>();
        for (ProjectMemberEntity member : members) {
            result.add(toProjectMemberView(member, project, userMap.get(member.getUserId())));
        }
        return result;
    }

    public PageView<SystemProjectMemberView> listProjectMembersPage(Long projectId, Integer pageNo, Integer pageSize) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        ProjectEntity project = requireManageableProject(projectId);
        Page<ProjectMemberEntity> page = new Page<ProjectMemberEntity>(safePageNo, safePageSize);
        Page<ProjectMemberEntity> entityPage = projectMemberMapper.selectPage(page, projectMemberListQuery(project));
        List<ProjectMemberEntity> members = entityPage.getRecords();
        Map<Long, StudioUserEntity> userMap = loadUserMap(extractProjectUserIds(members));
        List<SystemProjectMemberView> items = new ArrayList<SystemProjectMemberView>();
        for (ProjectMemberEntity member : members) {
            items.add(toProjectMemberView(member, project, userMap.get(member.getUserId())));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    @Transactional
    public ProjectMemberEntity saveProjectMember(ProjectMemberEntity entity) {
        ProjectEntity project = requireManageableProject(entity == null ? null : entity.getProjectId());
        StudioUserEntity user = requireUser(entity == null ? null : entity.getUserId());
        ProjectMemberEntity target = entity.getId() == null
                ? projectMemberMapper.selectByProjectAndUserIncludingDeleted(project.getId(), user.getId())
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
            projectMemberMapper.restoreById(target.getId(), project.getId(), user.getId(), target.getRoleCode(), target.getStatus());
            target.setDeleted(0);
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
        List<ProjectMemberRequestEntity> requests = projectMemberRequestMapper.selectList(projectMemberRequestListQuery(project));
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
        ProjectMemberRequestEntity existing = entity != null && entity.getId() != null
                ? requireProjectMemberRequest(entity.getId(), project.getId(), project.getTenantId())
                : null;
        String previousStatus = existing == null ? null : existing.getStatus();
        ProjectMemberRequestEntity target = entity.getId() == null
                ? new ProjectMemberRequestEntity()
                : existing;
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
        maybeNotifyProjectAccessReview(project, user, target, previousStatus);
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
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime recentThreshold = now.minusHours(WORKER_RECENT_INSTANCE_HOURS);
        List<WorkerLeaseEntity> workerLeases = workerLeaseMapper.selectList(new LambdaQueryWrapper<WorkerLeaseEntity>()
                .select(WorkerLeaseEntity::getWorkerGroupCode,
                        WorkerLeaseEntity::getWorkerCode,
                        WorkerLeaseEntity::getWorkerKind,
                        WorkerLeaseEntity::getInstanceId,
                        WorkerLeaseEntity::getHostName,
                        WorkerLeaseEntity::getPodName,
                        WorkerLeaseEntity::getNodeName,
                        WorkerLeaseEntity::getStatus,
                        WorkerLeaseEntity::getLastHeartbeatAt,
                        WorkerLeaseEntity::getLeaseExpiresAt)
                .eq(WorkerLeaseEntity::getTenantId, project.getTenantId())
                .orderByDesc(WorkerLeaseEntity::getLastHeartbeatAt)
                .orderByAsc(WorkerLeaseEntity::getWorkerGroupCode)
                .orderByAsc(WorkerLeaseEntity::getWorkerCode));
        List<ProjectWorkerBindingEntity> bindings = projectWorkerBindingMapper.selectList(new LambdaQueryWrapper<ProjectWorkerBindingEntity>()
                .eq(ProjectWorkerBindingEntity::getTenantId, project.getTenantId())
                .eq(ProjectWorkerBindingEntity::getProjectId, project.getId())
                .orderByAsc(ProjectWorkerBindingEntity::getWorkerGroupCode)
                .orderByAsc(ProjectWorkerBindingEntity::getWorkerCode));
        Map<String, List<WorkerLeaseEntity>> leaseMap = new LinkedHashMap<String, List<WorkerLeaseEntity>>();
        for (WorkerLeaseEntity lease : workerLeases) {
            String workerGroupCode = resolveWorkerGroupCode(lease);
            if (!hasText(workerGroupCode)) {
                continue;
            }
            if (!isOnlineLease(lease, now) && !isRecentLease(lease, recentThreshold)) {
                continue;
            }
            leaseMap.computeIfAbsent(workerGroupCode, key -> new ArrayList<WorkerLeaseEntity>()).add(lease);
        }
        Map<String, ProjectWorkerBindingEntity> bindingMap = new LinkedHashMap<String, ProjectWorkerBindingEntity>();
        for (ProjectWorkerBindingEntity binding : bindings) {
            String workerGroupCode = resolveWorkerGroupCode(binding);
            if (hasText(workerGroupCode)) {
                bindingMap.put(workerGroupCode, binding);
            }
        }
        Set<String> workerGroupCodes = new LinkedHashSet<String>();
        workerGroupCodes.addAll(leaseMap.keySet());
        workerGroupCodes.addAll(bindingMap.keySet());
        List<SystemProjectWorkerView> result = new ArrayList<SystemProjectWorkerView>();
        for (String workerGroupCode : workerGroupCodes) {
            List<WorkerLeaseEntity> leases = leaseMap.get(workerGroupCode);
            WorkerLeaseEntity latestLease = chooseDisplayLease(leases, now);
            List<SystemWorkerInstanceView> instances = toWorkerInstances(leases, now);
            result.add(toProjectWorkerView(project.getId(), project.getTenantId(), latestLease,
                    bindingMap.get(workerGroupCode),
                    countOnlineInstances(leases, now),
                    instances.size(),
                    latestHeartbeatAt(leases),
                    displayWorkerGroupStatus(instances),
                    instances));
        }
        return result;
    }

    public PageView<SystemProjectWorkerView> listProjectWorkersPage(Long projectId, Integer pageNo, Integer pageSize) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        ProjectEntity project = requireTenantManagedProject(projectId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime recentThreshold = now.minusHours(WORKER_RECENT_INSTANCE_HOURS);
        long total = workerLeaseMapper.countVisibleWorkerGroups(project.getTenantId(), project.getId(), recentThreshold, now);
        if (total <= 0L) {
            return PageView.of(safePageNo, safePageSize, 0L, Collections.<SystemProjectWorkerView>emptyList());
        }
        int offset = (safePageNo - 1) * safePageSize;
        List<String> workerGroupCodes = workerLeaseMapper.selectVisibleWorkerGroupPage(
                project.getTenantId(), project.getId(), recentThreshold, now, safePageSize, offset);
        if (workerGroupCodes.isEmpty()) {
            return PageView.of(safePageNo, safePageSize, total, Collections.<SystemProjectWorkerView>emptyList());
        }
        List<WorkerLeaseEntity> workerLeases = workerLeaseMapper.selectVisibleLeasesForGroups(
                project.getTenantId(), recentThreshold, now, workerGroupCodes);
        List<ProjectWorkerBindingEntity> bindings = projectWorkerBindingMapper.selectForWorkerGroups(
                project.getTenantId(), project.getId(), workerGroupCodes);
        return PageView.of(safePageNo, safePageSize, total,
                assembleProjectWorkerViews(project, workerGroupCodes, workerLeases, bindings, now));
    }

    public PageView<SystemProjectMemberRequestView> listProjectMemberRequestsPage(Long projectId, Integer pageNo, Integer pageSize) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        ProjectEntity project = requireManageableProject(projectId);
        Page<ProjectMemberRequestEntity> page = new Page<ProjectMemberRequestEntity>(safePageNo, safePageSize);
        Page<ProjectMemberRequestEntity> entityPage = projectMemberRequestMapper.selectPage(page, projectMemberRequestListQuery(project));
        List<ProjectMemberRequestEntity> requests = entityPage.getRecords();
        Set<Long> userIds = new LinkedHashSet<Long>();
        for (ProjectMemberRequestEntity request : requests) {
            addIfNotNull(userIds, request.getUserId());
            addIfNotNull(userIds, request.getInviterUserId());
            addIfNotNull(userIds, request.getReviewerUserId());
        }
        Map<Long, StudioUserEntity> userMap = loadUserMap(userIds);
        List<SystemProjectMemberRequestView> items = new ArrayList<SystemProjectMemberRequestView>();
        for (ProjectMemberRequestEntity request : requests) {
            items.add(toProjectMemberRequestView(
                    request,
                    project,
                    userMap.get(request.getUserId()),
                    userMap.get(request.getInviterUserId()),
                    userMap.get(request.getReviewerUserId())));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    private List<SystemProjectWorkerView> assembleProjectWorkerViews(ProjectEntity project,
                                                                     List<String> workerGroupCodes,
                                                                     List<WorkerLeaseEntity> workerLeases,
                                                                     List<ProjectWorkerBindingEntity> bindings,
                                                                     LocalDateTime now) {
        Map<String, List<WorkerLeaseEntity>> leaseMap = new LinkedHashMap<String, List<WorkerLeaseEntity>>();
        for (WorkerLeaseEntity lease : workerLeases) {
            String workerGroupCode = resolveWorkerGroupCode(lease);
            if (hasText(workerGroupCode)) {
                leaseMap.computeIfAbsent(workerGroupCode, key -> new ArrayList<WorkerLeaseEntity>()).add(lease);
            }
        }
        Map<String, ProjectWorkerBindingEntity> bindingMap = new LinkedHashMap<String, ProjectWorkerBindingEntity>();
        for (ProjectWorkerBindingEntity binding : bindings) {
            String workerGroupCode = resolveWorkerGroupCode(binding);
            if (hasText(workerGroupCode)) {
                bindingMap.put(workerGroupCode, binding);
            }
        }
        List<SystemProjectWorkerView> result = new ArrayList<SystemProjectWorkerView>();
        for (String workerGroupCode : workerGroupCodes) {
            List<WorkerLeaseEntity> leases = leaseMap.get(workerGroupCode);
            WorkerLeaseEntity latestLease = chooseDisplayLease(leases, now);
            List<SystemWorkerInstanceView> instances = toWorkerInstances(leases, now);
            result.add(toProjectWorkerView(project.getId(), project.getTenantId(), latestLease,
                    bindingMap.get(workerGroupCode),
                    countOnlineInstances(leases, now),
                    instances.size(),
                    latestHeartbeatAt(leases),
                    displayWorkerGroupStatus(instances),
                    instances));
        }
        return result;
    }

    @Transactional
    public ProjectWorkerBindingEntity saveProjectWorkerBinding(ProjectWorkerBindingEntity entity) {
        ProjectEntity project = requireTenantManagedProject(entity == null ? null : entity.getProjectId());
        String workerGroupCode = resolveWorkerGroupCode(entity);
        if (!hasText(workerGroupCode)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Worker group is required");
        }
        final String normalizedWorkerGroupCode = workerGroupCode.trim();
        ProjectWorkerBindingEntity target = entity.getId() == null
                ? projectWorkerBindingMapper.selectIncludingDeleted(project.getTenantId(), project.getId(), normalizedWorkerGroupCode)
                : requireProjectWorkerBinding(entity.getId(), project.getId(), project.getTenantId());
        if (entity.getId() != null && !normalizedWorkerGroupCode.equals(resolveWorkerGroupCode(target))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Worker group cannot be changed for an existing binding");
        }
        if (target == null) {
            target = new ProjectWorkerBindingEntity();
        }
        target.setTenantId(project.getTenantId());
        target.setProjectId(project.getId());
        target.setWorkerGroupCode(normalizedWorkerGroupCode);
        target.setWorkerCode(normalizedWorkerGroupCode);
        target.setEnabled(entity.getEnabled() == null ? 1 : entity.getEnabled());
        if (target.getId() == null) {
            projectWorkerBindingMapper.insert(target);
        } else if (target.getDeleted() != null && target.getDeleted() == 1) {
            projectWorkerBindingMapper.reviveDeletedById(target.getId(), target.getWorkerGroupCode(), target.getWorkerCode(), target.getEnabled());
            target.setDeleted(0);
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
        return resourceShareMapper.selectList(resourceShareQuery(project, resourceType));
    }

    public PageView<ResourceShareView> listResourceSharesPage(String resourceType,
                                                              Long projectId,
                                                              Integer pageNo,
                                                              Integer pageSize) {
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        ProjectEntity project = requireManageableProject(projectId);
        Page<ResourceShareEntity> page = new Page<ResourceShareEntity>(safePageNo, safePageSize);
        Page<ResourceShareEntity> entityPage = resourceShareMapper.selectPage(page, resourceShareQuery(project, resourceType));
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(),
                toResourceShareViews(project, entityPage.getRecords()));
    }

    public List<ShareResourceOptionView> listShareResourceOptions(String resourceType, Long projectId) {
        if (!hasText(resourceType)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Resource type is required");
        }
        ProjectEntity project = requireManageableProject(projectId);
        return resourceShareSupport.listShareResourceOptions(project.getTenantId(),
                project.getId(),
                resourceType.trim().toUpperCase());
    }

    @Transactional
    public ResourceShareEntity saveResourceShare(ResourceShareEntity entity) {
        ProjectEntity project = requireManageableProject(entity == null ? null : entity.getSourceProjectId());
        if (entity == null || entity.getTargetProjectId() == null || !hasText(entity.getResourceType()) || entity.getResourceId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Resource share target, type and resource id are required");
        }
        String normalizedResourceType = entity.getResourceType().trim().toUpperCase();
        resourceShareSupport.validateShareableResource(project.getTenantId(), project.getId(), normalizedResourceType, entity.getResourceId());
        ProjectEntity targetProject = requireProject(entity.getTargetProjectId(), project.getTenantId());
        if (project.getId().equals(targetProject.getId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Source and target project cannot be the same");
        }
        ResourceShareEntity target = entity.getId() == null
                ? resourceShareMapper.selectIncludingDeleted(project.getTenantId(), normalizedResourceType, entity.getResourceId(), targetProject.getId())
                : requireResourceShare(entity.getId(), project.getTenantId(), project.getId());
        if (target == null) {
            target = new ResourceShareEntity();
        }
        target.setTenantId(project.getTenantId());
        target.setSourceProjectId(project.getId());
        target.setTargetProjectId(targetProject.getId());
        target.setResourceType(normalizedResourceType);
        target.setResourceId(entity.getResourceId());
        target.setSharedByUserId(securityService.currentUserId());
        target.setEnabled(entity.getEnabled() == null ? 1 : entity.getEnabled());
        if (target.getId() == null) {
            resourceShareMapper.insert(target);
        } else if (target.getDeleted() != null && target.getDeleted() == 1) {
            resourceShareMapper.reviveDeletedById(target.getId(), target.getEnabled(), target.getSharedByUserId(), target.getSourceProjectId());
            target.setDeleted(0);
        } else {
            resourceShareMapper.updateById(target);
        }
        if (target.getEnabled() != null && target.getEnabled().intValue() == 1) {
            resourceShareSupport.notifyResourceShare(target, targetProject);
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

    private LambdaQueryWrapper<TenantEntity> tenantListQuery() {
        LambdaQueryWrapper<TenantEntity> queryWrapper = new LambdaQueryWrapper<TenantEntity>()
                .orderByAsc(TenantEntity::getTenantName)
                .orderByAsc(TenantEntity::getId);
        if (hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)) {
            return queryWrapper;
        }
        if (hasText(securityService.currentTenantId())) {
            queryWrapper.eq(TenantEntity::getTenantId, securityService.currentTenantId());
        } else {
            queryWrapper.eq(TenantEntity::getTenantId, "");
        }
        return queryWrapper;
    }

    private LambdaQueryWrapper<ProjectEntity> projectListQuery(String tenantId) {
        LambdaQueryWrapper<ProjectEntity> queryWrapper = new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getTenantId, tenantId)
                .orderByDesc(ProjectEntity::getDefaultProject)
                .orderByAsc(ProjectEntity::getProjectName)
                .orderByAsc(ProjectEntity::getId);
        if (!hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN)
                && securityService.currentProjectId() != null) {
            queryWrapper.eq(ProjectEntity::getId, securityService.currentProjectId());
        }
        return queryWrapper;
    }

    private LambdaQueryWrapper<TenantMemberEntity> tenantMemberListQuery(String tenantId) {
        return new LambdaQueryWrapper<TenantMemberEntity>()
                .eq(TenantMemberEntity::getTenantId, tenantId)
                .orderByAsc(TenantMemberEntity::getCreatedAt)
                .orderByAsc(TenantMemberEntity::getId);
    }

    private LambdaQueryWrapper<ProjectMemberEntity> projectMemberListQuery(ProjectEntity project) {
        return new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, project.getId())
                .orderByAsc(ProjectMemberEntity::getCreatedAt)
                .orderByAsc(ProjectMemberEntity::getId);
    }

    private LambdaQueryWrapper<ProjectMemberRequestEntity> projectMemberRequestListQuery(ProjectEntity project) {
        return new LambdaQueryWrapper<ProjectMemberRequestEntity>()
                .eq(ProjectMemberRequestEntity::getProjectId, project.getId())
                .orderByDesc(ProjectMemberRequestEntity::getCreatedAt)
                .orderByDesc(ProjectMemberRequestEntity::getId);
    }

    private Map<Long, StudioUserEntity> loadUserMap(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, StudioUserEntity> userMap = new LinkedHashMap<Long, StudioUserEntity>();
        for (StudioUserEntity user : userMapper.selectByIds(userIds)) {
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
        ProjectMemberEntity member = projectMemberMapper.selectByProjectAndUserIncludingDeleted(projectId, userId);
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
        String resolvedRoleCode = hasText(member.getRoleCode()) ? member.getRoleCode() : roleCode;
        projectMemberMapper.restoreById(member.getId(), projectId, userId, resolvedRoleCode, StudioConstants.MEMBER_STATUS_ACTIVE);
    }

    private boolean isReviewStatus(String status) {
        return StudioConstants.MEMBER_REQUEST_APPROVED.equalsIgnoreCase(status)
                || StudioConstants.MEMBER_REQUEST_REJECTED.equalsIgnoreCase(status);
    }

    private boolean isApprovedStatus(String status) {
        return StudioConstants.MEMBER_REQUEST_APPROVED.equalsIgnoreCase(status)
                || StudioConstants.MEMBER_REQUEST_ACCEPTED.equalsIgnoreCase(status);
    }

    private void maybeNotifyProjectAccessReview(ProjectEntity project,
                                                StudioUserEntity user,
                                                ProjectMemberRequestEntity request,
                                                String previousStatus) {
        if (project == null
                || user == null
                || request == null
                || request.getId() == null
                || !StudioConstants.MEMBER_REQUEST_APPLY.equalsIgnoreCase(request.getRequestType())
                || !isReviewStatus(request.getStatus())
                || request.getUserId() == null) {
            return;
        }
        if (previousStatus != null && previousStatus.equalsIgnoreCase(request.getStatus())) {
            return;
        }
        boolean approved = isApprovedStatus(request.getStatus());
        notificationService.notifyUsers(Collections.singletonList(request.getUserId()),
                new NotificationCommand()
                        .setCategory(StudioConstants.NOTIFICATION_CATEGORY_PROJECT_ACCESS_REVIEW)
                        .setTitle(approved ? "项目加入申请已通过" : "项目加入申请未通过")
                        .setContent(approved
                                ? "你已获准加入项目 " + project.getProjectName() + "，现在可以进入对应租户与项目。"
                                : (hasText(request.getReviewComment())
                                ? request.getReviewComment()
                                : "项目 " + project.getProjectName() + " 的加入申请未通过。"))
                        .setTargetType("PROJECT_MEMBER_REQUEST")
                        .setTargetId(request.getId())
                        .setTargetPath(approved ? "/dashboard" : "/access-center")
                        .setTargetTenantId(project.getTenantId())
                        .setTargetProjectId(approved ? project.getId() : null)
                        .setDedupeKey("project-access-review:" + request.getId() + ":" + request.getStatus().toLowerCase()));
    }

    private void addIfNotNull(Set<Long> target, Long value) {
        if (value != null) {
            target.add(value);
        }
    }

    private int countOnlineInstances(List<WorkerLeaseEntity> leases, LocalDateTime now) {
        if (leases == null || leases.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (WorkerLeaseEntity lease : leases) {
            if (isOnlineLease(lease, now)) {
                count++;
            }
        }
        return count;
    }

    private WorkerLeaseEntity chooseDisplayLease(List<WorkerLeaseEntity> leases, LocalDateTime now) {
        if (leases == null || leases.isEmpty()) {
            return null;
        }
        for (WorkerLeaseEntity lease : leases) {
            if (isOnlineLease(lease, now)) {
                return lease;
            }
        }
        return leases.get(0);
    }

    private List<SystemWorkerInstanceView> toWorkerInstances(List<WorkerLeaseEntity> leases, LocalDateTime now) {
        if (leases == null || leases.isEmpty()) {
            return Collections.emptyList();
        }
        List<SystemWorkerInstanceView> result = new ArrayList<SystemWorkerInstanceView>();
        for (WorkerLeaseEntity lease : leases) {
            result.add(toWorkerInstanceView(lease, isOnlineLease(lease, now)));
        }
        return result;
    }

    private boolean isOnlineLease(WorkerLeaseEntity lease, LocalDateTime now) {
        if (lease == null || !StudioConstants.WORKER_STATUS_ONLINE.equalsIgnoreCase(lease.getStatus())) {
            return false;
        }
        if (lease.getLeaseExpiresAt() != null && lease.getLeaseExpiresAt().isAfter(now)) {
            return true;
        }
        LocalDateTime heartbeatThreshold = now.minusSeconds(StudioConstants.WORKER_HEARTBEAT_TIMEOUT_SECONDS);
        return lease.getLastHeartbeatAt() != null && !lease.getLastHeartbeatAt().isBefore(heartbeatThreshold);
    }

    private boolean isRecentLease(WorkerLeaseEntity lease, LocalDateTime recentThreshold) {
        return lease != null
                && lease.getLastHeartbeatAt() != null
                && !lease.getLastHeartbeatAt().isBefore(recentThreshold);
    }

    private LocalDateTime latestHeartbeatAt(List<WorkerLeaseEntity> leases) {
        if (leases == null || leases.isEmpty()) {
            return null;
        }
        LocalDateTime latest = null;
        for (WorkerLeaseEntity lease : leases) {
            if (lease == null || lease.getLastHeartbeatAt() == null) {
                continue;
            }
            if (latest == null || lease.getLastHeartbeatAt().isAfter(latest)) {
                latest = lease.getLastHeartbeatAt();
            }
        }
        return latest;
    }

    private String displayWorkerGroupStatus(List<SystemWorkerInstanceView> instances) {
        if (instances == null || instances.isEmpty()) {
            return "NO_INSTANCE";
        }
        for (SystemWorkerInstanceView instance : instances) {
            if (instance != null && Boolean.TRUE.equals(instance.getOnline())) {
                return "ONLINE";
            }
        }
        return "OFFLINE";
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

    private LambdaQueryWrapper<ResourceShareEntity> resourceShareQuery(ProjectEntity project, String resourceType) {
        LambdaQueryWrapper<ResourceShareEntity> queryWrapper = new LambdaQueryWrapper<ResourceShareEntity>()
                .eq(ResourceShareEntity::getTenantId, project.getTenantId())
                .eq(ResourceShareEntity::getSourceProjectId, project.getId());
        if (hasText(resourceType)) {
            queryWrapper.eq(ResourceShareEntity::getResourceType, resourceType.trim().toUpperCase());
        }
        return queryWrapper
                .orderByDesc(ResourceShareEntity::getCreatedAt)
                .orderByDesc(ResourceShareEntity::getId);
    }

    private List<ResourceShareView> toResourceShareViews(ProjectEntity sourceProject, List<ResourceShareEntity> shares) {
        List<ResourceShareView> result = new ArrayList<ResourceShareView>();
        if (shares == null || shares.isEmpty()) {
            return result;
        }
        Map<Long, ProjectEntity> projectMap = loadProjectsForShares(shares);
        Map<String, ShareResourceOptionView> resourceMap = resourceShareSupport.listShareResourceOptionsByIds(
                sourceProject.getTenantId(), sourceProject.getId(), resourceIdsByType(shares));
        for (ResourceShareEntity share : shares) {
            result.add(toResourceShareView(share,
                    projectMap.get(share.getSourceProjectId()),
                    projectMap.get(share.getTargetProjectId()),
                    resourceMap.get(resourceKey(share.getResourceType(), share.getResourceId()))));
        }
        return result;
    }

    private Map<Long, ProjectEntity> loadProjectsForShares(List<ResourceShareEntity> shares) {
        Set<Long> projectIds = new LinkedHashSet<Long>();
        for (ResourceShareEntity share : shares) {
            if (share.getSourceProjectId() != null) {
                projectIds.add(share.getSourceProjectId());
            }
            if (share.getTargetProjectId() != null) {
                projectIds.add(share.getTargetProjectId());
            }
        }
        Map<Long, ProjectEntity> result = new LinkedHashMap<Long, ProjectEntity>();
        if (projectIds.isEmpty()) {
            return result;
        }
        List<ProjectEntity> projects = projectMapper.selectByIds(projectIds);
        for (ProjectEntity project : projects) {
            result.put(project.getId(), project);
        }
        return result;
    }

    private Map<String, Set<Long>> resourceIdsByType(List<ResourceShareEntity> shares) {
        Map<String, Set<Long>> result = new LinkedHashMap<String, Set<Long>>();
        for (ResourceShareEntity share : shares) {
            String resourceType = normalizeResourceType(share.getResourceType());
            if (!hasText(resourceType) || share.getResourceId() == null) {
                continue;
            }
            Set<Long> ids = result.get(resourceType);
            if (ids == null) {
                ids = new LinkedHashSet<Long>();
                result.put(resourceType, ids);
            }
            ids.add(share.getResourceId());
        }
        return result;
    }

    private String resourceKey(String resourceType, Long resourceId) {
        return normalizeResourceType(resourceType) + ":" + resourceId;
    }

    private String normalizeResourceType(String resourceType) {
        return hasText(resourceType) ? resourceType.trim().toUpperCase() : "";
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo.intValue() < 1 ? DEFAULT_PAGE_NO : pageNo.intValue();
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize.intValue() < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize.intValue(), MAX_PAGE_SIZE);
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
