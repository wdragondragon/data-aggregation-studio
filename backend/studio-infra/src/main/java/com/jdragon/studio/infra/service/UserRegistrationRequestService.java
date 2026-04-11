package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.request.UserRegistrationRequestCreateRequest;
import com.jdragon.studio.dto.model.request.UserRegistrationRequestReviewRequest;
import com.jdragon.studio.dto.model.system.UserRegistrationRequestView;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.UserRegistrationRequestEntity;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UserRegistrationRequestMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UserRegistrationRequestService {

    private final UserRegistrationRequestMapper requestMapper;
    private final StudioUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final StudioSecurityService securityService;
    private final NotificationService notificationService;

    public UserRegistrationRequestService(UserRegistrationRequestMapper requestMapper,
                                          StudioUserMapper userMapper,
                                          PasswordEncoder passwordEncoder,
                                          StudioSecurityService securityService,
                                          NotificationService notificationService) {
        this.requestMapper = requestMapper;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.securityService = securityService;
        this.notificationService = notificationService;
    }

    @Transactional
    public UserRegistrationRequestView submit(UserRegistrationRequestCreateRequest request) {
        if (request == null || !hasText(request.getUsername()) || !hasText(request.getPassword())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Username and password are required");
        }
        ensureNoActivePendingRequest(request.getUsername());
        UserRegistrationRequestEntity entity = new UserRegistrationRequestEntity();
        entity.setStatus(StudioConstants.REGISTRATION_REQUEST_PENDING);
        entity.setUsername(request.getUsername().trim());
        entity.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        entity.setDisplayName(hasText(request.getDisplayName()) ? request.getDisplayName().trim() : null);
        entity.setReason(hasText(request.getReason()) ? request.getReason().trim() : null);
        requestMapper.insert(entity);
        notifySuperAdmins(entity);
        return toView(entity, Collections.<Long, StudioUserEntity>emptyMap());
    }

    public List<UserRegistrationRequestView> list() {
        requireSuperAdmin();
        List<UserRegistrationRequestEntity> requests = requestMapper.selectList(new LambdaQueryWrapper<UserRegistrationRequestEntity>()
                .orderByDesc(UserRegistrationRequestEntity::getCreatedAt)
                .orderByDesc(UserRegistrationRequestEntity::getId));
        Map<Long, StudioUserEntity> userMap = loadUserMap(requests);
        List<UserRegistrationRequestView> result = new ArrayList<UserRegistrationRequestView>();
        for (UserRegistrationRequestEntity request : requests) {
            result.add(toView(request, userMap));
        }
        return result;
    }

    @Transactional
    public UserRegistrationRequestView approve(Long requestId, UserRegistrationRequestReviewRequest request) {
        requireSuperAdmin();
        UserRegistrationRequestEntity entity = requirePendingRequest(requestId);
        if (usernameExists(entity.getUsername())) {
            entity.setStatus(StudioConstants.REGISTRATION_REQUEST_REJECTED);
            entity.setReviewComment("Username already exists");
            entity.setReviewerUserId(securityService.currentUserId());
            entity.setReviewedAt(LocalDateTime.now());
            requestMapper.updateById(entity);
            return toView(entity, loadUserMap(java.util.Arrays.asList(entity)));
        }
        StudioUserEntity user = new StudioUserEntity();
        user.setTenantId(StudioConstants.DEFAULT_TENANT_ID);
        user.setUsername(entity.getUsername());
        user.setPasswordHash(entity.getPasswordHash());
        user.setDisplayName(entity.getDisplayName());
        user.setEnabled(Integer.valueOf(1));
        userMapper.insert(user);

        entity.setStatus(StudioConstants.REGISTRATION_REQUEST_APPROVED);
        entity.setReviewComment(request == null ? null : request.getReviewComment());
        entity.setReviewerUserId(securityService.currentUserId());
        entity.setApprovedUserId(user.getId());
        entity.setReviewedAt(LocalDateTime.now());
        requestMapper.updateById(entity);

        notificationService.notifyUsers(java.util.Collections.singletonList(user.getId()),
                new NotificationCommand()
                        .setCategory(StudioConstants.NOTIFICATION_CATEGORY_REGISTRATION_REVIEW)
                        .setTitle("注册申请已通过")
                        .setContent("管理员已通过你的注册申请，账号已创建完成。")
                        .setTargetPath("/dashboard")
                        .setTargetTenantId(StudioConstants.DEFAULT_TENANT_ID)
                        .setDedupeKey("registration-review:" + entity.getId() + ":approved"));
        return toView(entity, loadUserMap(java.util.Arrays.asList(entity)));
    }

    @Transactional
    public UserRegistrationRequestView reject(Long requestId, UserRegistrationRequestReviewRequest request) {
        requireSuperAdmin();
        UserRegistrationRequestEntity entity = requirePendingRequest(requestId);
        entity.setStatus(StudioConstants.REGISTRATION_REQUEST_REJECTED);
        entity.setReviewComment(request == null ? null : request.getReviewComment());
        entity.setReviewerUserId(securityService.currentUserId());
        entity.setReviewedAt(LocalDateTime.now());
        requestMapper.updateById(entity);

        StudioUserEntity existingUser = userMapper.selectOne(new LambdaQueryWrapper<StudioUserEntity>()
                .eq(StudioUserEntity::getUsername, entity.getUsername())
                .last("limit 1"));
        if (existingUser != null && existingUser.getId() != null) {
            notificationService.notifyUsers(java.util.Collections.singletonList(existingUser.getId()),
                    new NotificationCommand()
                            .setCategory(StudioConstants.NOTIFICATION_CATEGORY_REGISTRATION_REVIEW)
                            .setTitle("注册申请未通过")
                            .setContent(hasText(entity.getReviewComment()) ? entity.getReviewComment() : "管理员未通过你的注册申请。")
                            .setTargetPath("/dashboard")
                            .setTargetTenantId(StudioConstants.DEFAULT_TENANT_ID)
                            .setDedupeKey("registration-review:" + entity.getId() + ":rejected"));
        }
        return toView(entity, loadUserMap(java.util.Arrays.asList(entity)));
    }

    @Transactional
    public void delete(Long requestId) {
        requireSuperAdmin();
        UserRegistrationRequestEntity entity = requestMapper.selectById(requestId);
        if (entity == null) {
            return;
        }
        requestMapper.deleteById(requestId);
    }

    private void ensureNoActivePendingRequest(String username) {
        Long count = requestMapper.selectCount(new LambdaQueryWrapper<UserRegistrationRequestEntity>()
                .eq(UserRegistrationRequestEntity::getUsername, username.trim())
                .eq(UserRegistrationRequestEntity::getStatus, StudioConstants.REGISTRATION_REQUEST_PENDING));
        if (count != null && count.longValue() > 0L) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "A pending registration request already exists for this username");
        }
    }

    private boolean usernameExists(String username) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<StudioUserEntity>()
                .eq(StudioUserEntity::getUsername, username));
        return count != null && count.longValue() > 0L;
    }

    private UserRegistrationRequestEntity requirePendingRequest(Long requestId) {
        if (requestId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Request id is required");
        }
        UserRegistrationRequestEntity entity = requestMapper.selectById(requestId);
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Registration request not found");
        }
        if (!StudioConstants.REGISTRATION_REQUEST_PENDING.equalsIgnoreCase(entity.getStatus())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Registration request is not pending");
        }
        return entity;
    }

    private void notifySuperAdmins(UserRegistrationRequestEntity entity) {
        List<Long> superAdminUserIds = notificationService.superAdminUserIds();
        if (superAdminUserIds.isEmpty()) {
            return;
        }
        notificationService.notifyUsers(superAdminUserIds,
                new NotificationCommand()
                        .setCategory(StudioConstants.NOTIFICATION_CATEGORY_REGISTRATION_REQUEST)
                        .setTitle("收到新的注册登记")
                        .setContent("用户 " + entity.getUsername() + " 提交了注册登记，请前往系统管理审批。")
                        .setTargetType("USER_REGISTRATION_REQUEST")
                        .setTargetId(entity.getId())
                        .setTargetPath("/system?tab=registrationRequests")
                        .setTargetTenantId(StudioConstants.DEFAULT_TENANT_ID)
                        .setDedupeKey("registration-request:" + entity.getId() + ":pending"));
    }

    private Map<Long, StudioUserEntity> loadUserMap(List<UserRegistrationRequestEntity> requests) {
        Set<Long> userIds = new LinkedHashSet<Long>();
        for (UserRegistrationRequestEntity request : requests) {
            if (request.getReviewerUserId() != null) {
                userIds.add(request.getReviewerUserId());
            }
            if (request.getApprovedUserId() != null) {
                userIds.add(request.getApprovedUserId());
            }
        }
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<StudioUserEntity> users = userMapper.selectBatchIds(userIds);
        Map<Long, StudioUserEntity> userMap = new LinkedHashMap<Long, StudioUserEntity>();
        for (StudioUserEntity user : users) {
            userMap.put(user.getId(), user);
        }
        return userMap;
    }

    private UserRegistrationRequestView toView(UserRegistrationRequestEntity entity,
                                               Map<Long, StudioUserEntity> userMap) {
        UserRegistrationRequestView view = new UserRegistrationRequestView();
        view.setId(entity.getId());
        view.setStatus(entity.getStatus());
        view.setUsername(entity.getUsername());
        view.setDisplayName(entity.getDisplayName());
        view.setReason(entity.getReason());
        view.setReviewComment(entity.getReviewComment());
        view.setReviewerUserId(entity.getReviewerUserId());
        view.setReviewerUsername(resolveUsername(userMap.get(entity.getReviewerUserId())));
        view.setApprovedUserId(entity.getApprovedUserId());
        view.setApprovedUsername(resolveUsername(userMap.get(entity.getApprovedUserId())));
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setReviewedAt(entity.getReviewedAt());
        return view;
    }

    private String resolveUsername(StudioUserEntity user) {
        return user == null ? null : user.getUsername();
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
