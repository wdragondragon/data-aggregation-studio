package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.WorkflowRunDetailView;
import com.jdragon.studio.dto.model.WorkflowRunSummaryView;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.entity.WorkflowEdgeEntity;
import com.jdragon.studio.infra.entity.WorkflowNodeEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkflowEdgeMapper;
import com.jdragon.studio.infra.mapper.WorkflowNodeMapper;
import com.jdragon.studio.infra.service.StaleExecutionRecoveryService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.WorkflowRunService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRunSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(RunRecordEntity.class);
        initTableInfo(DispatchTaskEntity.class);
        initTableInfo(WorkflowDefinitionEntity.class);
        initTableInfo(WorkflowNodeEntity.class);
        initTableInfo(WorkflowEdgeEntity.class);
    }

    @Test
    void workflowRunListShouldSelectOnlySummaryFields() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkflowDefinitionMapper workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
        WorkflowNodeMapper workflowNodeMapper = mock(WorkflowNodeMapper.class);
        WorkflowEdgeMapper workflowEdgeMapper = mock(WorkflowEdgeMapper.class);
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        WorkflowRunService service = new WorkflowRunService(
                runRecordMapper,
                dispatchTaskMapper,
                workflowDefinitionMapper,
                workflowNodeMapper,
                workflowEdgeMapper,
                jdbcTemplate,
                securityService,
                mock(StaleExecutionRecoveryService.class));
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentProjectId()).thenReturn(100L);
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(Collections.singletonList(9001L));
        when(runRecordMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(runRecord()));
        when(dispatchTaskMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.<DispatchTaskEntity>emptyList());
        when(workflowDefinitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(workflowDefinition()));
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(workflowNode()));

        PageView<WorkflowRunSummaryView> page = service.list(null, null, null, null, 1, 20);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getWorkflowName()).isEqualTo("客户订单日终工作流");
        assertThat(page.getItems().get(0).getTotalNodes()).isEqualTo(1);
        verify(workflowEdgeMapper, never()).selectList(any(LambdaQueryWrapper.class));

        ArgumentCaptor<LambdaQueryWrapper<RunRecordEntity>> runCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runRecordMapper).selectList(runCaptor.capture());
        String runSelect = runCaptor.getValue().getSqlSelect();
        assertThat(runSelect)
                .contains("workflow_run_id", "workflow_definition_id", "workflow_version_id", "node_code", "message")
                .doesNotContain("payload_json", "result_json", "log_file_path", "log_object_key");

        ArgumentCaptor<LambdaQueryWrapper<DispatchTaskEntity>> taskCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dispatchTaskMapper).selectList(taskCaptor.capture());
        String taskSelect = taskCaptor.getValue().getSqlSelect();
        assertThat(taskSelect)
                .contains("workflow_run_id", "workflow_definition_id", "workflow_version_id", "node_code")
                .doesNotContain("payload_json");

        ArgumentCaptor<LambdaQueryWrapper<WorkflowDefinitionEntity>> definitionCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workflowDefinitionMapper).selectList(definitionCaptor.capture());
        assertThat(definitionCaptor.getValue().getSqlSelect())
                .contains("id", "name")
                .doesNotContain("current_version_id", "published");

        ArgumentCaptor<LambdaQueryWrapper<WorkflowNodeEntity>> nodeCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workflowNodeMapper).selectList(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getSqlSelect())
                .contains("workflow_version_id", "node_code", "node_name", "node_type")
                .doesNotContain("config_json", "field_mappings_json");
    }

    @Test
    void workflowRunStatusFilterShouldPageWorkflowRunIdsInSql() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkflowDefinitionMapper workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
        WorkflowNodeMapper workflowNodeMapper = mock(WorkflowNodeMapper.class);
        WorkflowEdgeMapper workflowEdgeMapper = mock(WorkflowEdgeMapper.class);
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        WorkflowRunService service = new WorkflowRunService(
                runRecordMapper,
                dispatchTaskMapper,
                workflowDefinitionMapper,
                workflowNodeMapper,
                workflowEdgeMapper,
                jdbcTemplate,
                securityService,
                mock(StaleExecutionRecoveryService.class));
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentProjectId()).thenReturn(100L);
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(Collections.singletonList(9001L));
        when(runRecordMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(runRecord()));
        when(dispatchTaskMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.<DispatchTaskEntity>emptyList());
        when(workflowDefinitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(workflowDefinition()));
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(workflowNode()));

        PageView<WorkflowRunSummaryView> page = service.list(null, "SUCCESS", null, null, 1, 20);

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getItems()).hasSize(1);
        ArgumentCaptor<String> idSqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> idParamsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).queryForList(idSqlCaptor.capture(), idParamsCaptor.capture(), eq(Long.class));
        String idSql = idSqlCaptor.getValue().toLowerCase();
        assertThat(idSql)
                .contains("summarized_runs",
                        "summary_status = :summarystatus",
                        "limit :limit offset :offset")
                .doesNotContain("group by workflow_run_id order by max(occurred_at) desc, workflow_run_id desc");
        assertThat(idParamsCaptor.getValue().getValue("summaryStatus")).isEqualTo("SUCCESS");
        assertThat(idParamsCaptor.getValue().getValue("limit")).isEqualTo(20);
        assertThat(idParamsCaptor.getValue().getValue("offset")).isEqualTo(0);
    }

    @Test
    void workflowRunDetailShouldSkipRuntimePayloadAndResultJson() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        WorkflowDefinitionMapper workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
        WorkflowNodeMapper workflowNodeMapper = mock(WorkflowNodeMapper.class);
        WorkflowEdgeMapper workflowEdgeMapper = mock(WorkflowEdgeMapper.class);
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        WorkflowRunService service = new WorkflowRunService(
                runRecordMapper,
                dispatchTaskMapper,
                workflowDefinitionMapper,
                workflowNodeMapper,
                workflowEdgeMapper,
                jdbcTemplate,
                securityService,
                mock(StaleExecutionRecoveryService.class));
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentProjectId()).thenReturn(100L);
        when(runRecordMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(runRecord()));
        when(dispatchTaskMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(dispatchTask()));
        when(workflowDefinitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(workflowDefinition()));
        when(workflowNodeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(workflowNode()));
        when(workflowEdgeMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(workflowEdge()));

        WorkflowRunDetailView detail = service.get(9001L);

        assertThat(detail.getWorkflowName()).isEqualTo("客户订单日终工作流");
        assertThat(detail.getWorkflow()).isNotNull();
        assertThat(detail.getWorkflow().getNodes()).hasSize(1);
        assertThat(detail.getWorkflow().getNodes().get(0).getNodeName()).isEqualTo("客户订单汇总节点");
        assertThat(detail.getWorkflow().getNodes().get(0).getConfig()).isEmpty();
        assertThat(detail.getWorkflow().getNodes().get(0).getFieldMappings()).isEmpty();
        assertThat(detail.getWorkflow().getEdges()).hasSize(1);
        assertThat(detail.getNodeRuns()).hasSize(1);
        assertThat(detail.getNodeRuns().get(0).getNodeName()).isEqualTo("客户订单汇总节点");
        assertThat(detail.getNodeRuns().get(0).getMessage()).isEqualTo("completed");

        ArgumentCaptor<LambdaQueryWrapper<RunRecordEntity>> runCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runRecordMapper).selectList(runCaptor.capture());
        String runSelect = runCaptor.getValue().getSqlSelect();
        assertThat(runSelect)
                .contains("workflow_run_id",
                        "workflow_definition_id",
                        "workflow_version_id",
                        "node_code",
                        "message",
                        "log_file_path")
                .doesNotContain("payload_json",
                        "result_json",
                        "log_object_key",
                        "log_object_bucket");

        ArgumentCaptor<LambdaQueryWrapper<DispatchTaskEntity>> taskCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dispatchTaskMapper).selectList(taskCaptor.capture());
        String taskSelect = taskCaptor.getValue().getSqlSelect();
        assertThat(taskSelect)
                .contains("workflow_run_id",
                        "workflow_definition_id",
                        "workflow_version_id",
                        "node_code",
                        "lease_owner")
                .doesNotContain("payload_json");

        ArgumentCaptor<LambdaQueryWrapper<WorkflowDefinitionEntity>> definitionCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workflowDefinitionMapper).selectList(definitionCaptor.capture());
        assertThat(definitionCaptor.getValue().getSqlSelect())
                .contains("id", "name")
                .doesNotContain("current_version_id", "published");

        ArgumentCaptor<LambdaQueryWrapper<WorkflowNodeEntity>> nodeCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workflowNodeMapper).selectList(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getSqlSelect())
                .contains("workflow_version_id", "node_code", "node_name", "node_type")
                .doesNotContain("config_json", "field_mappings_json");

        ArgumentCaptor<LambdaQueryWrapper<WorkflowEdgeEntity>> edgeCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(workflowEdgeMapper).selectList(edgeCaptor.capture());
        assertThat(edgeCaptor.getValue().getSqlSelect())
                .contains("workflow_version_id", "from_node_code", "to_node_code", "condition_type");
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private RunRecordEntity runRecord() {
        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(1L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 27, 9, 0, 0));
        entity.setWorkflowRunId(9001L);
        entity.setWorkflowDefinitionId(7001L);
        entity.setWorkflowVersionId(8001L);
        entity.setNodeCode("node_customer_order_close");
        entity.setStatus("SUCCESS");
        entity.setWorkerCode("worker-01");
        entity.setMessage("completed");
        entity.setStartedAt(LocalDateTime.of(2026, 6, 27, 9, 0, 0));
        entity.setEndedAt(LocalDateTime.of(2026, 6, 27, 9, 1, 0));
        return entity;
    }

    private WorkflowDefinitionEntity workflowDefinition() {
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setId(7001L);
        entity.setName("客户订单日终工作流");
        return entity;
    }

    private WorkflowNodeEntity workflowNode() {
        WorkflowNodeEntity entity = new WorkflowNodeEntity();
        entity.setWorkflowVersionId(8001L);
        entity.setNodeCode("node_customer_order_close");
        entity.setNodeName("客户订单汇总节点");
        entity.setNodeType("DATA_SCRIPT");
        return entity;
    }

    private WorkflowEdgeEntity workflowEdge() {
        WorkflowEdgeEntity entity = new WorkflowEdgeEntity();
        entity.setWorkflowVersionId(8001L);
        entity.setFromNodeCode("node_customer_order_close");
        entity.setToNodeCode("node_archive");
        entity.setConditionType("ON_SUCCESS");
        return entity;
    }

    private DispatchTaskEntity dispatchTask() {
        DispatchTaskEntity entity = new DispatchTaskEntity();
        entity.setId(2L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 27, 8, 59, 0));
        entity.setWorkflowRunId(9001L);
        entity.setWorkflowDefinitionId(7001L);
        entity.setWorkflowVersionId(8001L);
        entity.setNodeCode("node_customer_order_close");
        entity.setStatus("QUEUED");
        entity.setWorkerGroupCode("default-worker");
        entity.setLeaseOwner("worker-01");
        entity.setWorkerInstanceId("worker-01-001");
        return entity;
    }
}
