package com.jdragon.studio.server.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.entity.PermissionEntity;
import com.jdragon.studio.infra.mapper.PermissionMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
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

    public PermissionController(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    @Operation(summary = "List permissions")
    @GetMapping
    public Result<List<PermissionEntity>> list() {
        return Result.success(permissionMapper.selectList(new LambdaQueryWrapper<PermissionEntity>()
                .orderByAsc(PermissionEntity::getCode)));
    }

    @Operation(summary = "Create or update permission")
    @PostMapping
    public Result<PermissionEntity> save(@RequestBody PermissionEntity entity) {
        if (entity.getId() == null) {
            permissionMapper.insert(entity);
        } else {
            permissionMapper.updateById(entity);
        }
        return Result.success(entity);
    }
}
