package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionTaskListView;
import com.jdragon.studio.dto.model.CollectionTaskOptionView;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskWorkflowOptionView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.CollectionTaskMetricBindingEntity;
import com.jdragon.studio.infra.entity.CollectionTaskScheduleEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskMetricBindingMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskScheduleMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DataModelLineageService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionTaskListSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(CollectionTaskDefinitionEntity.class);
        initTableInfo(CollectionTaskMetricBindingEntity.class);
        initTableInfo(CollectionTaskScheduleEntity.class);
    }

    @Test
    void collectionTaskPageShouldSelectSnapshotColumnsAndBatchSchedules() {
        CollectionTaskDefinitionMapper definitionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskScheduleMapper scheduleMapper = mock(CollectionTaskScheduleMapper.class);
        CollectionTaskService service = collectionTaskService(definitionMapper, scheduleMapper);
        when(definitionMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<CollectionTaskDefinitionEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(Collections.singletonList(taskEntity()));
            return page;
        });
        when(scheduleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(scheduleEntity()));

        PageView<CollectionTaskListView> page = service.listSummaryPage(1, 20, "客户", "经营画像", "订单汇总");

        assertThat(page.getItems()).hasSize(1);
        CollectionTaskListView item = page.getItems().get(0);
        assertThat(item.getName()).isEqualTo("长期回归-客户订单增量采集任务");
        assertThat(item.getTargetDatasourceName()).isEqualTo("长期回归-客户经营画像数据源");
        assertThat(item.getTargetModelPhysicalLocator()).isEqualTo("lt_reg_customer_order_summary");
        assertThat(item.getSchedule()).isNotNull();
        assertThat(item.getSchedule().getCronExpression()).isEqualTo("0 0/30 * * * ?");

        ArgumentCaptor<LambdaQueryWrapper<CollectionTaskDefinitionEntity>> taskCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitionMapper).selectPage(any(Page.class), taskCaptor.capture());
        assertTaskListSelectIsSlim(taskCaptor.getValue().getSqlSelect());

        ArgumentCaptor<LambdaQueryWrapper<CollectionTaskScheduleEntity>> scheduleCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(scheduleMapper).selectList(scheduleCaptor.capture());
        assertThat(scheduleCaptor.getValue().getSqlSelect())
                .contains("collection_task_id", "cron_expression", "enabled", "timezone")
                .doesNotContain("last_triggered_at");
        verify(scheduleMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void collectionTaskOnlineListShouldUseSameSlimSelect() {
        CollectionTaskDefinitionMapper definitionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskScheduleMapper scheduleMapper = mock(CollectionTaskScheduleMapper.class);
        CollectionTaskService service = collectionTaskService(definitionMapper, scheduleMapper);
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(taskEntity()));
        when(scheduleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(scheduleEntity()));

        assertThat(service.listOnlineSummaries()).hasSize(1);

        ArgumentCaptor<LambdaQueryWrapper<CollectionTaskDefinitionEntity>> taskCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitionMapper).selectList(taskCaptor.capture());
        assertTaskListSelectIsSlim(taskCaptor.getValue().getSqlSelect());
        verify(scheduleMapper).selectList(any(LambdaQueryWrapper.class));
        verify(scheduleMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void collectionTaskOptionsShouldSelectOnlyIdProjectAndNameWithoutSchedules() {
        CollectionTaskDefinitionMapper definitionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskScheduleMapper scheduleMapper = mock(CollectionTaskScheduleMapper.class);
        CollectionTaskService service = collectionTaskService(definitionMapper, scheduleMapper);
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(taskEntity()));

        assertThat(service.listOptions())
                .extracting(CollectionTaskOptionView::getName)
                .containsExactly("长期回归-客户订单增量采集任务");

        ArgumentCaptor<LambdaQueryWrapper<CollectionTaskDefinitionEntity>> taskCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitionMapper).selectList(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getSqlSelect())
                .contains("id", "project_id", "name")
                .doesNotContain("task_type", "status", "source_count",
                        "target_datasource_name_snapshot", "target_model_name_snapshot",
                        "source_bindings_json", "target_binding_json", "field_mappings_json", "execution_options_json");
        verify(scheduleMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(scheduleMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void collectionTaskWorkflowOptionsShouldSelectOnlyWorkflowBindingColumns() {
        CollectionTaskDefinitionMapper definitionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskScheduleMapper scheduleMapper = mock(CollectionTaskScheduleMapper.class);
        CollectionTaskService service = collectionTaskService(definitionMapper, scheduleMapper);
        when(definitionMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<CollectionTaskDefinitionEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(Collections.singletonList(taskEntity()));
            return page;
        });

        PageView<CollectionTaskWorkflowOptionView> page = service.listWorkflowOptions(1, 8, "客户");

        assertThat(page.getItems()).hasSize(1);
        CollectionTaskWorkflowOptionView item = page.getItems().get(0);
        assertThat(item.getName()).isEqualTo("长期回归-客户订单增量采集任务");
        assertThat(item.getTaskType()).isEqualTo(CollectionTaskType.SINGLE_TABLE);
        assertThat(item.getSourceCount()).isEqualTo(1);

        ArgumentCaptor<LambdaQueryWrapper<CollectionTaskDefinitionEntity>> taskCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitionMapper).selectPage(any(Page.class), taskCaptor.capture());
        assertThat(taskCaptor.getValue().getSqlSelect())
                .contains("id", "project_id", "updated_at", "name", "task_type", "source_count")
                .doesNotContain("tenant_id", "deleted", "created_at", "status",
                        "target_datasource_name_snapshot", "target_datasource_type_code_snapshot",
                        "target_model_name_snapshot", "target_model_physical_locator_snapshot",
                        "source_bindings_json", "target_binding_json", "field_mappings_json", "execution_options_json");
        verify(scheduleMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(scheduleMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void runMetricBindingsShouldSelectMetricBindingSnapshotsInsteadOfTaskDefinitionJson() {
        CollectionTaskDefinitionMapper definitionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskMetricBindingMapper metricBindingMapper = mock(CollectionTaskMetricBindingMapper.class);
        CollectionTaskScheduleMapper scheduleMapper = mock(CollectionTaskScheduleMapper.class);
        CollectionTaskService service = collectionTaskService(definitionMapper, metricBindingMapper, scheduleMapper);
        when(metricBindingMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(sourceMetricBindingEntity(), targetMetricBindingEntity()));

        List<com.jdragon.studio.dto.model.CollectionTaskDefinitionView> bindings = service.listMetricBindings();

        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0).getName()).isEqualTo("长期回归-客户订单增量采集任务");
        assertThat(bindings.get(0).getSourceBindings())
                .extracting(CollectionTaskSourceBinding::getModelPhysicalLocator)
                .containsExactly("lt_reg_customer_order_detail");
        assertThat(bindings.get(0).getTargetBinding().getModelPhysicalLocator())
                .isEqualTo("lt_reg_customer_order_summary");

        ArgumentCaptor<LambdaQueryWrapper<CollectionTaskMetricBindingEntity>> metricCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(metricBindingMapper).selectList(metricCaptor.capture());
        assertThat(metricCaptor.getValue().getSqlSelect())
                .contains("collection_task_id", "binding_role", "datasource_name", "model_physical_locator")
                .doesNotContain("source_bindings_json", "target_binding_json", "field_mappings_json", "execution_options_json");
        verify(definitionMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(scheduleMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void publishShouldReturnSlimListViewInsteadOfFullDefinitionView() {
        CollectionTaskDefinitionMapper definitionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskMetricBindingMapper metricBindingMapper = mock(CollectionTaskMetricBindingMapper.class);
        CollectionTaskScheduleMapper scheduleMapper = mock(CollectionTaskScheduleMapper.class);
        CollectionTaskService service = collectionTaskService(definitionMapper, metricBindingMapper, scheduleMapper);
        CollectionTaskDefinitionEntity beforePublish = taskEntity();
        beforePublish.setStatus(CollectionTaskStatus.DRAFT.name());
        CollectionTaskDefinitionEntity afterPublish = taskEntity();
        afterPublish.setStatus(CollectionTaskStatus.ONLINE.name());
        when(definitionMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(beforePublish)
                .thenReturn(afterPublish);
        when(scheduleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(scheduleEntity()));

        CollectionTaskListView published = service.publish(11L);

        assertThat(published.getStatus()).isEqualTo(CollectionTaskStatus.ONLINE);
        assertThat(published.getTargetDatasourceName()).isEqualTo("长期回归-客户经营画像数据源");
        assertThat(published.getSchedule()).isNotNull();

        ArgumentCaptor<LambdaQueryWrapper<CollectionTaskDefinitionEntity>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitionMapper, times(2)).selectOne(queryCaptor.capture());
        for (LambdaQueryWrapper<CollectionTaskDefinitionEntity> query : queryCaptor.getAllValues()) {
            assertTaskListSelectIsSlim(query.getSqlSelect());
        }

        ArgumentCaptor<LambdaUpdateWrapper<CollectionTaskDefinitionEntity>> updateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(definitionMapper).update(any(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet()).contains("status");
        verify(definitionMapper, never()).selectById(any());
        verify(metricBindingMapper).update(any(), any(LambdaUpdateWrapper.class));
        verify(scheduleMapper).selectList(any(LambdaQueryWrapper.class));
        verify(scheduleMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private CollectionTaskService collectionTaskService(CollectionTaskDefinitionMapper definitionMapper,
                                                        CollectionTaskScheduleMapper scheduleMapper) {
        return collectionTaskService(definitionMapper, mock(CollectionTaskMetricBindingMapper.class), scheduleMapper);
    }

    private CollectionTaskService collectionTaskService(CollectionTaskDefinitionMapper definitionMapper,
                                                        CollectionTaskMetricBindingMapper metricBindingMapper,
                                                        CollectionTaskScheduleMapper scheduleMapper) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(accessService.sharedResourceIdList(any())).thenReturn(Collections.emptyList());
        return new CollectionTaskService(
                definitionMapper,
                metricBindingMapper,
                scheduleMapper,
                mock(DispatchTaskMapper.class),
                mock(RunRecordMapper.class),
                mock(DataSourceService.class),
                mock(DataModelService.class),
                mock(CollectionTaskAssemblerService.class),
                new ObjectMapper(),
                securityService,
                accessService,
                mock(DataModelLineageService.class),
                mock(DatasourceTypeCapabilityService.class));
    }

    private void assertTaskListSelectIsSlim(String sqlSelect) {
        assertThat(sqlSelect)
                .contains("target_datasource_name_snapshot", "target_datasource_type_code_snapshot",
                        "target_model_name_snapshot", "target_model_physical_locator_snapshot")
                .doesNotContain("source_bindings_json", "target_binding_json", "field_mappings_json", "execution_options_json");
    }

    private CollectionTaskDefinitionEntity taskEntity() {
        CollectionTaskDefinitionEntity entity = new CollectionTaskDefinitionEntity();
        entity.setId(11L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 27, 12, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 27, 12, 1, 0));
        entity.setName("长期回归-客户订单增量采集任务");
        entity.setTaskType(CollectionTaskType.SINGLE_TABLE.name());
        entity.setStatus(CollectionTaskStatus.ONLINE.name());
        entity.setSourceCount(1);
        entity.setTargetDatasourceNameSnapshot("长期回归-客户经营画像数据源");
        entity.setTargetDatasourceTypeCodeSnapshot("mysql8");
        entity.setTargetModelNameSnapshot("客户订单汇总模型");
        entity.setTargetModelPhysicalLocatorSnapshot("lt_reg_customer_order_summary");
        return entity;
    }

    private CollectionTaskScheduleEntity scheduleEntity() {
        CollectionTaskScheduleEntity entity = new CollectionTaskScheduleEntity();
        entity.setCollectionTaskId(11L);
        entity.setCronExpression("0 0/30 * * * ?");
        entity.setEnabled(1);
        entity.setTimezone("Asia/Shanghai");
        return entity;
    }

    private CollectionTaskMetricBindingEntity sourceMetricBindingEntity() {
        CollectionTaskMetricBindingEntity entity = metricBindingBaseEntity("SOURCE");
        entity.setSourceAlias("客户订单明细源");
        entity.setDatasourceId(21L);
        entity.setDatasourceName("长期回归-客户业务库");
        entity.setDatasourceTypeCode("mysql8");
        entity.setModelId(22L);
        entity.setModelName("客户订单明细模型");
        entity.setModelPhysicalLocator("lt_reg_customer_order_detail");
        return entity;
    }

    private CollectionTaskMetricBindingEntity targetMetricBindingEntity() {
        CollectionTaskMetricBindingEntity entity = metricBindingBaseEntity("TARGET");
        entity.setDatasourceId(31L);
        entity.setDatasourceName("长期回归-客户经营画像数据源");
        entity.setDatasourceTypeCode("mysql8");
        entity.setModelId(32L);
        entity.setModelName("客户订单汇总模型");
        entity.setModelPhysicalLocator("lt_reg_customer_order_summary");
        return entity;
    }

    private CollectionTaskMetricBindingEntity metricBindingBaseEntity(String role) {
        CollectionTaskMetricBindingEntity entity = new CollectionTaskMetricBindingEntity();
        entity.setId("SOURCE".equals(role) ? 101L : 102L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 29, 10, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 10, 1, 0));
        entity.setCollectionTaskId(11L);
        entity.setTaskNameSnapshot("长期回归-客户订单增量采集任务");
        entity.setTaskType(CollectionTaskType.SINGLE_TABLE.name());
        entity.setTaskStatus(CollectionTaskStatus.ONLINE.name());
        entity.setSourceCount(1);
        entity.setBindingRole(role);
        return entity;
    }
}
