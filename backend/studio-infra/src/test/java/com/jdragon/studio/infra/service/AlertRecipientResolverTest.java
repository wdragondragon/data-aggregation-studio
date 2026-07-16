package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertRecipientResolverTest {

    @Test
    void shouldExcludeRecipientsWhoAreNoLongerActiveProjectMembers() {
        ProjectMemberMapper mapper = mock(ProjectMemberMapper.class);
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        ProjectMemberEntity activeMember = member(2L, "PROJECT_MEMBER");
        ProjectMemberEntity activeAdmin = member(4L, StudioConstants.ROLE_PROJECT_ADMIN);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(activeMember, activeAdmin));
        when(userMapper.selectByIds(any())).thenReturn(Arrays.asList(user(2L, true), user(4L, true)));

        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setTenantId("default");
        rule.setProjectId(20L);
        rule.setRecipientUserIdsJson(Arrays.asList(1L, 2L));
        rule.setNotifyResourceOwner(1);
        rule.setNotifyProjectAdmins(1);

        assertEquals(Arrays.asList(2L, 4L), new AlertRecipientResolver(mapper, userMapper).resolve(rule, 3L));
    }

    @Test
    void shouldExcludeDisabledAccountsEvenWhenProjectMembershipIsActive() {
        ProjectMemberMapper mapper = mock(ProjectMemberMapper.class);
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        ProjectMemberEntity disabledMember = member(2L, "PROJECT_MEMBER");
        ProjectMemberEntity enabledAdmin = member(4L, StudioConstants.ROLE_PROJECT_ADMIN);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(disabledMember, enabledAdmin));
        when(userMapper.selectByIds(any())).thenReturn(Arrays.asList(user(2L, false), user(4L, true)));

        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setTenantId("default");
        rule.setProjectId(20L);
        rule.setRecipientUserIdsJson(Arrays.asList(2L, 4L));
        rule.setNotifyResourceOwner(1);
        rule.setNotifyProjectAdmins(1);

        assertEquals(Arrays.asList(4L), new AlertRecipientResolver(mapper, userMapper).resolve(rule, 2L));
    }

    private ProjectMemberEntity member(Long userId, String roleCode) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setTenantId("default");
        member.setProjectId(20L);
        member.setUserId(userId);
        member.setRoleCode(roleCode);
        member.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
        return member;
    }

    private StudioUserEntity user(Long userId, boolean enabled) {
        StudioUserEntity user = new StudioUserEntity();
        user.setId(userId);
        user.setTenantId("default");
        user.setEnabled(enabled ? 1 : 0);
        return user;
    }
}
