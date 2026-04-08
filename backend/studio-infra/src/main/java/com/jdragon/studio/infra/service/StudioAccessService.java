package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.auth.AuthProfileView;
import com.jdragon.studio.dto.model.auth.AuthProjectView;
import com.jdragon.studio.dto.model.auth.AuthTenantView;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.RoleEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.TenantEntity;
import com.jdragon.studio.infra.entity.TenantMemberEntity;
import com.jdragon.studio.infra.entity.UserRoleEntity;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.RoleMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.TenantMapper;
import com.jdragon.studio.infra.mapper.TenantMemberMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioUserPrincipal;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class StudioAccessService {

    private final StudioUserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final TenantMapper tenantMapper;
    private final TenantMemberMapper tenantMemberMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public StudioAccessService(StudioUserMapper userMapper,
                               UserRoleMapper userRoleMapper,
                               RoleMapper roleMapper,
                               TenantMapper tenantMapper,
                               TenantMemberMapper tenantMemberMapper,
                               ProjectMapper projectMapper,
                               ProjectMemberMapper projectMemberMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.tenantMapper = tenantMapper;
        this.tenantMemberMapper = tenantMemberMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    public AuthProfileView buildProfile(StudioUserPrincipal principal,
                                        String requestedTenantId,
                                        String requestedProjectId,
                                        String token) {
        if (principal == null) {
            return new AuthProfileView();
        }
        StudioUserEntity user = userMapper.selectById(principal.getUserId());
        if (user == null) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "User not found: " + principal.getUsername());
        }

        List<String> systemRoleCodes = loadSystemRoleCodes(user.getId());
        boolean superAdmin = containsRole(systemRoleCodes, StudioConstants.ROLE_SUPER_ADMIN);
        Map<String, List<String>> tenantRoleCodes = loadTenantRoleCodes(user.getId());
        Map<Long, List<String>> projectRoleCodes = loadProjectRoleCodes(user.getId());
        Map<Long, ProjectEntity> accessibleProjectMap = loadAccessibleProjectMap(superAdmin, projectRoleCodes.keySet());

        List<AuthTenantView> tenantViews = buildTenantViews(superAdmin, systemRoleCodes, tenantRoleCodes, accessibleProjectMap);
        String currentTenantId = resolveTenantId(user.getTenantId(), requestedTenantId, tenantViews);
        List<AuthProjectView> projectViews = buildProjectViews(superAdmin, systemRoleCodes, currentTenantId, projectRoleCodes);
        Long currentProjectId = resolveProjectId(requestedProjectId, projectViews);
        List<String> effectiveRoleCodes = mergeRoleCodes(
                systemRoleCodes,
                tenantRoleCodes.get(currentTenantId),
                currentProjectId == null ? null : projectRoleCodes.get(currentProjectId));

        AuthProfileView view = new AuthProfileView();
        view.setToken(token);
        view.setUserId(user.getId());
        view.setUsername(user.getUsername());
        view.setDisplayName(user.getDisplayName());
        view.setCurrentTenantId(currentTenantId);
        view.setCurrentProjectId(currentProjectId);
        view.setSystemRoleCodes(systemRoleCodes);
        view.setEffectiveRoleCodes(effectiveRoleCodes);
        view.setTenants(tenantViews);
        view.setProjects(projectViews);
        return view;
    }

    public StudioRequestContext buildRequestContext(StudioUserPrincipal principal,
                                                    String requestedTenantId,
                                                    String requestedProjectId) {
        AuthProfileView profile = buildProfile(principal, requestedTenantId, requestedProjectId, null);
        StudioRequestContext context = new StudioRequestContext();
        context.setUserId(profile.getUserId());
        context.setUsername(profile.getUsername());
        context.setDisplayName(profile.getDisplayName());
        context.setTenantId(profile.getCurrentTenantId());
        context.setProjectId(profile.getCurrentProjectId());
        context.setSystemRoleCodes(profile.getSystemRoleCodes());
        context.setEffectiveRoleCodes(profile.getEffectiveRoleCodes());
        return context;
    }

    private List<String> loadSystemRoleCodes(Long userId) {
        List<UserRoleEntity> userRoles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>()
                .eq(UserRoleEntity::getUserId, userId));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> roleIds = new LinkedHashSet<Long>();
        for (UserRoleEntity userRole : userRoles) {
            roleIds.add(userRole.getRoleId());
        }
        List<RoleEntity> roles = roleMapper.selectBatchIds(roleIds);
        List<String> codes = new ArrayList<String>();
        for (RoleEntity role : roles) {
            appendUnique(codes, role.getCode());
        }
        return codes;
    }

    private Map<String, List<String>> loadTenantRoleCodes(Long userId) {
        List<TenantMemberEntity> members = tenantMemberMapper.selectList(new LambdaQueryWrapper<TenantMemberEntity>()
                .eq(TenantMemberEntity::getUserId, userId)
                .eq(TenantMemberEntity::getStatus, StudioConstants.MEMBER_STATUS_ACTIVE));
        Map<String, List<String>> roleCodes = new LinkedHashMap<String, List<String>>();
        for (TenantMemberEntity member : members) {
            List<String> codes = roleCodes.get(member.getTenantId());
            if (codes == null) {
                codes = new ArrayList<String>();
                roleCodes.put(member.getTenantId(), codes);
            }
            appendUnique(codes, member.getRoleCode());
        }
        return roleCodes;
    }

    private Map<Long, List<String>> loadProjectRoleCodes(Long userId) {
        List<ProjectMemberEntity> members = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getUserId, userId)
                .eq(ProjectMemberEntity::getStatus, StudioConstants.MEMBER_STATUS_ACTIVE));
        Map<Long, List<String>> roleCodes = new LinkedHashMap<Long, List<String>>();
        for (ProjectMemberEntity member : members) {
            List<String> codes = roleCodes.get(member.getProjectId());
            if (codes == null) {
                codes = new ArrayList<String>();
                roleCodes.put(member.getProjectId(), codes);
            }
            appendUnique(codes, member.getRoleCode());
        }
        return roleCodes;
    }

    private Map<Long, ProjectEntity> loadAccessibleProjectMap(boolean superAdmin, Set<Long> projectIds) {
        List<ProjectEntity> projects;
        if (superAdmin) {
            projects = projectMapper.selectList(new LambdaQueryWrapper<ProjectEntity>()
                    .orderByDesc(ProjectEntity::getDefaultProject)
                    .orderByAsc(ProjectEntity::getProjectName));
        } else if (projectIds.isEmpty()) {
            projects = Collections.emptyList();
        } else {
            projects = projectMapper.selectBatchIds(projectIds);
        }
        Map<Long, ProjectEntity> projectMap = new LinkedHashMap<Long, ProjectEntity>();
        for (ProjectEntity project : projects) {
            projectMap.put(project.getId(), project);
        }
        return projectMap;
    }

    private List<AuthTenantView> buildTenantViews(boolean superAdmin,
                                                  List<String> systemRoleCodes,
                                                  Map<String, List<String>> tenantRoleCodes,
                                                  Map<Long, ProjectEntity> accessibleProjectMap) {
        List<TenantEntity> tenants;
        if (superAdmin) {
            tenants = tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
                    .orderByAsc(TenantEntity::getTenantName));
        } else {
            Set<String> tenantIds = new LinkedHashSet<String>(tenantRoleCodes.keySet());
            for (ProjectEntity project : accessibleProjectMap.values()) {
                if (project.getTenantId() != null && !project.getTenantId().trim().isEmpty()) {
                    tenantIds.add(project.getTenantId());
                }
            }
            if (tenantIds.isEmpty()) {
                return Collections.emptyList();
            }
            tenants = tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
                    .in(TenantEntity::getTenantId, tenantIds)
                    .orderByAsc(TenantEntity::getTenantName));
        }
        List<AuthTenantView> views = new ArrayList<AuthTenantView>();
        for (TenantEntity tenant : tenants) {
            AuthTenantView view = new AuthTenantView();
            view.setTenantId(tenant.getTenantId());
            view.setTenantCode(tenant.getTenantCode());
            view.setTenantName(tenant.getTenantName());
            view.setEnabled(asBoolean(tenant.getEnabled(), true));
            view.setRoleCodes(mergeRoleCodes(
                    superAdmin ? systemRoleCodes : null,
                    tenantRoleCodes.get(tenant.getTenantId()),
                    null));
            views.add(view);
        }
        sortTenantViews(views);
        return views;
    }

    private List<AuthProjectView> buildProjectViews(boolean superAdmin,
                                                    List<String> systemRoleCodes,
                                                    String tenantId,
                                                    Map<Long, List<String>> projectRoleCodes) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<ProjectEntity> projects;
        if (superAdmin) {
            projects = projectMapper.selectList(new LambdaQueryWrapper<ProjectEntity>()
                    .eq(ProjectEntity::getTenantId, tenantId)
                    .orderByDesc(ProjectEntity::getDefaultProject)
                    .orderByAsc(ProjectEntity::getProjectName));
        } else {
            if (projectRoleCodes.isEmpty()) {
                return Collections.emptyList();
            }
            projects = projectMapper.selectList(new LambdaQueryWrapper<ProjectEntity>()
                    .eq(ProjectEntity::getTenantId, tenantId)
                    .in(ProjectEntity::getId, projectRoleCodes.keySet())
                    .orderByDesc(ProjectEntity::getDefaultProject)
                    .orderByAsc(ProjectEntity::getProjectName));
        }
        List<AuthProjectView> views = new ArrayList<AuthProjectView>();
        for (ProjectEntity project : projects) {
            AuthProjectView view = new AuthProjectView();
            view.setProjectId(project.getId());
            view.setTenantId(project.getTenantId());
            view.setProjectCode(project.getProjectCode());
            view.setProjectName(project.getProjectName());
            view.setEnabled(asBoolean(project.getEnabled(), true));
            view.setDefaultProject(asBoolean(project.getDefaultProject(), false));
            view.setRoleCodes(mergeRoleCodes(
                    superAdmin ? systemRoleCodes : null,
                    projectRoleCodes.get(project.getId()),
                    null));
            views.add(view);
        }
        return views;
    }

    private String resolveTenantId(String fallbackTenantId,
                                   String requestedTenantId,
                                   List<AuthTenantView> tenantViews) {
        if (tenantViews.isEmpty()) {
            return null;
        }
        String normalizedRequested = normalize(requestedTenantId);
        if (normalizedRequested != null) {
            if (containsTenant(tenantViews, normalizedRequested)) {
                return normalizedRequested;
            }
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Tenant access denied: " + normalizedRequested);
        }
        String normalizedFallback = normalize(fallbackTenantId);
        if (normalizedFallback != null && containsTenant(tenantViews, normalizedFallback)) {
            return normalizedFallback;
        }
        if (containsTenant(tenantViews, StudioConstants.DEFAULT_TENANT_ID)) {
            return StudioConstants.DEFAULT_TENANT_ID;
        }
        return tenantViews.get(0).getTenantId();
    }

    private Long resolveProjectId(String requestedProjectId, List<AuthProjectView> projectViews) {
        if (projectViews.isEmpty()) {
            return null;
        }
        Long parsedProjectId = parseProjectId(requestedProjectId);
        if (parsedProjectId != null) {
            if (containsProject(projectViews, parsedProjectId)) {
                return parsedProjectId;
            }
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Project access denied: " + requestedProjectId);
        }
        for (AuthProjectView projectView : projectViews) {
            if (Boolean.TRUE.equals(projectView.getDefaultProject())) {
                return projectView.getProjectId();
            }
        }
        return projectViews.get(0).getProjectId();
    }

    private Long parseProjectId(String requestedProjectId) {
        String normalized = normalize(requestedProjectId);
        if (normalized == null) {
            return null;
        }
        try {
            return Long.valueOf(normalized);
        } catch (NumberFormatException e) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid project id: " + requestedProjectId);
        }
    }

    private boolean containsTenant(List<AuthTenantView> tenantViews, String tenantId) {
        for (AuthTenantView tenantView : tenantViews) {
            if (tenantId.equals(tenantView.getTenantId())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsProject(List<AuthProjectView> projectViews, Long projectId) {
        for (AuthProjectView projectView : projectViews) {
            if (projectId.equals(projectView.getProjectId())) {
                return true;
            }
        }
        return false;
    }

    private List<String> mergeRoleCodes(List<String> systemRoleCodes,
                                        List<String> tenantRoleCodes,
                                        List<String> projectRoleCodes) {
        List<String> merged = new ArrayList<String>();
        appendAllUnique(merged, systemRoleCodes);
        appendAllUnique(merged, tenantRoleCodes);
        appendAllUnique(merged, projectRoleCodes);
        return merged;
    }

    private void appendAllUnique(List<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            appendUnique(target, value);
        }
    }

    private void appendUnique(List<String> target, String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return;
        }
        for (String existing : target) {
            if (existing.equalsIgnoreCase(normalized)) {
                return;
            }
        }
        target.add(normalized);
    }

    private boolean containsRole(List<String> roleCodes, String roleCode) {
        if (roleCodes == null || roleCode == null) {
            return false;
        }
        for (String current : roleCodes) {
            if (roleCode.equalsIgnoreCase(current)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean asBoolean(Integer value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value != 0;
    }

    private void sortTenantViews(List<AuthTenantView> tenantViews) {
        Collections.sort(tenantViews, (left, right) -> {
            int leftPriority = tenantPriority(left);
            int rightPriority = tenantPriority(right);
            if (leftPriority != rightPriority) {
                return Integer.compare(leftPriority, rightPriority);
            }
            return compareText(left.getTenantName(), right.getTenantName());
        });
    }

    private int tenantPriority(AuthTenantView tenantView) {
        if (tenantView == null) {
            return Integer.MAX_VALUE;
        }
        if (StudioConstants.DEFAULT_TENANT_ID.equalsIgnoreCase(normalize(tenantView.getTenantId()))) {
            return 0;
        }
        return 1;
    }

    private int compareText(String left, String right) {
        String leftValue = left == null ? "" : left.toLowerCase(Locale.ENGLISH);
        String rightValue = right == null ? "" : right.toLowerCase(Locale.ENGLISH);
        return leftValue.compareTo(rightValue);
    }
}
