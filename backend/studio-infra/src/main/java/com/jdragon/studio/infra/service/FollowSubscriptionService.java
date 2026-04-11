package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.FollowStatusView;
import com.jdragon.studio.dto.model.request.FollowRequest;
import com.jdragon.studio.infra.entity.FollowSubscriptionEntity;
import com.jdragon.studio.infra.mapper.FollowSubscriptionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class FollowSubscriptionService {

    private final FollowSubscriptionMapper followSubscriptionMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;

    public FollowSubscriptionService(FollowSubscriptionMapper followSubscriptionMapper,
                                     StudioSecurityService securityService,
                                     ProjectResourceAccessService projectResourceAccessService) {
        this.followSubscriptionMapper = followSubscriptionMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    public FollowStatusView status(String targetType, Long targetId) {
        validateTarget(targetType, targetId);
        FollowStatusView view = new FollowStatusView();
        view.setTargetType(normalizeTargetType(targetType));
        view.setTargetId(targetId);
        view.setFollowing(findActiveSubscription(normalizeTargetType(targetType), targetId) != null);
        return view;
    }

    @Transactional
    public FollowStatusView follow(FollowRequest request) {
        validateTarget(request == null ? null : request.getTargetType(), request == null ? null : request.getTargetId());
        String targetType = normalizeTargetType(request.getTargetType());
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        Long currentUserId = requireCurrentUserId();
        FollowSubscriptionEntity existing = followSubscriptionMapper.selectOne(new LambdaQueryWrapper<FollowSubscriptionEntity>()
                .eq(FollowSubscriptionEntity::getTenantId, securityService.currentTenantId())
                .eq(FollowSubscriptionEntity::getProjectId, currentProjectId)
                .eq(FollowSubscriptionEntity::getUserId, currentUserId)
                .eq(FollowSubscriptionEntity::getTargetType, targetType)
                .eq(FollowSubscriptionEntity::getTargetId, request.getTargetId())
                .last("limit 1"));
        if (existing == null) {
            existing = new FollowSubscriptionEntity();
            existing.setTenantId(securityService.currentTenantId());
            existing.setProjectId(currentProjectId);
            existing.setUserId(currentUserId);
            existing.setTargetType(targetType);
            existing.setTargetId(request.getTargetId());
            existing.setEnabled(Integer.valueOf(1));
            followSubscriptionMapper.insert(existing);
        } else {
            existing.setEnabled(Integer.valueOf(1));
            followSubscriptionMapper.updateById(existing);
        }
        return status(targetType, request.getTargetId());
    }

    @Transactional
    public void unfollow(String targetType, Long targetId) {
        validateTarget(targetType, targetId);
        FollowSubscriptionEntity existing = findActiveSubscription(normalizeTargetType(targetType), targetId);
        if (existing == null) {
            return;
        }
        existing.setEnabled(Integer.valueOf(0));
        followSubscriptionMapper.updateById(existing);
    }

    public List<Long> followerUserIds(String tenantId, Long projectId, String targetType, Long targetId) {
        if (!hasText(tenantId) || projectId == null || !hasText(targetType) || targetId == null) {
            return new ArrayList<Long>();
        }
        List<FollowSubscriptionEntity> subscriptions = followSubscriptionMapper.selectList(new LambdaQueryWrapper<FollowSubscriptionEntity>()
                .eq(FollowSubscriptionEntity::getTenantId, tenantId)
                .eq(FollowSubscriptionEntity::getProjectId, projectId)
                .eq(FollowSubscriptionEntity::getTargetType, normalizeTargetType(targetType))
                .eq(FollowSubscriptionEntity::getTargetId, targetId)
                .eq(FollowSubscriptionEntity::getEnabled, 1));
        Set<Long> userIds = new LinkedHashSet<Long>();
        for (FollowSubscriptionEntity subscription : subscriptions) {
            if (subscription.getUserId() != null) {
                userIds.add(subscription.getUserId());
            }
        }
        return new ArrayList<Long>(userIds);
    }

    private FollowSubscriptionEntity findActiveSubscription(String targetType, Long targetId) {
        Long currentProjectId = projectResourceAccessService.requireCurrentProjectId();
        Long currentUserId = requireCurrentUserId();
        return followSubscriptionMapper.selectOne(new LambdaQueryWrapper<FollowSubscriptionEntity>()
                .eq(FollowSubscriptionEntity::getTenantId, securityService.currentTenantId())
                .eq(FollowSubscriptionEntity::getProjectId, currentProjectId)
                .eq(FollowSubscriptionEntity::getUserId, currentUserId)
                .eq(FollowSubscriptionEntity::getTargetType, targetType)
                .eq(FollowSubscriptionEntity::getTargetId, targetId)
                .eq(FollowSubscriptionEntity::getEnabled, 1)
                .last("limit 1"));
    }

    private void validateTarget(String targetType, Long targetId) {
        if (!hasText(targetType) || targetId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Target type and target id are required");
        }
        String normalized = normalizeTargetType(targetType);
        if (!StudioConstants.FOLLOW_TARGET_MODEL_SYNC_TASK.equals(normalized)
                && !StudioConstants.FOLLOW_TARGET_COLLECTION_TASK.equals(normalized)
                && !StudioConstants.FOLLOW_TARGET_COLLECTION_TASK_RUN.equals(normalized)
                && !StudioConstants.FOLLOW_TARGET_WORKFLOW.equals(normalized)
                && !StudioConstants.FOLLOW_TARGET_WORKFLOW_RUN.equals(normalized)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported follow target type: " + targetType);
        }
    }

    private Long requireCurrentUserId() {
        Long userId = securityService.currentUserId();
        if (userId == null) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return userId;
    }

    private String normalizeTargetType(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
