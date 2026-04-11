package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.UserRoleEntity;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserManagementService {

    private final StudioUserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final StudioSecurityService securityService;

    public UserManagementService(StudioUserMapper userMapper,
                                 UserRoleMapper userRoleMapper,
                                 PasswordEncoder passwordEncoder,
                                 StudioSecurityService securityService) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.securityService = securityService;
    }

    public List<StudioUserEntity> list() {
        requireSuperAdmin();
        List<StudioUserEntity> users = userMapper.selectList(new LambdaQueryWrapper<StudioUserEntity>()
                .orderByAsc(StudioUserEntity::getUsername));
        List<StudioUserEntity> result = new ArrayList<StudioUserEntity>();
        for (StudioUserEntity user : users) {
            result.add(sanitize(user));
        }
        return result;
    }

    @Transactional
    public StudioUserEntity save(StudioUserEntity entity) {
        requireSuperAdmin();
        if (entity == null || !hasText(entity.getUsername())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Username is required");
        }
        StudioUserEntity target;
        if (entity.getId() == null) {
            target = new StudioUserEntity();
            target.setTenantId(StudioConstants.DEFAULT_TENANT_ID);
        } else {
            target = requireUser(entity.getId());
        }
        ensureUniqueUsername(entity.getUsername(), target.getId());
        target.setUsername(entity.getUsername().trim());
        target.setDisplayName(hasText(entity.getDisplayName()) ? entity.getDisplayName().trim() : null);
        target.setEnabled(entity.getEnabled() == null ? Integer.valueOf(1) : entity.getEnabled());
        if (hasText(entity.getPasswordHash())) {
            target.setPasswordHash(resolvePasswordHash(entity.getPasswordHash().trim()));
        } else if (target.getId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Password is required");
        }
        if (target.getId() == null) {
            userMapper.insert(target);
        } else {
            userMapper.updateById(target);
        }
        return sanitize(target);
    }

    @Transactional
    public void delete(Long userId) {
        requireSuperAdmin();
        StudioUserEntity user = requireUser(userId);
        if (StudioConstants.DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(user.getUsername())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Default admin cannot be deleted");
        }
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleEntity>()
                .eq(UserRoleEntity::getUserId, userId));
        userMapper.deleteById(userId);
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

    private void ensureUniqueUsername(String username, Long selfId) {
        List<StudioUserEntity> duplicates = userMapper.selectList(new LambdaQueryWrapper<StudioUserEntity>()
                .eq(StudioUserEntity::getUsername, username.trim()));
        for (StudioUserEntity duplicate : duplicates) {
            if (selfId != null && selfId.equals(duplicate.getId())) {
                continue;
            }
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Username already exists");
        }
    }

    private String resolvePasswordHash(String passwordOrHash) {
        if (passwordOrHash.startsWith("$2a$") || passwordOrHash.startsWith("$2b$") || passwordOrHash.startsWith("$2y$")) {
            return passwordOrHash;
        }
        return passwordEncoder.encode(passwordOrHash);
    }

    private StudioUserEntity sanitize(StudioUserEntity entity) {
        if (entity == null) {
            return null;
        }
        StudioUserEntity copy = new StudioUserEntity();
        copy.setId(entity.getId());
        copy.setTenantId(entity.getTenantId());
        copy.setDeleted(entity.getDeleted());
        copy.setCreatedAt(entity.getCreatedAt());
        copy.setUpdatedAt(entity.getUpdatedAt());
        copy.setUsername(entity.getUsername());
        copy.setDisplayName(entity.getDisplayName());
        copy.setEnabled(entity.getEnabled());
        copy.setPasswordHash(null);
        return copy;
    }

    private void requireSuperAdmin() {
        if (!securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
