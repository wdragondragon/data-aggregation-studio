package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.ProtocolConversionMode;
import com.jdragon.studio.dto.enums.ProtocolConversionProtocol;
import com.jdragon.studio.dto.enums.ProtocolConversionStatus;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.ProtocolConversionServiceListView;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessLogMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionSubscriptionMapper;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;
import com.jdragon.studio.infra.service.OpenServiceInvocationLogService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.ProtocolConversionService;
import com.jdragon.studio.infra.service.RuntimeClusterSelectionService;
import com.jdragon.studio.infra.service.RuntimeClusterService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolConversionServiceListSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(ProtocolConversionServiceEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProtocolConversionServiceEntity.class);
        }
    }

    @Test
    void protocolConversionPageShouldSelectOnlyTableFields() {
        ProtocolConversionServiceMapper serviceMapper = mock(ProtocolConversionServiceMapper.class);
        ProtocolConversionService service = service(serviceMapper);
        when(serviceMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<ProtocolConversionServiceEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(Collections.singletonList(protocolConversionEntity()));
            return page;
        });

        PageView<ProtocolConversionServiceListView> page = service.list(1, 20, "客户", null);

        assertThat(page.getItems()).hasSize(1);
        ProtocolConversionServiceListView item = page.getItems().get(0);
        assertThat(item.getServiceName()).isEqualTo("长期回归-客户协议转换服务");
        assertThat(item.getTargetDatasourceName()).isEqualTo("长期回归-客户经营画像接口");
        assertThat(item.getSourceProtocol()).isEqualTo(ProtocolConversionProtocol.SOAP_11);
        assertThat(item.getTargetProtocol()).isEqualTo(ProtocolConversionProtocol.HTTP_JSON);
        assertThat(item.getRuntimeClusterId()).isEqualTo(46L);
        assertThat(item.getTokenRequired()).isNull();
        assertThat(item.getSourceMethod()).isNull();
        assertThat(item.getTargetDatasourceId()).isNull();

        ArgumentCaptor<LambdaQueryWrapper<ProtocolConversionServiceEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(serviceMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "project_id", "created_at", "updated_at", "runtime_cluster_id",
                        "service_code", "service_name", "status", "endpoint_path", "webservice_endpoint_path",
                        "source_protocol", "conversion_mode", "target_datasource_name_snapshot",
                        "target_path", "target_protocol")
                .doesNotContain("tenant_id", "deleted", "created_by",
                        "token_required", "default_subscription_name",
                        "source_method", "source_data_node_path",
                        "target_datasource_id", "target_method", "payload_mode",
                        "service_key", "webservice_config_json",
                        "field_mappings_json", "raw_transformers_json", "fixed_fields_json",
                        "body_bridge_options_json", "request_passthrough_json",
                        "target_headers_json", "target_query_json", "target_webservice_config_json",
                        "target_body_template", "target_data_node_path", "response_status_json");
    }

    @Test
    void protocolConversionListPublishShouldReturnSlimListViewInsteadOfFullDefinitionView() {
        ProtocolConversionServiceMapper serviceMapper = mock(ProtocolConversionServiceMapper.class);
        ProtocolConversionService service = service(serviceMapper);
        ProtocolConversionServiceEntity beforePublish = protocolConversionEntity();
        beforePublish.setStatus(ProtocolConversionStatus.DRAFT.name());
        beforePublish.setConversionMode(ProtocolConversionMode.BODY_BRIDGE.name());
        beforePublish.setServiceKey(null);
        beforePublish.setEndpointPath(null);
        beforePublish.setWebserviceEndpointPath(null);
        ProtocolConversionServiceEntity afterPublish = protocolConversionEntity();
        afterPublish.setStatus(ProtocolConversionStatus.ONLINE.name());
        when(serviceMapper.selectById(50L)).thenReturn(beforePublish);
        when(serviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(afterPublish);

        ProtocolConversionServiceListView published = service.publishSummary(50L);

        assertThat(published.getStatus()).isEqualTo(ProtocolConversionStatus.ONLINE);
        assertThat(published.getTokenRequired()).isNull();
        assertThat(published.getSourceMethod()).isNull();
        assertThat(published.getTargetDatasourceId()).isNull();
        verify(serviceMapper).selectById(50L);
        verify(serviceMapper, never()).updateById(any(ProtocolConversionServiceEntity.class));

        ArgumentCaptor<LambdaUpdateWrapper<ProtocolConversionServiceEntity>> updateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(serviceMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet())
                .contains("status", "updated_at", "service_key", "endpoint_path", "webservice_endpoint_path")
                .doesNotContain("field_mappings_json", "raw_transformers_json", "fixed_fields_json",
                        "target_headers_json", "target_query_json", "target_body_template", "response_status_json");

        ArgumentCaptor<LambdaQueryWrapper<ProtocolConversionServiceEntity>> listQueryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(serviceMapper).selectOne(listQueryCaptor.capture());
        assertTableListSelectIsSlim(listQueryCaptor.getValue().getSqlSelect());
    }

    @Test
    void protocolConversionListOfflineShouldUseReferenceCheckAndReturnSlimListView() {
        ProtocolConversionServiceMapper serviceMapper = mock(ProtocolConversionServiceMapper.class);
        ProtocolConversionService service = service(serviceMapper);
        ProtocolConversionServiceEntity reference = serviceReference();
        ProtocolConversionServiceEntity afterOffline = protocolConversionEntity();
        afterOffline.setStatus(ProtocolConversionStatus.OFFLINE.name());
        when(serviceMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(reference)
                .thenReturn(afterOffline);

        ProtocolConversionServiceListView offline = service.offlineSummary(50L);

        assertThat(offline.getStatus()).isEqualTo(ProtocolConversionStatus.OFFLINE);
        assertThat(offline.getTokenRequired()).isNull();
        assertThat(offline.getSourceMethod()).isNull();
        assertThat(offline.getTargetDatasourceId()).isNull();
        verify(serviceMapper, never()).selectById(any());
        verify(serviceMapper, never()).updateById(any(ProtocolConversionServiceEntity.class));

        ArgumentCaptor<LambdaUpdateWrapper<ProtocolConversionServiceEntity>> updateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(serviceMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet())
                .contains("status", "updated_at")
                .doesNotContain("field_mappings_json", "raw_transformers_json", "fixed_fields_json",
                        "target_headers_json", "target_query_json", "target_body_template", "response_status_json");

        ArgumentCaptor<LambdaQueryWrapper<ProtocolConversionServiceEntity>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(serviceMapper, org.mockito.Mockito.times(2)).selectOne(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues().get(0).getSqlSelect())
                .contains("id", "tenant_id", "project_id")
                .doesNotContain("field_mappings_json", "raw_transformers_json", "fixed_fields_json",
                        "target_headers_json", "target_query_json", "target_body_template", "response_status_json");
        assertTableListSelectIsSlim(queryCaptor.getAllValues().get(1).getSqlSelect());
    }

    private void assertTableListSelectIsSlim(String sqlSelect) {
        assertThat(sqlSelect)
                .contains("id", "project_id", "created_at", "updated_at", "runtime_cluster_id",
                        "service_code", "service_name", "status", "endpoint_path", "webservice_endpoint_path",
                        "source_protocol", "conversion_mode", "target_datasource_name_snapshot",
                        "target_path", "target_protocol")
                .doesNotContain("tenant_id", "deleted", "created_by",
                        "token_required", "default_subscription_name",
                        "source_method", "source_data_node_path",
                        "target_datasource_id", "target_method", "payload_mode",
                        "service_key", "webservice_config_json",
                        "field_mappings_json", "raw_transformers_json", "fixed_fields_json",
                        "body_bridge_options_json", "request_passthrough_json",
                        "target_headers_json", "target_query_json", "target_webservice_config_json",
                        "target_body_template", "target_data_node_path", "response_status_json");
    }

    private ProtocolConversionService service(ProtocolConversionServiceMapper serviceMapper) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(accessService.sharedResourceIdList(any())).thenReturn(Collections.emptyList());
        ProtocolConversionService service = new ProtocolConversionService(
                serviceMapper,
                mock(ProtocolConversionSubscriptionMapper.class),
                mock(ProtocolConversionAccessLogMapper.class),
                mock(ProtocolConversionAccessCounterMapper.class),
                mock(DataSourceService.class),
                securityService,
                accessService,
                new ObjectMapper(),
                mock(OpenServiceInvocationLogService.class));
        injectLegacyRuntimeSelection(service);
        return service;
    }

    private void injectLegacyRuntimeSelection(ProtocolConversionService service) {
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        RuntimeClusterEntity runtimeCluster = defaultLocalRuntimeCluster();
        when(runtimeClusterService.requireAuthorized(100L, runtimeCluster.getId())).thenReturn(runtimeCluster);
        when(runtimeClusterService.clusterName(runtimeCluster.getId())).thenReturn(runtimeCluster.getName());
        ReflectionTestUtils.setField(service, "runtimeClusterSelectionService",
                new RuntimeClusterSelectionService(runtimeClusterService, bindingService));
    }

    private RuntimeClusterEntity defaultLocalRuntimeCluster() {
        RuntimeClusterEntity entity = new RuntimeClusterEntity();
        entity.setId(46L);
        entity.setTenantId("default");
        entity.setCode("DEFAULT-LOCAL");
        entity.setName("Default Local Runtime Cluster");
        entity.setEnabled(1);
        return entity;
    }

    private ProtocolConversionServiceEntity protocolConversionEntity() {
        ProtocolConversionServiceEntity entity = new ProtocolConversionServiceEntity();
        entity.setId(50L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 29, 10, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 10, 5, 0));
        entity.setRuntimeClusterId(46L);
        entity.setCreatedBy(900L);
        entity.setServiceCode("customer_protocol_bridge");
        entity.setServiceName("长期回归-客户协议转换服务");
        entity.setStatus(ProtocolConversionStatus.ONLINE.name());
        entity.setEndpointPath("/openapi/protocol-conversions/customer_protocol_bridge/key");
        entity.setWebserviceEndpointPath("/openapi/protocol-conversions/ws/customer_protocol_bridge/key");
        entity.setTokenRequired(0);
        entity.setDefaultSubscriptionName("客户经营系统默认订阅");
        entity.setSourceProtocol(ProtocolConversionProtocol.SOAP_11.name());
        entity.setSourceMethod("POST");
        entity.setSourceDataNodePath("Envelope.Body.Customer");
        entity.setConversionMode(ProtocolConversionMode.FIELD_MAPPING.name());
        entity.setTargetDatasourceId(20L);
        entity.setTargetDatasourceNameSnapshot("长期回归-客户经营画像接口");
        entity.setTargetPath("/customer/profile");
        entity.setTargetProtocol(ProtocolConversionProtocol.HTTP_JSON.name());
        entity.setTargetMethod("POST");
        entity.setPayloadMode("OBJECT");
        entity.setFieldMappingsJson(Collections.emptyList());
        return entity;
    }

    private ProtocolConversionServiceEntity serviceReference() {
        ProtocolConversionServiceEntity entity = new ProtocolConversionServiceEntity();
        entity.setId(50L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        return entity;
    }
}
