package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.StudioUserListView;
import com.jdragon.studio.dto.model.StudioUserOptionView;
import com.jdragon.studio.dto.model.system.ResourceShareView;
import com.jdragon.studio.dto.model.system.SystemProjectMemberRequestView;
import com.jdragon.studio.dto.model.system.SystemProjectMemberView;
import com.jdragon.studio.dto.model.system.SystemProjectOptionView;
import com.jdragon.studio.dto.model.system.UserRegistrationRequestView;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.ProjectMemberRequestEntity;
import com.jdragon.studio.infra.entity.ResourceShareEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.UserRegistrationRequestEntity;
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
import com.jdragon.studio.infra.mapper.StudioExternalUserBindingMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.TenantMapper;
import com.jdragon.studio.infra.mapper.TenantMemberMapper;
import com.jdragon.studio.infra.mapper.UserRegistrationRequestMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.NotificationService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.SystemManagementService;
import com.jdragon.studio.infra.service.UserManagementService;
import com.jdragon.studio.infra.service.UserRegistrationRequestService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemManagementPaginationSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(StudioUserEntity.class);
        initTableInfo(UserRegistrationRequestEntity.class);
        initTableInfo(ProjectEntity.class);
        initTableInfo(ProjectMemberEntity.class);
        initTableInfo(ProjectMemberRequestEntity.class);
        initTableInfo(ResourceShareEntity.class);
        initTableInfo(DataModelEntity.class);
    }

    @Test
    void usersPageShouldUseDatabasePaginationInsteadOfFullList() {
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)).thenReturn(true);
        when(userMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<StudioUserEntity> page = invocation.getArgument(0);
            page.setTotal(3L);
            page.setRecords(Arrays.asList(user(10L, "lt_s64_客户运营", "客户运营"), user(11L, "lt_s64_订单分析", "订单分析")));
            return page;
        });
        UserManagementService service = new UserManagementService(
                userMapper,
                mock(UserRoleMapper.class),
                mock(PasswordEncoder.class),
                securityService,
                mock(StudioExternalUserBindingMapper.class));

        PageView<StudioUserListView> page = service.listPage(2, 2);

        assertThat(page.getPageNo()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(2);
        assertThat(page.getTotal()).isEqualTo(3L);
        assertThat(page.getItems()).extracting(StudioUserListView::getUsername)
                .containsExactly("lt_s64_客户运营", "lt_s64_订单分析");
        verify(userMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        verify(userMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void userOptionsShouldSelectOnlyDropdownFields() {
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)).thenReturn(true);
        when(userMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            LambdaQueryWrapper<StudioUserEntity> query = invocation.getArgument(0);
            String sqlSelect = String.valueOf(query.getSqlSelect()).toLowerCase(Locale.ROOT);
            assertThat(sqlSelect).contains("id", "username", "display_name");
            assertThat(sqlSelect).doesNotContain("tenant_id", "deleted", "created_at", "updated_at", "enabled", "password");
            return Arrays.asList(user(12L, "lt_s106_供应链专员", "供应链专员"), user(13L, "lt_s106_质量专员", "质量专员"));
        });
        UserManagementService service = new UserManagementService(
                userMapper,
                mock(UserRoleMapper.class),
                mock(PasswordEncoder.class),
                securityService,
                mock(StudioExternalUserBindingMapper.class));

        java.util.List<StudioUserOptionView> options = service.listOptions();

        assertThat(options).extracting(StudioUserOptionView::getUsername)
                .containsExactly("lt_s106_供应链专员", "lt_s106_质量专员");
        assertThat(options).extracting(StudioUserOptionView::getDisplayName)
                .containsExactly("供应链专员", "质量专员");
        verify(userMapper).selectList(any(LambdaQueryWrapper.class));
        verify(userMapper, never()).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void projectOptionsShouldSelectOnlyDropdownFields() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));
        when(projectMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            LambdaQueryWrapper<ProjectEntity> query = invocation.getArgument(0);
            String sqlSelect = String.valueOf(query.getSqlSelect()).toLowerCase(Locale.ROOT);
            assertThat(sqlSelect).contains("id", "project_name");
            assertThat(sqlSelect).doesNotContain("tenant_id", "deleted", "created_at", "updated_at", "project_code", "description", "enabled", "default_project");
            return Arrays.asList(project(100L, "长期回归测试项目"), project(200L, "财务共享验证项目"));
        });
        SystemManagementService service = systemService(projectMapper, mock(ProjectMemberMapper.class), mock(StudioUserMapper.class), securityService);

        java.util.List<SystemProjectOptionView> options = service.listProjectOptions();

        assertThat(options).extracting(SystemProjectOptionView::getProjectName)
                .containsExactly("长期回归测试项目", "财务共享验证项目");
        verify(projectMapper).selectList(any(LambdaQueryWrapper.class));
        verify(projectMapper, never()).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void registrationRequestsPageShouldExcludePasswordHashAtSqlSource() {
        UserRegistrationRequestMapper requestMapper = mock(UserRegistrationRequestMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN)).thenReturn(true);
        when(requestMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            LambdaQueryWrapper<UserRegistrationRequestEntity> query = invocation.getArgument(1);
            assertThat(String.valueOf(query.getSqlSelect()).toLowerCase(Locale.ROOT)).doesNotContain("password");
            Page<UserRegistrationRequestEntity> page = invocation.getArgument(0);
            page.setTotal(12L);
            page.setRecords(Collections.singletonList(registrationRequest(30L, "lt_s64_供应链注册")));
            return page;
        });
        UserRegistrationRequestService service = new UserRegistrationRequestService(
                requestMapper,
                mock(StudioUserMapper.class),
                mock(PasswordEncoder.class),
                securityService,
                mock(NotificationService.class));

        PageView<UserRegistrationRequestView> page = service.listPage(1, 1);

        assertThat(page.getTotal()).isEqualTo(12L);
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getUsername()).isEqualTo("lt_s64_供应链注册");
        verify(requestMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        verify(requestMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void projectMembersPageShouldHydrateUsersForCurrentPageOnly() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectEntity project = new ProjectEntity();
        project.setId(100L);
        project.setTenantId("default");
        project.setProjectName("长期回归测试项目");
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));
        when(projectMemberMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<ProjectMemberEntity> page = invocation.getArgument(0);
            page.setTotal(6L);
            page.setRecords(Arrays.asList(projectMember(201L, 301L), projectMember(202L, 302L)));
            return page;
        });
        when(userMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertUserHydrationSelect(invocation.getArgument(0));
            return Arrays.asList(
                    user(301L, "lt_s64_模型管理员", "模型管理员"),
                    user(302L, "lt_s64_质量分析师", "质量分析师"));
        });
        SystemManagementService service = systemService(projectMapper, projectMemberMapper, userMapper, securityService);

        PageView<SystemProjectMemberView> page = service.listProjectMembersPage(100L, 1, 2);

        assertThat(page.getTotal()).isEqualTo(6L);
        assertThat(page.getItems()).extracting(SystemProjectMemberView::getUsername)
                .containsExactly("lt_s64_模型管理员", "lt_s64_质量分析师");
        verify(projectMemberMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        verify(projectMemberMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(userMapper).selectList(any(LambdaQueryWrapper.class));
        verify(userMapper, never()).selectByIds(any());
    }

    @Test
    void projectMemberRequestsPageShouldHydrateUsersWithLightweightFields() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectMemberRequestMapper requestMapper = mock(ProjectMemberRequestMapper.class);
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectEntity project = project(100L, "长期回归测试项目");
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));
        when(requestMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<ProjectMemberRequestEntity> page = invocation.getArgument(0);
            page.setTotal(4L);
            page.setRecords(Collections.singletonList(projectMemberRequest(401L, 501L, 502L, 503L)));
            return page;
        });
        when(userMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertUserHydrationSelect(invocation.getArgument(0));
            return Arrays.asList(
                    user(501L, "lt_s109_财务申请人", "财务申请人"),
                    user(502L, "lt_s109_项目邀请人", "项目邀请人"),
                    user(503L, "lt_s109_租户审核人", "租户审核人"));
        });
        SystemManagementService service = new SystemManagementService(
                mock(TenantMapper.class),
                projectMapper,
                mock(TenantMemberMapper.class),
                mock(ProjectMemberMapper.class),
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
                mock(NotificationService.class));

        PageView<SystemProjectMemberRequestView> page = service.listProjectMemberRequestsPage(100L, 1, 1);

        assertThat(page.getTotal()).isEqualTo(4L);
        assertThat(page.getItems()).hasSize(1);
        SystemProjectMemberRequestView item = page.getItems().get(0);
        assertThat(item.getUsername()).isEqualTo("lt_s109_财务申请人");
        assertThat(item.getInviterUsername()).isEqualTo("lt_s109_项目邀请人");
        assertThat(item.getReviewerUsername()).isEqualTo("lt_s109_租户审核人");
        verify(requestMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        verify(requestMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(userMapper).selectList(any(LambdaQueryWrapper.class));
        verify(userMapper, never()).selectByIds(any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resourceSharesPageShouldHydrateLabelsForCurrentPageOnly() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ResourceShareMapper resourceShareMapper = mock(ResourceShareMapper.class);
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectEntity sourceProject = project(100L, "长期回归测试项目");
        ProjectEntity targetProject = project(200L, "财务共享验证项目");
        when(projectMapper.selectById(100L)).thenReturn(sourceProject);
        when(projectMapper.selectByIds(any())).thenReturn(Arrays.asList(sourceProject, targetProject));
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));
        when(resourceShareMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<ResourceShareEntity> page = invocation.getArgument(0);
            page.setTotal(8L);
            page.setRecords(Collections.singletonList(resourceShare(900L, 100L, 200L, StudioConstants.RESOURCE_TYPE_DATA_MODEL, 501L)));
            return page;
        });
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model(501L, "客户订单宽表", "dw_customer_order")));
        SystemManagementService service = new SystemManagementService(
                mock(TenantMapper.class),
                projectMapper,
                mock(TenantMemberMapper.class),
                mock(ProjectMemberMapper.class),
                mock(ProjectMemberRequestMapper.class),
                mock(ProjectWorkerBindingMapper.class),
                resourceShareMapper,
                mock(StudioUserMapper.class),
                mock(WorkerLeaseMapper.class),
                mock(DatasourceMapper.class),
                dataModelMapper,
                mock(CollectionTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class),
                mock(DataDevelopmentScriptMapper.class),
                mock(DataServiceDefinitionMapper.class),
                mock(DataIngestionServiceMapper.class),
                mock(ProtocolConversionServiceMapper.class),
                securityService,
                mock(NotificationService.class));

        PageView<ResourceShareView> page = service.listResourceSharesPage(StudioConstants.RESOURCE_TYPE_DATA_MODEL, 100L, 1, 1);

        assertThat(page.getTotal()).isEqualTo(8L);
        assertThat(page.getItems()).hasSize(1);
        ResourceShareView item = page.getItems().get(0);
        assertThat(item.getResourceLabel()).isEqualTo("客户订单宽表 / dw_customer_order");
        assertThat(item.getSourceProjectName()).isEqualTo("长期回归测试项目");
        assertThat(item.getTargetProjectName()).isEqualTo("财务共享验证项目");
        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> modelQueryCaptor = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(dataModelMapper).selectList(modelQueryCaptor.capture());
        assertThat(modelQueryCaptor.getValue().getSqlSegment().toUpperCase(Locale.ROOT)).contains("IN");
        verify(resourceShareMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        verify(resourceShareMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    private SystemManagementService systemService(ProjectMapper projectMapper,
                                                  ProjectMemberMapper projectMemberMapper,
                                                  StudioUserMapper userMapper,
                                                  StudioSecurityService securityService) {
        return new SystemManagementService(
                mock(TenantMapper.class),
                projectMapper,
                mock(TenantMemberMapper.class),
                projectMemberMapper,
                mock(ProjectMemberRequestMapper.class),
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
                mock(NotificationService.class));
    }

    private StudioUserEntity user(Long id, String username, String displayName) {
        StudioUserEntity user = new StudioUserEntity();
        user.setId(id);
        user.setTenantId("default");
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setEnabled(1);
        return user;
    }

    private UserRegistrationRequestEntity registrationRequest(Long id, String username) {
        UserRegistrationRequestEntity request = new UserRegistrationRequestEntity();
        request.setId(id);
        request.setUsername(username);
        request.setDisplayName("供应链注册用户");
        request.setStatus(StudioConstants.REGISTRATION_REQUEST_PENDING);
        return request;
    }

    private ProjectMemberEntity projectMember(Long id, Long userId) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setId(id);
        member.setTenantId("default");
        member.setProjectId(100L);
        member.setUserId(userId);
        member.setRoleCode(StudioConstants.ROLE_PROJECT_MEMBER);
        member.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
        return member;
    }

    private ProjectMemberRequestEntity projectMemberRequest(Long id, Long userId, Long inviterUserId, Long reviewerUserId) {
        ProjectMemberRequestEntity request = new ProjectMemberRequestEntity();
        request.setId(id);
        request.setTenantId("default");
        request.setProjectId(100L);
        request.setUserId(userId);
        request.setInviterUserId(inviterUserId);
        request.setReviewerUserId(reviewerUserId);
        request.setRequestType(StudioConstants.MEMBER_REQUEST_INVITE);
        request.setStatus(StudioConstants.MEMBER_REQUEST_PENDING);
        request.setReason("长期回归-S109 财务协作加入项目");
        return request;
    }

    private ProjectEntity project(Long id, String name) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setTenantId("default");
        project.setProjectName(name);
        return project;
    }

    private ResourceShareEntity resourceShare(Long id, Long sourceProjectId, Long targetProjectId, String resourceType, Long resourceId) {
        ResourceShareEntity share = new ResourceShareEntity();
        share.setId(id);
        share.setTenantId("default");
        share.setSourceProjectId(sourceProjectId);
        share.setTargetProjectId(targetProjectId);
        share.setResourceType(resourceType);
        share.setResourceId(resourceId);
        share.setEnabled(1);
        return share;
    }

    private DataModelEntity model(Long id, String name, String physicalLocator) {
        DataModelEntity model = new DataModelEntity();
        model.setId(id);
        model.setTenantId("default");
        model.setProjectId(100L);
        model.setName(name);
        model.setPhysicalLocator(physicalLocator);
        return model;
    }

    private static void assertUserHydrationSelect(LambdaQueryWrapper<StudioUserEntity> query) {
        String sqlSelect = String.valueOf(query.getSqlSelect()).toLowerCase(Locale.ROOT);
        assertThat(sqlSelect).contains("id", "username", "display_name");
        assertThat(sqlSelect).doesNotContain("password", "password_hash", "auth_source", "tenant_id",
                "deleted", "created_at", "updated_at", "enabled");
        assertThat(query.getSqlSegment().toUpperCase(Locale.ROOT)).contains("IN");
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }
}
