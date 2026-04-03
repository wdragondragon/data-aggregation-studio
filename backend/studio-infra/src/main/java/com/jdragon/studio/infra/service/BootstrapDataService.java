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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class BootstrapDataService {

    private final StudioUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;

    public BootstrapDataService(StudioUserMapper userMapper,
                                RoleMapper roleMapper,
                                PermissionMapper permissionMapper,
                                UserRoleMapper userRoleMapper,
                                RolePermissionMapper rolePermissionMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
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

        RoleEntity adminRole = roleMapper.selectOne(new LambdaQueryWrapper<RoleEntity>()
                .eq(RoleEntity::getCode, "ADMIN")
                .last("limit 1"));
        if (adminRole == null) {
            adminRole = new RoleEntity();
            adminRole.setCode("ADMIN");
            adminRole.setName("Administrator");
            adminRole.setDescription("Full access");
            roleMapper.insert(adminRole);
        }

        PermissionEntity permission = permissionMapper.selectOne(new LambdaQueryWrapper<PermissionEntity>()
                .eq(PermissionEntity::getCode, "studio:*")
                .last("limit 1"));
        if (permission == null) {
            permission = new PermissionEntity();
            permission.setCode("studio:*");
            permission.setName("Studio All");
            permission.setHttpMethod("*");
            permission.setPathPattern("/api/v1/**");
            permissionMapper.insert(permission);
        }

        if (userRoleMapper.selectCount(new LambdaQueryWrapper<UserRoleEntity>()
                .eq(UserRoleEntity::getUserId, user.getId())
                .eq(UserRoleEntity::getRoleId, adminRole.getId())) == 0) {
            UserRoleEntity userRole = new UserRoleEntity();
            userRole.setUserId(user.getId());
            userRole.setRoleId(adminRole.getId());
            userRoleMapper.insert(userRole);
        }

        if (rolePermissionMapper.selectCount(new LambdaQueryWrapper<RolePermissionEntity>()
                .eq(RolePermissionEntity::getRoleId, adminRole.getId())
                .eq(RolePermissionEntity::getPermissionId, permission.getId())) == 0) {
            RolePermissionEntity rolePermission = new RolePermissionEntity();
            rolePermission.setRoleId(adminRole.getId());
            rolePermission.setPermissionId(permission.getId());
            rolePermissionMapper.insert(rolePermission);
        }
    }
}

