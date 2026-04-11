package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.NotificationSnapshotView;
import com.jdragon.studio.dto.model.NotificationView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.NotificationQueryRequest;
import com.jdragon.studio.infra.entity.NotificationEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.RoleEntity;
import com.jdragon.studio.infra.entity.UserRoleEntity;
import com.jdragon.studio.infra.mapper.NotificationMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.RoleMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class NotificationService {

    private static final int RECENT_NOTIFICATION_LIMIT = 10;

    private final NotificationMapper notificationMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emittersByUserId = new ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>();

    public NotificationService(NotificationMapper notificationMapper,
                               ProjectMemberMapper projectMemberMapper,
                               UserRoleMapper userRoleMapper,
                               RoleMapper roleMapper,
                               StudioSecurityService securityService,
                               ProjectResourceAccessService projectResourceAccessService) {
        this.notificationMapper = notificationMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    public PageView<NotificationView> list(NotificationQueryRequest request) {
        Long currentUserId = requireCurrentUserId();
        int pageNo = request == null || request.getPageNo() == null || request.getPageNo().intValue() < 1 ? 1 : request.getPageNo().intValue();
        int pageSize = request == null || request.getPageSize() == null ? 20 : request.getPageSize().intValue();
        if (pageSize < 1) {
            pageSize = 20;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }
        boolean unreadOnly = request != null && Boolean.TRUE.equals(request.getUnreadOnly());
        LambdaQueryWrapper<NotificationEntity> query = baseUserNotificationQuery(currentUserId)
                .isNull(NotificationEntity::getArchivedAt)
                .isNull(unreadOnly, NotificationEntity::getReadAt)
                .orderByDesc(NotificationEntity::getCreatedAt)
                .orderByDesc(NotificationEntity::getId);
        Long total = notificationMapper.selectCount(query);
        List<NotificationEntity> entities = notificationMapper.selectList(query.last("limit " + ((pageNo - 1) * pageSize) + "," + pageSize));
        List<NotificationView> items = new ArrayList<NotificationView>();
        for (NotificationEntity entity : entities) {
            items.add(toView(entity));
        }
        return PageView.of(pageNo, pageSize, total == null ? 0L : total.longValue(), items);
    }

    public long unreadCount() {
        Long currentUserId = requireCurrentUserId();
        Long count = notificationMapper.selectCount(baseUserNotificationQuery(currentUserId)
                .isNull(NotificationEntity::getReadAt)
                .isNull(NotificationEntity::getArchivedAt));
        return count == null ? 0L : count.longValue();
    }

    public NotificationSnapshotView snapshot() {
        Long currentUserId = requireCurrentUserId();
        return snapshotForUser(currentUserId);
    }

    @Transactional
    public NotificationView markRead(Long notificationId) {
        NotificationEntity entity = requireCurrentUserNotification(notificationId);
        if (entity.getReadAt() == null) {
            entity.setReadAt(LocalDateTime.now());
            notificationMapper.updateById(entity);
        }
        emitSnapshot(entity.getRecipientUserId(), "notification-read");
        return toView(entity);
    }

    @Transactional
    public void markAllRead() {
        Long currentUserId = requireCurrentUserId();
        List<NotificationEntity> unread = notificationMapper.selectList(baseUserNotificationQuery(currentUserId)
                .isNull(NotificationEntity::getReadAt)
                .isNull(NotificationEntity::getArchivedAt));
        if (unread.isEmpty()) {
            emitSnapshot(currentUserId, "notification-read-all");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (NotificationEntity entity : unread) {
            entity.setReadAt(now);
            notificationMapper.updateById(entity);
        }
        emitSnapshot(currentUserId, "notification-read-all");
    }

    public SseEmitter connect() {
        Long currentUserId = requireCurrentUserId();
        final SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByUserId.computeIfAbsent(currentUserId, key -> new CopyOnWriteArrayList<SseEmitter>());
        emitters.add(emitter);
        emitter.onCompletion(() -> removeEmitter(currentUserId, emitter));
        emitter.onTimeout(() -> removeEmitter(currentUserId, emitter));
        emitter.onError(throwable -> removeEmitter(currentUserId, emitter));
        sendEvent(emitter, "snapshot", snapshotForUser(currentUserId));
        return emitter;
    }

    @Transactional
    public List<NotificationView> notifyUsers(List<Long> recipientUserIds, NotificationCommand command) {
        if (recipientUserIds == null || recipientUserIds.isEmpty() || command == null) {
            return Collections.emptyList();
        }
        Set<Long> uniqueRecipientIds = new LinkedHashSet<Long>();
        for (Long recipientUserId : recipientUserIds) {
            if (recipientUserId != null) {
                uniqueRecipientIds.add(recipientUserId);
            }
        }
        if (uniqueRecipientIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<NotificationView> created = new ArrayList<NotificationView>();
        for (Long recipientUserId : uniqueRecipientIds) {
            NotificationEntity entity = findByDedupeKey(recipientUserId, command.getDedupeKey());
            if (entity == null) {
                entity = new NotificationEntity();
                entity.setRecipientUserId(recipientUserId);
                entity.setTenantId(command.getTargetTenantId());
                entity.setProjectId(command.getTargetProjectId());
                entity.setCategory(command.getCategory());
                entity.setTitle(command.getTitle());
                entity.setContent(command.getContent());
                entity.setTargetType(command.getTargetType());
                entity.setTargetId(command.getTargetId());
                entity.setTargetPath(command.getTargetPath());
                entity.setTargetTenantId(command.getTargetTenantId());
                entity.setTargetProjectId(command.getTargetProjectId());
                entity.setDedupeKey(command.getDedupeKey());
                entity.setPayloadJson(command.getPayloadJson() == null
                        ? new java.util.LinkedHashMap<String, Object>()
                        : new java.util.LinkedHashMap<String, Object>(command.getPayloadJson()));
                notificationMapper.insert(entity);
            }
            created.add(toView(entity));
            emitSnapshot(recipientUserId, "notification-created");
        }
        return created;
    }

    public List<Long> activeProjectMemberUserIds(String tenantId, Long projectId) {
        if (tenantId == null || tenantId.trim().isEmpty() || projectId == null) {
            return Collections.emptyList();
        }
        List<ProjectMemberEntity> members = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getTenantId, tenantId)
                .eq(ProjectMemberEntity::getProjectId, projectId)
                .eq(ProjectMemberEntity::getStatus, StudioConstants.MEMBER_STATUS_ACTIVE));
        Set<Long> userIds = new LinkedHashSet<Long>();
        for (ProjectMemberEntity member : members) {
            if (member.getUserId() != null) {
                userIds.add(member.getUserId());
            }
        }
        return new ArrayList<Long>(userIds);
    }

    public List<Long> superAdminUserIds() {
        RoleEntity role = roleMapper.selectOne(new LambdaQueryWrapper<RoleEntity>()
                .eq(RoleEntity::getCode, StudioConstants.ROLE_SUPER_ADMIN)
                .last("limit 1"));
        if (role == null || role.getId() == null) {
            return Collections.emptyList();
        }
        List<UserRoleEntity> userRoles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>()
                .eq(UserRoleEntity::getRoleId, role.getId()));
        Set<Long> userIds = new LinkedHashSet<Long>();
        for (UserRoleEntity userRole : userRoles) {
            if (userRole.getUserId() != null) {
                userIds.add(userRole.getUserId());
            }
        }
        return new ArrayList<Long>(userIds);
    }

    public void emitSnapshot(Long userId, String eventName) {
        if (userId == null) {
            return;
        }
        NotificationSnapshotView snapshot = snapshotForUser(userId);
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByUserId.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        List<SseEmitter> stale = new ArrayList<SseEmitter>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(snapshot));
            } catch (IOException ex) {
                stale.add(emitter);
            }
        }
        if (!stale.isEmpty()) {
            emitters.removeAll(stale);
        }
    }

    private NotificationSnapshotView snapshotForUser(Long userId) {
        NotificationSnapshotView snapshot = new NotificationSnapshotView();
        if (userId == null) {
            return snapshot;
        }
        Long unreadCount = notificationMapper.selectCount(baseUserNotificationQuery(userId)
                .isNull(NotificationEntity::getReadAt)
                .isNull(NotificationEntity::getArchivedAt));
        snapshot.setUnreadCount(unreadCount == null ? 0L : unreadCount.longValue());
        List<NotificationEntity> entities = notificationMapper.selectList(baseUserNotificationQuery(userId)
                .isNull(NotificationEntity::getArchivedAt)
                .orderByDesc(NotificationEntity::getCreatedAt)
                .orderByDesc(NotificationEntity::getId)
                .last("limit " + RECENT_NOTIFICATION_LIMIT));
        List<NotificationView> recent = new ArrayList<NotificationView>();
        for (NotificationEntity entity : entities) {
            recent.add(toView(entity));
        }
        snapshot.setRecentNotifications(recent);
        return snapshot;
    }

    private NotificationEntity requireCurrentUserNotification(Long notificationId) {
        if (notificationId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Notification id is required");
        }
        NotificationEntity entity = notificationMapper.selectById(notificationId);
        if (entity == null || !requireCurrentUserId().equals(entity.getRecipientUserId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Notification not found");
        }
        return entity;
    }

    private NotificationEntity findByDedupeKey(Long recipientUserId, String dedupeKey) {
        if (recipientUserId == null || dedupeKey == null || dedupeKey.trim().isEmpty()) {
            return null;
        }
        return notificationMapper.selectOne(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getRecipientUserId, recipientUserId)
                .eq(NotificationEntity::getDedupeKey, dedupeKey.trim())
                .last("limit 1"));
    }

    private LambdaQueryWrapper<NotificationEntity> baseUserNotificationQuery(Long userId) {
        return new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getRecipientUserId, userId)
                .orderByDesc(NotificationEntity::getCreatedAt)
                .orderByDesc(NotificationEntity::getId);
    }

    private NotificationView toView(NotificationEntity entity) {
        NotificationView view = new NotificationView();
        view.setId(entity.getId());
        view.setCategory(entity.getCategory());
        view.setTitle(entity.getTitle());
        view.setContent(entity.getContent());
        view.setTargetType(entity.getTargetType());
        view.setTargetId(entity.getTargetId());
        view.setTargetPath(entity.getTargetPath());
        view.setTargetTenantId(entity.getTargetTenantId());
        view.setTargetProjectId(entity.getTargetProjectId());
        view.setRead(entity.getReadAt() != null);
        view.setReadAt(entity.getReadAt());
        view.setArchivedAt(entity.getArchivedAt());
        view.setCreatedAt(entity.getCreatedAt());
        view.setPayloadJson(entity.getPayloadJson() == null
                ? new java.util.LinkedHashMap<String, Object>()
                : new java.util.LinkedHashMap<String, Object>(entity.getPayloadJson()));
        return view;
    }

    private void sendEvent(SseEmitter emitter, String eventName, NotificationSnapshotView snapshot) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(snapshot));
        } catch (IOException ex) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Failed to open notification stream");
        }
    }

    private void removeEmitter(Long userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByUserId.get(userId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByUserId.remove(userId);
        }
    }

    private Long requireCurrentUserId() {
        Long currentUserId = securityService.currentUserId();
        if (currentUserId == null) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return currentUserId;
    }
}
