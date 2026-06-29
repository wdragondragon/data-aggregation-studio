package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.dto.model.RunListView;
import com.jdragon.studio.dto.model.RunRecordPageView;
import com.jdragon.studio.dto.model.RunRecordListView;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.RunMetricSummaryMapper;
import com.jdragon.studio.infra.service.RunService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunListSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(DispatchTaskEntity.class);
        initTableInfo(RunRecordEntity.class);
        initTableInfo(WorkflowDefinitionEntity.class);
    }

    @Test
    void runListShouldSelectOnlySummaryFieldsAndUseLightNameLookups() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        WorkflowDefinitionMapper workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        RunService service = new RunService(
                dispatchTaskMapper,
                runRecordMapper,
                collectionTaskService,
                qualityTaskService,
                workflowDefinitionMapper,
                securityService,
                new RunMetricSummaryMapper());
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentProjectId()).thenReturn(100L);
        when(collectionTaskService.listAccessibleNames()).thenReturn(nameMap(11L, "客户订单采集任务"));
        when(qualityTaskService.listAccessibleNames()).thenReturn(nameMap(22L, "合同完整性质量任务"));
        when(workflowDefinitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(workflowDefinition()));
        when(dispatchTaskMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(dispatchTask()));
        when(runRecordMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(runRecord()));

        RunListView view = service.list(11L, null, null, null, null, true);

        assertThat(view.getQueuedTasks()).hasSize(1);
        assertThat(view.getQueuedTasks().get(0).getCollectionTaskName()).isEqualTo("客户订单采集任务");
        assertThat(view.getRunRecords()).hasSize(1);
        assertThat(view.getRunRecords().get(0).getCollectionTaskName()).isEqualTo("客户订单采集任务");
        assertThat(view.getRunRecords().get(0).getWorkflowName()).isEqualTo("客户订单日终工作流");
        assertThat(view.getRunRecords().get(0).getDurationMs()).isEqualTo(60000L);

        verify(collectionTaskService).listAccessibleNames();
        verify(collectionTaskService, never()).list(null, null, null);
        verify(qualityTaskService).listAccessibleNames();
        verify(qualityTaskService, never()).list(null, null, null, null);

        ArgumentCaptor<LambdaQueryWrapper<DispatchTaskEntity>> dispatchCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dispatchTaskMapper).selectList(dispatchCaptor.capture());
        assertThat(dispatchCaptor.getValue().getSqlSelect())
                .contains("collection_task_id", "quality_task_id", "workflow_definition_id", "worker_group_code")
                .doesNotContain("payload_json");

        ArgumentCaptor<LambdaQueryWrapper<RunRecordEntity>> runCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runRecordMapper).selectList(runCaptor.capture());
        assertThat(runCaptor.getValue().getSqlSelect())
                .contains("collection_task_id", "quality_task_id", "workflow_definition_id", "message", "collected_records")
                .doesNotContain("payload_json", "result_json", "log_file_path", "log_object_key", "log_object_bucket", "log_chunk_count");

        ArgumentCaptor<LambdaQueryWrapper<WorkflowDefinitionEntity>> workflowCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workflowDefinitionMapper).selectList(workflowCaptor.capture());
        assertThat(workflowCaptor.getValue().getSqlSelect())
                .contains("id", "name")
                .doesNotContain("current_version_id", "published");
    }

    @Test
    void runRecordPageShouldSelectOnlyCurrentPageAndCountStatusGroups() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        WorkflowDefinitionMapper workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        RunService service = new RunService(
                dispatchTaskMapper,
                runRecordMapper,
                collectionTaskService,
                qualityTaskService,
                workflowDefinitionMapper,
                securityService,
                new RunMetricSummaryMapper());
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentProjectId()).thenReturn(100L);
        when(collectionTaskService.listAccessibleNames()).thenReturn(nameMap(11L, "客户订单采集任务"));
        when(qualityTaskService.listAccessibleNames()).thenReturn(nameMap(22L, "合同完整性质量任务"));
        when(workflowDefinitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(workflowDefinition()));
        when(runRecordMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<RunRecordEntity> page = invocation.getArgument(0);
            page.setTotal(48L);
            page.setRecords(Collections.singletonList(runRecord()));
            return page;
        });
        when(runRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L, 1L, 45L);

        RunRecordPageView page = service.listRunRecords(11L, null, null, true, false, null, null, null, 1, 10);

        assertThat(page.getPageNo()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(10);
        assertThat(page.getTotal()).isEqualTo(48L);
        assertThat(page.getFailedCount()).isEqualTo(2L);
        assertThat(page.getRunningCount()).isEqualTo(1L);
        assertThat(page.getSuccessCount()).isEqualTo(45L);
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getCollectionTaskName()).isEqualTo("客户订单采集任务");

        verify(dispatchTaskMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(runRecordMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(runRecordMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        verify(runRecordMapper, times(3)).selectCount(any(LambdaQueryWrapper.class));

        ArgumentCaptor<LambdaQueryWrapper<RunRecordEntity>> pageQueryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runRecordMapper).selectPage(any(Page.class), pageQueryCaptor.capture());
        assertThat(pageQueryCaptor.getValue().getSqlSelect())
                .contains("collection_task_id", "quality_task_id", "workflow_definition_id", "message", "collected_records")
                .doesNotContain("payload_json", "result_json", "log_file_path", "log_object_key", "log_object_bucket", "log_chunk_count");
    }

    @Test
    void runRecordSummaryShouldSelectOnlyDrawerFieldsAndUseSingleNameLookups() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        WorkflowDefinitionMapper workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        RunService service = new RunService(
                dispatchTaskMapper,
                runRecordMapper,
                collectionTaskService,
                qualityTaskService,
                workflowDefinitionMapper,
                securityService,
                new RunMetricSummaryMapper());
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentProjectId()).thenReturn(100L);
        when(runRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(runRecord());
        when(collectionTaskService.getAccessibleName(11L)).thenReturn("客户订单采集任务");
        when(qualityTaskService.getAccessibleName(22L)).thenReturn("合同完整性质量任务");
        when(workflowDefinitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(workflowDefinition());

        RunRecordListView summary = service.getSummary(2L);

        assertThat(summary.getCollectionTaskName()).isEqualTo("客户订单采集任务");
        assertThat(summary.getQualityTaskName()).isEqualTo("合同完整性质量任务");
        assertThat(summary.getWorkflowName()).isEqualTo("客户订单日终工作流");

        verify(collectionTaskService).getAccessibleName(11L);
        verify(collectionTaskService, never()).listAccessibleNames();
        verify(qualityTaskService).getAccessibleName(22L);
        verify(qualityTaskService, never()).listAccessibleNames();

        ArgumentCaptor<LambdaQueryWrapper<RunRecordEntity>> summaryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runRecordMapper).selectOne(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getSqlSelect())
                .contains("collection_task_id", "quality_task_id", "workflow_definition_id", "message", "collected_records")
                .doesNotContain("payload_json", "result_json", "log_file_path", "log_object_key", "log_object_bucket", "log_chunk_count");

        ArgumentCaptor<LambdaQueryWrapper<WorkflowDefinitionEntity>> workflowCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workflowDefinitionMapper).selectOne(workflowCaptor.capture());
        assertThat(workflowCaptor.getValue().getSqlSelect())
                .contains("id", "name")
                .doesNotContain("current_version_id", "published");
    }

    @Test
    void runLogPointerShouldSelectOnlyLogRoutingFields() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        WorkflowDefinitionMapper workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        RunService service = new RunService(
                dispatchTaskMapper,
                runRecordMapper,
                collectionTaskService,
                qualityTaskService,
                workflowDefinitionMapper,
                securityService,
                new RunMetricSummaryMapper());
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentProjectId()).thenReturn(100L);
        when(runRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(runRecord());

        RunRecordEntity pointer = service.getLogPointer(2L);

        assertThat(pointer.getId()).isEqualTo(2L);

        ArgumentCaptor<LambdaQueryWrapper<RunRecordEntity>> pointerCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runRecordMapper).selectOne(pointerCaptor.capture());
        assertThat(pointerCaptor.getValue().getSqlSelect())
                .contains("log_file_path", "log_storage_type", "log_object_bucket", "log_object_key", "worker_code")
                .doesNotContain("payload_json", "result_json", "collected_records", "read_succeed_records", "write_succeed_records");
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private Map<Long, String> nameMap(Long id, String name) {
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        result.put(id, name);
        return result;
    }

    private DispatchTaskEntity dispatchTask() {
        DispatchTaskEntity entity = new DispatchTaskEntity();
        entity.setId(1L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 27, 10, 0, 0));
        entity.setCollectionTaskId(11L);
        entity.setQualityTaskId(22L);
        entity.setWorkflowDefinitionId(33L);
        entity.setWorkflowVersionId(44L);
        entity.setStatus("QUEUED");
        entity.setWorkerGroupCode("studio-worker");
        entity.setAttempts(1);
        entity.setMaxRetries(3);
        return entity;
    }

    private RunRecordEntity runRecord() {
        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(2L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 27, 10, 1, 0));
        entity.setCollectionTaskId(11L);
        entity.setQualityTaskId(22L);
        entity.setWorkflowDefinitionId(33L);
        entity.setWorkflowVersionId(44L);
        entity.setStatus("SUCCESS");
        entity.setMessage("客户订单采集完成");
        entity.setStartedAt(LocalDateTime.of(2026, 6, 27, 10, 1, 0));
        entity.setEndedAt(LocalDateTime.of(2026, 6, 27, 10, 2, 0));
        entity.setCollectedRecords(100L);
        entity.setSuccessRecords(100L);
        return entity;
    }

    private WorkflowDefinitionEntity workflowDefinition() {
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setId(33L);
        entity.setName("客户订单日终工作流");
        return entity;
    }
}
