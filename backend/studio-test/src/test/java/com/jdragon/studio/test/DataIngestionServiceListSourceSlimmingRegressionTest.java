package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.DataIngestionStatus;
import com.jdragon.studio.dto.enums.DataIngestionTargetType;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionServiceListView;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.DataIngestionServiceSaveRequest;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.mapper.DataIngestionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataIngestionSubscriptionMapper;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.DataIngestionService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.OpenServiceInvocationLogService;
import com.jdragon.studio.infra.service.PluginRuntimeOptionSchemaService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataIngestionServiceListSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(DataIngestionServiceEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DataIngestionServiceEntity.class);
        }
    }

    @Test
    void dataIngestionServiceListShouldSelectSourcePositionSummaryInsteadOfFullFieldMappings() {
        DataIngestionServiceMapper serviceMapper = mock(DataIngestionServiceMapper.class);
        DataIngestionService service = service(serviceMapper, mock(DataSourceService.class), mock(DataModelService.class),
                mock(PluginRuntimeOptionSchemaService.class));
        when(serviceMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<DataIngestionServiceEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(Collections.singletonList(listEntity()));
            return page;
        });

        PageView<DataIngestionServiceListView> page = service.list(1, 20, "客户", null, null);

        assertThat(page.getItems()).hasSize(1);
        DataIngestionServiceListView item = page.getItems().get(0);
        assertThat(item.getSourcePositions()).containsExactly("QUERY", "HEADER");
        assertThat(item.getDatasourceName()).isEqualTo("长期回归-客户经营画像数据源");
        assertThat(item.getModelName()).isEqualTo("客户画像模型");
        assertThat(item.getRequestFormat()).isNull();
        assertThat(item.getPayloadMode()).isNull();
        assertThat(item.getDatasourceId()).isNull();
        assertThat(item.getModelId()).isNull();
        assertThat(item.getTokenRequired()).isNull();

        ArgumentCaptor<LambdaQueryWrapper<DataIngestionServiceEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(serviceMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "project_id", "created_at", "updated_at",
                        "service_code", "service_name", "status", "target_type",
                        "datasource_name_snapshot", "model_name_snapshot",
                        "model_physical_locator", "endpoint_path", "source_positions_json",
                        "source_count", "target_count")
                .doesNotContain("tenant_id", "deleted", "created_by",
                        "request_format", "payload_mode", "data_node_path",
                        "datasource_id", "datasource_type_code", "model_id",
                        "max_batch_size", "token_required", "default_subscription_name",
                        "webservice_enabled", "field_mappings_json", "writer_options_json",
                        "source_bindings_json", "webservice_config_json");
    }

    @Test
    void dataIngestionServiceSaveShouldPersistSourcePositionSummary() {
        DataIngestionServiceMapper serviceMapper = mock(DataIngestionServiceMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        PluginRuntimeOptionSchemaService capabilityService = mock(PluginRuntimeOptionSchemaService.class);
        DataIngestionService service = service(serviceMapper, dataSourceService, dataModelService, capabilityService);
        AtomicReference<DataIngestionServiceEntity> savedRef = new AtomicReference<DataIngestionServiceEntity>();
        when(serviceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(dataSourceService.getInternal(10L)).thenReturn(datasource());
        when(dataModelService.get(20L)).thenReturn(model());
        when(capabilityService.sourceCategory("mysql8")).thenReturn("DATABASE");
        when(serviceMapper.insert(any(DataIngestionServiceEntity.class))).thenAnswer(invocation -> {
            DataIngestionServiceEntity entity = invocation.getArgument(0);
            entity.setId(30L);
            savedRef.set(entity);
            return 1;
        });
        when(serviceMapper.selectById(30L)).thenAnswer(invocation -> savedRef.get());

        DataIngestionServiceView saved = service.save(saveRequest());

        assertThat(saved.getSourcePositions()).containsExactly("BODY", "QUERY", "HEADER", "FORM");
        assertThat(saved.getSourceCount()).isEqualTo(1);
        assertThat(saved.getTargetCount()).isEqualTo(1);
        assertThat(savedRef.get().getSourcePositionsJson()).containsExactly("BODY", "QUERY", "HEADER", "FORM");
        assertThat(savedRef.get().getFieldMappingsJson()).hasSize(3);
        assertThat(savedRef.get().getSourceBindingsJson()).hasSize(1);
        assertThat(savedRef.get().getSourceCount()).isEqualTo(1);
        assertThat(savedRef.get().getTargetCount()).isEqualTo(1);
    }

    private DataIngestionService service(DataIngestionServiceMapper serviceMapper,
                                         DataSourceService dataSourceService,
                                         DataModelService dataModelService,
                                         PluginRuntimeOptionSchemaService capabilityService) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(900L);
        when(accessService.currentProjectId()).thenReturn(100L);
        when(accessService.requireCurrentProjectId()).thenReturn(100L);
        when(accessService.sharedResourceIdList(any())).thenReturn(Collections.emptyList());
        return new DataIngestionService(
                serviceMapper,
                mock(DataIngestionSubscriptionMapper.class),
                mock(DataIngestionAccessLogMapper.class),
                mock(DataIngestionAccessCounterMapper.class),
                dataSourceService,
                dataModelService,
                mock(DataDevelopmentSqlExecutor.class),
                securityService,
                accessService,
                capabilityService,
                mock(CollectionTaskAssemblerService.class),
                new ObjectMapper(),
                mock(OpenServiceInvocationLogService.class));
    }

    private DataIngestionServiceEntity listEntity() {
        DataIngestionServiceEntity entity = new DataIngestionServiceEntity();
        entity.setId(30L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 29, 10, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 10, 1, 0));
        entity.setServiceCode("customer_profile_ingest");
        entity.setServiceName("客户画像接入服务");
        entity.setStatus(DataIngestionStatus.ONLINE.name());
        entity.setTargetType(DataIngestionTargetType.DATABASE.name());
        entity.setCreatedBy(900L);
        entity.setRequestFormat("XML");
        entity.setPayloadMode("ARRAY");
        entity.setDataNodePath("payload.rows");
        entity.setDatasourceId(10L);
        entity.setDatasourceNameSnapshot("长期回归-客户经营画像数据源");
        entity.setDatasourceTypeCode("mysql8");
        entity.setModelId(20L);
        entity.setModelNameSnapshot("客户画像模型");
        entity.setModelPhysicalLocator("lt_customer_profile");
        entity.setEndpointPath("/openapi/data-ingestion-services/customer_profile_ingest/key");
        entity.setMaxBatchSize(500);
        entity.setTokenRequired(0);
        entity.setDefaultSubscriptionName("客户经营系统默认订阅");
        entity.setWebserviceEnabled(1);
        entity.setSourcePositionsJson(Arrays.asList("QUERY", "HEADER"));
        return entity;
    }

    private DataIngestionServiceSaveRequest saveRequest() {
        DataIngestionServiceSaveRequest request = new DataIngestionServiceSaveRequest();
        request.setServiceCode("customer_profile_ingest");
        request.setServiceName("客户画像接入服务");
        request.setTargetType(DataIngestionTargetType.DATABASE);
        request.setDatasourceId(10L);
        request.setModelId(20L);
        request.setFieldMappings(Arrays.asList(
                mapping(DataIngestionSourcePosition.QUERY, "customer_id", "customer_id"),
                mapping(DataIngestionSourcePosition.HEADER, "trace_id", "trace_id"),
                mapping(DataIngestionSourcePosition.FORM, "phone", "phone")
        ));
        return request;
    }

    private DataIngestionFieldMapping mapping(DataIngestionSourcePosition sourcePosition, String sourceField, String targetField) {
        DataIngestionFieldMapping mapping = new DataIngestionFieldMapping();
        mapping.setSourcePosition(sourcePosition);
        mapping.setSourceField(sourceField);
        mapping.setTargetField(targetField);
        return mapping;
    }

    private DataSourceDefinition datasource() {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(10L);
        datasource.setName("长期回归-客户经营画像数据源");
        datasource.setTypeCode("mysql8");
        return datasource;
    }

    private DataModelDefinition model() {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(20L);
        model.setDatasourceId(10L);
        model.setName("客户画像模型");
        model.setPhysicalLocator("lt_customer_profile");
        return model;
    }
}
