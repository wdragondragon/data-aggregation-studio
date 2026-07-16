package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.enums.DispatchExecutionType;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.QualityTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.model.AlertSignal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionEventAlertSignalTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(CollectionTaskDefinitionEntity.class);
        initTableInfo(QualityTaskDefinitionEntity.class);
    }

    @Test
    void shouldPublishErrorAsTerminalExecutionAlertSignal() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity run = new RunRecordEntity();
        run.setId(100L);
        run.setTenantId("default");
        run.setProjectId(20L);
        run.setCollectionTaskId(30L);
        when(runRecordMapper.selectById(100L)).thenReturn(run);

        CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskDefinitionEntity task = new CollectionTaskDefinitionEntity();
        task.setId(30L);
        task.setName("orders");
        when(collectionMapper.selectOne(any())).thenReturn(task);
        FollowSubscriptionService followService = mock(FollowSubscriptionService.class);
        when(followService.followerUserProjectIds(any(), any(), any(), any())).thenReturn(Collections.emptyMap());
        when(followService.followerUserIds(any(), any(), any(), any())).thenReturn(Collections.emptyList());

        ExecutionEventService service = new ExecutionEventService(runRecordMapper, mock(DispatchTaskMapper.class),
                collectionMapper, mock(WorkflowDefinitionMapper.class), mock(DispatchService.class),
                mock(RunMetricSummaryMapper.class), followService, mock(NotificationService.class),
                mock(DataModelLineageService.class), mock(QualityIssueService.class),
                mock(CollectionTaskIncrementalStateService.class), mock(StaleExecutionRecoveryService.class));
        AlertSignalPublisher publisher = mock(AlertSignalPublisher.class);
        service.setAlertSignalSupport(publisher, mock(QualityTaskDefinitionMapper.class));

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(100L);
        event.setEventType("ERROR");
        event.setExecutionType(DispatchExecutionType.COLLECTION_TASK);
        event.setCollectionTaskId(30L);
        event.setProjectId(20L);
        event.setOccurredAt(LocalDateTime.now());
        service.publish(event);

        ArgumentCaptor<AlertSignal> captor = ArgumentCaptor.forClass(AlertSignal.class);
        verify(publisher).publish(captor.capture());
        assertEquals("ERROR", captor.getValue().getStatus());
        assertEquals("COLLECTION_TASK", captor.getValue().getSubjectType());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<CollectionTaskDefinitionEntity>> queryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(collectionMapper, times(2)).selectOne(queryCaptor.capture());
        for (LambdaQueryWrapper<CollectionTaskDefinitionEntity> query : queryCaptor.getAllValues()) {
            assertProjectScoped(query, "default", 20L, 30L);
        }
    }

    @Test
    void shouldScopeQualityTaskAlertLookupToRunTenantAndProject() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity run = new RunRecordEntity();
        run.setId(101L);
        run.setTenantId("tenant-a");
        run.setProjectId(21L);
        run.setQualityTaskId(31L);
        when(runRecordMapper.selectById(101L)).thenReturn(run);

        QualityTaskDefinitionMapper qualityMapper = mock(QualityTaskDefinitionMapper.class);
        QualityTaskDefinitionEntity task = new QualityTaskDefinitionEntity();
        task.setId(31L);
        task.setTaskName("quality orders");
        when(qualityMapper.selectOne(any())).thenReturn(task);
        FollowSubscriptionService followService = mock(FollowSubscriptionService.class);

        ExecutionEventService service = new ExecutionEventService(runRecordMapper, mock(DispatchTaskMapper.class),
                mock(CollectionTaskDefinitionMapper.class), mock(WorkflowDefinitionMapper.class), mock(DispatchService.class),
                mock(RunMetricSummaryMapper.class), followService, mock(NotificationService.class),
                mock(DataModelLineageService.class), mock(QualityIssueService.class),
                mock(CollectionTaskIncrementalStateService.class), mock(StaleExecutionRecoveryService.class));
        AlertSignalPublisher publisher = mock(AlertSignalPublisher.class);
        service.setAlertSignalSupport(publisher, qualityMapper);

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(101L);
        event.setEventType("FAILED");
        event.setExecutionType(DispatchExecutionType.QUALITY_TASK);
        event.setQualityTaskId(31L);
        event.setProjectId(21L);
        event.setOccurredAt(LocalDateTime.now());
        service.publish(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<QualityTaskDefinitionEntity>> queryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(qualityMapper).selectOne(queryCaptor.capture());
        assertProjectScoped(queryCaptor.getValue(), "tenant-a", 21L, 31L);
        verify(publisher).publish(any(AlertSignal.class));
    }

    @Test
    void shouldPublishTaskAndWorkflowSignalsForTaskNodeExecution() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity run = new RunRecordEntity();
        run.setId(102L);
        run.setTenantId("tenant-a");
        run.setProjectId(22L);
        run.setWorkflowDefinitionId(40L);
        run.setWorkflowRunId(400L);
        run.setCollectionTaskId(32L);
        when(runRecordMapper.selectById(102L)).thenReturn(run);
        when(runRecordMapper.selectCount(any())).thenReturn(0L);

        CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskDefinitionEntity collectionTask = new CollectionTaskDefinitionEntity();
        collectionTask.setId(32L);
        collectionTask.setName("workflow collection");
        when(collectionMapper.selectOne(any())).thenReturn(collectionTask);
        WorkflowDefinitionMapper workflowMapper = mock(WorkflowDefinitionMapper.class);
        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId(40L);
        workflow.setName("daily workflow");
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        FollowSubscriptionService followService = mock(FollowSubscriptionService.class);
        when(followService.followerUserProjectIds(any(), any(), any(), any())).thenReturn(Collections.emptyMap());
        when(followService.followerUserIds(any(), any(), any(), any())).thenReturn(Collections.emptyList());

        ExecutionEventService service = new ExecutionEventService(runRecordMapper, mock(DispatchTaskMapper.class),
                collectionMapper, workflowMapper, mock(DispatchService.class), mock(RunMetricSummaryMapper.class),
                followService, mock(NotificationService.class), mock(DataModelLineageService.class),
                mock(QualityIssueService.class), mock(CollectionTaskIncrementalStateService.class),
                mock(StaleExecutionRecoveryService.class));
        AlertSignalPublisher publisher = mock(AlertSignalPublisher.class);
        service.setAlertSignalSupport(publisher, mock(QualityTaskDefinitionMapper.class));

        ExecutionEvent event = new ExecutionEvent();
        event.setRunRecordId(102L);
        event.setEventType("FAILED");
        event.setExecutionType(DispatchExecutionType.WORKFLOW_NODE);
        event.setWorkflowDefinitionId(40L);
        event.setWorkflowRunId(400L);
        event.setCollectionTaskId(32L);
        event.setProjectId(22L);
        event.setOccurredAt(LocalDateTime.now());
        service.publish(event);

        ArgumentCaptor<AlertSignal> captor = ArgumentCaptor.forClass(AlertSignal.class);
        verify(publisher, times(2)).publish(captor.capture());
        Set<String> subjectTypes = captor.getAllValues().stream()
                .map(AlertSignal::getSubjectType)
                .collect(Collectors.toSet());
        assertEquals(Set.of("COLLECTION_TASK", "WORKFLOW"), subjectTypes);
    }

    private void assertProjectScoped(LambdaQueryWrapper<?> query, String tenantId, Long projectId, Long resourceId) {
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("tenant_id"));
        assertTrue(sql.contains("project_id"));
        assertTrue(sql.contains("id"));
        assertEquals(3, query.getParamNameValuePairs().size());
        assertTrue(query.getParamNameValuePairs().containsValue(tenantId));
        assertTrue(query.getParamNameValuePairs().containsValue(projectId));
        assertTrue(query.getParamNameValuePairs().containsValue(resourceId));
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }
}
