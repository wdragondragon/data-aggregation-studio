package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.model.WorkspaceAccessOverviewView;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.ProjectMemberRequestEntity;
import com.jdragon.studio.infra.entity.TenantEntity;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberRequestMapper;
import com.jdragon.studio.infra.mapper.TenantMapper;
import com.jdragon.studio.infra.service.NotificationService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.WorkspaceAccessService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceAccessSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(ProjectMemberEntity.class);
        initTableInfo(ProjectMemberRequestEntity.class);
        initTableInfo(ProjectEntity.class);
        initTableInfo(TenantEntity.class);
    }

    @Test
    void accessOverviewShouldSelectOnlyDisplayedFields() {
        TenantMapper tenantMapper = mock(TenantMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
        ProjectMemberRequestMapper projectMemberRequestMapper = mock(ProjectMemberRequestMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        WorkspaceAccessService service = new WorkspaceAccessService(
                tenantMapper,
                projectMapper,
                projectMemberMapper,
                projectMemberRequestMapper,
                securityService,
                mock(NotificationService.class));

        when(securityService.currentUserId()).thenReturn(9001L);
        when(projectMemberMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(activeMembership(1001L)));
        when(projectMemberRequestMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(pendingRequest(1002L)));
        when(projectMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(enabledProjects(), projectLookupProjects());
        when(tenantMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(tenant()));

        WorkspaceAccessOverviewView overview = service.overview();

        assertThat(overview.getTenantGroups()).hasSize(1);
        assertThat(overview.getTenantGroups().get(0).getProjects()).hasSize(1);
        assertThat(overview.getTenantGroups().get(0).getProjects().get(0).getPendingRequestStatus())
                .isEqualTo(StudioConstants.MEMBER_REQUEST_PENDING);
        assertThat(overview.getRequests()).hasSize(1);
        assertThat(overview.getRequests().get(0).getProjectName()).isEqualTo("长期回归-资源接收项目");

        ArgumentCaptor<LambdaQueryWrapper<ProjectMemberEntity>> memberCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectMemberMapper).selectList(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getSqlSelect())
                .contains("project_id")
                .doesNotContain("tenant_id", "role_code", "created_at", "updated_at");

        ArgumentCaptor<LambdaQueryWrapper<ProjectMemberRequestEntity>> requestCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectMemberRequestMapper).selectList(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "created_at", "updated_at", "project_id", "request_type", "status", "reason", "review_comment")
                .doesNotContain("user_id", "inviter_user_id", "reviewer_user_id");

        ArgumentCaptor<LambdaQueryWrapper<ProjectEntity>> projectCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectMapper, org.mockito.Mockito.times(2)).selectList(projectCaptor.capture());
        List<LambdaQueryWrapper<ProjectEntity>> projectQueries = projectCaptor.getAllValues();
        assertThat(projectQueries.get(0).getSqlSelect())
                .contains("id", "tenant_id", "project_code", "project_name", "description", "enabled", "default_project")
                .doesNotContain("created_at", "updated_at", "deleted");
        assertThat(projectQueries.get(1).getSqlSelect())
                .contains("id", "tenant_id", "project_name")
                .doesNotContain("project_code", "description", "enabled", "default_project", "created_at", "updated_at");

        ArgumentCaptor<LambdaQueryWrapper<TenantEntity>> tenantCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tenantMapper).selectList(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().getSqlSelect())
                .contains("tenant_id", "tenant_code", "tenant_name", "enabled")
                .doesNotContain("description", "created_at", "updated_at");
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private ProjectMemberEntity activeMembership(Long projectId) {
        ProjectMemberEntity entity = new ProjectMemberEntity();
        entity.setProjectId(projectId);
        return entity;
    }

    private ProjectMemberRequestEntity pendingRequest(Long projectId) {
        ProjectMemberRequestEntity entity = new ProjectMemberRequestEntity();
        entity.setId(2002L);
        entity.setTenantId("default");
        entity.setProjectId(projectId);
        entity.setRequestType(StudioConstants.MEMBER_REQUEST_APPLY);
        entity.setStatus(StudioConstants.MEMBER_REQUEST_PENDING);
        entity.setReason("申请参与长期回归验证");
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 29, 9, 30));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 9, 30));
        return entity;
    }

    private List<ProjectEntity> enabledProjects() {
        ProjectEntity joined = project(1001L, "长期回归测试项目");
        ProjectEntity available = project(1002L, "长期回归-资源接收项目");
        return Arrays.asList(joined, available);
    }

    private List<ProjectEntity> projectLookupProjects() {
        return Collections.singletonList(project(1002L, "长期回归-资源接收项目"));
    }

    private ProjectEntity project(Long id, String name) {
        ProjectEntity entity = new ProjectEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setProjectCode("lt_reg_project_" + id);
        entity.setProjectName(name);
        entity.setDescription("长期测试项目");
        entity.setEnabled(1);
        entity.setDefaultProject(0);
        return entity;
    }

    private TenantEntity tenant() {
        TenantEntity entity = new TenantEntity();
        entity.setTenantId("default");
        entity.setTenantCode("default");
        entity.setTenantName("Default Tenant");
        entity.setEnabled(1);
        return entity;
    }
}
