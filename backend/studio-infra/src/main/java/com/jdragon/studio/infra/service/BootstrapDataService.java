package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.entity.PermissionEntity;
import com.jdragon.studio.infra.entity.RoleEntity;
import com.jdragon.studio.infra.entity.RolePermissionEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.UserRoleEntity;
import com.jdragon.studio.infra.mapper.PermissionMapper;
import com.jdragon.studio.infra.mapper.RoleMapper;
import com.jdragon.studio.infra.mapper.RolePermissionMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class BootstrapDataService {

    private final StudioUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final TenantProjectFoundationService tenantProjectFoundationService;

    public BootstrapDataService(StudioUserMapper userMapper,
                                RoleMapper roleMapper,
                                PermissionMapper permissionMapper,
                                UserRoleMapper userRoleMapper,
                                RolePermissionMapper rolePermissionMapper,
                                TenantProjectFoundationService tenantProjectFoundationService) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.tenantProjectFoundationService = tenantProjectFoundationService;
    }

    public void bootstrap() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        StudioUserEntity user = userMapper.selectOne(new LambdaQueryWrapper<StudioUserEntity>()
                .eq(StudioUserEntity::getUsername, StudioConstants.DEFAULT_ADMIN_USERNAME)
                .last("limit 1"));
        if (user == null) {
            user = new StudioUserEntity();
            user.setUsername(StudioConstants.DEFAULT_ADMIN_USERNAME);
            user.setDisplayName("Studio Admin");
            user.setPasswordHash(encoder.encode(StudioConstants.DEFAULT_ADMIN_PASSWORD));
            user.setEnabled(1);
            userMapper.insert(user);
        }

        PermissionEntity permission = ensurePermission();
        RoleEntity adminRole = ensureRole(StudioConstants.ROLE_ADMIN, "Administrator", "Legacy full access role");
        RoleEntity superAdminRole = ensureRole(StudioConstants.ROLE_SUPER_ADMIN, "Super Administrator", "Global studio administrator");
        ensureRole(StudioConstants.ROLE_TENANT_ADMIN, "Tenant Administrator", "Manage users, workers and projects in a tenant");
        ensureRole(StudioConstants.ROLE_PROJECT_ADMIN, "Project Administrator", "Manage project members and project resources");
        ensureRole(StudioConstants.ROLE_PROJECT_MEMBER, "Project Member", "Use project scoped resources");

        ensureRolePermission(adminRole.getId(), permission.getId());
        ensureRolePermission(superAdminRole.getId(), permission.getId());
        ensureUserRole(user.getId(), adminRole.getId());
        ensureUserRole(user.getId(), superAdminRole.getId());

        tenantProjectFoundationService.bootstrapFoundation(user);
    }

    private PermissionEntity ensurePermission() {
        PermissionEntity permission = permissionMapper.selectOne(new LambdaQueryWrapper<PermissionEntity>()
                .eq(PermissionEntity::getCode, "studio:*")
                .last("limit 1"));
        if (permission != null) {
            return permission;
        }
        permission = new PermissionEntity();
        permission.setCode("studio:*");
        permission.setName("Studio All");
        permission.setHttpMethod("*");
        permission.setPathPattern("/api/v1/**");
        permissionMapper.insert(permission);
        return permission;
    }

    private RoleEntity ensureRole(String code, String name, String description) {
        RoleEntity role = roleMapper.selectOne(new LambdaQueryWrapper<RoleEntity>()
                .eq(RoleEntity::getCode, code)
                .last("limit 1"));
        if (role != null) {
            return role;
        }
        role = new RoleEntity();
        role.setCode(code);
        role.setName(name);
        role.setDescription(description);
        roleMapper.insert(role);
        return role;
    }

    private void ensureUserRole(Long userId, Long roleId) {
        if (userRoleMapper.selectCount(new LambdaQueryWrapper<UserRoleEntity>()
                .eq(UserRoleEntity::getUserId, userId)
                .eq(UserRoleEntity::getRoleId, roleId)) > 0) {
            return;
        }
        UserRoleEntity userRole = new UserRoleEntity();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRoleMapper.insert(userRole);
    }

    private void ensureRolePermission(Long roleId, Long permissionId) {
        if (rolePermissionMapper.selectCount(new LambdaQueryWrapper<RolePermissionEntity>()
                .eq(RolePermissionEntity::getRoleId, roleId)
                .eq(RolePermissionEntity::getPermissionId, permissionId)) > 0) {
            return;
        }
        RolePermissionEntity rolePermission = new RolePermissionEntity();
        rolePermission.setRoleId(roleId);
        rolePermission.setPermissionId(permissionId);
        rolePermissionMapper.insert(rolePermission);
    }
}

