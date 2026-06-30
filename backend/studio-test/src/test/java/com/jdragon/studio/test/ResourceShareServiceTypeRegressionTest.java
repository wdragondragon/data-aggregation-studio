package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceShareServiceTypeRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(DataServiceDefinitionEntity.class);
        initTableInfo(DataIngestionServiceEntity.class);
        initTableInfo(ProtocolConversionServiceEntity.class);
    }

    @Test
    void shouldShareDataService() {
        TestContext context = context();
        when(context.dataServiceDefinitionMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertDataServiceShareOptionSelect(invocation.getArgument(0));
            return Collections.singletonList(dataService(300L, "订单查询服务", "order_query_service"));
        });

        ResourceShareEntity saved = context.service.saveResourceShare(share(StudioConstants.RESOURCE_TYPE_DATA_SERVICE, 300L));

        assertEquals(StudioConstants.RESOURCE_TYPE_DATA_SERVICE, saved.getResourceType());
        assertEquals(300L, saved.getResourceId());
        assertEquals(10L, saved.getSourceProjectId());
        assertEquals(20L, saved.getTargetProjectId());
        verify(context.dataServiceDefinitionMapper, never()).selectById(any());
    }

    @Test
    void shouldShareDataIngestionService() {
        TestContext context = context();
        when(context.dataIngestionServiceMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertDataIngestionShareOptionSelect(invocation.getArgument(0));
            return Collections.singletonList(dataIngestionService(400L, "订单写入服务", "order_write_service"));
        });

        ResourceShareEntity saved = context.service.saveResourceShare(share(StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE, 400L));

        assertEquals(StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE, saved.getResourceType());
        assertEquals(400L, saved.getResourceId());
        assertEquals(10L, saved.getSourceProjectId());
        assertEquals(20L, saved.getTargetProjectId());
        verify(context.dataIngestionServiceMapper, never()).selectById(any());
    }

    @Test
    void shouldShareProtocolConversionService() {
        TestContext context = context();
        when(context.protocolConversionServiceMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertProtocolConversionShareOptionSelect(invocation.getArgument(0));
            return Collections.singletonList(protocolConversionService(410L, "订单状态协议转换服务", "order_status_bridge"));
        });

        ResourceShareEntity saved = context.service.saveResourceShare(share(StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE, 410L));

        assertEquals(StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE, saved.getResourceType());
        assertEquals(410L, saved.getResourceId());
        assertEquals(10L, saved.getSourceProjectId());
        assertEquals(20L, saved.getTargetProjectId());
        verify(context.protocolConversionServiceMapper, never()).selectById(any());
    }

    @Test
    void notificationLabelShouldUseLightweightResourceQuery() {
        TestContext context = context(Collections.singletonList(88L));
        when(context.dataServiceDefinitionMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertDataServiceShareOptionSelect(invocation.getArgument(0));
            return Collections.singletonList(dataService(300L, "客户画像共享查询服务", "customer_profile_query"));
        });

        ResourceShareEntity saved = context.service.saveResourceShare(share(StudioConstants.RESOURCE_TYPE_DATA_SERVICE, 300L));

        assertEquals(StudioConstants.RESOURCE_TYPE_DATA_SERVICE, saved.getResourceType());
        verify(context.dataServiceDefinitionMapper, times(2)).selectList(any(LambdaQueryWrapper.class));
        verify(context.dataServiceDefinitionMapper, never()).selectById(any());
    }

    private TestContext context() {
        return context(Collections.emptyList());
    }

    private TestContext context(java.util.List<Long> notificationRecipients) {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ResourceShareMapper resourceShareMapper = mock(ResourceShareMapper.class);
        DataServiceDefinitionMapper dataServiceDefinitionMapper = mock(DataServiceDefinitionMapper.class);
        DataIngestionServiceMapper dataIngestionServiceMapper = mock(DataIngestionServiceMapper.class);
        ProtocolConversionServiceMapper protocolConversionServiceMapper = mock(ProtocolConversionServiceMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        NotificationService notificationService = mock(NotificationService.class);

        ProjectEntity sourceProject = project(10L);
        ProjectEntity targetProject = project(20L);
        when(projectMapper.selectById(10L)).thenReturn(sourceProject);
        when(projectMapper.selectById(20L)).thenReturn(targetProject);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(900L);
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));
        when(notificationService.activeProjectMemberUserIds("default", 20L)).thenReturn(notificationRecipients);
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
                protocolConversionServiceMapper,
                securityService,
                notificationService
        );
        return new TestContext(service, dataServiceDefinitionMapper, dataIngestionServiceMapper, protocolConversionServiceMapper);
    }

    private static void assertDataServiceShareOptionSelect(LambdaQueryWrapper<DataServiceDefinitionEntity> query) {
        String sqlSelect = String.valueOf(query.getSqlSelect()).toLowerCase(Locale.ROOT);
        assertThat(sqlSelect).contains("id", "tenant_id", "project_id", "deleted", "service_name", "service_code", "status");
        assertThat(sqlSelect).doesNotContain("custom_sql", "source_type", "datasource_id", "webservice_config_json",
                "endpoint_path", "service_key", "default_subscription_name");
    }

    private static void assertDataIngestionShareOptionSelect(LambdaQueryWrapper<DataIngestionServiceEntity> query) {
        String sqlSelect = String.valueOf(query.getSqlSelect()).toLowerCase(Locale.ROOT);
        assertThat(sqlSelect).contains("id", "tenant_id", "project_id", "deleted", "service_name", "service_code", "status");
        assertThat(sqlSelect).doesNotContain("writer_options_json", "field_mappings_json", "source_positions_json",
                "source_bindings_json", "webservice_config_json", "endpoint_path", "service_key", "default_subscription_name");
    }

    private static void assertProtocolConversionShareOptionSelect(LambdaQueryWrapper<ProtocolConversionServiceEntity> query) {
        String sqlSelect = String.valueOf(query.getSqlSelect()).toLowerCase(Locale.ROOT);
        assertThat(sqlSelect).contains("id", "tenant_id", "project_id", "deleted", "service_name", "service_code", "status");
        assertThat(sqlSelect).doesNotContain("webservice_config_json", "field_mappings_json", "raw_transformers_json",
                "fixed_fields_json", "body_bridge_options_json", "request_passthrough_json", "target_headers_json",
                "target_query_json", "target_body_template", "response_status_json");
    }

    private DataServiceDefinitionEntity dataService(Long id, String serviceName, String serviceCode) {
        DataServiceDefinitionEntity service = new DataServiceDefinitionEntity();
        service.setId(id);
        service.setTenantId("default");
        service.setProjectId(10L);
        service.setServiceName(serviceName);
        service.setServiceCode(serviceCode);
        service.setStatus("ONLINE");
        return service;
    }

    private DataIngestionServiceEntity dataIngestionService(Long id, String serviceName, String serviceCode) {
        DataIngestionServiceEntity service = new DataIngestionServiceEntity();
        service.setId(id);
        service.setTenantId("default");
        service.setProjectId(10L);
        service.setServiceName(serviceName);
        service.setServiceCode(serviceCode);
        service.setStatus("ONLINE");
        return service;
    }

    private ProtocolConversionServiceEntity protocolConversionService(Long id, String serviceName, String serviceCode) {
        ProtocolConversionServiceEntity service = new ProtocolConversionServiceEntity();
        service.setId(id);
        service.setTenantId("default");
        service.setProjectId(10L);
        service.setServiceName(serviceName);
        service.setServiceCode(serviceCode);
        service.setStatus("ONLINE");
        return service;
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
        project.setProjectName("长期回归-资源接收项目");
        return project;
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private static class TestContext {
        private final SystemManagementService service;
        private final DataServiceDefinitionMapper dataServiceDefinitionMapper;
        private final DataIngestionServiceMapper dataIngestionServiceMapper;
        private final ProtocolConversionServiceMapper protocolConversionServiceMapper;

        private TestContext(SystemManagementService service,
                            DataServiceDefinitionMapper dataServiceDefinitionMapper,
                            DataIngestionServiceMapper dataIngestionServiceMapper,
                            ProtocolConversionServiceMapper protocolConversionServiceMapper) {
            this.service = service;
            this.dataServiceDefinitionMapper = dataServiceDefinitionMapper;
            this.dataIngestionServiceMapper = dataIngestionServiceMapper;
            this.protocolConversionServiceMapper = protocolConversionServiceMapper;
        }
    }
}
