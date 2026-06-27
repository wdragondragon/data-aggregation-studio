package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.WorkflowListView;
import com.jdragon.studio.dto.model.WorkflowOptionView;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.entity.WorkflowScheduleEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkflowEdgeMapper;
import com.jdragon.studio.infra.mapper.WorkflowNodeMapper;
import com.jdragon.studio.infra.mapper.WorkflowScheduleMapper;
import com.jdragon.studio.infra.mapper.WorkflowVersionMapper;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.WorkflowService;
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

class WorkflowListSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(WorkflowDefinitionEntity.class);
        initTableInfo(WorkflowScheduleEntity.class);
    }

    @Test
    void workflowPageShouldSelectOnlySummaryFieldsAndBatchSchedules() {
        WorkflowDefinitionMapper definitionMapper = mock(WorkflowDefinitionMapper.class);
        WorkflowScheduleMapper scheduleMapper = mock(WorkflowScheduleMapper.class);
        WorkflowService service = workflowService(definitionMapper, scheduleMapper);
        when(definitionMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<WorkflowDefinitionEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(Collections.singletonList(workflowDefinition()));
            return page;
        });
        when(scheduleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(schedule()));

        PageView<WorkflowListView> page = service.listSummaryPage(1, 20);

        assertThat(page.getItems()).hasSize(1);
        WorkflowListView item = page.getItems().get(0);
        assertThat(item.getName()).isEqualTo("长期回归-客户订单日终工作流");
        assertThat(item.getSchedule()).isNotNull();
        assertThat(item.getSchedule().getCronExpression()).isEqualTo("0 0 2 * * ?");

        ArgumentCaptor<LambdaQueryWrapper<WorkflowDefinitionEntity>> definitionCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitionMapper).selectPage(any(Page.class), definitionCaptor.capture());
        assertDefinitionSummarySelect(definitionCaptor.getValue().getSqlSelect());

        ArgumentCaptor<LambdaQueryWrapper<WorkflowScheduleEntity>> scheduleCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(scheduleMapper).selectList(scheduleCaptor.capture());
        assertScheduleSummarySelect(scheduleCaptor.getValue().getSqlSelect());
        verify(scheduleMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void workflowSummaryListShouldReuseSlimSelectAndBatchSchedules() {
        WorkflowDefinitionMapper definitionMapper = mock(WorkflowDefinitionMapper.class);
        WorkflowScheduleMapper scheduleMapper = mock(WorkflowScheduleMapper.class);
        WorkflowService service = workflowService(definitionMapper, scheduleMapper);
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(workflowDefinition()));
        when(scheduleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(schedule()));

        assertThat(service.listSummaries()).hasSize(1);

        ArgumentCaptor<LambdaQueryWrapper<WorkflowDefinitionEntity>> definitionCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitionMapper).selectList(definitionCaptor.capture());
        assertDefinitionSummarySelect(definitionCaptor.getValue().getSqlSelect());

        ArgumentCaptor<LambdaQueryWrapper<WorkflowScheduleEntity>> scheduleCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(scheduleMapper).selectList(scheduleCaptor.capture());
        assertScheduleSummarySelect(scheduleCaptor.getValue().getSqlSelect());
        verify(scheduleMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void workflowOptionsShouldSelectOnlyIdProjectAndNameWithoutSchedules() {
        WorkflowDefinitionMapper definitionMapper = mock(WorkflowDefinitionMapper.class);
        WorkflowScheduleMapper scheduleMapper = mock(WorkflowScheduleMapper.class);
        WorkflowService service = workflowService(definitionMapper, scheduleMapper);
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(workflowDefinition()));

        assertThat(service.listOptions())
                .extracting(WorkflowOptionView::getName)
                .containsExactly("长期回归-客户订单日终工作流");

        ArgumentCaptor<LambdaQueryWrapper<WorkflowDefinitionEntity>> definitionCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitionMapper).selectList(definitionCaptor.capture());
        assertThat(definitionCaptor.getValue().getSqlSelect())
                .contains("id", "project_id", "name")
                .doesNotContain("code", "current_version_id", "published", "created_by");
        verify(scheduleMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(scheduleMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private WorkflowService workflowService(WorkflowDefinitionMapper definitionMapper,
                                            WorkflowScheduleMapper scheduleMapper) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(accessService.sharedResourceIdList(any())).thenReturn(Collections.emptyList());
        return new WorkflowService(
                definitionMapper,
                mock(WorkflowVersionMapper.class),
                mock(WorkflowNodeMapper.class),
                mock(WorkflowEdgeMapper.class),
                scheduleMapper,
                mock(DispatchTaskMapper.class),
                mock(RunRecordMapper.class),
                securityService,
                accessService);
    }

    private void assertDefinitionSummarySelect(String sqlSelect) {
        assertThat(sqlSelect)
                .contains("id", "tenant_id", "project_id", "created_at", "updated_at",
                        "code", "name", "current_version_id", "published")
                .doesNotContain("created_by");
    }

    private void assertScheduleSummarySelect(String sqlSelect) {
        assertThat(sqlSelect)
                .contains("workflow_definition_id", "cron_expression", "enabled", "timezone")
                .doesNotContain("last_triggered_at");
    }

    private WorkflowDefinitionEntity workflowDefinition() {
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setId(7001L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 27, 12, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 27, 12, 30, 0));
        entity.setCode("wf_customer_order_daily_close");
        entity.setName("长期回归-客户订单日终工作流");
        entity.setCurrentVersionId(8001L);
        entity.setPublished(1);
        entity.setCreatedBy(1L);
        return entity;
    }

    private WorkflowScheduleEntity schedule() {
        WorkflowScheduleEntity entity = new WorkflowScheduleEntity();
        entity.setWorkflowDefinitionId(7001L);
        entity.setCronExpression("0 0 2 * * ?");
        entity.setEnabled(1);
        entity.setTimezone("Asia/Shanghai");
        entity.setLastTriggeredAt(LocalDateTime.of(2026, 6, 27, 2, 0, 0));
        return entity;
    }
}
