package com.jdragon.studio.test;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.ProjectMemberRequestEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberRequestMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.ResourceShareMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.TenantMapper;
import com.jdragon.studio.infra.mapper.TenantMemberMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.NotificationService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.SystemManagementService;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemProjectMemberRequestRegressionTest {

    @Test
    void shouldRestoreDeletedProjectMemberWhenApprovedRequestTargetsSameUser() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
        ProjectMemberRequestMapper requestMapper = mock(ProjectMemberRequestMapper.class);
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        SystemManagementService service = service(projectMapper, projectMemberMapper, requestMapper, userMapper, securityService);

        ProjectEntity project = new ProjectEntity();
        project.setId(100L);
        project.setTenantId("default");
        project.setProjectName("客户经营分析项目");
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(1L);
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));

        StudioUserEntity user = new StudioUserEntity();
        user.setId(200L);
        user.setUsername("test_20260620_客户分析师");
        user.setDisplayName("客户分析师");
        when(userMapper.selectById(200L)).thenReturn(user);

        ProjectMemberEntity deletedMember = new ProjectMemberEntity();
        deletedMember.setId(300L);
        deletedMember.setTenantId("default");
        deletedMember.setProjectId(100L);
        deletedMember.setUserId(200L);
        deletedMember.setRoleCode(StudioConstants.ROLE_PROJECT_MEMBER);
        deletedMember.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
        deletedMember.setDeleted(1);
        when(projectMemberMapper.selectByProjectAndUserIncludingDeleted(100L, 200L)).thenReturn(deletedMember);

        ProjectMemberRequestEntity request = new ProjectMemberRequestEntity();
        request.setProjectId(100L);
        request.setUserId(200L);
        request.setRequestType(StudioConstants.MEMBER_REQUEST_INVITE);
        request.setStatus(StudioConstants.MEMBER_REQUEST_APPROVED);
        request.setReason("客户经营分析项目成员邀请");

        ProjectMemberRequestEntity result = service.saveProjectMemberRequest(request);

        assertEquals(StudioConstants.MEMBER_REQUEST_APPROVED, result.getStatus());
        verify(requestMapper).insert(any(ProjectMemberRequestEntity.class));
        verify(projectMemberMapper).restoreById(
                300L,
                100L,
                200L,
                StudioConstants.ROLE_PROJECT_MEMBER,
                StudioConstants.MEMBER_STATUS_ACTIVE);
        verify(projectMemberMapper, never()).insert(any(ProjectMemberEntity.class));
        verify(projectMemberMapper, never()).updateById(any(ProjectMemberEntity.class));
    }

    private SystemManagementService service(ProjectMapper projectMapper,
                                            ProjectMemberMapper projectMemberMapper,
                                            ProjectMemberRequestMapper requestMapper,
                                            StudioUserMapper userMapper,
                                            StudioSecurityService securityService) {
        return new SystemManagementService(
                mock(TenantMapper.class),
                projectMapper,
                mock(TenantMemberMapper.class),
                projectMemberMapper,
                requestMapper,
                mock(ProjectWorkerBindingMapper.class),
                mock(ResourceShareMapper.class),
                userMapper,
                mock(WorkerLeaseMapper.class),
                mock(DatasourceMapper.class),
                mock(DataModelMapper.class),
                mock(CollectionTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class),
                mock(DataDevelopmentScriptMapper.class),
                mock(DataServiceDefinitionMapper.class),
                mock(DataIngestionServiceMapper.class),
                mock(ProtocolConversionServiceMapper.class),
                securityService,
                mock(NotificationService.class)
        );
    }
}
