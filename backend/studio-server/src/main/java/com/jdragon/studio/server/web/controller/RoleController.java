package com.jdragon.studio.server.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.entity.RoleEntity;
import com.jdragon.studio.infra.entity.RolePermissionEntity;
import com.jdragon.studio.infra.entity.UserRoleEntity;
import com.jdragon.studio.infra.mapper.RoleMapper;
import com.jdragon.studio.infra.mapper.RolePermissionMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import com.jdragon.studio.infra.service.StudioSecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Roles", description = "Role management APIs")
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final StudioSecurityService securityService;

    public RoleController(RoleMapper roleMapper,
                          UserRoleMapper userRoleMapper,
                          RolePermissionMapper rolePermissionMapper,
                          StudioSecurityService securityService) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.securityService = securityService;
    }

    @Operation(summary = "List roles")
    @GetMapping
    public Result<List<RoleEntity>> list() {
        requireSuperAdmin();
        return Result.success(roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>()
                .orderByAsc(RoleEntity::getCode)));
    }

    @Operation(summary = "Create or update role")
    @PostMapping
    public Result<RoleEntity> save(@RequestBody RoleEntity entity) {
        requireSuperAdmin();
        if (entity.getId() == null) {
            roleMapper.insert(entity);
        } else {
            roleMapper.updateById(entity);
        }
        return Result.success(entity);
    }

    @Operation(summary = "Delete role")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        requireSuperAdmin();
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleEntity>()
                .eq(UserRoleEntity::getRoleId, id));
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermissionEntity>()
                .eq(RolePermissionEntity::getRoleId, id));
        roleMapper.deleteById(id);
        return Result.success(null);
    }

    private void requireSuperAdmin() {
        if (!securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
    }
}
