package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.StudioExternalUserBindingEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.StudioExternalUserBindingMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AlertRecipientResolver {

    private final ProjectMemberMapper projectMemberMapper;
    private final StudioUserMapper studioUserMapper;
    private final StudioExternalUserBindingMapper externalUserBindingMapper;

    @Autowired
    public AlertRecipientResolver(ProjectMemberMapper projectMemberMapper,
                                  StudioUserMapper studioUserMapper,
                                  StudioExternalUserBindingMapper externalUserBindingMapper) {
        this.projectMemberMapper = projectMemberMapper;
        this.studioUserMapper = studioUserMapper;
        this.externalUserBindingMapper = externalUserBindingMapper;
    }

    AlertRecipientResolver(ProjectMemberMapper projectMemberMapper, StudioUserMapper studioUserMapper) {
        this(projectMemberMapper, studioUserMapper, null);
    }

    public List<Long> resolve(AlertRuleEntity rule, Long ownerUserId) {
        Set<Long> result = new LinkedHashSet<Long>();
        List<ProjectMemberEntity> activeMembers = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getTenantId, rule.getTenantId())
                .eq(ProjectMemberEntity::getProjectId, rule.getProjectId())
                .eq(ProjectMemberEntity::getStatus, StudioConstants.MEMBER_STATUS_ACTIVE));
        Set<Long> activeMemberIds = new LinkedHashSet<Long>();
        for (ProjectMemberEntity member : activeMembers) {
            if (member.getUserId() != null) {
                activeMemberIds.add(member.getUserId());
            }
        }
        Set<Long> enabledMemberIds = new LinkedHashSet<Long>();
        if (!activeMemberIds.isEmpty()) {
            List<StudioUserEntity> users = studioUserMapper.selectByIds(activeMemberIds);
            if (users != null) {
                for (StudioUserEntity user : users) {
                    if (user != null && user.getId() != null && Integer.valueOf(1).equals(user.getEnabled())) {
                        enabledMemberIds.add(user.getId());
                    }
                }
            }
        }
        if (rule.getRecipientUserIdsJson() != null) {
            for (Long userId : rule.getRecipientUserIdsJson()) {
                if (enabledMemberIds.contains(userId)) {
                    result.add(userId);
                }
            }
        }
        if (Integer.valueOf(1).equals(rule.getNotifyResourceOwner()) && enabledMemberIds.contains(ownerUserId)) {
            result.add(ownerUserId);
        }
        if (Integer.valueOf(1).equals(rule.getNotifyProjectAdmins())) {
            for (ProjectMemberEntity member : activeMembers) {
                if (StudioConstants.ROLE_PROJECT_ADMIN.equalsIgnoreCase(member.getRoleCode())
                        && enabledMemberIds.contains(member.getUserId())) {
                    result.add(member.getUserId());
                }
            }
        }
        result.remove(null);
        return new ArrayList<Long>(result);
    }

    public Map<Long, String> resolveElinkUserIds(Collection<Long> studioUserIds) {
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        Set<Long> ids = new LinkedHashSet<Long>();
        if (studioUserIds != null) {
            for (Long userId : studioUserIds) {
                if (userId != null) {
                    ids.add(userId);
                }
            }
        }
        if (ids.isEmpty() || externalUserBindingMapper == null) {
            return result;
        }
        List<StudioExternalUserBindingEntity> bindings = externalUserBindingMapper.selectList(
                new LambdaQueryWrapper<StudioExternalUserBindingEntity>()
                        .eq(StudioExternalUserBindingEntity::getProviderCode, StudioConstants.ELINK_PROVIDER_CODE)
                        .in(StudioExternalUserBindingEntity::getStudioUserId, ids));
        for (StudioExternalUserBindingEntity binding : bindings) {
            if (binding != null && binding.getStudioUserId() != null
                    && binding.getExternalUserId() != null && !binding.getExternalUserId().trim().isEmpty()) {
                result.putIfAbsent(binding.getStudioUserId(), binding.getExternalUserId().trim());
            }
        }
        return result;
    }

    public Map<Long, String> resolveStudioUserMobiles(Collection<Long> studioUserIds) {
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        Set<Long> ids = new LinkedHashSet<Long>();
        if (studioUserIds != null) {
            for (Long userId : studioUserIds) {
                if (userId != null) {
                    ids.add(userId);
                }
            }
        }
        if (ids.isEmpty()) {
            return result;
        }
        List<StudioUserEntity> users = studioUserMapper.selectByIds(ids);
        if (users == null) {
            return result;
        }
        for (StudioUserEntity user : users) {
            if (user == null || user.getId() == null) {
                continue;
            }
            String mobile = StudioMobileSupport.normalize(user.getMobilePhone());
            if (mobile != null) {
                result.putIfAbsent(user.getId(), mobile);
            }
        }
        return result;
    }
}
