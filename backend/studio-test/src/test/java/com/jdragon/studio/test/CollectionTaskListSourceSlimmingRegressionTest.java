package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionTaskListView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.CollectionTaskScheduleEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
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
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionTaskListSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(CollectionTaskDefinitionEntity.class);
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

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private CollectionTaskService collectionTaskService(CollectionTaskDefinitionMapper definitionMapper,
                                                        CollectionTaskScheduleMapper scheduleMapper) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(accessService.sharedResourceIdList(any())).thenReturn(Collections.emptyList());
        return new CollectionTaskService(
                definitionMapper,
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
}
