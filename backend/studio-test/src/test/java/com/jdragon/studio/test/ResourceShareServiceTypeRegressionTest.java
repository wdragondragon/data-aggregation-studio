package com.jdragon.studio.test;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ResourceShareEntity;
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
import static org.mockito.Mockito.when;

class ResourceShareServiceTypeRegressionTest {

    @Test
    void shouldShareDataService() {
        TestContext context = context();
        DataServiceDefinitionEntity service = new DataServiceDefinitionEntity();
        service.setId(300L);
        service.setTenantId("default");
        service.setProjectId(10L);
        service.setServiceName("订单查询服务");
        when(context.dataServiceDefinitionMapper.selectById(300L)).thenReturn(service);

        ResourceShareEntity saved = context.service.saveResourceShare(share(StudioConstants.RESOURCE_TYPE_DATA_SERVICE, 300L));

        assertEquals(StudioConstants.RESOURCE_TYPE_DATA_SERVICE, saved.getResourceType());
        assertEquals(300L, saved.getResourceId());
        assertEquals(10L, saved.getSourceProjectId());
        assertEquals(20L, saved.getTargetProjectId());
    }

    @Test
    void shouldShareDataIngestionService() {
        TestContext context = context();
        DataIngestionServiceEntity service = new DataIngestionServiceEntity();
        service.setId(400L);
        service.setTenantId("default");
        service.setProjectId(10L);
        service.setServiceName("订单写入服务");
        when(context.dataIngestionServiceMapper.selectById(400L)).thenReturn(service);

        ResourceShareEntity saved = context.service.saveResourceShare(share(StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE, 400L));

        assertEquals(StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE, saved.getResourceType());
        assertEquals(400L, saved.getResourceId());
        assertEquals(10L, saved.getSourceProjectId());
        assertEquals(20L, saved.getTargetProjectId());
    }

    private TestContext context() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ResourceShareMapper resourceShareMapper = mock(ResourceShareMapper.class);
        DataServiceDefinitionMapper dataServiceDefinitionMapper = mock(DataServiceDefinitionMapper.class);
        DataIngestionServiceMapper dataIngestionServiceMapper = mock(DataIngestionServiceMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        NotificationService notificationService = mock(NotificationService.class);

        ProjectEntity sourceProject = project(10L);
        ProjectEntity targetProject = project(20L);
        when(projectMapper.selectById(10L)).thenReturn(sourceProject);
        when(projectMapper.selectById(20L)).thenReturn(targetProject);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(900L);
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));
        when(notificationService.activeProjectMemberUserIds("default", 20L)).thenReturn(Collections.emptyList());
        when(resourceShareMapper.selectIncludingDeleted(any(), any(), any(), any())).thenReturn(null);

        when(resourceShareMapper.insert(any(ResourceShareEntity.class))).thenAnswer(invocation -> {
            ResourceShareEntity inserted = invocation.getArgument(0);
            inserted.setId(500L);
            return 1;
        });

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
                mock(DataModelMapper.class),
                mock(CollectionTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class),
                mock(DataDevelopmentScriptMapper.class),
                dataServiceDefinitionMapper,
                dataIngestionServiceMapper,
                securityService,
                notificationService
        );
        return new TestContext(service, dataServiceDefinitionMapper, dataIngestionServiceMapper);
    }

    private ResourceShareEntity share(String resourceType, Long resourceId) {
        ResourceShareEntity share = new ResourceShareEntity();
        share.setSourceProjectId(10L);
        share.setTargetProjectId(20L);
        share.setResourceType(resourceType);
        share.setResourceId(resourceId);
        share.setEnabled(1);
        return share;
    }

    private ProjectEntity project(Long id) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setTenantId("default");
        return project;
    }

    private static class TestContext {
        private final SystemManagementService service;
        private final DataServiceDefinitionMapper dataServiceDefinitionMapper;
        private final DataIngestionServiceMapper dataIngestionServiceMapper;

        private TestContext(SystemManagementService service,
                            DataServiceDefinitionMapper dataServiceDefinitionMapper,
                            DataIngestionServiceMapper dataIngestionServiceMapper) {
            this.service = service;
            this.dataServiceDefinitionMapper = dataServiceDefinitionMapper;
            this.dataIngestionServiceMapper = dataIngestionServiceMapper;
        }
    }
}
