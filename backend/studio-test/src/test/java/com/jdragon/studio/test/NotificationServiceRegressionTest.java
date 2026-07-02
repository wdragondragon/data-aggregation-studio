package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceRegressionTest {

    @BeforeAll
    static void initMyBatisLambdaCaches() {
        if (TableInfoHelper.getTableInfo(NotificationEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), NotificationEntity.class);
        }
    }

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

    @Test
    void markReadShouldUseLightweightMarkerAndVoidUpdate() {
        TestContext context = context();
        NotificationEntity notification = new NotificationEntity();
        notification.setId(200L);
        notification.setRecipientUserId(10L);
        when(context.notificationMapper.selectOne(any())).thenReturn(notification);

        context.service.markRead(200L);

        ArgumentCaptor<LambdaQueryWrapper<NotificationEntity>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(context.notificationMapper).selectOne(queryCaptor.capture());
        String sqlSelect = String.valueOf(queryCaptor.getValue().getSqlSelect()).toLowerCase();
        assertTrue(sqlSelect.contains("id"));
        assertTrue(sqlSelect.contains("recipient_user_id"));
        assertTrue(sqlSelect.contains("read_at"));
        assertFalse(sqlSelect.contains("title"));
        assertFalse(sqlSelect.contains("content"));
        assertFalse(sqlSelect.contains("target_path"));
        assertFalse(sqlSelect.contains("payload_json"));

        ArgumentCaptor<LambdaUpdateWrapper<NotificationEntity>> updateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(context.notificationMapper).update(isNull(), updateCaptor.capture());
        String updateSql = String.valueOf(updateCaptor.getValue().getSqlSet()).toLowerCase()
                + " " + String.valueOf(updateCaptor.getValue().getSqlSegment()).toLowerCase();
        assertTrue(updateSql.contains("read_at"));
        assertTrue(updateSql.contains("recipient_user_id"));
        assertTrue(updateSql.contains("archived_at"));
        verify(context.notificationMapper, never()).selectList(any());
    }

    @Test
    void markAllReadShouldUpdateByConditionWithoutLoadingUnreadRows() {
        TestContext context = context();

        context.service.markAllRead();

        ArgumentCaptor<LambdaUpdateWrapper<NotificationEntity>> updateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(context.notificationMapper).update(isNull(), updateCaptor.capture());
        String updateSql = String.valueOf(updateCaptor.getValue().getSqlSet()).toLowerCase()
                + " " + String.valueOf(updateCaptor.getValue().getSqlSegment()).toLowerCase();
        assertTrue(updateSql.contains("read_at"));
        assertTrue(updateSql.contains("recipient_user_id"));
        assertTrue(updateSql.contains("archived_at"));
        verify(context.notificationMapper, never()).selectList(any());
    }

    private TestContext context() {
        NotificationMapper notificationMapper = mock(NotificationMapper.class);
        ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
        StudioUserMapper studioUserMapper = mock(StudioUserMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentUserId()).thenReturn(10L);
        NotificationService service = new NotificationService(
                notificationMapper,
                projectMemberMapper,
                studioUserMapper,
                mock(UserRoleMapper.class),
                mock(RoleMapper.class),
                securityService,
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
