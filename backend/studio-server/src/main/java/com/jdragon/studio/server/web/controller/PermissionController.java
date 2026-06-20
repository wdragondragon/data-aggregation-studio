package com.jdragon.studio.server.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.entity.PermissionEntity;
import com.jdragon.studio.infra.entity.RolePermissionEntity;
import com.jdragon.studio.infra.mapper.PermissionMapper;
import com.jdragon.studio.infra.mapper.RolePermissionMapper;
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

@Tag(name = "Permissions", description = "Permission management APIs")
@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {

    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final StudioSecurityService securityService;

    public PermissionController(PermissionMapper permissionMapper,
                                RolePermissionMapper rolePermissionMapper,
                                StudioSecurityService securityService) {
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.securityService = securityService;
    }

    @Operation(summary = "List permissions")
    @GetMapping
    public Result<List<PermissionEntity>> list() {
        requireSuperAdmin();
        return Result.success(permissionMapper.selectList(new LambdaQueryWrapper<PermissionEntity>()
                .orderByAsc(PermissionEntity::getCode)));
    }

    @Operation(summary = "Create or update permission")
    @PostMapping
    public Result<PermissionEntity> save(@RequestBody PermissionEntity entity) {
        requireSuperAdmin();
        if (entity.getId() == null) {
            permissionMapper.insert(entity);
        } else {
            permissionMapper.updateById(entity);
        }
        return Result.success(entity);
    }

    @Operation(summary = "Delete permission")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        requireSuperAdmin();
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermissionEntity>()
                .eq(RolePermissionEntity::getPermissionId, id));
        permissionMapper.deleteById(id);
        return Result.success(null);
    }

    private void requireSuperAdmin() {
        if (!securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
    }
}
