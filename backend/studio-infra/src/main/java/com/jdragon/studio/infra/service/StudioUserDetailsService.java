package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.infra.entity.PermissionEntity;
import com.jdragon.studio.infra.entity.RolePermissionEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.UserRoleEntity;
import com.jdragon.studio.infra.mapper.PermissionMapper;
import com.jdragon.studio.infra.mapper.RolePermissionMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import com.jdragon.studio.infra.security.StudioUserPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class StudioUserDetailsService implements UserDetailsService {

    private final StudioUserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;

    public StudioUserDetailsService(StudioUserMapper userMapper,
                                    UserRoleMapper userRoleMapper,
                                    RolePermissionMapper rolePermissionMapper,
                                    PermissionMapper permissionMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        StudioUserEntity user = userMapper.selectOne(new LambdaQueryWrapper<StudioUserEntity>()
                .eq(StudioUserEntity::getUsername, username)
                .last("limit 1"));
        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        List<UserRoleEntity> userRoles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>()
                .eq(UserRoleEntity::getUserId, user.getId()));
        Set<Long> roleIds = new HashSet<Long>();
        for (UserRoleEntity userRole : userRoles) {
            roleIds.add(userRole.getRoleId());
        }

        Set<Long> permissionIds = new HashSet<Long>();
        if (!roleIds.isEmpty()) {
            List<RolePermissionEntity> rolePermissions = rolePermissionMapper.selectList(
                    new LambdaQueryWrapper<RolePermissionEntity>().in(RolePermissionEntity::getRoleId, roleIds));
            for (RolePermissionEntity rolePermission : rolePermissions) {
                permissionIds.add(rolePermission.getPermissionId());
            }
        }

        List<SimpleGrantedAuthority> authorities = new ArrayList<SimpleGrantedAuthority>();
        if (!permissionIds.isEmpty()) {
            List<PermissionEntity> permissions = permissionMapper.selectBatchIds(permissionIds);
            for (PermissionEntity permission : permissions) {
                authorities.add(new SimpleGrantedAuthority(permission.getCode()));
            }
        }

        return new StudioUserPrincipal(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getEnabled() != null && user.getEnabled() == 1,
                authorities
        );
    }
}
