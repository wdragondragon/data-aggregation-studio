package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.ElinkUserOptionView;
import com.jdragon.studio.dto.model.StudioUserListView;
import com.jdragon.studio.dto.model.StudioUserOptionView;
import com.jdragon.studio.infra.entity.StudioExternalUserBindingEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.UserRoleEntity;
import com.jdragon.studio.infra.mapper.StudioExternalUserBindingMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserManagementService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 200;

    private final StudioUserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final StudioSecurityService securityService;
    private final StudioExternalUserBindingMapper externalUserBindingMapper;
    private ElinkManagerOptionService elinkManagerOptionService;

    public UserManagementService(StudioUserMapper userMapper,
                                 UserRoleMapper userRoleMapper,
                                 PasswordEncoder passwordEncoder,
                                 StudioSecurityService securityService,
                                 StudioExternalUserBindingMapper externalUserBindingMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.securityService = securityService;
        this.externalUserBindingMapper = externalUserBindingMapper;
    }

    @Autowired(required = false)
    void setElinkManagerOptionService(ElinkManagerOptionService elinkManagerOptionService) {
        this.elinkManagerOptionService = elinkManagerOptionService;
    }

    public List<StudioUserListView> list() {
        requireSuperAdmin();
        List<StudioUserEntity> users = userMapper.selectList(userListQuery());
        Map<Long, StudioExternalUserBindingEntity> elinkBindings = loadElinkBindingMap(users);
        List<StudioUserListView> result = new ArrayList<StudioUserListView>();
        for (StudioUserEntity user : users) {
            result.add(toListView(user, elinkBindings.get(user.getId())));
        }
        return result;
    }

    public List<StudioUserOptionView> listOptions() {
        requireSuperAdmin();
        List<StudioUserEntity> users = userMapper.selectList(new LambdaQueryWrapper<StudioUserEntity>()
                .select(StudioUserEntity::getId,
                        StudioUserEntity::getUsername,
                        StudioUserEntity::getDisplayName)
                .orderByAsc(StudioUserEntity::getUsername)
                .orderByAsc(StudioUserEntity::getId));
        List<StudioUserOptionView> result = new ArrayList<StudioUserOptionView>();
        for (StudioUserEntity user : users) {
            result.add(toOptionView(user));
        }
        return result;
    }

    public PageView<StudioUserListView> listPage(Integer pageNo, Integer pageSize) {
        requireSuperAdmin();
        int safePageNo = normalizePageNo(pageNo);
        int safePageSize = normalizePageSize(pageSize);
        Page<StudioUserEntity> page = new Page<StudioUserEntity>(safePageNo, safePageSize);
        Page<StudioUserEntity> entityPage = userMapper.selectPage(page, userListQuery());
        Map<Long, StudioExternalUserBindingEntity> elinkBindings = loadElinkBindingMap(entityPage.getRecords());
        List<StudioUserListView> items = new ArrayList<StudioUserListView>();
        for (StudioUserEntity user : entityPage.getRecords()) {
            items.add(toListView(user, elinkBindings.get(user.getId())));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    @Transactional
    public StudioUserEntity save(StudioUserEntity entity) {
        requireSuperAdmin();
        if (entity == null || !hasText(entity.getUsername())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Username is required");
        }
        boolean mobileProvided = entity.getMobilePhone() != null;
        StudioUserEntity target;
        if (entity.getId() == null) {
            target = new StudioUserEntity();
            target.setTenantId(StudioConstants.DEFAULT_TENANT_ID);
            target.setAuthSource(StudioConstants.AUTH_SOURCE_LOCAL);
        } else {
            target = requireUser(entity.getId());
        }
        ensureUniqueUsername(entity.getUsername(), target.getId());
        target.setUsername(entity.getUsername().trim());
        target.setDisplayName(hasText(entity.getDisplayName()) ? entity.getDisplayName().trim() : null);
        if (entity.getMobilePhone() != null) {
            String mobile = StudioMobileSupport.normalize(entity.getMobilePhone());
            if (StudioMobileSupport.hasText(entity.getMobilePhone()) && mobile == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Mobile format is invalid");
            }
            target.setMobilePhone(mobile);
        }
        target.setEnabled(entity.getEnabled() == null ? Integer.valueOf(1) : entity.getEnabled());
        if (hasText(entity.getPasswordHash())) {
            if (StudioConstants.AUTH_SOURCE_GATEWAY.equalsIgnoreCase(target.getAuthSource())) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Gateway users do not support local password updates");
            }
            target.setPasswordHash(resolvePasswordHash(entity.getPasswordHash().trim()));
        } else if (target.getId() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Password is required");
        }
        if (!hasText(target.getAuthSource())) {
            target.setAuthSource(StudioConstants.AUTH_SOURCE_LOCAL);
        }
        try {
            if (target.getId() == null) {
                userMapper.insert(target);
            } else {
                userMapper.updateById(target);
                if (mobileProvided) {
                    userMapper.update(null, new LambdaUpdateWrapper<StudioUserEntity>()
                            .eq(StudioUserEntity::getId, target.getId())
                            .set(StudioUserEntity::getMobilePhone, target.getMobilePhone()));
                }
            }
            synchronizeElinkBinding(target, entity);
        } catch (DuplicateKeyException ex) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "The eLink account is already bound, or the Studio user already has another eLink binding");
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
        externalUserBindingMapper.hardDeleteByProviderAndStudioUserId(
                StudioConstants.ELINK_PROVIDER_CODE, userId);
        externalUserBindingMapper.delete(new LambdaQueryWrapper<StudioExternalUserBindingEntity>()
                .eq(StudioExternalUserBindingEntity::getStudioUserId, userId));
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
        copy.setMobilePhone(entity.getMobilePhone());
        copy.setEnabled(entity.getEnabled());
        copy.setAuthSource(entity.getAuthSource());
        copy.setExternalAccount(loadExternalAccount(entity.getId()));
        StudioExternalUserBindingEntity elinkBinding = loadElinkBinding(entity.getId());
        if (elinkBinding != null) {
            copy.setElinkUserId(elinkBinding.getExternalUserId());
            copy.setElinkUserName(elinkBinding.getExternalAccount());
        }
        copy.setPasswordHash(null);
        return copy;
    }

    private String loadExternalAccount(Long userId) {
        if (userId == null) {
            return null;
        }
        StudioExternalUserBindingEntity binding = externalUserBindingMapper.selectOne(
                new LambdaQueryWrapper<StudioExternalUserBindingEntity>()
                        .eq(StudioExternalUserBindingEntity::getStudioUserId, userId)
                        .eq(StudioExternalUserBindingEntity::getProviderCode, StudioConstants.GATEWAY_PROVIDER_CODE)
                        .last("limit 1"));
        return binding == null ? null : binding.getExternalAccount();
    }

    private StudioUserListView toListView(StudioUserEntity entity,
                                          StudioExternalUserBindingEntity elinkBinding) {
        StudioUserListView view = new StudioUserListView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(Boolean.valueOf(entity.getDeleted() != null && entity.getDeleted().intValue() == 1));
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setUsername(entity.getUsername());
        view.setDisplayName(entity.getDisplayName());
        view.setMobilePhone(entity.getMobilePhone());
        view.setEnabled(entity.getEnabled());
        if (elinkBinding != null) {
            view.setElinkUserId(elinkBinding.getExternalUserId());
            view.setElinkUserName(elinkBinding.getExternalAccount());
        }
        return view;
    }

    private void synchronizeElinkBinding(StudioUserEntity target, StudioUserEntity request) {
        boolean clear = Boolean.TRUE.equals(request.getClearElinkUserBinding());
        if (clear && hasText(request.getElinkUserId())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "eLink account cannot be supplied while requesting the binding to be cleared");
        }
        if (clear) {
            externalUserBindingMapper.hardDeleteByProviderAndStudioUserId(
                    StudioConstants.ELINK_PROVIDER_CODE, target.getId());
            return;
        }
        if (request.getElinkUserId() == null) {
            return;
        }
        String elinkUserId = request.getElinkUserId().trim();
        if (elinkUserId.isEmpty() || elinkUserId.length() > 128
                || elinkUserId.indexOf('|') >= 0 || "@all".equalsIgnoreCase(elinkUserId)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid eLink account");
        }
        ElinkUserOptionView managerUser = elinkManagerOptionService == null
                ? null : elinkManagerOptionService.requireUser(elinkUserId);
        StudioExternalUserBindingEntity duplicate = externalUserBindingMapper.selectOne(
                new LambdaQueryWrapper<StudioExternalUserBindingEntity>()
                        .eq(StudioExternalUserBindingEntity::getProviderCode, StudioConstants.ELINK_PROVIDER_CODE)
                        .eq(StudioExternalUserBindingEntity::getExternalUserId, elinkUserId)
                        .last("limit 1"));
        if (duplicate != null && !target.getId().equals(duplicate.getStudioUserId())) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "The eLink account is already bound to another Studio user");
        }
        StudioExternalUserBindingEntity binding = loadElinkBinding(target.getId());
        if (binding == null) {
            binding = new StudioExternalUserBindingEntity();
            binding.setTenantId(target.getTenantId());
            binding.setProviderCode(StudioConstants.ELINK_PROVIDER_CODE);
            binding.setStudioUserId(target.getId());
        }
        binding.setExternalUserId(elinkUserId);
        String requestedName = managerUser != null ? managerUser.getName() : request.getElinkUserName();
        if (requestedName != null) {
            String name = requestedName.trim();
            if (name.length() > 255) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "eLink user name is too long");
            }
            binding.setExternalAccount(name.isEmpty() ? elinkUserId : name);
        } else if (!hasText(binding.getExternalAccount())) {
            binding.setExternalAccount(elinkUserId);
        }
        if (binding.getId() == null) {
            externalUserBindingMapper.insert(binding);
        } else {
            externalUserBindingMapper.updateById(binding);
        }
    }

    private StudioExternalUserBindingEntity loadElinkBinding(Long userId) {
        if (userId == null) {
            return null;
        }
        return externalUserBindingMapper.selectOne(new LambdaQueryWrapper<StudioExternalUserBindingEntity>()
                .eq(StudioExternalUserBindingEntity::getProviderCode, StudioConstants.ELINK_PROVIDER_CODE)
                .eq(StudioExternalUserBindingEntity::getStudioUserId, userId)
                .last("limit 1"));
    }

    private Map<Long, StudioExternalUserBindingEntity> loadElinkBindingMap(
            Collection<StudioUserEntity> users) {
        Map<Long, StudioExternalUserBindingEntity> result =
                new LinkedHashMap<Long, StudioExternalUserBindingEntity>();
        List<Long> userIds = new ArrayList<Long>();
        if (users != null) {
            for (StudioUserEntity user : users) {
                if (user != null && user.getId() != null) {
                    userIds.add(user.getId());
                }
            }
        }
        if (userIds.isEmpty()) {
            return result;
        }
        List<StudioExternalUserBindingEntity> bindings = externalUserBindingMapper.selectList(
                new LambdaQueryWrapper<StudioExternalUserBindingEntity>()
                        .eq(StudioExternalUserBindingEntity::getProviderCode, StudioConstants.ELINK_PROVIDER_CODE)
                        .in(StudioExternalUserBindingEntity::getStudioUserId, userIds));
        for (StudioExternalUserBindingEntity binding : bindings) {
            if (binding != null && binding.getStudioUserId() != null) {
                result.putIfAbsent(binding.getStudioUserId(), binding);
            }
        }
        return result;
    }

    private StudioUserOptionView toOptionView(StudioUserEntity entity) {
        StudioUserOptionView view = new StudioUserOptionView();
        view.setId(entity.getId());
        view.setUsername(entity.getUsername());
        view.setDisplayName(entity.getDisplayName());
        return view;
    }

    private LambdaQueryWrapper<StudioUserEntity> userListQuery() {
        return new LambdaQueryWrapper<StudioUserEntity>()
                .select(StudioUserEntity::getId,
                        StudioUserEntity::getTenantId,
                        StudioUserEntity::getDeleted,
                        StudioUserEntity::getCreatedAt,
                        StudioUserEntity::getUpdatedAt,
                        StudioUserEntity::getUsername,
                        StudioUserEntity::getDisplayName,
                        StudioUserEntity::getMobilePhone,
                        StudioUserEntity::getEnabled)
                .orderByAsc(StudioUserEntity::getUsername)
                .orderByAsc(StudioUserEntity::getId);
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

    private void requireSuperAdmin() {
        if (!securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
