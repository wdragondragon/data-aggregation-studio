package com.jdragon.studio.test;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.entity.NotificationEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.NotificationMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.RoleMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import com.jdragon.studio.infra.service.NotificationCommand;
import com.jdragon.studio.infra.service.NotificationService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceRegressionTest {

    @Test
    void activeProjectMemberUserIdsShouldSkipDisabledUsers() {
        TestContext context = context();
        when(context.projectMemberMapper.selectList(any())).thenReturn(Arrays.asList(
                projectMember(10L),
                projectMember(11L),
                projectMember(12L)
        ));
        when(context.studioUserMapper.selectByIds(any())).thenReturn(Arrays.asList(
                studioUser(10L, 1),
                studioUser(11L, 0)
        ));

        assertEquals(Collections.singletonList(10L), context.service.activeProjectMemberUserIds("default", 20L));
    }

    @Test
    void notifyUsersShouldSkipDisabledRecipients() {
        TestContext context = context();
        when(context.studioUserMapper.selectByIds(any())).thenReturn(Arrays.asList(
                studioUser(10L, 1),
                studioUser(11L, 0)
        ));
        when(context.notificationMapper.selectOne(any())).thenReturn(null);
        when(context.notificationMapper.selectCount(any())).thenReturn(0L);
        when(context.notificationMapper.selectList(any())).thenReturn(Collections.emptyList());

        context.service.notifyUsers(Arrays.asList(10L, 11L, 12L), new NotificationCommand()
                .setCategory(StudioConstants.NOTIFICATION_CATEGORY_RESOURCE_SHARE)
                .setTitle("收到新的共享资源")
                .setContent("项目收到共享资源。")
                .setTargetTenantId("default")
                .setTargetProjectId(20L)
                .setTargetType(StudioConstants.RESOURCE_TYPE_WORKFLOW)
                .setTargetId(30L)
                .setDedupeKey("resource-share:30:20:test"));

        ArgumentCaptor<NotificationEntity> captor = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(context.notificationMapper).insert(captor.capture());
        assertEquals(10L, captor.getValue().getRecipientUserId());
    }

    private TestContext context() {
        NotificationMapper notificationMapper = mock(NotificationMapper.class);
        ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
        StudioUserMapper studioUserMapper = mock(StudioUserMapper.class);
        NotificationService service = new NotificationService(
                notificationMapper,
                projectMemberMapper,
                studioUserMapper,
                mock(UserRoleMapper.class),
                mock(RoleMapper.class),
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class)
        );
        return new TestContext(service, notificationMapper, projectMemberMapper, studioUserMapper);
    }

    private ProjectMemberEntity projectMember(Long userId) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setTenantId("default");
        member.setProjectId(20L);
        member.setUserId(userId);
        member.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
        return member;
    }

    private StudioUserEntity studioUser(Long userId, Integer enabled) {
        StudioUserEntity user = new StudioUserEntity();
        user.setId(userId);
        user.setTenantId("default");
        user.setUsername("lt_reg_s19_user_" + userId);
        user.setEnabled(enabled);
        return user;
    }

    private static class TestContext {
        private final NotificationService service;
        private final NotificationMapper notificationMapper;
        private final ProjectMemberMapper projectMemberMapper;
        private final StudioUserMapper studioUserMapper;

        private TestContext(NotificationService service,
                            NotificationMapper notificationMapper,
                            ProjectMemberMapper projectMemberMapper,
                            StudioUserMapper studioUserMapper) {
            this.service = service;
            this.notificationMapper = notificationMapper;
            this.projectMemberMapper = projectMemberMapper;
            this.studioUserMapper = studioUserMapper;
        }
    }
}
