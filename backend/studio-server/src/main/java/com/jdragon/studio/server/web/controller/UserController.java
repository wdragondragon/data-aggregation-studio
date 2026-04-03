package com.jdragon.studio.server.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.UserRoleEntity;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Users", description = "User management APIs")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final StudioUserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserController(StudioUserMapper userMapper, UserRoleMapper userRoleMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Operation(summary = "List users")
    @GetMapping
    public Result<List<StudioUserEntity>> list() {
        return Result.success(userMapper.selectList(new LambdaQueryWrapper<StudioUserEntity>()
                .orderByAsc(StudioUserEntity::getUsername)));
    }

    @Operation(summary = "Create or update user")
    @PostMapping
    public Result<StudioUserEntity> save(@RequestBody StudioUserEntity entity) {
        if (entity.getPasswordHash() != null && !entity.getPasswordHash().startsWith("$2a$")) {
            entity.setPasswordHash(passwordEncoder.encode(entity.getPasswordHash()));
        }
        if (entity.getId() == null) {
            userMapper.insert(entity);
        } else {
            userMapper.updateById(entity);
        }
        return Result.success(entity);
    }

    @Operation(summary = "Delete user")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleEntity>()
                .eq(UserRoleEntity::getUserId, id));
        userMapper.deleteById(id);
        return Result.success(null);
    }
}
