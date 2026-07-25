package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.DataServiceParamPosition;
import com.jdragon.studio.dto.enums.DataServiceQueryOperator;
import com.jdragon.studio.dto.enums.DataServiceSourceType;
import com.jdragon.studio.dto.enums.DataServiceStatus;
import com.jdragon.studio.dto.enums.DataServiceType;
import com.jdragon.studio.dto.enums.DataServiceValueType;
import com.jdragon.studio.dto.model.DataServiceListView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.DataServicePublishParamEntity;
import com.jdragon.studio.infra.entity.DataServiceRequestParamEntity;
import com.jdragon.studio.infra.entity.DataServiceResponseParamEntity;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataServicePublishParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceRequestParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceResponseParamMapper;
import com.jdragon.studio.infra.mapper.DataServiceSubscriptionMapper;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataServiceResponseCacheService;
import com.jdragon.studio.infra.service.DataServiceService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.OpenServiceInvocationLogService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.RuntimeClusterSelectionService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.StudioTransformerSupport;
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

class DataServiceListSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(DataServiceDefinitionEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DataServiceDefinitionEntity.class);
        }
    }

    @Test
    void dataServicePageShouldSelectOnlyTableFields() {
        DataServiceDefinitionMapper definitionMapper = mock(DataServiceDefinitionMapper.class);
        DataServiceService service = service(definitionMapper);
        when(definitionMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<DataServiceDefinitionEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(Collections.singletonList(dataServiceEntity()));
            return page;
        });

        PageView<DataServiceListView> page = service.list(1, 20, "客户", null, null);

        assertThat(page.getItems()).hasSize(1);
        DataServiceListView item = page.getItems().get(0);
        assertThat(item.getServiceName()).isEqualTo("长期回归-客户画像查询服务");
        assertThat(item.getDatasourceName()).isEqualTo("长期回归-客户经营画像数据源");
        assertThat(item.getModelName()).isEqualTo("客户画像模型");
        assertThat(item.getRuntimeClusterId()).isEqualTo(40L);
        assertThat(item.getSourceType()).isEqualTo(DataServiceSourceType.TABLE);
        assertThat(item.getDatasourceId()).isNull();
        assertThat(item.getModelId()).isNull();
        assertThat(item.getRequestMethod()).isNull();
        assertThat(item.getTokenRequired()).isNull();

        ArgumentCaptor<LambdaQueryWrapper<DataServiceDefinitionEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitionMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "project_id", "runtime_cluster_id", "created_at", "updated_at",
                        "service_code", "service_name", "status", "source_type",
                        "datasource_name_snapshot", "datasource_type_code",
                        "model_name_snapshot", "model_physical_locator", "endpoint_path")
                .doesNotContain("tenant_id", "deleted", "created_by", "service_type",
                        "datasource_id", "model_id", "request_method", "response_type",
                        "cache_enabled", "token_required", "default_subscription_name",
                        "webservice_enabled", "custom_sql", "request_params_json",
                        "response_params_json", "webservice_config_json");
    }

    @Test
    void dataServiceListPublishShouldReturnSlimListViewInsteadOfFullDefinitionView() {
        DataServiceDefinitionMapper definitionMapper = mock(DataServiceDefinitionMapper.class);
        DataServiceRequestParamMapper requestParamMapper = mock(DataServiceRequestParamMapper.class);
        DataServiceResponseParamMapper responseParamMapper = mock(DataServiceResponseParamMapper.class);
        DataServicePublishParamMapper publishParamMapper = mock(DataServicePublishParamMapper.class);
        DataServiceService service = service(definitionMapper, requestParamMapper, responseParamMapper, publishParamMapper);
        DataServiceDefinitionEntity beforePublish = dataServiceEntity();
        beforePublish.setStatus(DataServiceStatus.DRAFT.name());
        beforePublish.setServiceKey(null);
        beforePublish.setEndpointPath(null);
        DataServiceDefinitionEntity afterPublish = dataServiceEntity();
        afterPublish.setStatus(DataServiceStatus.ONLINE.name());
        when(definitionMapper.selectById(10L)).thenReturn(beforePublish);
        when(definitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(afterPublish);
        when(requestParamMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(requestParamEntity()));
        when(responseParamMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(responseParamEntity()));
        when(publishParamMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(publishParamEntity()));

        DataServiceListView published = service.publishSummary(10L);

        assertThat(published.getStatus()).isEqualTo(DataServiceStatus.ONLINE);
        assertThat(published.getDatasourceId()).isNull();
        assertThat(published.getModelId()).isNull();
        assertThat(published.getTokenRequired()).isNull();
        verify(definitionMapper).selectById(10L);
        verify(definitionMapper, never()).updateById(any(DataServiceDefinitionEntity.class));

        ArgumentCaptor<LambdaUpdateWrapper<DataServiceDefinitionEntity>> updateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(definitionMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet())
                .contains("status", "updated_at", "service_key", "endpoint_path")
                .doesNotContain("webservice_config_json", "custom_sql", "cache_enabled", "token_required");

        ArgumentCaptor<LambdaQueryWrapper<DataServiceDefinitionEntity>> listQueryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitionMapper).selectOne(listQueryCaptor.capture());
        assertTableListSelectIsSlim(listQueryCaptor.getValue().getSqlSelect());
    }

    @Test
    void dataServiceListOfflineShouldUseReferenceCheckAndReturnSlimListView() {
        DataServiceDefinitionMapper definitionMapper = mock(DataServiceDefinitionMapper.class);
        DataServiceService service = service(definitionMapper);
        DataServiceDefinitionEntity reference = serviceReference();
        DataServiceDefinitionEntity afterOffline = dataServiceEntity();
        afterOffline.setStatus(DataServiceStatus.OFFLINE.name());
        when(definitionMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(reference)
                .thenReturn(afterOffline);

        DataServiceListView offline = service.offlineSummary(10L);

        assertThat(offline.getStatus()).isEqualTo(DataServiceStatus.OFFLINE);
        assertThat(offline.getRequestMethod()).isNull();
        assertThat(offline.getTokenRequired()).isNull();
        verify(definitionMapper, never()).selectById(any());
        verify(definitionMapper, never()).updateById(any(DataServiceDefinitionEntity.class));

        ArgumentCaptor<LambdaUpdateWrapper<DataServiceDefinitionEntity>> updateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(definitionMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet())
                .contains("status", "updated_at")
                .doesNotContain("webservice_config_json", "custom_sql", "cache_enabled", "token_required");

        ArgumentCaptor<LambdaQueryWrapper<DataServiceDefinitionEntity>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitionMapper, org.mockito.Mockito.times(2)).selectOne(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues().get(0).getSqlSelect())
                .contains("id", "tenant_id", "project_id")
                .doesNotContain("webservice_config_json", "custom_sql", "cache_enabled", "token_required");
        assertTableListSelectIsSlim(queryCaptor.getAllValues().get(1).getSqlSelect());
    }

    private void assertTableListSelectIsSlim(String sqlSelect) {
        assertThat(sqlSelect)
                .contains("id", "project_id", "runtime_cluster_id", "created_at", "updated_at",
                        "service_code", "service_name", "status", "source_type",
                        "datasource_name_snapshot", "datasource_type_code",
                        "model_name_snapshot", "model_physical_locator", "endpoint_path")
                .doesNotContain("tenant_id", "deleted", "created_by", "service_type",
                        "datasource_id", "model_id", "request_method", "response_type",
                        "cache_enabled", "token_required", "default_subscription_name",
                        "webservice_enabled", "custom_sql", "request_params_json",
                        "response_params_json", "webservice_config_json");
    }

    private DataServiceService service(DataServiceDefinitionMapper definitionMapper) {
        return service(definitionMapper,
                mock(DataServiceRequestParamMapper.class),
                mock(DataServiceResponseParamMapper.class),
                mock(DataServicePublishParamMapper.class));
    }

    private DataServiceService service(DataServiceDefinitionMapper definitionMapper,
                                       DataServiceRequestParamMapper requestParamMapper,
                                       DataServiceResponseParamMapper responseParamMapper,
                                       DataServicePublishParamMapper publishParamMapper) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(accessService.sharedResourceIdList(any())).thenReturn(Collections.emptyList());
        DataServiceService service = new DataServiceService(
                definitionMapper,
                requestParamMapper,
                responseParamMapper,
                publishParamMapper,
                mock(DataServiceSubscriptionMapper.class),
                mock(DataServiceAccessLogMapper.class),
                mock(DataServiceAccessCounterMapper.class),
                mock(DataSourceService.class),
                mock(DataModelService.class),
                mock(DatasourceTypeCapabilityService.class),
                securityService,
                accessService,
                mock(DataServiceResponseCacheService.class),
                new StudioTransformerSupport(new ObjectMapper()),
                mock(OpenServiceInvocationLogService.class));
        injectRuntimeSelection(service);
        return service;
    }

    private void injectRuntimeSelection(DataServiceService service) {
        ReflectionTestUtils.setField(service, "runtimeClusterSelectionService",
                mock(RuntimeClusterSelectionService.class));
    }

    private DataServiceDefinitionEntity dataServiceEntity() {
        DataServiceDefinitionEntity entity = new DataServiceDefinitionEntity();
        entity.setId(10L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setRuntimeClusterId(40L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 29, 11, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 11, 5, 0));
        entity.setCreatedBy(900L);
        entity.setServiceCode("customer_profile_query");
        entity.setServiceName("长期回归-客户画像查询服务");
        entity.setServiceType(DataServiceType.MODEL_PUBLISH.name());
        entity.setStatus(DataServiceStatus.ONLINE.name());
        entity.setSourceType(DataServiceSourceType.TABLE.name());
        entity.setDatasourceId(20L);
        entity.setDatasourceNameSnapshot("长期回归-客户经营画像数据源");
        entity.setDatasourceTypeCode("mysql8");
        entity.setModelId(30L);
        entity.setModelNameSnapshot("客户画像模型");
        entity.setModelPhysicalLocator("lt_customer_profile");
        entity.setRequestMethod("POST");
        entity.setResponseType("XML");
        entity.setEndpointPath("/openapi/data-services/customer_profile_query/key");
        entity.setCacheEnabled(1);
        entity.setTokenRequired(0);
        entity.setDefaultSubscriptionName("客户经营系统默认订阅");
        entity.setWebserviceEnabled(1);
        entity.setCustomSql("select * from lt_customer_profile");
        return entity;
    }

    private DataServiceDefinitionEntity serviceReference() {
        DataServiceDefinitionEntity entity = new DataServiceDefinitionEntity();
        entity.setId(10L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        return entity;
    }

    private DataServiceRequestParamEntity requestParamEntity() {
        DataServiceRequestParamEntity entity = new DataServiceRequestParamEntity();
        entity.setId(1L);
        entity.setServiceId(10L);
        entity.setSortOrder(1);
        entity.setParamName("customerId");
        entity.setFieldName("customerId");
        entity.setValueType(DataServiceValueType.STRING.name());
        entity.setQueryOperator(DataServiceQueryOperator.EQ.name());
        entity.setRequired(1);
        entity.setFixedParam(0);
        return entity;
    }

    private DataServiceResponseParamEntity responseParamEntity() {
        DataServiceResponseParamEntity entity = new DataServiceResponseParamEntity();
        entity.setId(2L);
        entity.setServiceId(10L);
        entity.setSortOrder(1);
        entity.setEnabled(1);
        entity.setParamName("customerName");
        entity.setFieldName("customerName");
        return entity;
    }

    private DataServicePublishParamEntity publishParamEntity() {
        DataServicePublishParamEntity entity = new DataServicePublishParamEntity();
        entity.setId(3L);
        entity.setServiceId(10L);
        entity.setSortOrder(1);
        entity.setFrontendParamName("customerId");
        entity.setBackendParamName("customerId");
        entity.setPosition(DataServiceParamPosition.QUERY.name());
        entity.setValueType(DataServiceValueType.STRING.name());
        entity.setRequired(1);
        return entity;
    }
}
