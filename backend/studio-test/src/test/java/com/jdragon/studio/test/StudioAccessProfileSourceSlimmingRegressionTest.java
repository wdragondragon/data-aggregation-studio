package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.model.auth.AuthProfileView;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.RoleEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.TenantEntity;
import com.jdragon.studio.infra.entity.TenantMemberEntity;
import com.jdragon.studio.infra.entity.UserRoleEntity;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.RoleMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.TenantMapper;
import com.jdragon.studio.infra.mapper.TenantMemberMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import com.jdragon.studio.infra.security.StudioUserPrincipal;
import com.jdragon.studio.infra.service.StudioAccessService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudioAccessProfileSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(StudioUserEntity.class);
        initTableInfo(UserRoleEntity.class);
        initTableInfo(RoleEntity.class);
        initTableInfo(TenantEntity.class);
        initTableInfo(TenantMemberEntity.class);
        initTableInfo(ProjectEntity.class);
        initTableInfo(ProjectMemberEntity.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void projectMemberProfileShouldUseSourceLightQueriesForHeaderContext() {
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        TenantMapper tenantMapper = mock(TenantMapper.class);
        TenantMemberMapper tenantMemberMapper = mock(TenantMemberMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertUserProfileSelect(invocation.getArgument(0));
            return user(10L, "default", "lt_s111_项目成员", "项目成员");
        });
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(tenantMemberMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertSelect(invocation.getArgument(0),
                    new String[]{"tenant_id", "role_code"},
                    new String[]{"id", "user_id", "status", "deleted", "created_at", "updated_at"});
            return Collections.singletonList(tenantMember("default", StudioConstants.ROLE_TENANT_ADMIN));
        });
        when(projectMemberMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertSelect(invocation.getArgument(0),
                    new String[]{"project_id", "role_code"},
                    new String[]{"id", "tenant_id", "user_id", "status", "deleted", "created_at", "updated_at"});
            return Collections.singletonList(projectMember(100L, StudioConstants.ROLE_PROJECT_MEMBER));
        });
        when(tenantMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertTenantProfileSelect(invocation.getArgument(0));
            return Collections.singletonList(tenant("default", "Default Tenant"));
        });
        when(projectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(project(100L, "default", "长期回归测试项目")));
        StudioAccessService service = new StudioAccessService(userMapper, userRoleMapper, roleMapper, tenantMapper,
                tenantMemberMapper, projectMapper, projectMemberMapper);

        AuthProfileView profile = service.buildProfile(principal(10L, "default", "lt_s111_项目成员"),
                "default",
                "100",
                "token-s111");

        assertThat(profile.getCurrentTenantId()).isEqualTo("default");
        assertThat(profile.getCurrentProjectId()).isEqualTo(100L);
        assertThat(profile.getTenants()).hasSize(1);
        assertThat(profile.getProjects()).hasSize(1);
        ArgumentCaptor<LambdaQueryWrapper<ProjectEntity>> projectCaptor = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(projectMapper, times(2)).selectList(projectCaptor.capture());
        List<LambdaQueryWrapper<ProjectEntity>> projectQueries = projectCaptor.getAllValues();
        assertAccessibleProjectSelect(projectQueries.get(0));
        assertAuthProjectSelect(projectQueries.get(1));
        verify(projectMapper, never()).selectByIds(any());
        verify(roleMapper, never()).selectByIds(any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void superAdminProfileShouldLoadRolesLightlyAndAvoidAllProjectPrefetch() {
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        TenantMapper tenantMapper = mock(TenantMapper.class);
        TenantMemberMapper tenantMemberMapper = mock(TenantMemberMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertUserProfileSelect(invocation.getArgument(0));
            return user(1L, "default", "admin", "Studio Admin");
        });
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertSelect(invocation.getArgument(0),
                    new String[]{"role_id"},
                    new String[]{"id", "tenant_id", "user_id", "deleted", "created_at", "updated_at"});
            UserRoleEntity entity = new UserRoleEntity();
            entity.setRoleId(1L);
            return Collections.singletonList(entity);
        });
        when(roleMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertSelect(invocation.getArgument(0),
                    new String[]{"code"},
                    new String[]{"name", "description", "tenant_id", "deleted", "created_at", "updated_at"});
            RoleEntity role = new RoleEntity();
            role.setId(1L);
            role.setCode(StudioConstants.ROLE_SUPER_ADMIN);
            return Collections.singletonList(role);
        });
        when(projectMemberMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(tenantMemberMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(tenantMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertTenantProfileSelect(invocation.getArgument(0));
            return Collections.singletonList(tenant("default", "Default Tenant"));
        });
        when(projectMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertAuthProjectSelect(invocation.getArgument(0));
            return Collections.singletonList(project(100L, "default", "长期回归测试项目"));
        });
        StudioAccessService service = new StudioAccessService(userMapper, userRoleMapper, roleMapper, tenantMapper,
                tenantMemberMapper, projectMapper, projectMemberMapper);

        AuthProfileView profile = service.buildProfile(principal(1L, "default", "admin"),
                "default",
                "100",
                "token-s111");

        assertThat(profile.getSystemRoleCodes()).containsExactly(StudioConstants.ROLE_SUPER_ADMIN);
        assertThat(profile.getCurrentProjectId()).isEqualTo(100L);
        verify(projectMapper, times(1)).selectList(any(LambdaQueryWrapper.class));
        verify(projectMapper, never()).selectByIds(any());
        verify(roleMapper).selectList(any(LambdaQueryWrapper.class));
        verify(roleMapper, never()).selectByIds(any());
    }

    private static StudioUserPrincipal principal(Long userId, String tenantId, String username) {
        return new StudioUserPrincipal(userId, tenantId, username, "masked", true, Collections.emptyList());
    }

    private static StudioUserEntity user(Long id, String tenantId, String username, String displayName) {
        StudioUserEntity user = new StudioUserEntity();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setDisplayName(displayName);
        return user;
    }

    private static TenantEntity tenant(String tenantId, String tenantName) {
        TenantEntity tenant = new TenantEntity();
        tenant.setTenantId(tenantId);
        tenant.setTenantCode(tenantId);
        tenant.setTenantName(tenantName);
        tenant.setEnabled(1);
        return tenant;
    }

    private static ProjectEntity project(Long id, String tenantId, String projectName) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setTenantId(tenantId);
        project.setProjectCode("lt_s111_project");
        project.setProjectName(projectName);
        project.setEnabled(1);
        project.setDefaultProject(1);
        return project;
    }

    private static TenantMemberEntity tenantMember(String tenantId, String roleCode) {
        TenantMemberEntity member = new TenantMemberEntity();
        member.setTenantId(tenantId);
        member.setRoleCode(roleCode);
        return member;
    }

    private static ProjectMemberEntity projectMember(Long projectId, String roleCode) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setProjectId(projectId);
        member.setRoleCode(roleCode);
        return member;
    }

    private static void assertUserProfileSelect(LambdaQueryWrapper<StudioUserEntity> query) {
        assertSelect(query,
                new String[]{"id", "tenant_id", "username", "display_name"},
                new String[]{"password", "password_hash", "auth_source", "enabled", "deleted", "created_at", "updated_at"});
    }

    private static void assertTenantProfileSelect(LambdaQueryWrapper<TenantEntity> query) {
        assertSelect(query,
                new String[]{"tenant_id", "tenant_code", "tenant_name", "enabled"},
                new String[]{"id", "description", "deleted", "created_at", "updated_at"});
    }

    private static void assertAccessibleProjectSelect(LambdaQueryWrapper<ProjectEntity> query) {
        assertSelect(query,
                new String[]{"id", "tenant_id"},
                new String[]{"project_code", "project_name", "description", "enabled", "default_project", "deleted", "created_at", "updated_at"});
    }

    private static void assertAuthProjectSelect(LambdaQueryWrapper<ProjectEntity> query) {
        assertSelect(query,
                new String[]{"id", "tenant_id", "project_code", "project_name", "enabled", "default_project"},
                new String[]{"description", "deleted", "created_at", "updated_at"});
    }

    private static void assertSelect(LambdaQueryWrapper<?> query, String[] includes, String[] excludes) {
        Set<String> columns = new LinkedHashSet<String>();
        for (String column : String.valueOf(query.getSqlSelect()).toLowerCase(Locale.ROOT).split(",")) {
            columns.add(column.trim());
        }
        assertThat(columns).contains(includes);
        assertThat(columns).doesNotContain(Arrays.asList(excludes).toArray(new String[0]));
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }
}
